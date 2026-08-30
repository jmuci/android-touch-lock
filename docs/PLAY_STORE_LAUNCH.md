# Play Store Launch — Audit, Checklist & Store Documentation

> Working doc for shipping Touch Lock to Google Play. Generated from a full-repo audit on 2026-08-07,
> refreshed 2026-08-30 against the current branch (accessibility service gained notification-shade
> dismissal and BACK-key interception since the original audit — justification text and disclosure
> strings below now reflect that).
> Update the "Status" column as items close.

---

## 0. Timeline Reality Check — read this first

Your Play Console account is **new / never published**. Google requires new personal accounts to
run a **closed test with 12+ opted-in testers active for 14 continuous days** before production
access unlocks — this is a Play Console platform gate, not a review-time thing, and it cannot be
skipped or expedited. (**Corrected 2026-08-30**: this used to be 20 testers — Google lowered it to
12 in December 2024. Confirmed against the current Play Console Help article as of this review.)

**What this means concretely:**
- "Publish next week" is realistic for *submitting to closed testing*, not for a public production
  listing. Public go-live is closed-testing-start + 14 days, at the earliest, plus normal review time.
- The 14-day clock only starts once **12 distinct testers have opted in**, and only counts testers
  who stay opted in for 14 *consecutive* days — a tester who opts in, drops out, then rejoins resets
  their own clock. Pad your recruiting list beyond exactly 12 so a couple of drop-outs don't stall you.
- **New, separate gate — not in the original audit**: new developer accounts also require **identity
  verification** (government ID) before anything can be submitted at all. Google states 24 hours
  typically, up to a few days in practice. If you haven't done this yet in Play Console, it's a
  same-priority action as recruiting testers — do both today, since neither blocks the other but both
  block everything downstream.
- Action **today**, not next week: kick off identity verification, create the closed test track, write
  the tester list (friends, family, Android dev communities, r/androidapps beta threads, etc.), and get
  invites out. Every day this slips is a day added to the public launch date.
- Recommendation: treat next week's target as "closed testing live with testers actively opted in,"
  and set public launch expectations ~3 weeks out. The lower tester bar (12 vs 20) helps, but budget
  slack for at least one round of back-and-forth on the Accessibility declaration — see §1.1 B3 and
  the new §1.5 below, which is the part of this plan most likely to bounce on first submission.

---

## 1. Audit Findings

### 1.1 Blocking / must-fix before submission

