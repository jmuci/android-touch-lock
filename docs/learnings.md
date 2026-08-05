# Learnings, Android Patterns and Tradeoffs

Context and educational notes on Android patterns used in this project.

---

## 1. Learnings & Android Patterns Used

### Foreground Notifications

Used to keep the service alive and allow fast user control.

```kotlin
// LockOverlayService
startForeground(
    NOTIFICATION_ID,
    notificationManager.buildUnlockedNotification(),
    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE  // Android 14+ requirement
)

// LockNotificationManager
fun buildUnlockedNotification(): Notification {
    val toggleIntent = Intent(context, LockOverlayService::class.java).apply {
        action = LockOverlayService.ACTION_TOGGLE
    }
    val togglePendingIntent = PendingIntent.getService(
        context, 0, toggleIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_lock_open_24)
        .setContentTitle("Touch Lock ready")
        .setContentText("Tap to lock")
        .setColor(ContextCompat.getColor(context, R.color.purple_200))
        .setColorized(true)      // foreground services only
        .setOnlyAlertOnce(true)
        .setAutoCancel(false)    // prevent tap-dismiss
        .setOngoing(true)        // prevent swipe-dismiss (pre-Android 12)
        .setContentIntent(togglePendingIntent)
        .build()
}
```

The service receives commands through intents:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    when (intent?.action) {
        ACTION_INIT             -> initService()
        ACTION_TOGGLE           -> toggleLock()
        ACTION_DELAYED_LOCK     -> startDelayedLock()
        ACTION_CANCEL_COUNTDOWN -> cancelCountdown()
        ACTION_RESTORE_NOTIFICATION -> restoreNotification()
        ACTION_DISMISS          -> dismissService()
        null                    -> initService()  // system restart
    }
    return START_STICKY
}
```

Key takeaway:

> Foreground services are mandatory for long-running system behavior on modern Android.

---

### Overlay Views (Why not Compose?)

Compose is lifecycle-bound and not ideal for system overlays.

The overlays are defined programmatically extending `LinearLayout` or `FrameLayout` and added to WindowManager:

```kotlin
windowManager.addView(overlayView, fullScreenLayoutParams())
```

Three overlays exist:

- **Main overlay** (`OverlayView`) — full-screen, inter cepts all touches
- **Unlock handle** (`UnlockHandleView`) — 140dp square, appears after double-tap
- **Countdown** (`CountdownOverlayView`) — 180dp circle, `FLAG_NOT_TOUCHABLE` (non-blocking)

Key takeaway:

> Use classic Views for system overlays. It's safer and more predictable than Compose for windows outside the activity lifecycle.

---

### Combining Flows in ViewModel

Instead of exposing multiple flows, combine them into one:

```kotlin
val uiState: StateFlow<TouchLockUiState> = combine(
    observeLockState(),
    observeUsageTimer(),
    observeDebugOverlayVisible(),
    _hasOverlayPermission,
    _areNotificationsAvailable
) { lockState, usageTimer, debugVisible, hasOverlayPerm, areNotifAvailable ->
    TouchLockUiState(
        lockState = lockState,
        usageTimer = usageTimer,
        debugOverlayVisible = debugVisible,
        hasOverlayPermission = hasOverlayPerm,
        areNotificationsAvailable = areNotifAvailable
    )
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = TouchLockUiState()
)
```

Key takeaway:

> Derived state belongs in the ViewModel, not the UI. One flow to collect is better than five.

---

### Usage Timer & Countdown Logic

- Timer runs only while lock is active
- Uses elapsed time accumulation: `elapsed += now - lastStart`

```kotlin
private val scope = CoroutineScope(dispatcher + Job())  // custom scope for ticking

