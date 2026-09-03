# Touch Lock — Architecture Guide

> Single source of truth for system design, component responsibilities, data flow, and key decisions.
> Update this file whenever architecture, data flow, permissions, or core behavior changes.

---

## Project Overview

Touch Lock is a lightweight Android utility that disables touch input via a full-screen `WindowManager` overlay while keeping underlying content visible. The primary use case is supervised scenarios (toddler watching video, preventing hang-ups during video calls). The app is intentionally offline-first and requires no account.

It ships in two modes:
- **Default mode** (always available): touch-blocking overlay only, no special permissions beyond
  `SYSTEM_ALERT_WINDOW`.
- **Strong Lock mode** (optional, off by default): additionally uses an `AccessibilityService`, scoped
  narrowly to parental-supervision lock enforcement — blocking navigation bar taps and relaunching the
  protected app if the user navigates away while locked. See "Strong Lock: Accessibility-Based
  Hardening" below. Default mode remains fully functional and is the fallback if Strong Lock is off,
  unavailable, or fails.

**Key constraints**: minSdk 26, no network, no kiosk mode, no device owner APIs. Accessibility is
optional and additive, never required for the app's core function.

---

## Architecture Overview

```
┌─────────────────────────────────┐
│   UI Layer (Jetpack Compose)    │  HomeScreen + HomeViewModel
├─────────────────────────────────┤
│   Domain Layer                  │  Use Cases, Repository Interfaces, Models
├─────────────────────────────────┤
│   Platform Layer                │  Repository Impls, DataStore, Overlays,
│                                 │  Notifications, Permissions, Haptics
├─────────────────────────────────┤
│   Service Layer                 │  LockOverlayService (foreground service)
│   (single source of truth)      │  OverlayController (WindowManager)
└─────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Package | Owns |
|-------|---------|------|
| UI | `ui/screens/home/` | Compose screens, ViewModel, `TouchLockUiState` |
| Domain | `domain/` | Models (`LockState`, `UsageTimerState`), use cases, repository interfaces |
| Platform | `platform/` | DataStore, overlays, notifications, permission checks, haptics, time abstraction |
| Service | `service/` | `LockOverlayService` — foreground service, lock state owner |
| DI | `di/` | Hilt `AppModule` — all bindings |

### Architectural Rules

- UI must never interact with `WindowManager`, services, or overlays directly.
- All UI actions flow: `UI → ViewModel → UseCase → Repository → Service (via Intent)`.
- `LockOverlayService` is the single source of truth for lock state via a process-global `StateFlow`.
- Overlay logic stays in `platform/overlay/` and `service/`, never in ViewModel or UI.

---

## Core Flows

### 1. App Startup

```
MainActivity.onCreate()
  → ContextCompat.startForegroundService(ACTION_INIT)
  → LockOverlayService.initService()
      → startForeground(NOTIFICATION_ID, unlockedNotification)
      → _lockState.value = LockState.Unlocked
```

`MainActivity.onResume()` also sends `ACTION_RESTORE_NOTIFICATION` on every resume to re-assert foreground status if the notification was dismissed.

### 2. Delayed Lock (from UI button)

```
HomeScreen "Enable" tap
  → HomeViewModel.startDelayedLock()  [delegates to StartDelayedLockUseCase]
  → LockRepositoryImpl sends ACTION_DELAYED_LOCK intent
  → LockOverlayService.startDelayedLock()
      → cancelCountdown()  [safety: clears any previous countdown]
      → launch countdownJob: repeat(10) with 1s delay
          → overlayController.showCountdown(secondsRemaining)
          → update notification with countdown text
      → on 0 remaining: overlayController.hideCountdown() → startLock()
```

**Note**: The UI button always triggers `startDelayedLock()` (10-second countdown), not an immediate lock. There is no direct "lock now" path from the UI.

### 3. Lock Engagement

```
LockOverlayService.startLock()
  → cancelCountdown()  [clears any in-flight countdown callbacks]
  → check overlayPermissionManager.hasPermission() — abort if false
  → overlayController.showMainOverlay(debugTintVisible)
  → _lockState.value = LockState.Locked
  → assertForegroundState(lockedNotification)
  → hapticController.vibrateOnLock()