| # | Finding | Why it matters | Fix |
|---|---------|-----------------|-----|
| B1 | ~~No release `signingConfig` in `app/build.gradle.kts`~~ | — | **Done (2026-08-30)** — upload keystore generated, `keystore.properties` filled in, and `./gradlew bundleRelease` verified to produce a correctly signed AAB (`jarsigner -verify` confirms `jar verified`, signed by the `touchlock-upload` key, cert valid until 2054). Keystore backed up to Bitwarden as a Secure Note (base64-encoded `.jks` + passwords) |
| B2 | ~~No hosted Privacy Policy URL~~ | — | **Done (2026-08-30)** — policy moved to `website/privacy-policy.html` alongside the new promo site (§3.2). Pages' source setting was switched from legacy branch-deploy to the repo's own **GitHub Actions** workflow (it had briefly regressed to a 404 after the `website/` move, since the source setting hadn't been flipped yet), the `Deploy Pages` workflow was run, and the live URL is confirmed working again |
| B3 | Accessibility Permission Declaration Form not yet filed | Any app declaring `BIND_ACCESSIBILITY_SERVICE` gets routed through Play's Permissions Declaration Form during review; undeclared/unjustified use is a common auto-rejection reason. **Confirmed 2026-08-30 against current Play policy**: since Touch Lock is not a disability accessibility tool, it goes through the *non-accessibility-tool* declaration path (reason = "app functionality") — this is the correct path and is a legitimate, commonly-approved one (call-blocking, parental-control, and password-manager apps all use it), not a mismatch. See §1.5 for the residual risk and how to write the justification to minimize it | Justification text in §3.3, updated to lead with "no alternative API exists" framing (technical necessity, not convenience) — paste into the form. Needs the screen recording from B4 |
| B4 | No screen-recording demo of Strong Lock for the declaration form | Google requires a video walkthrough of exactly how the accessibility service is used, not just text | Record a 30–60s screen capture: enable Strong Lock → show the in-app disclosure screen → grant in Settings → demonstrate the BACK button blocked, the notification shade auto-dismissing, and snap-back after Home/Recents, all while locked. Keep the raw file — Play Console sometimes asks for it again after initial submission |
| B5 | Closed testing not yet started | See §0 | Start today — this is the actual critical path to any launch date. Requires 12 testers now, not 20 |
| B6 | **New finding, 2026-08-30**: `FOREGROUND_SERVICE_SPECIAL_USE` also needs its own Play Console declaration — separate from the Accessibility one, and missing from the original audit entirely | Confirmed against current Play policy: any app using the `specialUse` foreground service type must declare it in Play Console's **App content → Foreground service permissions** section, describing the functionality, the user impact if the task were deferred, and (again) a video demo. Undeclared use of `specialUse` is a plausible rejection reason on its own, independent of the Accessibility declaration | Draft justification added in §3.5 (new) — the manifest's `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value ("Touch input overlay management") is the closest fit to an existing category; if none of Play Console's dropdown options match, use the manual "describe your use case" free-text option with that text |
| B7 | ~~No in-app link to the Privacy Policy~~ | — | **Done (2026-08-30)** — added an underlined "Privacy Policy" text link to the bottom of `HomeScreen`, opening the live URL via `LocalUriHandler`. Confirmed against current Play policy: apps requesting a sensitive permission (Accessibility qualifies) must link the privacy policy both on the store listing *and* from within the app itself — Play Console's field alone isn't sufficient |

### 1.2 Important — should fix before submission

| # | Finding | Why it matters | Fix |
|---|---------|-----------------|-----|
| I1 | `docs/ARCHITECTURE.md` "Risks / Technical Debt" lists 2 stale-doc issues (`DEBUGGING_GUIDE.md` references removed components; README lists a removed "Orientation control" feature) | Minor, but reviewers occasionally skim linked docs from a GitHub-linked support URL; stale docs read as unmaintained | **Mostly resolved as of 2026-08-30**: README no longer lists "Orientation control". `DEBUGGING_GUIDE.md`'s `OrientationLockActivity` mention is legitimate historical context (explains why a workaround was removed), not a stale reference. Only `docs/ARCHITECTURE.md`'s own tech-debt table entries are now themselves stale — cosmetic, not launch-blocking |
| I2 | ~~`firebender.json` still contains the rule *"Do not introduce Accessibility services or Accessibility permissions unless explicitly instructed"*~~ | — | **Done** — fixed in efc736e (2026-08-09) |
| I3 | ~~Two uncommitted files in the working tree (`TouchLockAccessibilityService.kt`, `SnapBackDecisionLogicTest.kt`)~~ | — | **Done** — committed and merged (PRs #24, #25) |
| I4 | No crash/ANR visibility configured | You'll be flying blind on stability post-launch | **Do not add a third-party crash SDK** (Crashlytics, Sentry, etc.) without discussing it first — this project is offline-first by explicit constraint, and most crash SDKs phone home. Play Console's built-in **Android vitals** (crashes, ANRs, excessive wakeups) requires zero code/network changes and is the right default here |
| I5 | Target audience / content rating not yet set in Play Console | The app is *about* protecting toddlers, but it's *used and configured by* an adult (a toddler doesn't install or configure Touch Lock). Declaring it "designed for children" would pull in Play Families Policy, which restricts or forbids exactly the kind of Accessibility usage Strong Lock relies on | When you get to the questionnaire: target audience = adults (skip "designed for children"), content rating = Everyone. Mention "supervised use by a parent/guardian" in the description, not "for kids" |
| I6 | `versionCode = 1`, `versionName = "1.0"` | Fine for a first release — just confirming it's intentional and not a placeholder you forgot to bump | No action unless unintentional |

### 1.3 Solid / no action needed

These were audited and are already in good shape — called out so you know they were checked, not skipped:

- **Manifest permissions** are minimal and each is justified (`SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `VIBRATE`). No `AD_ID`, no location, no contacts/storage.
- **`<queries>` block** is scoped to `LAUNCHER` intent only — not `QUERY_ALL_PACKAGES` — and has an inline comment explaining why. This is exactly the minimal-scope pattern Play policy expects.
- **`isAccessibilityTool` is correctly absent** from `accessibility_service_config.xml` — a false claim here risks account termination; not claiming it is correct since this isn't a disability-access tool.
- **`canRetrieveWindowContent="false"`** — the service structurally cannot read screen content, matching every privacy claim made in the README/strings/disclosure copy. This is a strong, verifiable claim to make in the Data Safety form and Permissions Declaration Form.
- **In-app disclosure before Settings hand-off** already exists (`strong_lock_disclosure_*` strings, wired in `HomeScreen.kt`) and is dedicated/specific rather than a generic OS permission dialog — this is what Play's Permissions Declaration Form actually wants to see.
- **No network code anywhere** — genuinely offline, which simplifies the Data Safety form to "no data collected" across the board.
- **`allowBackup=true` with default/empty extraction & backup rules** — fine; the only persisted data (`DataStore`: usage-date, accumulated-millis, last-start-time, a debug flag) contains no PII.
- **Foreground service type** correctly declared as `specialUse` with the required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` string for Android 14+.
- **`minifyEnabled` + `shrinkResources`** on release builds, with a proguard-rules.pro that correctly keeps the domain models and string-referenced service action constants.
- **CI**: unit tests run on every PR/push to `main`/`develop` via GitHub Actions; a separate `ui-tests.yml` also exists.
- **LICENSE**: MIT, present, unambiguous.

### 1.4 How to generate the release keystore

Run this yourself, in your own terminal — not pasted through an assistant — since it prompts for
passwords interactively and those shouldn't pass through any tool logs.

```bash
mkdir -p ~/Keys/touchlock
keytool -genkeypair -v \
  -keystore ~/Keys/touchlock/touchlock-upload.jks \
  -alias touchlock-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

