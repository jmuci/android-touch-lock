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

        /** Mirrors [TouchLockAccessibilityService.snapBackTimestamps]. */
        private val snapBackTimestamps = ArrayDeque<Long>()
        val snapBackCount: Int
            get() = snapBackTimestamps.size
        var forceUnlockTriggered = false
            private set
        var lastRelaunchedPackage: String? = null
            private set

        private var suppressSnapBackUntilElapsedMillis = 0L

        /** Test-controlled clock, standing in for SystemClock.elapsedRealtime(). */
        var currentElapsedMillis = 0L

        /**
         * Mirrors [TouchLockAccessibilityService.lastKnownForegroundPackage] — continuously
         * updated from every window-state event regardless of lock state, filtered through
         * [isEligibleProtectedCandidate] (`onAccessibilityEvent()`'s unconditional capture).
         */
        var lastKnownForegroundPackage: String? = null
            private set

        /**
         * Mirrors [TouchLockAccessibilityService.currentForegroundPackage] — the *unfiltered*
         * package from every window-state event, including SystemUI and allowlisted packages.
         * This is what suppression decisions must consult; [lastKnownForegroundPackage] answers
         * the different question of "what may be captured as the protected app".
         */
        var currentForegroundPackage: String? = null
            private set

        /** Mirrors dismissShade()'s grace-window bookkeeping. */
        fun dismissShade() {
            suppressSnapBackUntilElapsedMillis =
                currentElapsedMillis + SNAP_BACK_SUPPRESSION_AFTER_SELF_ACTION_MILLIS
        }

        /**
         * Mirrors the unconditional `lastKnownForegroundPackage = eventPackage` capture at the
         * top of onAccessibilityEvent(), which runs for every window-state event regardless of
         * lock state.
         */
        fun observeWindowState(packageName: String?) {
            if (packageName != null) {
                currentForegroundPackage = packageName
            }
            if (isEligibleProtectedCandidate(packageName)) {
                lastKnownForegroundPackage = packageName
            }
        }

        /**
         * Locks using whatever [observeWindowState] has captured so far, mirroring
         * onServiceConnected()'s real capture source — rather than a caller-supplied override.
         */
        fun lockUsingObservedForegroundPackage() =
            setLocked(locked = true, knownForegroundPackage = lastKnownForegroundPackage)

        fun setLocked(locked: Boolean, knownForegroundPackage: String? = "com.protected.app") {
            if (locked && protectedPackageName == null) {
                // Mirrors onServiceConnected()'s capture: uses whatever was last observed, which
                // can be null if the service (re)connected right as the lock engaged, or if the
                // lock engaged while our own app (never eligible) was the only thing seen so far.
                protectedPackageName = knownForegroundPackage
                snapBackTimestamps.clear()
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
            // Mirrors the unfiltered capture at the very top of onAccessibilityEvent(), which
            // runs before the lock-state check.
            currentForegroundPackage = eventPackage
            if (!isLocked) return
            // No protected package was captured at lock-engagement time — nothing to compare
            // against or snap back to for the rest of this session. Deliberately does not adopt
            // whatever shows up next; see the equivalent comment in production.
            val protected = protectedPackageName ?: return
            if (eventPackage in allowlisted) return
            if (eventPackage != protected) {
                if (currentElapsedMillis < suppressSnapBackUntilElapsedMillis) return
                performSnapBack(protected)
            }
        }

        /**
         * Mirrors onKeyEvent()'s BACK-consumption decision logic.
         *
         * Reads [currentForegroundPackage] rather than taking the package as a parameter, on
         * purpose: this test previously passed a package in directly, so it asserted the
         * *intended* logic while production read `lastKnownForegroundPackage` — which excludes
         * allowlisted packages by construction, making the allowlist check dead code and BACK
         * consumed even in Settings. Deriving the package from observed events is what makes that
         * class of mismatch visible here.
         */
        fun consumesBack(): Boolean {
            if (!isLocked) return false
            val foregroundPackage = currentForegroundPackage
            if (foregroundPackage != null && foregroundPackage in allowlisted) return false
            return true
        }

        /** Mirrors [TouchLockAccessibilityService.performSnapBack]'s rolling-window rate limit. */
        private fun performSnapBack(protected: String) {
            snapBackTimestamps.addLast(currentElapsedMillis)
            while (snapBackTimestamps.isNotEmpty() &&
                currentElapsedMillis - snapBackTimestamps.first() > SNAP_BACK_RATE_LIMIT_WINDOW_MILLIS
            ) {
                snapBackTimestamps.removeFirst()
            }

            if (snapBackTimestamps.size >= MAX_SNAP_BACK_ATTEMPTS) {
                forceUnlockTriggered = true
                return
            }
            lastRelaunchedPackage = protected
        }

        companion object {
            // Not private: tests below sweep the boundary around this value directly rather than
            // hardcoding it a second time, so a future change to the threshold can't silently
            // desync the tests from the value they're supposed to be bounding.
            const val MAX_SNAP_BACK_ATTEMPTS = 3
            const val SNAP_BACK_RATE_LIMIT_WINDOW_MILLIS = 5000L
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
        harness.observeWindowState("com.other.app")
        assertThat(harness.consumesBack()).isFalse()
    }

    @Test
    fun `consumes BACK while locked`() {
        val harness = Harness(isLocked = true)
        harness.observeWindowState("com.protected.app")
        assertThat(harness.consumesBack()).isTrue()
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

        // Must arrive as an observed window-state event, not a direct parameter: an allowlisted
        // package never lands in lastKnownForegroundPackage, so reading that field here is exactly
        // the bug this asserts against.
        harness.observeWindowState("com.android.settings")

        assertThat(harness.consumesBack()).isFalse()
        assertThat(harness.lastKnownForegroundPackage).isNull()
    }

    @Test
    fun `consumes BACK again once a non-allowlisted app returns to the foreground`() {
        val harness = Harness(allowlisted = setOf("com.android.settings"), isLocked = true)

        harness.observeWindowState("com.android.settings")
        harness.observeWindowState("com.protected.app")

        assertThat(harness.consumesBack()).isTrue()
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
    fun `allows up to MAX_SNAP_BACK_ATTEMPTS minus one relaunches before the limit trips`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        repeat(Harness.MAX_SNAP_BACK_ATTEMPTS - 1) { harness.onWindowStateChanged("com.launcher") }

        assertThat(harness.snapBackCount).isEqualTo(Harness.MAX_SNAP_BACK_ATTEMPTS - 1)
        assertThat(harness.forceUnlockTriggered).isFalse()
    }

    @Test
    fun `the MAX_SNAP_BACK_ATTEMPTSth relaunch attempt in the same lock session releases the lock instead of relaunching`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        repeat(Harness.MAX_SNAP_BACK_ATTEMPTS) { harness.onWindowStateChanged("com.launcher") }

        assertThat(harness.forceUnlockTriggered).isTrue()
    }

    @Test
    fun `force-unlock trips exactly at the MAX_SNAP_BACK_ATTEMPTSth relaunch, not before or after`() {
        // Sweeps attempt counts around the boundary instead of relying on a single hardcoded
        // example. This is the same class of bug (`>` vs `>=`) that slipped through here
        // undetected once already — this file's harness had drifted from the `>=` fix already
        // applied in production, so its old single-example tests were passing while asserting the
        // pre-fix, buggy threshold. A boundary sweep makes a future regression fail immediately
        // instead of needing the "right" example count to be re-derived by hand.
        for (attempts in 1..(Harness.MAX_SNAP_BACK_ATTEMPTS + 2)) {
            val harness = Harness(isLocked = true)
            harness.setLocked(true)

            repeat(attempts) { harness.onWindowStateChanged("com.launcher") }

            val expectedTriggered = attempts >= Harness.MAX_SNAP_BACK_ATTEMPTS
            assertThat(harness.forceUnlockTriggered).isEqualTo(expectedTriggered)
        }
    }

    // --- Rate limiting: rolling window, not a flat per-session cap ---
    //
    // Confirmed on-device (emulator, gesture navigation — the modern default): a flat per-session
    // cap defeats itself under completely normal use. Four genuine, isolated swipe-home gestures
    // roughly 10-15s apart — not rapid-fire, not an attempt to defeat the lock — exhausted a flat
    // counter and silently released Strong Lock. A rolling window fixes that: only a real, rapid
    // fight against the lock still trips the safety release.

    @Test
    fun `snap-backs spread beyond the rate-limit window never accumulate toward the release threshold`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        repeat(5) {
            harness.onWindowStateChanged("com.launcher")
            harness.currentElapsedMillis += Harness.SNAP_BACK_RATE_LIMIT_WINDOW_MILLIS + 1
        }

        assertThat(harness.forceUnlockTriggered).isFalse()
    }

    @Test
    fun `4 snap-backs packed inside the rate-limit window still releases the lock`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        repeat(4) {
            harness.onWindowStateChanged("com.launcher")
            harness.currentElapsedMillis += 100L // rapid, well within the window
        }

        assertThat(harness.forceUnlockTriggered).isTrue()
    }

    @Test
    fun `an attempt exactly at the window boundary still counts toward the limit`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)
        // One below the trip threshold at t=0, so the boundary attempt below is what tips it —
        // if it didn't count (pruned early), this would stay under the limit instead.
        repeat(Harness.MAX_SNAP_BACK_ATTEMPTS - 1) { harness.onWindowStateChanged("com.launcher") }

        // Exactly the window width, not past it — pruning requires strictly *greater than* the
        // window (see performSnapBack's `now - snapBackTimestamps.first() > ...`), so the first
        // attempt is still in-window here and this is the MAX_SNAP_BACK_ATTEMPTSth surviving
        // entry, not a fresh 1st.
        harness.currentElapsedMillis += Harness.SNAP_BACK_RATE_LIMIT_WINDOW_MILLIS
        harness.onWindowStateChanged("com.launcher")

        assertThat(harness.forceUnlockTriggered).isTrue()
    }

    @Test
    fun `attempts older than the window drop out of the count`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)
        // One below the trip threshold, so this test's pruning assertion below isn't confounded
        // by the limit having already tripped.
        repeat(Harness.MAX_SNAP_BACK_ATTEMPTS - 1) { harness.onWindowStateChanged("com.launcher") }
        assertThat(harness.snapBackCount).isEqualTo(Harness.MAX_SNAP_BACK_ATTEMPTS - 1)

        harness.currentElapsedMillis += Harness.SNAP_BACK_RATE_LIMIT_WINDOW_MILLIS + 1
        harness.onWindowStateChanged("com.launcher") // 1 more, long after the earlier ones aged out

        assertThat(harness.snapBackCount).isEqualTo(1)
        assertThat(harness.forceUnlockTriggered).isFalse()
    }

    @Test
    fun `snap-back count resets on a new lock session`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)
        // One below the trip threshold, so forceUnlockTriggered (which never resets on its own —
        // only a fresh Harness/lock session matters here) isn't already true before the reset.
        repeat(Harness.MAX_SNAP_BACK_ATTEMPTS - 1) { harness.onWindowStateChanged("com.launcher") }
        assertThat(harness.snapBackCount).isEqualTo(Harness.MAX_SNAP_BACK_ATTEMPTS - 1)

        harness.setLocked(false)
        harness.setLocked(true)
        harness.onWindowStateChanged("com.launcher")

        assertThat(harness.snapBackCount).isEqualTo(1)
        assertThat(harness.forceUnlockTriggered).isFalse()
    }

    // --- Protected package unknown at lock-engagement time (reconnect race, or the lock engaging
    // while our own app — never eligible — was the only thing seen this session) ---
    //
    // On-device bug found via manual testing: engaging Strong Lock via the in-app "Lock in 10s"
    // button without switching away first (so our own, allowlisted, package is the only thing
    // ever observed) captured a null protected package, same as the reconnect race below. The
    // *previous* fix for that adopted whatever window-state event arrived next as "protected" —
    // but the very next event is exactly as likely to be the user's real escape attempt (e.g.
    // pressing Home) as it is to be the still-current app after a reconnect. On-device, that meant
    // pressing Home right after locking silently adopted the launcher as "protected" and never
    // snapped back at all. Failing to protect anything for a session with no known target is safer
    // than protecting the wrong one for the rest of it.

    @Test
    fun `does not snap back for the rest of the session when the protected package is unknown at lock time`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true, knownForegroundPackage = null)

        harness.onWindowStateChanged("com.launcher") // the on-device repro: pressing Home right after locking

        assertThat(harness.protectedPackageName).isNull()
        assertThat(harness.lastRelaunchedPackage).isNull()
        assertThat(harness.snapBackCount).isEqualTo(0)
    }

    @Test
    fun `does not adopt a later event as protected even after several window-state changes`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true, knownForegroundPackage = null)

        harness.onWindowStateChanged("com.was.in.foreground")
        harness.onWindowStateChanged("com.launcher")
        harness.onWindowStateChanged("com.other.app")

        assertThat(harness.protectedPackageName).isNull()
        assertThat(harness.snapBackCount).isEqualTo(0)
    }

    @Test
    fun `a fresh lock cycle with a known foreground package recovers snap-back`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true, knownForegroundPackage = null)
        harness.onWindowStateChanged("com.launcher") // ignored — no protected package this session
        harness.setLocked(false)

        harness.observeWindowState("com.real.app")
        harness.setLocked(true, knownForegroundPackage = harness.lastKnownForegroundPackage)
        harness.onWindowStateChanged("com.launcher")

        assertThat(harness.lastRelaunchedPackage).isEqualTo("com.real.app")
        assertThat(harness.snapBackCount).isEqualTo(1)
    }

    // --- SystemUI must never poison the lock-engagement capture either (the primary bug
    // scenario from the Codex review: locking right after pulling the shade from the persistent
    // notification, via onServiceConnected()'s lastKnownForegroundPackage-based capture — distinct
    // from the reconnect-race adoption path covered above). ---

    @Test
    fun `pulling the shade right before locking does not poison the protected package`() {
        val harness = Harness(isLocked = false)
        harness.observeWindowState("com.real.app")
        harness.observeWindowState(Harness.SYSTEM_UI_PACKAGE) // shade opened right before lock

        harness.lockUsingObservedForegroundPackage()

        assertThat(harness.protectedPackageName).isEqualTo("com.real.app")
    }

    @Test
    fun `SystemUI alone observed before locking leaves no protected package captured`() {
        val harness = Harness(isLocked = false)
        harness.observeWindowState(Harness.SYSTEM_UI_PACKAGE)

        harness.lockUsingObservedForegroundPackage()

        assertThat(harness.protectedPackageName).isNull()
    }

    @Test
    fun `allowlisted package observed before locking is never captured as the protected package`() {
        val harness = Harness(allowlisted = setOf("com.android.settings"), isLocked = false)
        harness.observeWindowState("com.real.app")
        harness.observeWindowState("com.android.settings")

        harness.lockUsingObservedForegroundPackage()

        assertThat(harness.protectedPackageName).isEqualTo("com.real.app")
    }

    @Test
    fun `a normal foreground app observed before locking is correctly captured as the protected package`() {
        val harness = Harness(isLocked = false)
        harness.observeWindowState("com.real.app")

        harness.lockUsingObservedForegroundPackage()

        assertThat(harness.protectedPackageName).isEqualTo("com.real.app")
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

    @Test
    fun `shade dismissals never consume from the snap-back rate-limit budget, even interleaved with real snap-backs`() {
        val harness = Harness(isLocked = true)
        harness.setLocked(true)

        // MAX_SNAP_BACK_ATTEMPTS-1 real snap-backs (staying below the trip threshold), each
        // preceded by a shade dismissal that must not itself count — these are two independent
        // mechanisms (dismissShade's grace window vs performSnapBack's rate-limit window) and a
        // dismissal returns before ever reaching performSnapBack, so it must never inflate
        // snapBackCount. If dismissals did count, this many dismissals plus real snap-backs would
        // have tripped the limit already.
        repeat(Harness.MAX_SNAP_BACK_ATTEMPTS - 1) {
            harness.dismissShade()
            harness.currentElapsedMillis += 1600L // past the 1500ms shade-dismiss grace window
            harness.onWindowStateChanged("com.launcher")
        }

        assertThat(harness.snapBackCount).isEqualTo(Harness.MAX_SNAP_BACK_ATTEMPTS - 1)
        assertThat(harness.forceUnlockTriggered).isFalse()
    }
}