```

### 4. Unlock Flow (double-tap → hold)

```
OverlayView.onTouchEvent()
  → detects double-tap (< 400ms between taps)
  → calls onDoubleTapDetected()
  → LockOverlayService.overlayController.showUnlockHandle()
      → UnlockHandleView added to WindowManager
      → auto-hide handler posted (4 seconds)

UnlockHandleView.onTouchEvent()
  → ACTION_DOWN: starts 1000ms long-press timer
  → if 1000ms elapses: onUnlockRequested() → LockOverlayService.stopLock()
  → ACTION_UP before 1000ms: cancels timer

LockOverlayService.stopLock()
  → cancelCountdown()
  → overlayController.hideMainOverlay()
  → overlayController.hideUnlockHandle()
  → _lockState.value = LockState.Unlocked
  → assertForegroundState(unlockedNotification)
  → hapticController.vibrateOnUnlock()
```

### 5. Notification Toggle

```
User taps notification
  → PendingIntent fires ACTION_TOGGLE intent
  → LockOverlayService.toggleLock()
      → if Locked: stopLock()
      → if Unlocked: startLock()
```

### 6. Service Restart (system kill → START_STICKY)

```
System restarts LockOverlayService with null intent
  → onStartCommand(null, ...)
  → initService()  [re-initializes to Unlocked state]
  → DataStore config preserved, usage data preserved if same day
