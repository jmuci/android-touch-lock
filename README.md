# Touch Lock
[![Android Automatic Unit Tests Run](https://github.com/jmuci/android-touch-lock/actions/workflows/unit-tests.yml/badge.svg)](https://github.com/jmuci/android-touch-lock/actions/workflows/unit-tests.yml)


**Touch Lock** is a lightweight Android utility app that temporarily disables touch input on the screen while keeping content visible.

It is designed for **supervised scenarios**, such as:

- Letting a toddler watch a video without accidentally tapping UI controls
    
- Preventing hang-ups or unintended interactions during video calls
    
- Displaying content hands-free (recipes, presentations, timers, etc.)
    

The app works fully **offline** and does not require an account or network access.

---

## ✨ Key Features

- 🔒 **Touch-blocking overlay** that prevents all screen interaction
    
- 🔔 **Quick activation via persistent notification**
    
- 🔄 **Orientation control** (portrait, landscape, or follow system)
    
- 🔓 **Safe unlock gesture** (intentional long-press)
    
- 📴 **Works without internet**
    
- 🧩 Designed to be simple, transparent, and Play Store–compliant
    

> Touch Lock does **not** attempt to be a full parental control or kiosk app.  
> It focuses on a single, well-defined problem: temporarily disabling touch input.

---

## 🚫 What This App Does _Not_ Do (by Design)

- It does **not** monitor or inspect other apps’ UI
    
- It does **not** collect usage data or analytics
    
- It does **not** block system gestures (e.g. notification shade)
    
- It does **not** require Accessibility services
    

These constraints are intentional and align with Android platform and Play Store best practices.

---

## 🏗️ Architecture Overview

Touch Lock follows a **clean, production-oriented architecture**, optimized for correctness, lifecycle safety, and extensibility—without unnecessary complexity.

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

- **Single source of truth**:  
    The foreground service owns the lock state. UI only _requests_ changes.
    
- **Loose coupling**:  
    UI does not directly interact with system services or `WindowManager`.
    
- **Lifecycle-aware**:  
    The overlay continues working even if the UI process is killed.
    
- **Offline-first**:  
    No network dependencies; configuration is stored locally.
    

---

## 🧩 Main Components

### UI Layer

- **Jetpack Compose** for all screens
    
- **Navigation Compose** for simple in-app navigation
    
- **ViewModels + StateFlow** for reactive state handling
    

### Overlay Runtime

- **Foreground Service** to ensure reliability while the app is backgrounded
    
- **WindowManager overlay** (`TYPE_APPLICATION_OVERLAY`) to block touch input
    
- Custom overlay view that:
    
    - Consumes all touch events
        
    - Detects an intentional unlock gesture
        

### Persistence

- **Preferences DataStore**
    
    - Orientation mode
        
    - Unlock configuration
        
- No database required for MVP
    

### Dependency Injection

- **Hilt** for DI
    
- Singleton repository and controllers
    
- Clear separation between Android framework code and business logic
    

---

## 🔐 Permissions & Privacy

Touch Lock requests **only one special permission**:

### Draw over other apps

Required to display the touch-blocking overlay on top of other applications.

- No Accessibility permission is used
    
- No data leaves the device
    
- No personal information is collected
    

Permission usage is clearly explained in the UI before redirecting to system settings.

---

## 🛠️ Tech Stack

- **Kotlin**
    
- **Jetpack Compose**
    
- **StateFlow / Coroutines**
    
- **Hilt**
    
- **DataStore**
    
- **Foreground Service**
    
- **WindowManager overlays**
    

Minimum SDK and target SDK follow current Android recommendations.

---

## 📦 Project Structure (Simplified)

```
ui/            → Compose UI, ViewModels, navigation
domain/        → Models, use cases, repository interfaces
data/          → Repository implementations, DataStore
overlay/       → Foreground service, overlay controller, view
notification/  → Notification management
permission/    → Overlay permission handling
di/            → Hilt modules
```

---

## 🔮 Future Enhancements (Out of Scope for MVP)

- PIN or pattern-based unlock
    
- Accessibility-assisted gesture detection (with explicit justification)
    
- Usage statistics
    
- App pinning detection and guidance
    
- Tablet / multi-window optimizations
    

These are intentionally excluded from the initial release to keep scope tight and risk low.

---

## ⚠️ Disclaimer

Touch Lock is intended for **temporary, supervised use**.  
It is not a replacement for full parental control solutions or device management tools.

---

## 📄 License

MIT License
