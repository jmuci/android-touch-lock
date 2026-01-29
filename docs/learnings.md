# Learnings, Android Patterns and Tradeoffs

The purpose of this document is to provide context but also educational to review common Android patterns that aren't applied all the time.


---

## 1. Learnings & Android Patterns Used

### Foreground Notifications

Used to keep the service alive and allow fast user control.

This is how it's started
```kotlin

    // LockOverlayService
            startForeground(
                NOTIFICATION_ID,
                notificationManager.buildUnlockedNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
    
    // Notfication building at notification manager
    fun buildUnlockedNotification(): Notification {
        val toggleIntent = Intent(context, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_TOGGLE
        }

        val togglePendingIntent = PendingIntent.getService(
            context,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_open_24)
            .setContentTitle("Touch Lock ready")
            .setContentText("Tap to lock")
            .setColor(ContextCompat.getColor(context, R.color.purple_200))
            .setColorized(true) // foreground services only
            .setOnlyAlertOnce(true)
            .setContentIntent(togglePendingIntent)
            .setOngoing(true)
            .build()
    }

```
The service receives commands through intents: 
```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_INIT -> initService()
            ACTION_START -> startLock()
            ACTION_STOP -> stopLock()
            // ...
        }
    }
```


Key takeaway:

> Foreground services are mandatory for long‑running system behavior on modern Android.

---

### Overlay Views (Why not Compose?)

Compose is lifecycle‑bound and not ideal for system overlays.

The overlays are defined programtically extending `LinearLayout` or `FrameLaout` and are added to the window manager like this:
```kotlin
        windowManager.addView(overlayView, fullScreenLayoutParams(orientationMode))
```


Key takeaway:

> Use classic Views for system overlays. It’s safer and more predictable.

---

### Combining Flows in ViewModel

Instead of listening to multiple flows, we combine them like this: `combine(flowA, flowB, flowC) { a, b, c -> UiState(a, b, c) }`

This is how the VM does it: 
```kotlin
    val uiState: StateFlow<TouchLockUiState> = combine(
        observeLockState(),
        observeOrientationMode(),
        _hasOverlayPermission,
        _areNotificationsAvailable,
        observeUsageTimer()
    ) { lockState, orientationMode, hasOverlayPermission, areNotificationsAvailable, usageTimer ->
        TouchLockUiState(
            lockState = lockState,
            orientationMode = orientationMode,
            hasOverlayPermission = hasOverlayPermission,
            areNotificationsAvailable = areNotificationsAvailable,
            usageTimer = usageTimer
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TouchLockUiState()
    )
```


Key takeaway:

> Derived state belongs in the ViewModel, not the UI.

---

### Usage Timer & Countdown Logic

- Timer runs only while lock is active

- Uses elapsed time accumulation instead of ticking clocks

`elapsed += now - lastStart`

This how time ticks are emitted while the lock screen is on: 
```kotlin
    private val scope = CoroutineScope(dispatcher + Job()) // Custom coroutine scope for ticking
    //[...]
    private fun startTimer() {
        if (tickJob?.isActive == true) return // Already running

        scope.launch {
            // Save start time immediately
            saveUsageData(startTime = timeProvider.currentTimeMillis())

            _timerState.value = _timerState.value.copy(isRunning = true)

            // Start tick job for real-time updates
            tickJob = scope.launch {
                while (isActive) { // isActive Returns true when the coroutine is still active. Available within coroutine scopes
                    delay(1000) // Update every second and throws CancellationException if cancelled. This would be enough to cancel

                    // Check for midnight rollover
                    val today = timeProvider.getCurrentDateString()
                    if (today != currentDate) {
                        // New day! Reset timer
                        currentDate = today
                        _timerState.value = UsageTimerState(
                            elapsedMillisToday = 0L,
                            isRunning = true
                        )
                        lockPreferences.clearUsageData()
                        saveUsageData(startTime = timeProvider.currentTimeMillis())
                    } else {
                        // Increment elapsed time
                        _timerState.value = _timerState.value.copy(
                            elapsedMillisToday = _timerState.value.elapsedMillisToday + 1000
                        )
                    }
                }
            }
        }
    }

    private fun stopTimer() {
        tickJob?.cancel()
        tickJob = null

        scope.launch {
            _timerState.value = _timerState.value.copy(isRunning = false)
            saveUsageData(stopTime = true)
        }
    }

```