It'll prompt for a keystore password, a key password (press Enter to reuse the keystore password),
and some identity fields (name, org, city, etc. — these end up in the certificate, not anywhere
user-facing). Store the file **outside any git worktree** — `~/Keys/touchlock/` above, or wherever
you keep long-lived secrets — since worktrees can be deleted and this key signs every future update
to the app. **Back it up somewhere durable** (password manager attachment, encrypted cloud backup):
losing it means filing a Play Console key-reset request, which has real downtime.

Then:
```bash
cp keystore.properties.example keystore.properties
```
and fill in `storeFile` (the absolute path above), `storePassword`, `keyAlias` (`touchlock-upload`),
and `keyPassword`. `keystore.properties` is gitignored — it'll never be committed.

Verify it works:
```bash
./gradlew bundleRelease
```
This should now produce a signed `.aab` under `app/build/outputs/bundle/release/`. First upload to
Play Console — enroll in **Play App Signing** when prompted (Google re-signs your upload with its
own key for distribution; this is the current recommended default and lets you recover from a lost
upload key more easily than without it).

### 1.5 Real risk: Accessibility declaration is the part most likely to bounce

This is the single biggest source of uncertainty in the whole plan, and it's worth being direct about
it rather than treating the drafted justification text as a guarantee of approval.

**The actual policy, confirmed 2026-08-30 against Google's current AccessibilityService guidance**:
the API's stated primary purpose is apps that support users with disabilities (screen readers,
switch/voice input, etc.). Apps like Touch Lock that aren't accessibility tools go through a
*different* declaration path — you state a reason (the relevant one here is "app functionality"),
and this is a well-trodden, commonly-approved path: call-blocking apps, password managers, and
parental-control apps all ship on Play using exactly this route. So the framing in §3.3 is the
*correct* one, not a misfit — but "correct path" isn't the same as "automatic approval." Reviewers
manually re-derive, from your justification and video, whether the feature you're describing could
plausibly be done another way. If it reads like "accessibility made this more convenient," that's a
rejection. If it reads like "there is no other public API that reaches the navigation bar or
notification shade," that survives scrutiny much better — which is why §3.3 was reworded to lead
with the technical impossibility, not the feature benefit.

**Concretely, expect one of three outcomes:**
1. Approved on first submission — plausible, given the justification is specific and technically
   grounded, and comparable apps exist in this category.
2. Approved after a clarification round — Google asks a follow-up question or wants the video
   re-cut to show something specific. Budget a few extra days for this; don't schedule the 14-day
   testing clock so tightly that a review round-trip blows the timeline.
3. Rejected outright, with a suggestion to use a narrower mechanism instead — the most likely
   alternative Google would point to is Android's built-in **Screen Pinning / Lock Task Mode**
   (`startLockTask()`), which blocks Home/Recents/notification-shade natively without Accessibility
   at all. This is explicitly out of scope per `CLAUDE.md` ("No Kiosk Mode... do not introduce device
   owner APIs or system gesture blocking" — Lock Task Mode in its unprivileged form doesn't need
   device-owner status, so it may not even conflict with that rule as narrowly read, but that's a
   product/architecture call for you to make, not one to decide unilaterally if rejection happens).

