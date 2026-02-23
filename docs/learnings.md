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

### Why not AccessibilityService?

- Meant for users with disabilities, not general-purpose touch interception
- Google Play heavily scrutinizes and often rejects misuse
- Requires intrusive permissions and disclosures

> Conclusion: Using AccessibilityService for a kids lock app is overreaching and risky.

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
