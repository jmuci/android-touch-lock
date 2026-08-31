# Pre-Release QA Session Prompt

A ready-to-copy prompt for running an exhaustive manual QA pass on Touch Lock before a Play Store
submission. This is the actual prompt used for the pass that shipped [PR #33](https://github.com/jmuci/android-touch-lock/pull/33)
(stuck countdown notification, snap-back off-by-one, Strong Lock ANR mitigation) and its follow-ups
([#37](https://github.com/jmuci/android-touch-lock/pull/37), [#38](https://github.com/jmuci/android-touch-lock/pull/38),
[#40](https://github.com/jmuci/android-touch-lock/pull/40)), revised afterward with what that run
actually taught about running it. See [What changed, and why](#what-changed-and-why) at the bottom
for the delta against the original.

## How to use this

Copy the prompt below verbatim into a fresh Claude Code session in this repo. Update the two
bracketed placeholders if a new feature area needs its own focus-area coverage since the last run.
Re-run this whole pass before every Play Store submission, not just the first one — most of the
bugs it found were state-transition races that a quick smoke test won't surface.

---

## The prompt

> I want you to run an exhaustive manual QA pass on the Touch Lock Android app before I upload the
> final release build to Google Play. This is the last check before submission, so be thorough —
> take as much time as you need, and don't stop at the first pass of obvious flows.
>
> **CONTEXT**
> Touch Lock (com.tenmilelabs.touchlock) is an Android app that shows a full-screen overlay to
> block touch input while the underlying app (video, call) stays visible — for handing a phone to
> a young child. Read CLAUDE.md, README.md, docs/ARCHITECTURE.md, and docs/TESTING_GUIDE.md first
> for full context on the architecture and constraints before testing. Two lock modes exist:
> - Default: overlay only (SYSTEM_ALERT_WINDOW), no accessibility service.
> - Optional "Toddler-Proof Lock" (Strong Lock): adds an AccessibilityService that blocks the BACK
>   button, auto-dismisses the notification shade, and relaunches the protected app if the user
>   navigates away — all while the base lock is active.
>
> **SETUP**
> 1. Check whether `keystore.properties` exists at the repo root. If it does, build and install the
>    *release* variant (`./gradlew installRelease`) — that's what actually ships, and R8/ProGuard
>    can introduce bugs a debug build won't show. If it doesn't exist, fall back to `installDebug`.
>    If a device already has a differently-signed build installed from a previous session, this
>    fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — that's a leftover-install problem, not a
>    build bug; `adb uninstall com.tenmilelabs.touchlock` on that device first.
> 2. You have both a real device and emulator(s) available. Run `adb devices` to confirm what's
>    connected. Assign hardware by focus area:
>    - Focus Area 2 (accessibility) and Focus Area 3 (permissions/interruptions) → the real device.
>      These are the areas most likely to depend on real OEM timing/behavior rather than emulator
>      approximations — several existing bugs in this codebase were only ever reproducible on
>      physical hardware, per comments in TouchLockAccessibilityService.kt.
>    - Focus Area 1 (core lifecycle) and Focus Area 4 (device/UI diversity) → emulator(s). Spin up
>      multiple AVDs with different API levels, screen densities, and nav modes (gesture vs
>      3-button) for Focus Area 4 specifically — that coverage isn't possible on one physical
>      device. If there's no `avdmanager`/cmdline-tools available to create new AVDs, use whatever
>      AVDs already exist and say so explicitly in the final report as a coverage gap — don't
>      silently settle for less diversity than asked for.
>    - If only one real device is connected and two subagents both need it (Areas 2 and 3), have
>      them coordinate turns rather than assume simultaneous access — don't let them silently
>      clobber each other's test session state.
> 3. If you have no way to run the app interactively at all on either, say so explicitly rather
>    than substituting a purely code-reading review.
> 4. Before changing any accessibility-service setting on the real device — enabling/disabling
>    Touch Lock's own service, or anything touching `enabled_accessibility_services` — read the
>    current value first (`adb shell settings get secure enabled_accessibility_services`) and
>    restore it exactly when you're done, rather than clearing it. The real device may already have
>    unrelated third-party accessibility services enabled; a blanket reset at cleanup time silently
>    disables those too.
>
> **APPROACH**
> Spawn 4 subagents to work in parallel, each owning one focus area below. Each subagent should
> actually operate the running app (tap, swipe, background it, rotate it, kill it) rather than only
> reading code — but should also read the specific source files called out below, since some of
> these bugs are timing/race-condition-shaped and won't reliably surface from a fixed sequence of
> taps. Each subagent should keep going until it's confident it has tried to break its area, not
> stop after a fixed checklist — try unusual orderings, rapid repeated actions, and interrupting
> flows mid-way.
>
> Beyond the specific scenarios listed per focus area, deliberately probe the general shape of "a
> dependency becomes unavailable at the exact moment a multi-step operation is mid-flight" —
> permission revoked between one check and the next, the accessibility service disconnecting
> mid-lock, an overlay `addView` failing right as a countdown reaches zero. This codebase's real
> bugs have clustered in exactly this pattern more than any other single cause; the focus areas
> below call out the obvious instances, but don't limit yourself to only those.
>
> Each subagent reports back: what it tried, what worked, and a bug list with repro steps,
> severity, and the file/line it suspects, for anything that didn't.
>
> **FOCUS AREA 1 — Core lock/unlock lifecycle**
> - Grant/deny/revoke SYSTEM_ALERT_WINDOW mid-session; lock, then revoke the permission from system
>   Settings while locked — must fail safely, never crash (this is an explicit project constraint).
> - The 10-second countdown: cancel it mid-countdown, trigger it repeatedly in quick succession,
>   rotate the device while it's running, background the app during it. Also try revoking the
>   overlay permission at the exact moment the countdown reaches zero — does the countdown
>   notification get cleaned up, or left stuck?
> - The overlay itself failing to attach (not just permission being missing) right when a lock is
>   meant to engage — does the service correctly abort and report Unlocked, rather than claiming
>   Locked with no actual overlay on screen?
> - Unlock gesture: release the press-and-hold early, hold on the wrong region, double-tap without
>   holding, rapid repeated unlock attempts.
> - Kill the app process (Force Stop, or via "don't keep activities" dev option) while locked —
>   does the foreground service/notification survive, and does the lock state recover correctly on
>   relaunch?
> - Rotate the device while locked and while the countdown is running.
> - Deny POST_NOTIFICATIONS (Android 13+) — does the service still function per Android's
>   foreground service rules, without crashing?
>
> **FOCUS AREA 2 — Toddler-Proof Lock / accessibility service edge cases**
> Read `TouchLockAccessibilityService.kt` first — it has several deliberately tuned timing windows
> that are easy to defeat with the wrong test sequence.
>
> Methodology note before you start: `adb shell input swipe` (and other `adb shell input`
> injection commands) return as soon as the event is injected — they do **not** wait for the
> system to finish processing it. Android's ANR / input-dispatch-timeout detection is asynchronous
> and can take 5+ seconds to fire. A fast `adb` return is not evidence that nothing froze. After
> any gesture test that could plausibly stall the input pipeline, wait at least 20–25 seconds and
> check both the actual screen state and logcat (`adb logcat | grep -Ei "anr|input dispatching|not
> responding"`) before concluding it's clean.
>
> - Snap-back is rate-limited to 3 attempts per rolling 5-second window
>   (`MAX_SNAP_BACK_ATTEMPTS`, `SNAP_BACK_RATE_LIMIT_WINDOW_MILLIS`) — verify 3 rapid Home-button
>   presses within 5s correctly force-unlocks, but the same 3 presses spread more than 5s apart
>   never accumulate and never force an unlock.
> - Enable Strong Lock, then lock immediately after pulling down the notification shade from the
>   persistent lock notification itself — verify the protected app captured is the real prior app,
>   not SystemUI.
> - Pull the shade down while locked with Strong Lock on — verify it auto-dismisses without a false
>   snap-back firing (there's a 1500ms suppression window after the app's own dismiss action —
>   `SNAP_BACK_SUPPRESSION_AFTER_SELF_ACTION_MILLIS`; try to catch a case where it still misfires).
> - Press BACK while Settings, the dialer, or an emergency-alert screen is in front during Strong
>   Lock setup — must NOT be blocked (allowlist exemption) even though BACK is blocked everywhere
>   else.
> - Hold Volume-Up + Volume-Down together to force-unlock — test while already unlocked (shouldn't
>   crash), and test taps too short to reach the hold threshold (shouldn't accidentally trigger).
> - Revoke the Accessibility permission from system Settings while a Strong Lock session is
>   active — must degrade gracefully to the base lock, never crash, and the overlay must actually
>   re-attach through the fallback path rather than silently disappearing.
> - If Strong Lock uses any `TYPE_ACCESSIBILITY_OVERLAY` window, test edge-swipe gestures (opening
>   the notification shade, and — separately — the system Home/Back gesture-nav swipes)
>   specifically on a **gesture-navigation** device or profile, not just 3-button nav. This class
>   of window can conflict with the system's own edge-swipe gesture monitor for input-dispatch
>   priority, and that specific conflict doesn't exist in 3-button nav. Run the same test with the
>   overlay window absent entirely as a control, so you can tell whether any residual stall is
>   inherent to having an accessibility-overlay window at all versus specific to how this app uses
>   it.
> - Try the onboarding flow's "Continue to Settings" deep link on whatever emulator API level you
>   have — confirm it does something reasonable even if it can't land exactly on Touch Lock's
>   toggle.
> - Try turning on the accessibility "Shortcut" option despite the in-app warning against it —
>   confirm the warned-about volume-button-disable behavior is real and the app doesn't break
>   weirdly when it happens mid-lock.
>
> **FOCUS AREA 3 — Permissions, lifecycle, and system interruptions**
> - Incoming phone call while locked (use the emulator's call simulation).
> - Doze mode / battery optimization enabled for the app — does the lock survive being idle for a
>   simulated long period?
> - Toggle airplane mode while locked (app is offline-first, so this should be a no-op, but confirm
>   no crash).
> - Reinstall/upgrade the app while a lock was previously active — does stale state get handled
>   cleanly on first launch?
> - Force-dismiss the persistent notification (swipe it away) — per existing project notes this has
>   a known defense (`setAutoCancel(false)`, restore-on-resume) — verify it actually holds up,
>   including what happens if the app is never brought back to the foreground afterward, and
>   specifically that resuming the app rebuilds the notification matching the *current* lock state
>   rather than whichever one was last shown.
>
> **FOCUS AREA 4 — UI, device diversity, and rendering**
> - Test on at least two different screen sizes/densities and both light and dark theme — the
>   disclosure screen has previously needed scroll-into-view fixes for its buttons on small
>   screens; check every screen at a small size, not just the default emulator profile.
> - Test on a 3-button-nav emulator profile AND a gesture-nav one — the nav-bar-blocking mechanism
>   differs materially between them, not just cosmetically: BACK is a real key event, nothing
>   modern uses hardware keys for Home/Recents, and (per Focus Area 2) an accessibility-overlay
>   window used for nav-bar blocking can behave differently — including failure modes — under
>   gesture nav specifically. Verify snap-back, not a block, is what actually covers gesture-nav
>   navigation attempts.
> - Rotate the device while both the main lock overlay and the Strong Lock nav-bar overlay are
>   showing at once — confirm both reposition correctly in the same rotation, not just whichever
>   one happens to be checked first.
> - RTL layout if you can switch a locale that uses it.
> - Rapid repeated taps on every button in the app — confirm nothing double-fires (e.g., two lock
>   countdowns starting at once).
>
> **AFTER TESTING**
> 1. Collect all subagents' bug reports. Before fixing anything, re-verify each one yourself by
>    reproducing it again — don't trust a single subagent's repro without confirming it, since a
>    flaky/misread test result wastes a fix cycle. If host-machine load or another environmental
>    factor could plausibly explain an odd result (e.g. an unexpected process kill during a
>    background test), check for that confound and say so rather than treating an ambiguous result
>    as a confirmed bug.
> 2. For everything confirmed real: fix it, following this project's existing conventions (Kotlin,
>    Compose, MVI, no `!!`, explicit sealed state types — see CLAUDE.md). Don't refactor unrelated
>    code while you're in there. If the buggy decision logic is mirrored by a separate test harness
>    elsewhere in the suite (grep for the same constants/thresholds by name), fix and update every
>    copy — a harness that quietly drifts from the real fix keeps passing while testing the old,
>    wrong behavior.
> 3. After fixes, rebuild (`./gradlew bundleRelease` if you were testing release, or the
>    equivalent) and confirm it still compiles and signs correctly.
> 4. When a bug can only be mitigated, not fully eliminated (e.g. reduced from a severe failure to
>    a brief, self-recovering one), don't unilaterally decide that's good enough. Quantify the
>    before/after severity as precisely as you can — ideally a proper A/B reproduction: the same
>    repro steps with and without the fix, plus a control condition with the suspect mechanism
>    removed entirely — and present the residual risk and mitigation options to me as an explicit
>    choice.
> 5. Give me a final summary that distinguishes three tiers: what was fully fixed, what was
>    mitigated with an accepted residual risk (and how much the severity actually dropped), and
>    what you suspected but weren't confident enough to fix. I'd rather know about a
>    suspected-but-unconfirmed issue than have it silently dropped.

---

## What changed, and why

The prompt above is the original QA-pass prompt with the following additions, each grounded in a
specific thing that went wrong or was learned while actually running it once:

- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE` note (Setup #1)** — hit this switching between signed
  builds across sessions on the same device; it's a leftover-install problem, not a build failure,
  and wastes time if misdiagnosed as one.
- **AVD-creation-tooling caveat (Setup #2)** — no `avdmanager`/cmdline-tools were available in that
  run, so Focus Area 4's API-level diversity had to fall back to whatever AVDs already existed.
  The original prompt had no guidance for this; silently testing less than asked is worse than
  disclosing the gap.
- **Real-device accessibility-settings caution (new Setup #4)** — cleanup at the end of that run
  ran a blanket `settings put secure enabled_accessibility_services null` on the real device,
  which wiped out a pre-existing, unrelated third-party accessibility service that was enabled
  before testing ever started. Caught and restored immediately, but it shouldn't have happened —
  hence the explicit read-before-write instruction.
- **State-transition-race framing (new paragraph in Approach)** — every real bug found in that run
  (stuck countdown notification, snap-back off-by-one, an untested overlay-attach-failure path,
  the accessibility mid-lock disconnect race) was some variant of "a dependency disappears exactly
  between two steps of an operation." The original prompt's focus areas each stumbled onto
  instances of this, but nothing named the pattern, so it's now called out directly as something
  worth probing on purpose rather than by accident.
- **`adb shell input swipe` methodology note (Focus Area 2)** — the original prompt asked for
  gesture/rapid-input testing but didn't warn that `adb`'s input-injection commands return
  immediately, well before Android's asynchronous ANR detection would fire (5+ seconds later). An
  early pass on this run wrongly concluded a fix had worked because the `adb` command returned
  fast; it took a deliberate 20–25 second wait plus a logcat check to find the real, still-present
  stall.
- **Gesture-nav-specific accessibility-overlay conflict (Focus Area 2 and 4)** — a Strong Lock ANR
  turned out to be specific to `TYPE_ACCESSIBILITY_OVERLAY` windows conflicting with the system's
  edge-swipe gesture monitor, a conflict that only exists under gesture navigation. The original
  prompt's Focus Area 4 already asked to test both nav modes, but treated it as a UI/rendering
  concern rather than a correctness one — it can determine whether an entire bug class reproduces
  at all. Also added the "test with the overlay window absent as a control" instruction, since
  that's what made it possible to tell how much residual risk was inherent to the mechanism versus
  this app's specific use of it.
- **Overlay-attach-failure and combined-rotation checks (Focus Area 1 and 4)** — both were gaps
  found only when writing regression tests after the fact, not during the manual pass itself; added
  here so a future manual pass has a chance of catching them live.
- **Notification-restore-reflects-current-state check (Focus Area 3)** — sharpened from "verify the
  defense holds up" to specifically verify the *restored* notification matches live state, since a
  stale-but-plausible notification is the failure mode that's easy to miss by eye.
- **Test-harness-drift instruction (After Testing #2)** — a separate, independent test harness that
  mirrored the same fixed decision logic had not been updated when the original fix landed, so it
  kept passing while asserting the pre-fix, buggy threshold. Now explicit: search for other copies
  of the same logic before considering a fix complete.
- **Mitigated-vs-eliminated framing (new After Testing #4)** — the Strong Lock ANR could be
  meaningfully reduced but not fully eliminated. Deciding unilaterally that a mitigation was "good
  enough" would have been the wrong call to make alone; this makes explicit that residual risk
  after a partial fix is a decision for the person shipping the app, not the person fixing the bug.
- **Three-tier final summary (After Testing #5)** — sharpened from "what was found/fixed" into an
  explicit fixed / mitigated-with-residual-risk / unconfirmed split, since those three carry very
  different implications for a launch decision and were easy to blur together in prose.