private fun startTimer() {
    if (tickJob?.isActive == true) return  // idempotent

    scope.launch {
        saveUsageData(startTime = timeProvider.currentTimeMillis())
        _timerState.value = _timerState.value.copy(isRunning = true)

        tickJob = scope.launch {
            while (isActive) {
                delay(1000)

                val today = timeProvider.getCurrentDateString()
                if (today != currentDate) {
                    // Midnight rollover: reset
                    currentDate = today
                    _timerState.value = UsageTimerState(elapsedMillisToday = 0L, isRunning = true)
                    lockPreferences.clearUsageData()
                    saveUsageData(startTime = timeProvider.currentTimeMillis())
                } else {
                    _timerState.value = _timerState.value.copy(
                        elapsedMillisToday = _timerState.value.elapsedMillisToday + 1000
                    )
                }
            }
        }
    }
}
```

**Crash recovery**: On `ObserveUsageTimerUseCase` init, if `lastStartTime` is non-null in DataStore, the app was killed while locked. The delta `(now - lastStartTime)` is added to accumulated millis.

Key takeaway:

> Measure time deltas, not wall-clock ticks. Store `lastStartTime` for crash recovery.

---

### DataStore for Persistence

```kotlin
override suspend fun updateUsageData(data: UsageData) {
    dataStore.edit { preferences ->
        preferences[Keys.USAGE_DATE] = data.date
        preferences[Keys.USAGE_ACCUMULATED_MILLIS] = data.accumulatedMillis
        data.lastStartTime?.let { preferences[Keys.USAGE_LAST_START_TIME] = it }
            ?: preferences.remove(Keys.USAGE_LAST_START_TIME)
    }
}
```

Key takeaway:

> DataStore is safer, async, and testable compared to SharedPreferences.

---

### Daily Reset Logic

```kotlin
if (storedDate != today) resetUsage()
```

Key takeaway:

> Never rely on timers or alarms for date rollovers. Date comparison on read is simpler and battery-friendly.

---

### Countdown Cancellation Safety

Pending coroutine jobs can fire unexpectedly during rapid state changes. Always cancel before transitioning state:

```kotlin
fun startLock() {
    cancelCountdown()   // clear any pending callbacks FIRST
    // ... rest of startLock logic
}

