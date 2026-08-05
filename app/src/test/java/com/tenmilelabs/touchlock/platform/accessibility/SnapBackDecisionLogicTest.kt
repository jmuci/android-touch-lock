package com.tenmilelabs.touchlock.platform.accessibility

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [TouchLockAccessibilityService] extends `AccessibilityService` and can't be instantiated in a
 * pure JVM test (no Android runtime, no Hilt instrumentation) — same constraint documented in
 * `LockOverlayServiceTest`. This harness mirrors the exact snap-back / fail-open decision logic
 * from `onAccessibilityEvent()` and `onKeyEvent()` so the safety-critical pieces (rate limiting,
 * fail-open) are covered without spinning up the real service.
 */
class SnapBackDecisionLogicTest {

    private class Harness(
        private val allowlisted: Set<String> = emptySet(),
        private var isLocked: Boolean = true,
    ) {
        var protectedPackageName: String? = null
            private set
        var snapBackCount = 0
            private set
        var forceUnlockTriggered = false
            private set
        var lastRelaunchedPackage: String? = null
            private set

        fun setLocked(locked: Boolean, knownForegroundPackage: String? = "com.protected.app") {
            if (locked && protectedPackageName == null) {
                // Mirrors onServiceConnected()'s capture: uses whatever was last observed, which
                // can be null if the service (re)connected right as the lock engaged.
                protectedPackageName = knownForegroundPackage
                snapBackCount = 0
            } else if (!locked) {
                protectedPackageName = null
            }
            isLocked = locked
        }

        /** Mirrors onAccessibilityEvent()'s decision logic for a TYPE_WINDOW_STATE_CHANGED event. */
        fun onWindowStateChanged(eventPackage: String) {
            if (!isLocked) return
            // Edge case fix: adopt the first package observed while locked if the lock-engagement
            // capture raced and came up null, instead of leaving snap-back disabled all session.
            if (protectedPackageName == null) {
                protectedPackageName = eventPackage
                return
            }
            val protected = protectedPackageName ?: return
            if (eventPackage in allowlisted) return
            if (eventPackage != protected) performSnapBack(protected)
        }

        /** Mirrors onKeyEvent()'s BACK-consumption decision logic. */
        fun consumesBack(foregroundPackage: String?): Boolean {
            if (!isLocked) return false
            if (foregroundPackage != null && foregroundPackage in allowlisted) return false
            return true
        }

        private fun performSnapBack(protected: String) {
            snapBackCount++
            if (snapBackCount > MAX_SNAP_BACK_ATTEMPTS) {
                forceUnlockTriggered = true
                return
            }
            lastRelaunchedPackage = protected
        }

        companion object {
            private const val MAX_SNAP_BACK_ATTEMPTS = 3
        }
    }

    // --- Fail-open ---

    @Test
    fun `does not snap back when unlocked`() {
        val harness = Harness(isLocked = false)
        harness.onWindowStateChanged("com.other.app")
        assertThat(harness.lastRelaunchedPackage).isNull()
        assertThat(harness.forceUnlockTriggered).isFalse()
    }

    @Test
    fun `does not consume BACK when unlocked`() {
        val harness = Harness(isLocked = false)
        assertThat(harness.consumesBack("com.other.app")).isFalse()
    }

    @Test
    fun `consumes BACK while locked`() {
        val harness = Harness(isLocked = true)
        assertThat(harness.consumesBack("com.protected.app")).isTrue()
    }

    // --- Allowlist ---

    @Test
    fun `does not snap back to or away from an allowlisted foreground package`() {
        val harness = Harness(allowlisted = setOf("com.android.settings"), isLocked = true)
        harness.setLocked(true)

        harness.onWindowStateChanged("com.android.settings")

        assertThat(harness.lastRelaunchedPackage).isNull()
    }

    @Test
    fun `does not consume BACK while an allowlisted package is foreground`() {
        val harness = Harness(allowlisted = setOf("com.android.settings"), isLocked = true)
        assertThat(harness.consumesBack("com.android.settings")).isFalse()
    }

    // --- Snap-back on package change ---

    @Test
    fun `snaps back when foreground package differs from protected package`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        harness.onWindowStateChanged("com.launcher")

        assertThat(harness.lastRelaunchedPackage).isEqualTo("com.protected.app")
        assertThat(harness.snapBackCount).isEqualTo(1)
    }

    @Test
    fun `does not snap back when the protected package itself reports window state changes`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        harness.onWindowStateChanged("com.protected.app")

        assertThat(harness.lastRelaunchedPackage).isNull()
        assertThat(harness.snapBackCount).isEqualTo(0)
    }

    // --- Rate limiting ---

    @Test
    fun `allows up to 3 snap-back relaunches per lock session`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        repeat(3) { harness.onWindowStateChanged("com.launcher") }

        assertThat(harness.snapBackCount).isEqualTo(3)
        assertThat(harness.forceUnlockTriggered).isFalse()
    }

    @Test
    fun `4th relaunch attempt in the same lock session releases the lock instead of looping`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        repeat(4) { harness.onWindowStateChanged("com.launcher") }

        assertThat(harness.forceUnlockTriggered).isTrue()
    }

    @Test
    fun `snap-back count resets on a new lock session`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)
        repeat(3) { harness.onWindowStateChanged("com.launcher") }
        assertThat(harness.snapBackCount).isEqualTo(3)

        harness.setLocked(false)
        harness.setLocked(true)
        harness.onWindowStateChanged("com.launcher")

        assertThat(harness.snapBackCount).isEqualTo(1)
        assertThat(harness.forceUnlockTriggered).isFalse()
    }

    // --- Reconnect race: protected package captured as unknown at lock-engagement time ---

    @Test
    fun `adopts the first observed package when locked with an unknown protected package`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true, knownForegroundPackage = null)

        harness.onWindowStateChanged("com.was.in.foreground")

        assertThat(harness.lastRelaunchedPackage).isNull()
        assertThat(harness.snapBackCount).isEqualTo(0)
    }

    @Test
    fun `snaps back correctly on subsequent events after adopting an unknown protected package`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true, knownForegroundPackage = null)
        harness.onWindowStateChanged("com.was.in.foreground")

        harness.onWindowStateChanged("com.launcher")

        assertThat(harness.lastRelaunchedPackage).isEqualTo("com.was.in.foreground")
        assertThat(harness.snapBackCount).isEqualTo(1)
    }
}
