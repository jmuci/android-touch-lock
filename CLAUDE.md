# Touch Lock - Claude Code Project Instructions

## Project Overview
Touch Lock is an Android application that temporarily disables touch input by displaying a full-screen WindowManager overlay while keeping underlying content visible. The app is designed for supervised use cases such as toddlers watching videos or video calls.

## Core Constraints
- **Offline-first**: Must remain fully offline and must not require any network access or backend services
- **Minimum SDK**: 26 - Do not introduce compatibility code for lower API levels
- **No Accessibility Services**: Do not introduce Accessibility services or Accessibility permissions unless explicitly instructed
- **No Kiosk Mode**: Do not introduce kiosk mode, device owner APIs, or system gesture blocking

## Technology Stack
- **UI**: Jetpack Compose
- **Dependency Injection**: Hilt
- **State Management**: StateFlow for lock state exposure

## Architecture
The app uses a layered architecture:
- **UI Layer**: Jetpack Compose screens and components
- **ViewModel Layer**: State holders and UI logic
- **Domain Layer**: Use cases and business logic
- **Data Layer**: Repositories
- **System Runtime**: Services, overlays, notifications

### Architectural Rules
- UI code must never directly interact with Android services, WindowManager, or overlays
- All UI actions must flow through ViewModels, use cases, and repositories
- A foreground service owns the overlay lifecycle and is the single source of truth for lock state
- Do NOT move overlay or WindowManager logic into UI or ViewModel layers

## Dependency Injection Guidelines
- Use `@Binds` for interface-to-implementation bindings
- Use `@Provides` only when construction logic is required
- Avoid injecting Activities into long-lived or singleton components

## Permissions & Platform Constraints
- Do NOT introduce new permissions without explicit approval
- Overlay permission (`SYSTEM_ALERT_WINDOW`) must be checked before attempting to show any overlay
- The app must fail safely if overlay permission is missing and must never crash

## Notifications
- Use NotificationCompat for notifications
- Create a notification channel (API 26+)
- Notification icons must be monochrome vector drawable resources

## Development Style
- Prefer explicit, boring, and maintainable solutions over clever abstractions
- Avoid premature optimization or overengineering
- Do not refactor existing architecture unless explicitly requested
- When extending functionality, prefer modifying existing components rather than adding new layers
- All generated code should be complete, paste-ready Kotlin files
- Call out Android platform limitations or Play Store policy risks explicitly when relevant

## Lifecycle & Safety
- Ensure lifecycle correctness when working with services, notifications, and overlays
- Fail safely when permissions are missing
- Avoid crashes caused by system-level APIs (WindowManager, services)
- Clean up overlays defensively on service destruction

## Ignored Paths
The following paths should be ignored when searching/indexing:
- `**/build/**`
- `**/.gradle/**`
- `**/.idea/**`
- `**/*.iml`
- `**/local.properties`
- `**/firebase.json`
- `**/firebender.json`