fun stopLock() {
    cancelCountdown()   // clear any pending callbacks FIRST
    // ... rest of stopLock logic
}
```

Key takeaway:

> Always cancel pending work before initiating a state transition. Assume stale callbacks exist.

---

## 2. Why Not X? (Design Decisions)

### Why not AccessibilityService? (original decision — reversed 2026-08-04, see addendum below)

- Meant for users with disabilities, not general-purpose touch interception
- Google Play heavily scrutinizes and often rejects misuse
- Requires intrusive permissions and disclosures

> Original conclusion: Using AccessibilityService for a kids lock app is overreaching and risky.
>
> **Reversed 2026-08-04**: the touch overlay alone cannot block the navigation bar or notification
> shade, so a supervised child can still tap Home/Back/Recents or pull down the shade and escape the
> lock — the exact failure mode this app exists to prevent. An `AccessibilityService`, used narrowly
> and only for that purpose (never to read screen content, never a claimed disability tool), is the only
> remaining mechanism capable of closing that gap. It ships as an **optional, off-by-default** mode with
> a dedicated in-app disclosure — the original risk (scope creep, opaque data access) is mitigated by
> keeping the capability minimal and disclosed, not by avoiding the API outright. See the Task 0 findings
> below for what was verified on-device before committing to this reversal.

**2026-08-04 addendum — Task 0 spike, device-verified findings.** This decision is under active
reconsideration (see the "Strong Lock" accessibility plan). Before investing in the real
architecture, a throwaway `AccessibilityService` was sideloaded onto a real device (Samsung
SM-S908U, Android 16 / SDK 36) to settle three unvalidated premises. Code was deleted after
testing; only these findings are kept.

1. **Does a `TYPE_ACCESSIBILITY_OVERLAY` window swallow 3-button nav bar taps?** **Yes, confirmed.**
   A window added via the *service's own* `WindowManager` (not the app's — that throws
   `BadTokenException` for type 2032), sized to the nav bar region with `FLAG_NOT_FOCUSABLE`
   (no `FLAG_NOT_TOUCHABLE`), fully intercepted taps on all three on-screen buttons (Home, Back,
   Recents). Verified two ways: the overlay's own touch listener logged the intercepted events,
   and — more importantly — the foreground activity (Settings) never changed across any of the
   three taps. A baseline with the service disabled confirmed the same tap *does* navigate to the
   launcher, ruling out a broken test.

2. **Does `onKeyEvent` receive `KEYCODE_BACK`?** **No — confirmed broken on gesture nav, this
   Android version.** A real edge-swipe back gesture (touch-injected, not a synthetic key) was
   fully handled by `ShellBackPreview` / `BackAnimationController` via the modern
   `OnBackInvokedCallback` predictive-back path — logs showed the callback dispatch end-to-end,
   and `onKeyEvent` never fired despite `canRequestFilterKeyEvents`/`flagRequestFilterKeyEvents`
   being set and the service confirmed bound (`dumpsys accessibility` showed
   `requestFilterKeyEvents=true`, `capabilities=8`). This matches the plan's stated risk almost
   exactly: predictive back has fully replaced the legacy `KeyEvent` dispatch path that
   `AccessibilityService.onKeyEvent` taps into, at least on Android 16. On 3-button nav the point
   is moot in practice — the overlay already swallows the on-screen Back tap before any key event
   would be generated (see finding 1) — but there is currently **no working mechanism for
   consuming a gesture-nav back swipe** that starts outside the nav-bar-only overlay region. A
   synthetic `adb shell input keyevent` (both `KEYCODE_BACK` and `KEYCODE_VOLUME_DOWN`) also never
   reached `onKeyEvent`, though that test is inconclusive on its own (injected keys may not be
   filtered the same way as real ones) — the real edge-swipe result is the load-bearing one.
3. **Does `performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)` reliably close the
   shade?** **Yes, confirmed.** Opened the shade (`cmd statusbar expand-notifications`), triggered
   the global action, `performGlobalAction` returned `true`, and a screenshot confirmed the shade
   was fully closed.

**Net effect on the plan's capability matrix:** nav-bar tap blocking (finding 1) and shade
dismissal (finding 3) are solid on 3-button nav. The gesture-nav back-swipe mitigation
(`onKeyEvent`) does **not** work as designed on Android 16 — a design relying on it needs
either a fallback (e.g., snap-back after the fact) or must accept gesture-nav back as
unblockable, same as Home/Recents swipes already are.

---

### Why use a system overlay instead?

- Purpose-built for touch blocking
- Clear user intent and permission model (`SYSTEM_ALERT_WINDOW`)
- Accepted pattern for lock/screen filter apps

> Conclusion: Overlays are the correct, minimal, and policy-safe solution.

---

### Why not a full-screen Activity?

- Can be killed by the system
- Breaks the user's current app (video playback, YouTube, etc.)
- Causes video call apps (WhatsApp, Meet) to enter picture-in-picture mode
- Poor UX when switching apps

> Conclusion: An overlay locks _on top of any app_ without disrupting it.

---

### Why not keep everything in Compose?

- Compose relies on lifecycle owners
- Overlay windows live outside normal activity lifecycles
- Higher risk of crashes or leaks

> Conclusion: Classic Views are safer and simpler for system-level UI.

---

### Why not SharedPreferences?

- Sync read can cause ANR on main thread
- DataStore is async and coroutine-friendly
- Better long-term maintainability

> Conclusion: DataStore for all persistence.

---

### Why not timers or alarms for daily reset?

- Unreliable, battery-hungry, complex to manage
- Date comparison on DataStore read is instant and simple

> Conclusion: `if (storedDate != today) resetUsage()` is sufficient.

---

### Why a Foreground Service?

Modern Android aggressively kills background work. A foreground service:
- Keeps the lock active when the app is backgrounded
- Keeps the overlay visible
- Maintains system transparency via persistent notification
- Survives process death via `START_STICKY`

---

## 3. Summary of Design Decisions

- Prefer **single consolidated UI state** over many flows
- Service owns system behavior, ViewModel owns UI state
- Overlays instead of full-screen activities
- Avoid over-engineering for a focused utility app
- Cancel pending work before state transitions (countdown safety)
- Multi-layer notification protection on Android 12+
