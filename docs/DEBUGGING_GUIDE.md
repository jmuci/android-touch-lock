# Touch Lock Debugging Guide

Practical techniques for debugging and diagnosing issues in Touch Lock.

## Table of Contents

1. [Setting Up Debugging](#setting-up-debugging)
2. [Common Issues & Solutions](#common-issues--solutions)
3. [Logcat Filtering Techniques](#logcat-filtering-techniques)
4. [Testing Scenarios](#testing-scenarios)
5. [Performance Profiling](#performance-profiling)
6. [Known Issues](#known-issues)

---

## Setting Up Debugging

### Enable Timber Logging

Timber logs in debug builds only (`BuildConfig.DEBUG`). Use Android Studio's Logcat view, build a debug variant, and filter by tag or component name.

### Enable Layout Inspector

**Android Studio** → **Tools** → **Layout Inspector** → select device and app. Useful for verifying overlay view hierarchy and visibility.

### Enable Debug Tint (In-App)

The "Debug" button (visible in debug builds only) toggles a 40% red tint on the main overlay. Use this to confirm the `OverlayView` is attached and positioned correctly.

---

## Common Issues & Solutions

### Issue 1: Lock Not Engaging

**Symptoms**: Tap "Enable" but no overlay appears after 10-second countdown.

**Debug Steps**:

1. **Check overlay permission**:
   ```bash
   adb shell dumpsys package com.tenmilelabs.touchlock | grep -i "ALERT_WINDOW"
   ```
   Expected: `android.permission.SYSTEM_ALERT_WINDOW: true`

2. **Check logs**:
   ```bash
   adb logcat | grep "LockOverlayService"
   ```
   Look for: `"startLock() called"` and `"Lock state changed to LOCKED"`
   Or: `"Missing SYSTEM_ALERT_WINDOW permission"` (abort reason)

3. **Check countdown completed**:
   ```bash
   adb logcat | grep "Countdown"
   ```
   Should see ticks from 10 → 0 then `"startLock() called"`

**Solutions**:
- If permission missing: UI shows "Request permission" button — grant it from Settings
- If countdown aborted: check `cancelCountdown()` was not called unexpectedly
- If service not starting: verify manifest has `LockOverlayService` declared with `foregroundServiceType="specialUse"`

---

### Issue 2: Unlock Not Working

**Symptoms**: Double-tap doesn't show unlock handle, or handle doesn't respond to hold.

**Debug Steps**:

1. **Verify overlay is visible** with the debug tint toggle (red = overlay attached)

2. **Check touch events**:
   ```bash
   adb logcat | grep "onTouchEvent"
   ```
   Expected sequence:
   ```
   OverlayView.onTouchEvent ACTION_DOWN
   Tap detected, tapCount: 1
   OverlayView.onTouchEvent ACTION_DOWN
   Tap detected, tapCount: 2
   Double-tap detected! Calling onDoubleTapDetected()
   UnlockHandleView.init() called
   ```

3. **Check handle interaction**:
   ```bash
   adb logcat | grep "UnlockHandleView"
   ```
   Look for: `"ACTION_DOWN, starting long-press timer"` → `"long press triggered, calling onUnlockRequested()"`

**Solutions**:
- Double-tap must be within 400ms — try tapping faster
- Handle hold must be at least 1000ms — hold longer
- Handle auto-hides after 4 seconds — try again if it disappeared

---

### Issue 3: Countdown Not Auto-Locking

**Symptoms**: Countdown reaches 0 but lock doesn't engage.

**Debug Steps**:

1. **Check countdown ticks**:
   ```bash
   adb logcat | grep "Countdown tick"
   ```
   Should see: `10 → 9 → 8 → ... → 1 → 0`

2. **Check lock triggered**:
   ```bash
   adb logcat | grep "startLock"
   ```
   Should appear after countdown reaches 0.

3. **Check for cancellation**:
   ```bash
   adb logcat | grep "cancelCountdown"
   ```
   If this appears mid-countdown, something triggered `stopLock()` or `cancelCountdown()`.

**Solutions**:
- Verify `ACTION_CANCEL_COUNTDOWN` intent wasn't sent (notification "Cancel" button)
- On heavily loaded devices, ticks may be delayed (coroutine-based `delay()`, not AlarmManager)
- Check that permission is still granted (countdown proceeds but lock will fail at `startLock()` without it)

---

### Issue 4: Service Killed Unexpectedly

**Symptoms**: Lock stops working, notification disappears.

**Debug Steps**:

1. **Check service status**:
   ```bash
   adb shell dumpsys activity services com.tenmilelabs.touchlock
   ```

2. **Check notification status**:
   ```bash
   adb shell dumpsys notification | grep touchlock
   ```

3. **Check logs for `onDestroy`**:
   ```bash
   adb logcat | grep "LockOverlayService.*onDestroy"
   ```

**Solutions**:
- Verify `START_STICKY` is returned from `onStartCommand()` (it is, by design)
- Check `assertForegroundState()` is called on every state transition
- If user dismissed notification: bring app to foreground — `onResume()` restores it via `ACTION_RESTORE_NOTIFICATION`
- Test with "Don't keep activities" enabled in Developer Options

---

### Issue 5: Notification Disappeared

**Symptoms**: Service is running but notification not visible in shade.

**Root Cause**: Android 12+ allows users to force-swipe "ongoing" notifications.

**Recovery**: Open the app — `MainActivity.onResume()` sends `ACTION_RESTORE_NOTIFICATION` which re-asserts foreground status and rebuilds the notification.

**Debug**:
```bash
adb logcat | grep "restoreNotification\|assertForegroundState"
```

---

## Logcat Filtering Techniques

**Service logs only**:

```bash
adb logcat | grep "LockOverlayService"
```

**Overlay-related logs**:

```bash
adb logcat | grep -E "OverlayView|UnlockHandleView|OverlayController|CountdownOverlay"
```

**State transitions only**:

```bash
adb logcat | grep "Lock state"
```

**Errors and warnings only**:

```bash
adb logcat "*:W" | grep "touchlock"
```

**App process only**:

```bash
adb shell pidof com.tenmilelabs.touchlock | xargs -I{} adb logcat --pid {}
```

**Capture to file**:

```bash
adb logcat > debug_session.log &
# interact with app, then Ctrl+C
grep "Lock state" debug_session.log
```

---

## Testing Scenarios

### Scenario 1: Basic Lock/Unlock Cycle

1. Open app → tap "Enable"
2. Wait for 10-second countdown
3. Verify overlay appears + notification shows "Touch Lock active"
4. Double-tap screen → verify unlock handle appears
5. Hold handle 1 second → verify overlay disappears
6. Verify notification returns to "Touch Lock ready"

**Expected logs**:

```
startDelayedLock() called
Countdown tick: 9s remaining
...
startLock() called
Lock state changed to LOCKED
Double-tap detected!
UnlockHandleView.init()
stopLock() called
Lock state changed to UNLOCKED
```

---

### Scenario 2: Notification Toggle

1. Enable lock (wait for countdown)
2. Tap notification → verify lock disables
3. Tap notification again → verify lock re-enables (immediately, no countdown)

**Expected logs**:

```
toggleLock() called
Locked -> toggling to unlocked
...
toggleLock() called
Unlocked -> toggling to locked
```

---

### Scenario 3: Cancel Countdown

1. Tap "Enable" → countdown starts
2. Tap "Cancel" in the countdown notification
3. Verify overlay does NOT appear
4. Verify notification returns to "Touch Lock ready"

**Expected logs**:

```
startDelayedLock() called
Countdown tick: 9s remaining
cancelCountdown() called
Lock state: UNLOCKED
```

---

### Scenario 4: App Backgrounding While Locked

1. Enable lock (wait for countdown)
2. Press home → open another app
3. Pull down notification shade → verify notification shows "Touch Lock active"
4. Tap notification → verify lock toggles off
5. Re-open Touch Lock app → verify UI reflects unlocked state

---

### Scenario 5: Permission Denial

1. Revoke "Draw over other apps" from Settings
2. Try to enable lock
3. Verify UI shows permission warning (no crash)

**Expected logs**:

```
Missing SYSTEM_ALERT_WINDOW permission, cannot start lock
```

---

### Scenario 6: Notification Restore After Dismissal (Android 12+)

1. Enable lock
2. Force-swipe notification from shade (may require long-press)
3. Open the app
4. Verify notification reappears in shade

**Expected logs**:

```
onResume: sending ACTION_RESTORE_NOTIFICATION
restoreNotification() called
assertForegroundState() called
```

---

## Performance Profiling

**CPU while locked** (expect <1%):

```bash
adb shell top -n 1 | grep "com.tenmilelabs"
```

**Memory** (expect <50MB PSS):

```bash
adb shell dumpsys meminfo com.tenmilelabs.touchlock
```

**Service restart count** (expect 1 at startup):

```bash
adb logcat | grep "initService" | wc -l
```

---

## Known Issues

### Accidental Double-Tap

**Problem**: Unintentional double-taps can reveal the unlock handle.

**Current behavior**: Working as designed. The 400ms window is intentional. The handle auto-hides after 4 seconds, and still requires a 1-second hold to actually unlock.

---

### Video Call Apps Enter PiP Mode

**Problem**: WhatsApp, Zoom, Meet may auto-minimize to picture-in-picture when the overlay is shown.

**Root cause**: These apps detect a new fullscreen window and assume a call should minimize. This is in the calling app, not Touch Lock.

**Status**: Known limitation. Documented in README. A previous attempt to fix this (using an OrientationLockActivity) was removed as it made the problem worse.

---

### Countdown Ticks May Skip on Loaded Devices

**Problem**: On a heavily loaded device, the countdown may show non-sequential numbers (9 → 7).

**Root cause**: `delay(1000)` inside a coroutine on a loaded main thread is not guaranteed to be exact.

**Impact**: Cosmetic only — actual lock engages after all 10 ticks complete.

---

### Notification Dismissed on Android 12+

**Problem**: User can force-swipe "ongoing" notifications.

**Current mitigations**:
1. `.setAutoCancel(false)` prevents tap-dismiss
2. `MainActivity.onResume()` restores notification on app open
3. `assertForegroundState()` re-asserts on every state change

**Remaining gap**: If app is never opened after dismissal, notification stays gone (service is still running).

---

## Debugging Checklist

- [ ] Timber logs visible in Logcat (debug build)
- [ ] Overlay permission granted (`Settings → Apps → Touch Lock → Draw over other apps`)
- [ ] Notification permission granted
- [ ] Service running (`adb shell dumpsys activity services com.tenmilelabs.touchlock`)
- [ ] Notification visible in shade
- [ ] Layout Inspector shows `OverlayView` in hierarchy when locked
- [ ] Touch events reaching `OverlayView` (check `onTouchEvent` logs)
- [ ] No `E/` errors in Logcat
- [ ] Service restarts correctly after kill (`START_STICKY` behavior)