**What to do about it now, not after a rejection:** nothing further needed for submission — the
justification and manifest are already about as strong as they can be. Just don't treat "closed
testing passed" as proof the Accessibility declaration will clear too; they're reviewed on different
tracks, and it's possible to clear the 12-tester/14-day gate and still get bounced on this specific
piece when you apply for production access.

---

## 2. Pre-Launch Checklist

Roughly sequenced — earlier items unblock later ones.

### This week (critical path)
- [ ] **C0** — **New, 2026-08-30**: Start Play Console identity verification (government ID) today if not already done — it's a separate gate from closed testing, typically resolves in 24h–a few days, and blocks submission entirely until approved.
- [ ] **C1** — Recruit ≥12 testers willing to stay opted in for 14 consecutive days (friends, family, r/androiddev, r/androidapps, Discord communities). **Corrected 2026-08-30: 12, not 20** — Google lowered the minimum in Dec 2024. Recruit a couple extra as buffer against drop-outs, since a tester who lapses resets their own clock.
- [x] **C2** — ~~Commit the two uncommitted files (snap-back fix) and push~~ — done, merged via PRs #24/#25.
- [ ] **C3** — Gradle wiring done (2026-08-07). **Still open**: no keystore file found anywhere on this machine — run the `keytool` command in §1.4 yourself (B1).
- [x] **C4** — ~~Enable GitHub Pages~~ — done 2026-08-30, confirmed live at `https://jmuci.github.io/android-touch-lock/privacy-policy.html`. Note: it's serving `main`'s current version — the shade-dismissal accuracy fix on this branch won't be live until this branch merges.
- [ ] **C5** — Create the app in Play Console; fill Store Listing (draft copy in §3.1).
- [ ] **C6** — Complete the Data Safety form (answers in §3.4).
- [ ] **C7** — Complete the Content Rating (IARC) questionnaire and Target Audience section per I5.
- [ ] **C8** — Complete the Accessibility Permissions Declaration Form (justification text in §3.3, refreshed 2026-08-30 to cover BACK-key interception and shade dismissal, and hardened per §1.5's risk analysis) — attach the screen recording from C9.
- [ ] **C8b** — **New, 2026-08-30**: Complete the separate Foreground Service Permission declaration (App content → Foreground service permissions) using the draft in §3.5 — missed entirely in the original audit (B6).
- [ ] **C9** — Record the 30–60s Strong Lock screen-capture demo (B4) — should now show all three behaviors: BACK blocked, shade auto-dismissed, snap-back on Home/Recents. Reused for both C8 and C8b.
- [ ] **C10** — Build a signed release AAB (`./gradlew bundleRelease`), upload to the **Closed testing** track, add your tester list, publish the closed test.

### Before / during the 14-day testing window
- [x] **C11** — ~~Doc cleanup pass~~ — `firebender.json` fixed (I2, done in efc736e); README no longer lists "Orientation control" (I1, resolved). Only `docs/ARCHITECTURE.md`'s own tech-debt table is now stale — optional, non-blocking.
- [ ] **C12** — Capture Play Store screenshots (min 2, recommend 4–8) from a real device/emulator — reuse or refresh the three already in `docs/screenshots/`.
- [ ] **C13** — Produce a 1024×500 feature graphic.
- [ ] **C14** — Produce/confirm a 512×512 hi-res app icon (separate from the adaptive launcher icon assets already in `res/mipmap-*`).
- [ ] **C15** — Pick a Play Console category (suggest **Tools** or **Parenting**).
- [ ] **C16** — Smoke-test the signed release build end-to-end on a real device: default mode lock/unlock, Strong Lock disclosure → enable → nav-bar block → snap-back, notification toggle, rotation (given the recent rotation-safety fixes on this branch).
- [ ] **C17** — Watch closed-testing feedback and Android vitals (I4) for crashes/ANRs from real testers.

### At production release
- [ ] **C18** — Re-confirm 12 testers stayed opted in for 14 continuous days (Play Console shows this status directly — don't self-track).
- [ ] **C19** — Promote the tested build to Production.
- [ ] **C20** — Post-launch: monitor Android vitals and Play Console reviews for the first week.

---

## 3. Store & Compliance Documentation (ready to paste)

### 3.1 Store Listing Copy

**Short description** (≤80 chars):
> Block accidental screen taps so kids can safely watch videos or calls.

**Full description** (draft — trim/adjust freely, Play allows up to 4000 chars):

> Touch Lock is a lightweight utility that temporarily disables touch input on your screen while
> keeping whatever's on it — a video, a call, a recipe — fully visible.
>
> Built for supervised moments: handing a phone to a toddler to watch a video, preventing accidental
> hang-ups or muted mics during a video call, or displaying something hands-free.
>
> HOW IT WORKS
> • Open the app or video you want to protect
> • Tap the Touch Lock notification (or the in-app button) to lock — a 10-second countdown gives you
>   time to switch to the right app first
> • Touch input is blocked until you double-tap and hold the unlock handle for one second
>
> OPTIONAL: TODDLER-PROOF LOCK
> An optional, off-by-default mode adds stronger protection using Android's Accessibility service: it
> blocks navigation-bar taps (Home/Back/Recents), closes the notification shade if it's pulled down,
> and automatically brings the protected app back to the front if it's swiped away. It's explained on
> a dedicated screen before you turn it on, and it never reads, records, or shares anything on your
> screen — only the name of the app in the foreground, processed entirely on-device.
>
> PRIVACY
> Touch Lock works fully offline. No account, no ads, no analytics, no data collection of any kind —
> nothing ever leaves your device.
>
> Touch Lock is a parenting-control tool, installed and configured by a parent or guardian — not a
> full device-management suite. There's no device-owner mode, no app blocking, no remote management.

**Suggested category:** Parenting (Tools is the fallback if Parenting isn't a good fit for your
region's category list)

**Target audience:** Adults / general audience — confirmed with you 2026-08-07: the app is marketed
*to* parents as a parenting-control tool, but it's *configured and used* by the adult, not the
child. Do not mark it "designed for children" in Play Console's target-audience questionnaire — see
I5. These are two different things: "parenting control app" is accurate marketing copy; "designed
for children" is a specific Play Console policy flag that pulls in Families Policy restrictions
hostile to Accessibility API use. Keep them separate.

---

### 3.2 Privacy Policy

**Status: written, not yet live.** The full policy is at `website/privacy-policy.html` (self-contained,
light/dark aware, no external dependencies) rather than inline here, so it's the single source of
truth instead of drifting out of sync with a copy pasted into this doc. It's now part of the
promo site added 2026-08-30 (`website/index.html`), which links to it from the footer/nav.

**To make it live:** GitHub repo → Settings → Pages → Source: **GitHub Actions**. A workflow
(`.github/workflows/pages.yml`) already deploys the contents of `website/` on every push to `main`
— enabling Pages just needs that one-time source selection. Once enabled, the policy will be
reachable at:

```
https://jmuci.github.io/android-touch-lock/privacy-policy.html
```

Use that exact URL in both the Play Console Store Listing's Privacy Policy field and the Data Safety
section. It already reflects the parenting-control framing and lists every permission (including
the optional Accessibility service) with the same "never reads screen content, never leaves the
device" claims made throughout this doc.

---

### 3.3 Accessibility Permissions Declaration Form — justification text (draft)

> **What is the core functionality of your app?**
> Touch Lock is a parenting-control app: a parent or guardian installs and configures it, then
> temporarily disables touch input on the screen (via a standard draw-over-other-apps overlay) so a
> young child handed the device — for example, to watch a video — can't accidentally interact with
> it, or so touches don't interrupt a video call. It works fully offline with no accounts, ads, or
> data collection. Privacy policy: https://jmuci.github.io/android-touch-lock/privacy-policy.html
>
> **Why does your app need the Accessibility API, and how does it enhance the app's functionality?**
> The core touch-blocking overlay cannot block taps on the system navigation bar (Home/Back/Recents)
> or the notification shade — those are owned by the system, outside any app's normal window. An
> optional, off-by-default "Toddler-Proof Lock" mode uses the Accessibility API narrowly to close
> that gap, in three ways: (a) it requests filtered key events so it can consume the BACK button
> while locked, preventing that one navigation-bar action outright; (b) it listens for window-state
> events so that if the notification shade is pulled down while locked, it dismisses it via the
> standard `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` action (API 31+; a documented no-op below that,
> where the shade simply stays open); and (c) for actions it cannot block outright — Home, Recents, a
> shade swipe on API < 31 — it relaunches the protected app to the front. It is not used for any
> other purpose, and the app's default mode is fully functional without it.
>
> **Does your Accessibility Service declare `isAccessibilityTool="true"`?**
> No. This is not a tool for users with disabilities.
>
> **What user-facing features rely on the Accessibility API, and can the app function without it?**
> Only the optional "Toddler-Proof Lock" enhancement. The app's default lock/unlock functionality is
> fully independent of Accessibility and works identically whether the service is off, unavailable,
> or fails at runtime.
>
> **Does the app request `canRetrieveWindowContent`?**
> No — `canRetrieveWindowContent="false"` in the service's configuration. The service structurally
> cannot read screen content; it only receives the package name of the foreground app.
>
> **Does the app request `canRequestFilterKeyEvents`?**
> Yes. This is used solely to consume the hardware/gesture BACK signal while Touch Lock is active, so
> that one navigation-bar action is blocked outright rather than only reversed after the fact. It has
> no effect, and consumes no key events, whenever the lock is off.
>
> **How and when is the user informed of the Accessibility Service's use?**
> Before requesting the permission, the app shows a dedicated, full-screen in-app disclosure
> ("Turn on Toddler-Proof Lock?") explaining exactly what the service does, what data it accesses
> (foreground app package name only), and how that data is used and not shared, with an explicit
> "Not now" decline option that leaves the app fully functional in its default mode. Only after
> reading this does the user proceed to Android Settings to grant the permission.

---

### 3.4 Data Safety Form — suggested answers

Given there is no network code anywhere in the app and no third-party SDKs, the Data Safety form
should be filled as:

- **Does your app collect or share any of the required user data types?** No
- **Is all user data encrypted in transit?** N/A (no data transmitted)
- **Do you provide a way for users to request data deletion?** N/A (nothing is collected)
- **Data types**: leave every category (location, personal info, financial, health, messages,
  photos/videos, audio, files, app activity, app info/performance, device/other IDs) unchecked.

If Google's questionnaire asks about **on-device-only data** (usage-time tracking, Strong Lock
enabled/disabled flag), note in the form's free-text field that this data is stored locally via
Android DataStore only, is never transmitted, and is deleted on uninstall.

---

### 3.5 Foreground Service Permission declaration — justification text (draft, new 2026-08-30)

This is a separate Play Console declaration from the Accessibility one (§3.3) — found while
re-auditing the plan; it wasn't in the original 2026-08-07 pass. Any app using
`FOREGROUND_SERVICE_SPECIAL_USE` must justify it under **App content → Foreground service
permissions** in Play Console.

> **Describe the app functionality that uses this foreground service type:**
> Touch Lock's core feature is a full-screen overlay that blocks touch input while the underlying
> app (a video, a call) stays visible, so a device can be handed to a young child without accidental
> taps. This requires an ongoing foreground service — declared `specialUse` because none of Android's
> more specific foreground service types (camera, location, media playback, etc.) describe drawing a
> system overlay to intercept touch — to own the `WindowManager` overlay's lifecycle for as long as
> the lock is active, matching the manifest's declared subtype, "Touch input overlay management."
>
> **What is the impact to the user if you don't use a foreground service, or if the task is
> deferred/interrupted?**
> Without a persistent foreground service, Android would stop the overlay shortly after the app
> leaves the foreground — which is the entire point of the feature, since it's used precisely while
> the user has switched to a video or call app. The touch-blocking overlay would silently disappear
> mid-use, defeating the app's purpose and potentially exposing the device to accidental taps the
> user believed were still blocked.
>
> **Does this service run continuously, or only while the user is actively using this feature?**
> Only while Touch Lock is actively locked. It starts when the user locks the screen (from the app
> or the persistent notification) and stops as soon as they unlock — it never runs otherwise.

---

## 4. Open questions for you

- **Privacy policy hosting**: resolved and live — GitHub Pages enabled 2026-08-30, confirmed
  reachable at `https://jmuci.github.io/android-touch-lock/privacy-policy.html` (§3.2).
- **Support email**: `website/privacy-policy.html` currently lists `jm.mucientes.fayos@gmail.com` as
  the contact. Confirm that's the one you want public on the Play Store listing too, or swap it.
- **Screenshots**: the three in `docs/screenshots/` may be enough as a starting point, but confirm
  they're current after the recent theming/rotation fixes on this branch before reusing them.