```

---

## State Management

### Lock State

- Owned by `LockOverlayService` as a **process-global** `MutableStateFlow<LockState>`:
  ```kotlin
  companion object {
      private val _lockState = MutableStateFlow<LockState>(LockState.Unlocked)
      val lockState: StateFlow<LockState> = _lockState.asStateFlow()
  }
  ```
- Process-global scope means it survives service restarts within the same process.
- `LockRepositoryImpl` exposes this flow to the domain layer.

### UI State

`HomeViewModel` combines 5 flows into a single `TouchLockUiState` via `combine()`:

```kotlin
combine(
    observeLockState(),          // LockState (Locked | Unlocked)
    observeUsageTimer(),         // UsageTimerState (elapsed millis, isRunning)
    observeDebugOverlayVisible(), // Boolean (debug builds only)
    _hasOverlayPermission,       // Boolean (refreshed on resume)
    _areNotificationsAvailable   // Boolean (refreshed on resume)
) { ... → TouchLockUiState }
.stateIn(WhileSubscribed(5_000))
```

Permission states (`_hasOverlayPermission`, `_areNotificationsAvailable`) are `MutableStateFlow` updated synchronously in `onResume()` via `HomeViewModel.refreshPermissions()`.

### Usage Timer State

Managed by `ObserveUsageTimerUseCase` — **must be `@Singleton`** to prevent leaks from its custom `CoroutineScope(dispatcher + Job())`.

- On lock: `startTimer()` — saves `lastStartTime` to DataStore, starts 1s tick job
- On unlock: `stopTimer()` — cancels tick job, accumulates elapsed millis to DataStore
- Midnight rollover: detected inside tick loop by comparing `timeProvider.getCurrentDateString()` to stored date
- Crash recovery: on load, if `lastStartTime` is non-null (app was killed while locked), adds `(now - lastStartTime)` to accumulated millis

---

## System Integrations

### Permissions

| Permission | Purpose | Check |
|-----------|---------|-------|
| `SYSTEM_ALERT_WINDOW` | Display overlay | `Settings.canDrawOverlays()` via `OverlayPermissionManager` |
| `POST_NOTIFICATIONS` | Foreground notification | App-level + channel-level check via `NotificationPermissionManager` |
| `FOREGROUND_SERVICE` | Run foreground service | Declared in manifest, no runtime check needed |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ foreground type | Declared in manifest |
| `VIBRATE` | Haptic feedback | Declared in manifest |

- If `SYSTEM_ALERT_WINDOW` is missing, `startLock()` aborts silently; UI shows a permission prompt.
- `NotificationPermissionManager` returns `false` if the notification channel is set to `IMPORTANCE_NONE`.

### Overlay System

Three overlays managed by `OverlayController`:

| Overlay | Type | Flags | Size | Purpose |
|---------|------|-------|------|---------|
| Main (`OverlayView`) | `TYPE_APPLICATION_OVERLAY` | `NOT_FOCUSABLE` | `MATCH_PARENT` | Intercepts all touches |
| Unlock Handle (`UnlockHandleView`) | `TYPE_APPLICATION_OVERLAY` | `NOT_FOCUSABLE` | 300×300dp | Unlock confirmation UI |
| Countdown (`CountdownOverlayView`) | `TYPE_APPLICATION_OVERLAY` | `NOT_FOCUSABLE` \| `NOT_TOUCHABLE` | 180×180dp | Non-blocking countdown display |

All overlays use `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` (requires `SYSTEM_ALERT_WINDOW`).

The countdown overlay has `FLAG_NOT_TOUCHABLE` — users can still interact with underlying apps during the countdown.

### Notifications

Built by `LockNotificationManager` using `NotificationCompat`. Channel: `touch_lock_channel`, importance `LOW`.

| State | Icon | Title | Action |
|-------|------|-------|--------|
| Unlocked | `ic_lock_open_24` | "Touch Lock ready" | `ACTION_TOGGLE` |
| Locked | `ic_lock_24` | "Touch Lock active" | `ACTION_TOGGLE` |
| Countdown | `ic_lock_open_24` | "Locking in Xs..." | `ACTION_CANCEL_COUNTDOWN` |

All notifications: `.setOngoing(true)`, `.setAutoCancel(false)`, `.setColorized(true)`.

**Android 14+** foreground service requires `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` in `startForeground()`.

### Foreground Service Actions (Intent API)

| Action | Trigger | Handler |
|--------|---------|---------|
| `ACTION_INIT` | `MainActivity.onCreate()` | `initService()` |
| `ACTION_TOGGLE` | Notification tap | `toggleLock()` |
| `ACTION_DELAYED_LOCK` | UI "Enable" button | `startDelayedLock()` |
| `ACTION_CANCEL_COUNTDOWN` | Countdown notification tap | `cancelCountdown()` |
| `ACTION_RESTORE_NOTIFICATION` | `MainActivity.onResume()` | `restoreNotification()` |
| `ACTION_DISMISS` | (unused in current UI) | Hides overlay, stops service |
| `null` | System restart | `initService()` |

### Haptics

`HapticController` abstracts `Vibrator` / `VibratorManager` (Android 12+):
- Lock: `[0, 50, 30, 80]` — two short pulses
- Unlock: `[0, 80]` — single pulse

### Persistence (DataStore)

`LockPreferences` stores four keys:

| Key | Type | Purpose |
|-----|------|---------|
| `USAGE_DATE` | String (`yyyy-MM-dd`) | Daily reset detection |
| `USAGE_ACCUMULATED_MILLIS` | Long | Elapsed lock time today |
| `USAGE_LAST_START_TIME` | Long | Crash recovery: lock was active when process died |
| `DEBUG_OVERLAY_VISIBLE` | Boolean | Debug tint toggle |

Date mismatch on read returns `null` → triggers daily reset in `ObserveUsageTimerUseCase`.

---

## Strong Lock: Accessibility-Based Hardening (Optional)

Strong Lock is an **optional, off-by-default** enhancement layered on top of the default touch overlay.
It never replaces the default path — if accessibility is off, unavailable (e.g. Android 17+ Advanced
Protection Mode), or fails at runtime, the app behaves exactly as it does today via the existing
`TYPE_APPLICATION_OVERLAY` path.

```
TouchLockAccessibilityService (@AndroidEntryPoint)
  ├── @Inject holder; registers itself on onServiceConnected(), clears on onUnbind()/onDestroy()
  ├── onKeyEvent()          → consumes BACK while locked (3-button nav only — see below);
  │                           detects the volume-key force-unlock combo (without consuming)
  └── onAccessibilityEvent() → shade fronted? dismiss. non-allowlisted package fronted? snap back.

AccessibilityServiceHolder (@Singleton)
  └── nullable service ref + StateFlow<Boolean> isConnected

OverlayController
  └── connected? add via the SERVICE's WindowManager with TYPE_ACCESSIBILITY_OVERLAY (nav bar only)
      else       → current TYPE_APPLICATION_OVERLAY path, unchanged
