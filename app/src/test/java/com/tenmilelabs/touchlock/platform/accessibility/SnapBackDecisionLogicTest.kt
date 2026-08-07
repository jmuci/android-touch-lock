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

        private var suppressSnapBackUntilElapsedMillis = 0L

        /** Test-controlled clock, standing in for SystemClock.elapsedRealtime(). */
        var currentElapsedMillis = 0L

        /** Mirrors dismissShade()'s grace-window bookkeeping. */
        fun dismissShade() {
            suppressSnapBackUntilElapsedMillis =
                currentElapsedMillis + SNAP_BACK_SUPPRESSION_AFTER_SELF_ACTION_MILLIS
        }

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

        /** Mirrors isEligibleProtectedCandidate(): SystemUI and allowlisted packages are transient
         *  system surfaces, never adopted as the protected package. */
        private fun isEligibleProtectedCandidate(pkg: String?): Boolean =
            pkg != null && pkg != SYSTEM_UI_PACKAGE && pkg !in allowlisted

        /** Mirrors onAccessibilityEvent()'s decision logic for a TYPE_WINDOW_STATE_CHANGED event. */
        fun onWindowStateChanged(eventPackage: String) {
            if (!isLocked) return
            // Edge case fix: adopt the first eligible package observed while locked if the
            // lock-engagement capture raced and came up null, instead of leaving snap-back
            // disabled all session. Never adopt SystemUI/allowlisted packages this way.
            if (protectedPackageName == null) {
                if (isEligibleProtectedCandidate(eventPackage)) {
                    protectedPackageName = eventPackage
                }
                return
            }
            val protected = protectedPackageName ?: return
            if (eventPackage in allowlisted) return
            if (eventPackage != protected) {
                if (currentElapsedMillis < suppressSnapBackUntilElapsedMillis) return
                performSnapBack(protected)
            }
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
            private const val SNAP_BACK_SUPPRESSION_AFTER_SELF_ACTION_MILLIS = 1500L
            const val SYSTEM_UI_PACKAGE = "com.android.systemui"
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

    // --- SystemUI must never be adopted as the protected package ---
    //
    // Codex review finding: pulling the shade (e.g. from the persistent notification) right as
    // the lock engages, or right as a mid-lock accessibility reconnect races the capture, could
    // adopt "com.android.systemui" as the protected package. It has no launch intent, so every
    // subsequent real app switch reads as "navigation away" and snap-back silently fails for the
    // rest of the session.

    @Test
    fun `does not adopt SystemUI as the protected package during the reconnect race`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true, knownForegroundPackage = null)

        harness.onWindowStateChanged(Harness.SYSTEM_UI_PACKAGE)
        assertThat(harness.protectedPackageName).isNull()

        harness.onWindowStateChanged("com.real.app")
        assertThat(harness.protectedPackageName).isEqualTo("com.real.app")
    }

    @Test
    fun `snaps back to the correctly-adopted package, never to SystemUI`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true, knownForegroundPackage = null)
        harness.onWindowStateChanged(Harness.SYSTEM_UI_PACKAGE) // fails to adopt
        harness.onWindowStateChanged("com.real.app") // adopts as protected

        harness.onWindowStateChanged("com.launcher") // real navigation away

        assertThat(harness.lastRelaunchedPackage).isEqualTo("com.real.app")
        assertThat(harness.snapBackCount).isEqualTo(1)
    }

    // --- Self-action grace window: dismissShade()'s own side effects must never trigger snap-back ---
    //
    // Confirmed on-device: performGlobalAction while dismissing the shade generated a spurious
    // window-state-changed event that got misread as the user leaving the protected app, firing a
    // real snap-back. Repeating the pull escalated the count, and — left unguarded — would have hit
    // the rate limit and force-released the lock entirely from nothing but shade dismissal.

    @Test
    fun `does not snap back for a window-state event immediately following our own dismissShade`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        harness.dismissShade()
        harness.onWindowStateChanged("com.launcher")

        assertThat(harness.lastRelaunchedPackage).isNull()
        assertThat(harness.snapBackCount).isEqualTo(0)
    }

    @Test
    fun `repeated dismissShade calls never accumulate snap-back attempts while still within the grace window`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        repeat(4) {
            harness.dismissShade()
            harness.onWindowStateChanged("com.launcher")
        }

        assertThat(harness.snapBackCount).isEqualTo(0)
        assertThat(harness.forceUnlockTriggered).isFalse()
    }

    @Test
    fun `snap-back resumes normally once the grace window has elapsed`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)
        harness.dismissShade()
        harness.currentElapsedMillis += 1501L // past the 1500ms grace window

        harness.onWindowStateChanged("com.launcher")

        assertThat(harness.lastRelaunchedPackage).isEqualTo("com.protected.app")
        assertThat(harness.snapBackCount).isEqualTo(1)
    }
}