Key takeaway:

> Measure time deltas, not wall‑clock ticks.

---

### DataStore for Persistence

`val dataStore = context.dataStore`

Used for:

- Daily usage

- Last active date

Usage example:
```kotlin

    override val orientationMode: Flow<OrientationMode> = dataStore.data
        .map { preferences ->
            val modeName = preferences[Keys.ORIENTATION_MODE] ?: OrientationMode.FOLLOW_SYSTEM.name
            OrientationMode.valueOf(modeName)
        }

    override suspend fun setOrientationMode(mode: OrientationMode) {
        dataStore.edit { preferences ->
            preferences[Keys.ORIENTATION_MODE] = mode.name
        }
    }

```

Key takeaway:

> DataStore is safer, async, and testable compared to SharedPreferences.

---

### Daily Reset Logic

if (storedDate != today) resetUsage()

Key takeaway:

> Never rely on timers for date rollovers.

---

## 2. Why Not X? (Design Decisions)

This section explains _intentional choices_ made in the app and why certain Android APIs or approaches were **explicitly not used**.

### Why not AccessibilityService?

**AccessibilityService** is often suggested for touch interception, but it was intentionally avoided.

**Reasons:**

- Accessibility services are meant for users with disabilities, not general-purpose control

- Google Play heavily scrutinizes and often rejects apps misusing accessibility

- Requires intrusive permissions and disclosures

- Harder to justify to users and reviewers


**Conclusion:**

> Using AccessibilityService for a kids lock app would be overreaching and risky.

---

### Why use a system overlay instead?

The app uses a **SYSTEM_ALERT_WINDOW overlay** to intercept touch events.

**Benefits:**

- Purpose-built for touch blocking

- Clear user intent and permission model

- Predictable lifecycle

- Accepted pattern for lock / screen filter apps


windowManager.addView(overlayView, layoutParams)

**Conclusion:**

> Overlays are the correct, minimal, and policy-safe solution.

---

### Why not a full-screen Activity?

A full-screen Activity could theoretically block touches, but:

- Can be killed by the system

- Breaks the user’s current app (video playback, YouTube, etc.)

- Poor UX when switching apps


**Conclusion:**

> An overlay allows locking _on top of any app_ without disrupting it.

---

### Why not keep everything in Compose?

Compose is excellent for UI, but **not ideal for system overlays**.

**Reasons:**

- Compose relies on lifecycle owners

- Overlay windows live outside normal activity lifecycles

- Higher risk of crashes or leaks


**Conclusion:**

> Classic Views are safer and simpler for system-level UI.

---

### Why not SharedPreferences?

The app uses **DataStore** instead of SharedPreferences.

**Reasons:**

- Async and coroutine-friendly

- Safer against ANRs

- Better long-term maintainability


context.dataStore.edit { prefs ->

prefs[USAGE_TIME] = value

}

---

### Why not timers or alarms for daily reset?

Timers and alarms are unreliable for date rollovers.

**Instead:**

- Store the last active date

- Compare against today on read


if (storedDate != today) resetUsage()

**Conclusion:**

> Date comparison is simpler, safer, and battery-friendly.

---

### Why a Foreground Service?

Modern Android aggressively kills background work.

**Foreground service ensures:**

- Lock remains active

- Overlay is not removed

- System transparency via notification


startForeground(NOTIFICATION_ID, notification)

---

### Why this architecture?

- ViewModel owns state

- Service owns system behavior

- UI is reactive and dumb


**This results in:**

- Testable logic

- Predictable behavior

- Minimal Android-specific coupling




## 3. Summary of Our Design Discussions

- Prefer **single consolidated UI state** over many flows

- Keep services thin

- Let ViewModel orchestrate time and persistence

- Use overlays instead of full‑screen activities

- Avoid over‑engineering for a focused utility app


All of these decisions are reflected cleanly in the final codebase.