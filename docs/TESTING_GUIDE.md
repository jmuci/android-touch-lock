# Testing Guide for Touch Lock

This document covers testing best practices for Android services, ViewModels, and the specific testing strategy for LockOverlayService.

## Table of Contents

1. [Android Testing Fundamentals](#android-testing-fundamentals)
2. [Service Testing Best Practices](#service-testing-best-practices)
3. [LockOverlayService Testing Strategy](#lockoverlayservice-testing-strategy)
4. [Testing Implementation Plan](#testing-implementation-plan)
5. [Test Examples](#test-examples)

---

## Android Testing Fundamentals

### Test Types Pyramid

```
        ⬆️  Integration Tests (least reliable, slowest, most complete)
       /\
      /  \
     /    \
    /      \
   /        \
  /          \
 /            \
/______________\  Unit Tests (most reliable, fastest, focused)
```

**For Touch Lock**:
- **Unit Tests**: 70% - ViewModels, UseCases, Repositories with fakes
- **Integration Tests**: 20% - Android-specific components (Services, Activities)
- **E2E Tests**: 10% - Full app flows (optional for MVP)

### Test Dependency Categories

| Category | Purpose | Best For |
|----------|---------|----------|
| **JUnit** | Basic test framework | All tests |
| **Mockk** | Mocking Android objects | Isolating dependencies |
| **Google Truth** | Fluent assertions | Clear test failures |
| **Turbine** | Flow testing | StateFlow assertions |
| **Coroutines Test** | Test dispatcher | Deterministic async tests |
| **Hilt Testing** | DI in tests | Real object graphs |
| **Robolectric** | Android simulation | Offline Android testing |
| **Espresso** | UI testing | Integration tests |

---

## Service Testing Best Practices

### 1. **Don't Unit Test Services Alone**

❌ **Why NOT to unit test in isolation**:
- Services are tightly coupled to Android lifecycle
- `onStartCommand()`, `onBind()`, `onCreate()` are called by the framework
- Testing without a framework is artificial and misses real issues
- Mocking everything defeats the purpose

✅ **Better approach**: Test the service's dependencies (repositories, controllers) in unit tests, then test the service integration with those fakes.

### 2. **Test Service Logic via Repository Interface**

Your current architecture is already **excellent** for this:

```
UI → UseCase → Repository (abstraction) → LockOverlayService (implementation)
                                     ↓
                            Test with Fake Repository
```

The `LockRepositoryImpl` is what calls the service. You can test it without launching the actual service.

### 3. **Service Lifecycle Testing Requires Instrumentation**

If you **need** to test service lifecycle specifically:
- Use `ServiceTestRule` (from androidx.test)
- Requires **instrumented tests** (Android device/emulator)
- Much slower, use sparingly
- Good for: service restart, foreground notification, binding lifecycle

### 4. **Test State, Not Implementation Details**

✅ **Good test**: "After calling `startLock()`, the `lockState` should be `Locked`"
❌ **Bad test**: "After calling `startLock()`, the method should call `overlayController.show()` exactly once"

Focus on observable state, not internal call sequences.

---

## LockOverlayService Testing Strategy

Given your current architecture, here's the recommended approach:

### Current State: What You're Already Testing Well ✅

From your existing tests:
- `HomeViewModelTest` tests the ViewModel + fake repositories
- `FakeLockRepository` allows testing without launching the service
- This covers ~80% of the lock/unlock logic

### What's NOT Being Tested ⚠️

1. **Service Lifecycle Events** (onCreate, onStartCommand, onDestroy)
2. **Intent Routing** (ACTION_START, ACTION_STOP, etc. → correct methods)
3. **Broadcast Receiver Behavior** (receiving unlock broadcasts)
4. **Coroutine Job Management** (countdown job, pending lock job)
5. **Notification State Transitions** (correct notification shown at each state)
6. **Permission Checks** (properly handling permission denial)

### Recommended Testing Approach

#### **Tier 1: Unit Tests (with Fakes)** ← START HERE
Test the service's core logic without launching it:

```kotlin
// Don't test the service directly, test via the repository fake
@Test
fun `startLock calls overlayController show with correct orientation` = runTest {
    // Use FakeLockRepository to simulate service calls
    // Verify state changes in lockState flow
    // ✅ Fast, deterministic, clear failures
}
```

**Effort**: Low | **Value**: High | **Speed**: Fast

#### **Tier 2: Integration Tests (ServiceTestRule)** ← OPTIONAL
Test actual service lifecycle if needed:

```kotlin
@Rule
val serviceRule = ServiceTestRule()

@Test
fun `service starts and emits unlocked notification` {
    val intent = Intent(context, LockOverlayService::class.java)
    val binder = serviceRule.bindService(intent)
    // Verify notification was shown
}
```

**Effort**: High | **Value**: Medium | **Speed**: Slow

#### **Tier 3: End-to-End Tests** ← NICE TO HAVE
Test full app workflows:

```kotlin
@Test
fun `user taps enable button, overlay appears, double tap unlocks` {
    // Launch activity
    // Tap enable button
    // Verify overlay visible
    // Tap double-tap on overlay
    // Verify unlock handle appears
}
```

**Effort**: Very High | **Value**: Medium (flaky) | **Speed**: Very Slow

---

## Testing Implementation Plan

### Phase 1: Unit Tests (Recommended Priority 1) ⭐

**Goal**: Test service logic via `FakeLockRepository`

**What to test**:

- ✅ Lock state transitions
- ✅ Intent action routing
- ✅ Notification state changes
- ✅ Countdown timer logic
- ✅ Job cancellation on stop

**Where**: `/app/src/test/java/...`

**How**: Mock `OverlayController`, `NotificationManager`, `ConfigRepository`, use real `LockOverlayService` with test dispatchers

**Tools**: JUnit, Mockk, Turbine, Coroutines Test
**Time**: 2-3 hours

### Phase 2: Integration Tests (Recommended Priority 2) ⭐

**Goal**: Test service lifecycle specific behaviors

**What to test**:

- ✅ Service starts in unlocked state
- ✅ Service restarts with START_STICKY
- ✅ Foreground notification always shown
- ✅ Broadcast receivers registered/unregistered properly
- ✅ Jobs cancelled on destroy

**Where**: `/app/src/androidTest/java/...`

**How**: Use `ServiceTestRule`, provide real service with mocked dependencies

**Tools**: Androidx Test, ServiceTestRule, Hilt Testing

**Time**: 3-4 hours

### Phase 3: E2E Tests (Optional, Priority 3)

**Goal**: Test complete user workflows

**What to test**:

- ✅ Button tap → lock appears → double-tap → unlock
- ✅ Notification tap → toggle lock state
- ✅ Permission denial → graceful handling
- ✅ Countdown timer → auto-lock

**Where**: `/app/src/androidTest/java/...`

**How**: Use Espresso for UI, test real service

**Tools**: Espresso, Compose Test Framework

**Time**: 4-5 hours (complex due to flakiness)


## Recommended Next Steps

### **Option A: Minimal Testing (Recommended for MVP)** ⭐

Focus on what's **already working well**:

1. ✅ Keep `HomeViewModelTest` as is (tests the interface)
2. ✅ Keep `FakeLockRepository` (prevents need for service)
3. ✅ Add 3-4 unit tests for service edge cases:
   - Permission denial handling
   - Intent routing
   - State transitions
4. **Skip** integration tests for now (service is simple enough)

**Time**: 1-2 hours
**Coverage**: ~85% of important logic
**Maintenance**: Low

### **Option B: Comprehensive Testing (For Production)**

Add full test suite:

1. ✅ Unit tests (core logic)
2. ✅ Integration tests (service lifecycle)
3. ✅ E2E tests (user workflows)
4. ✅ Performance tests (no memory leaks)

**Time**: 8-10 hours
**Coverage**: 95%+ of logic
**Maintenance**: High

---

## Testing Anti-Patterns to Avoid

❌ **Never**:

- Mock the service itself and test the mock (defeats purpose)
- Test private methods directly (test behavior, not implementation)
- Test the service's onCreate() by directly calling it (framework doesn't work that way)
- Make timing-dependent tests (flaky, fail on slow devices)
- Test Android SDK classes (Google tests those)

✅ **Instead**:

- Test via public interfaces (repository, use cases, state flows)
- Test behavior and state, not implementation
- Use test dispatchers and advanceUntilIdle() for determinism
- Mock external dependencies, keep core logic real
- Test error handling and edge cases

---

## Integration with CI/CD

### GitHub Actions

Test run automatically on each opened PR with GH actions see `.github/workflows` see `ui-tests.yml` and `unit-tests.yml`

---

## Summary & Recommendations

| Component | Test Type | Priority | Why |
|-----------|-----------|----------|-----|
| **LockOverlayService** | Unit (via fake) | ⭐⭐⭐ | Core logic, easy to test |
| **LockRepositoryImpl** | Unit | ⭐⭐ | Just calls service, already tested via ViewModel |
| **Service Lifecycle** | Integration | ⭐⭐ | Important but rarely changes |
| **UI/Compose** | E2E/Espresso | ⭐ | Flaky, low value for now |

**For MVP**: Focus on Option A (Minimal Testing)

**For Production**: Aim for Option B (Comprehensive Testing)

**For Shipping**: Ensure ~80% code coverage and 0 test failures in CI/CD