```

- `LockOverlayService` remains the single source of truth for lock state. The accessibility service is
  an input/window capability provider, not a second state owner — it reads `lockState`, never sets it.
- **Fail-open**: if lock state is anything other than `Locked` (including unknown/unreachable),
  `onKeyEvent` returns `false` and no global action or relaunch fires.
- **Never set `isAccessibilityTool="true"`** — this is not a disability tool; a false claim risks
  account termination.
- **Mandatory allowlist**, consulted before any suppression action (BACK consumption, shade dismissal,
  snap-back): Settings/OEM settings, the default dialer/in-call UI, the system alarm/clock, emergency
  alert packages, and TouchLock's own package are never suppressed and never snapped away from.
- **On-device findings that shaped this design** (Samsung SM-S908U, Android 16 — see
  `docs/learnings.md`): the `TYPE_ACCESSIBILITY_OVERLAY` nav-bar overlay does swallow 3-button nav taps
  (Home/Back/Recents) and `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` does reliably close the shade —
  both confirmed and load-bearing for this design. `onKeyEvent`'s BACK consumption does **not** work
  under gesture navigation — a real edge-swipe back gesture is fully handled by the modern predictive
  back path (`OnBackInvokedCallback`) and never reaches `onKeyEvent`. Gesture-nav back, home, and
  recents are therefore all handled the same way: **reactively, via snap-back**, not blocked outright.

---

## Known Constraints / Tradeoffs

### System Bars Remain Accessible While Locked (default mode)

In **default mode**, the overlay is `TYPE_APPLICATION_OVERLAY` and does not hide or block system bars
(status bar, navigation bar). These remain accessible while locked. This is a deliberate tradeoff:
hiding system bars requires a focused/`Activity` window, but a focusable window pushed video call apps
(WhatsApp, Meet) into picture-in-picture mode — the opposite of the desired behavior. The overlay stays
`FLAG_NOT_FOCUSABLE` for this reason, in both default and Strong Lock mode.

**Decision**: Accept system bar accessibility in default mode. It's an edge case; the primary use case
(toddler watching video) is unaffected. **Strong Lock mode (optional)** narrows this: it elevates the
overlay to `TYPE_ACCESSIBILITY_OVERLAY` over the navigation bar region only — confirmed on-device to
swallow nav bar taps (Home/Back/Recents) without requiring focus — and dismisses the notification shade
via `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` if it's opened. The status bar itself is deliberately
never occluded, in either mode: covering it would hide the mic/camera privacy and screen-record/cast
indicators, which Play policy prohibits. Strong Lock also does **not** block gesture-navigation
back/home/recents swipes — those are handled reactively via snap-back (relaunching the protected app),
not blocked outright; see `docs/learnings.md` for the on-device findings behind this.

### Countdown Uses Coroutine for Clock ticks with `delay(1000)`

Countdown ticks use `delay(1000)` in a coroutine, not `AlarmManager`. On heavily loaded devices, ticks may be delayed or skipped. The visual countdown may show non-sequential numbers (9 → 7). Acceptable for a 10-second cosmetic countdown; actual lock engages after the full `repeat(10)` loop.

### Notification Dismissal on Android 12+

Users can force-swipe "ongoing" notifications on Android 12+. Mitigations:
1. `.setAutoCancel(false)` — prevents dismissal on tap
2. `MainActivity.onResume()` → `ACTION_RESTORE_NOTIFICATION` — re-asserts foreground on app resume
3. `assertForegroundState()` called on every state transition

If the app is never brought to foreground after notification dismissal, the service remains running (it's still foreground) but the notification is gone. This is a known Android 12+ platform limitation.

### Focusable System UI Sits on Top of the Overlay, and Can Even Hide It Entirely

Any focusable system `Activity` (an incoming/outgoing call UI, a permission dialog, a `ResolverActivity`
disambiguation sheet) draws on top of the `FLAG_NOT_FOCUSABLE` main overlay and receives touches
normally, regardless of lock state — confirmed on-device with a real call-intent resolver sheet.
This is the same tradeoff as "System Bars Remain Accessible" above, just for foreground Activities
instead of system bars, and is directly relevant to the "hands-free video call" use case: a real
incoming call's UI is fully answerable/dialable despite the lock being active.

Separately, and specific to **Strong Lock**: Android's own anti-tapjacking protection fully hides an
app's `SYSTEM_ALERT_WINDOW`/`TYPE_APPLICATION_OVERLAY` window whenever system Settings is the
foreground app (`dumpsys window` shows `mForceHideNonSystemOverlayWindow=true` for the main overlay in
this state) — confirmed on-device. Since Settings is deliberately allowlisted (BACK must stay
functional there, so the user isn't trapped configuring Strong Lock), a user who ends up in Settings
while locked gets a fully touchable, unblocked Settings screen — only the separate nav-bar strip
(`TYPE_ACCESSIBILITY_OVERLAY`, not subject to this OS restriction) stays blocked. This is a platform
restriction on the overlay window type itself, not something the app can override.

### Revoking `POST_NOTIFICATIONS` While Locked Silently Releases the Lock

Revoking notification permission while locked (`pm revoke ... POST_NOTIFICATIONS`, or the equivalent
Settings toggle) triggers the OS to force-kill the app process as part of permission-cache
invalidation — confirmed on-device via logcat, this is standard Android behavior, not
app-triggered. The service restarts via `START_STICKY` with a null intent, takes the safety-net
branch in `onStartCommand()` (deliberately does not auto-re-lock — see the comment there), and the
fallback notification itself is suppressed by the OS since the permission is gone. Net effect: the
lock is silently released with no visible signal to the supervising parent, since the one channel
that would show a signal (the notification) is exactly what's missing. This is a side effect of two
independently-reasonable, already-documented choices (don't auto-relock on an ambiguous restart; OS
suppresses notifications without permission) rather than an app bug, and isn't fixed here — flagged
as a known, low-probability edge case (requires deliberately revoking a permission while locked).

### Double-Tap Threshold is Fixed

The 400ms double-tap window in `OverlayView` is hardcoded. This may be too fast for some users (especially children's parents with slower tapping). A future improvement could make this configurable or use `ViewConfiguration.getDoubleTapTimeout()`.

### Notification Shade Detection Uses Package Only, Not Class Name (Strong Lock)

An earlier version of `TouchLockAccessibilityService` tried to identify the shade specifically by
matching `event.className` against guessed AOSP/OEM shade window class names
(`NotificationShadeWindowView`, `NotificationPanelView`, etc.). **Confirmed wrong via live device
logs** (Samsung SM-S908U, Android 16): the shade's `TYPE_WINDOW_STATE_CHANGED` event reports a
generic `android.widget.FrameLayout` className, not any OEM-specific class — so the guess never
matched and the shade never auto-dismissed. Reading the real window title (`dumpsys window`
confirms it's literally `"NotificationShade"`) would need `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`,
which isn't otherwise needed anywhere else in this service.

Fixed by dropping class-name matching entirely: any `TYPE_WINDOW_STATE_CHANGED` event whose
package is `com.android.systemui` triggers `dismissShade()`. This is broader than "only the shade,"
but `performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)` is a documented no-op when the
shade isn't open, so a false-positive trigger (e.g. some other SystemUI surface) costs nothing.
Confirmed working end-to-end on-device.

---

## Risks / Technical Debt

| Risk | Severity | Notes |
|------|----------|-------|
| `ObserveUsageTimerUseCase` custom `CoroutineScope` | Medium | Must remain `@Singleton`. If accidentally scoped to ViewModel, scope never cancels and leaks memory. The `@Singleton` annotation is the only guard. |
| No unlock PIN/biometric | Low | By design for MVP. A determined child could exploit the double-tap → hold gesture. |
| `DEBUGGING_GUIDE.md` references removed components | Low | References `OrientationLockActivity`, `ACTION_START`/`ACTION_STOP` (replaced by `ACTION_TOGGLE`). |
| `README.md` lists "Orientation control" as a feature | Low | Feature was removed. README still lists it. |
| Countdown coroutine is not tested in isolation | Medium | `startDelayedLock()` relies on `delay()` inside a `LifecycleCoroutineScope`. Tests would need `StandardTestDispatcher` + `advanceTimeBy()`. Current test coverage for countdown is unclear. |
| `SuppressionAllowlist` Settings/Clock resolution untested | Low | `Intent#resolveActivity()` can't be exercised in pure JVM unit tests (Android SDK stub method); only the own-package/emergency-package/dialer entries have test coverage. Verify the Settings and Clock apps actually resolve on-device. |
| Snap-back relaunch may fight a foreground-service-restricted launcher on some OEMs | Low | `startActivity()` from an `AccessibilityService` should be exempt from Android's background-activity-launch restrictions, but this is unverified on-device across OEMs. |
