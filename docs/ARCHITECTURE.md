# Touch Lock — Architecture Guide

> Single source of truth for system design, component responsibilities, data flow, and key decisions.
> Update this file whenever architecture, data flow, permissions, or core behavior changes.

---

## Project Overview

Touch Lock is a lightweight Android utility that disables touch input via a full-screen `WindowManager` overlay while keeping underlying content visible. The primary use case is supervised scenarios (toddler watching video, preventing hang-ups during video calls). The app is intentionally offline-first, requires no account, and avoids Accessibility services.

**Key constraints**: minSdk 26, no network, no Accessibility services, no kiosk mode, no device owner APIs.

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

## Known Constraints / Tradeoffs

### System Bars Remain Accessible While Locked

The overlay is `TYPE_APPLICATION_OVERLAY` but does not hide system bars (status bar, navigation bar). These remain accessible while locked. This is a deliberate tradeoff: hiding system bars requires an `Activity` window, but using an `Activity` for this purpose caused video call apps (WhatsApp, Meet) to enter picture-in-picture mode — the opposite of the desired behavior.

**Decision**: Accept system bar accessibility. It's an edge case; the primary use case (toddler watching video) is unaffected.

### Countdown Uses Coroutine for Clock ticks with `delay(1000)`

Countdown ticks use `delay(1000)` in a coroutine, not `AlarmManager`. On heavily loaded devices, ticks may be delayed or skipped. The visual countdown may show non-sequential numbers (9 → 7). Acceptable for a 10-second cosmetic countdown; actual lock engages after the full `repeat(10)` loop.

### Notification Dismissal on Android 12+

Users can force-swipe "ongoing" notifications on Android 12+. Mitigations:
1. `.setAutoCancel(false)` — prevents dismissal on tap
2. `MainActivity.onResume()` → `ACTION_RESTORE_NOTIFICATION` — re-asserts foreground on app resume
3. `assertForegroundState()` called on every state transition

If the app is never brought to foreground after notification dismissal, the service remains running (it's still foreground) but the notification is gone. This is a known Android 12+ platform limitation.

### Double-Tap Threshold is Fixed

The 400ms double-tap window in `OverlayView` is hardcoded. This may be too fast for some users (especially children's parents with slower tapping). A future improvement could make this configurable or use `ViewConfiguration.getDoubleTapTimeout()`.

---

## Risks / Technical Debt

| Risk | Severity | Notes |
|------|----------|-------|
| `ObserveUsageTimerUseCase` custom `CoroutineScope` | Medium | Must remain `@Singleton`. If accidentally scoped to ViewModel, scope never cancels and leaks memory. The `@Singleton` annotation is the only guard. |
| No unlock PIN/biometric | Low | By design for MVP. A determined child could exploit the double-tap → hold gesture. |
| `DEBUGGING_GUIDE.md` references removed components | Low | References `OrientationLockActivity`, `ACTION_START`/`ACTION_STOP` (replaced by `ACTION_TOGGLE`). |
| `README.md` lists "Orientation control" as a feature | Low | Feature was removed. README still lists it. |
| Countdown coroutine is not tested in isolation | Medium | `startDelayedLock()` relies on `delay()` inside a `LifecycleCoroutineScope`. Tests would need `StandardTestDispatcher` + `advanceTimeBy()`. Current test coverage for countdown is unclear. |
| `OverlayController` has no unit tests | Medium | Pure Android class (WindowManager). Would need Robolectric or instrumented tests. |
