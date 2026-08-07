# Touch Lock
[![Android Automatic Unit Tests Run](https://github.com/jmuci/android-touch-lock/actions/workflows/unit-tests.yml/badge.svg)](https://github.com/jmuci/android-touch-lock/actions/workflows/unit-tests.yml)


**Touch Lock** is a lightweight Android utility app that temporarily disables touch input on the screen while keeping content visible.

It is designed for **supervised scenarios**, such as:

- Letting a toddler watch a video without accidentally tapping UI controls

- Preventing hang-ups or unintended interactions during video calls

- Displaying content hands-free (recipes, presentations, timers, etc.)


The app works fully **offline** and does not require an account or network access.

---

## Key Features

- **Touch-blocking overlay** that prevents all screen interaction

- **Quick activation via persistent notification**

- **10-second countdown** before locking (with cancel option)

- **Daily Usage Tracking** – Tracks how long the lock has been active today

- **Safe unlock gesture** – double-tap to reveal handle, then hold 1 second to unlock

- **Haptic feedback** on lock and unlock

- **Works without internet**

- Designed to be simple, transparent, and Play Store-compliant

| Main UI | Unlock Handle | Notification Lock |
|---------|---------------|-------------------|
| ![Main UI](docs/screenshots/main_ui.png) | ![Unlock Handle](docs/screenshots/unlock_handle.png) | ![Notification Lock](docs/screenshots/notification_lock.png) |

> Touch Lock does **not** attempt to be a full parental control or kiosk app — there's no device-owner
> mode, no app blocking, no remote management. It focuses on one well-defined problem: temporarily
> disabling touch input, with an optional Strong Lock mode that narrows (but does not eliminate) a few
> specific escape routes.

---

## How It Works

1. Parent starts a video or app

2. Enables Touch Lock via notification or app UI (10-second countdown)

3. Overlay intercepts all touch events

4. Usage timer runs only while lock is enabled

5. Usage resets automatically at midnight

> **Known limitations** (default mode — touch overlay only):
> - Gestures on the system UI (drag the notifications bar or press on the navigation menu)
> - Some video call apps (e.g. WhatsApp) may automatically minimize
> the call to picture-in-picture when touch locking is enabled. This behavior is
> controlled by the calling app and cannot be overridden safely by Touch Lock.
>
> An optional **Strong Lock** mode (see below) narrows the first limitation: it blocks navigation bar
> taps and auto-dismisses the notification shade while locked. It does not make the app unescapable —
> on gesture-navigation devices, back/home/recents swipes are not blocked, only reacted to (the
> protected app is relaunched near-instantly if the user swipes away). Shade auto-dismiss additionally
> requires Android 12 (API 31) or higher — the platform API it relies on doesn't exist on older
> versions. On Android 8–11, Strong Lock still blocks the navigation bar and snaps back, but the
> shade itself stays open if pulled down.

---

## What This App Does _Not_ Do (by Design)

- It does **not** collect usage data or analytics, and nothing leaves the device — in either mode below

- It does **not** introduce kiosk mode, device owner APIs, or system gesture blocking

- In its **default mode**, it does **not** require Accessibility services, does not monitor other apps'
  UI, and does not block system gestures (e.g. notification shade, status bar)

- An optional **Strong Lock** mode (off by default) uses an Android Accessibility service, scoped
  narrowly to parental-supervision lock enforcement:
  - It receives window-change events carrying the **foreground app's package name** — never screen
    content — to block navigation bar taps and relaunch the protected app if the user navigates away
    while locked
  - It never reads, records, or transmits what's on screen
  - It never occludes the status bar or its privacy/camera/mic/cast indicators
  - It requires an explicit, dedicated in-app disclosure and consent before enabling — declining leaves
    the app exactly as it works in default mode


These constraints are intentional and align with Android platform and Play Store best practices.

---

## Technical Documentation

For engineering-focused documentation:

- **[Architecture Guide](docs/ARCHITECTURE.md)** – System design, data flow, component responsibilities, known constraints
- **[Debugging Guide](docs/DEBUGGING_GUIDE.md)** – Troubleshooting, logcat tips, common issues
- **[Testing Guide](docs/TESTING_GUIDE.md)** – Testing strategy, examples, anti-patterns
- **[Learnings & Tradeoffs](docs/learnings.md)** – Design decisions and why-not-X explanations

---

## Architecture Overview

Touch Lock follows a clean, layered architecture optimized for correctness and lifecycle safety.

### High-level layers

```
          UI (Jetpack Compose)
                   ↓
          ViewModel (StateFlow)
                   ↓
          Domain / Use Cases
                   ↓
              Repository
                   ↓
Foreground Service + Overlay Runtime
```

### Core principles

- **Single source of truth**: The foreground service owns the lock state. UI only _requests_ changes.

- **Loose coupling**: UI does not directly interact with system services or `WindowManager`.

- **Lifecycle-aware**: The overlay continues working even if the UI process is killed.

- **Offline-first**: No network dependencies; configuration is stored locally via DataStore.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full details.

---

## Project Structure

```
ui/            → Compose UI, ViewModels, state
domain/        → Models, use cases, repository interfaces
platform/      → Repository implementations, DataStore, overlays, permissions, haptics, time
service/       → Foreground service (LockOverlayService)
di/            → Hilt modules
```

---

## Permissions & Privacy

Touch Lock requests:

- `SYSTEM_ALERT_WINDOW` – Required for touch overlay (draw over other apps)
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` – Required for persistent lock
- `POST_NOTIFICATIONS` – Required to show the persistent notification
- `VIBRATE` – Haptic feedback on lock/unlock

**Optional** — only if you enable Strong Lock mode:

- An Accessibility service (enabled via Android Settings, not a manifest-granted permission) – used
  exclusively for parental-supervision lock enforcement: blocking navigation bar taps and relaunching
  the protected app if you navigate away while locked. Reading window-change events (foreground package
  name only, never screen content) is the extent of what this service does. Shown to you via a
  dedicated in-app disclosure screen before you're taken to Settings to enable it; declining leaves the
  app working exactly as it does without it.

No user data is collected. No network access. No data leaves the device — in either mode.

---

## Disclaimer

Touch Lock is intended for **temporary, supervised use**.
It is not a replacement for full parental control solutions or device management tools.

---

## License

MIT License
