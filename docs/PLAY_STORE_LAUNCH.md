# Play Store Launch — Audit, Checklist & Store Documentation

> Working doc for shipping Touch Lock to Google Play. Generated from a full-repo audit on 2026-08-07.
> Update the "Status" column as items close.

---

## 0. Timeline Reality Check — read this first

Your Play Console account is **new / never published**. Google requires new personal accounts to
run a **closed test with 20+ opted-in testers active for 14 continuous days** before production
access unlocks — this is a Play Console platform gate, not a review-time thing, and it cannot be
skipped or expedited.

**What this means concretely:**
- "Publish next week" is realistic for *submitting to closed testing*, not for a public production
  listing. Public go-live is closed-testing-start + 14 days, at the earliest, plus normal review time.
- The 14-day clock only starts once **20 distinct testers have opted in and installed the build** —
  not once you invite them. If you don't already have 20 people lined up who will install within a
  day or two, add that lead time on top.
- Action **today**, not next week: create the closed test track, write the 20-tester list (friends,
  family, Android dev communities, r/androidapps beta threads, etc.), and get invites out. Every day
  this slips is a day added to the public launch date.
- Recommendation: treat next week's target as "closed testing live with testers actively opted in,"
  and set public launch expectations ~3 weeks out.

---

## 1. Audit Findings

### 1.1 Blocking / must-fix before submission

| # | Finding | Why it matters | Fix |
|---|---------|-----------------|-----|
| B1 | No release `signingConfig` in `app/build.gradle.kts` | `./gradlew bundleRelease` today has nothing to sign the AAB with — you'll hit this the first time you try to produce a release build | **Done (2026-08-07)**: `app/build.gradle.kts` now reads signing credentials from a gitignored `keystore.properties` (template at `keystore.properties.example`). **Remaining**: you still need to generate the actual upload keystore yourself (§1.4) — do that in your own terminal, not through an assistant, since it needs interactive password entry |
| B2 | No hosted Privacy Policy URL | Mandatory for any app that requests a sensitive permission (Accessibility qualifies) and for the Data Safety section — Play Console will not let you publish without one | **Done (2026-08-07)**: full policy written to `docs/privacy-policy.html`. **Remaining**: enable GitHub Pages on this repo (Settings → Pages → Source: `main` branch, `/docs` folder) — it'll be live at `https://jmuci.github.io/android-touch-lock/privacy-policy.html` |
| B3 | Accessibility Permission Declaration Form not yet filed | Any app declaring `BIND_ACCESSIBILITY_SERVICE` gets routed through Play's Permissions Declaration Form during review; undeclared/unjustified use is a common auto-rejection reason | Justification text in §3.3, updated to lead with "parenting control app" framing per your confirmed positioning — paste into the form. You may also need a short screen recording demonstrating the feature (see B4) |
| B4 | No screen-recording demo of Strong Lock for the declaration form | Google increasingly asks for a video walkthrough of exactly how the accessibility service is used, not just text | Record a 30–60s screen capture: enable Strong Lock → show the in-app disclosure screen → grant in Settings → demonstrate nav-bar tap blocked / snap-back while locked. Keep the raw file — Play Console sometimes asks for it after initial submission too |
| B5 | Closed testing not yet started | See §0 | Start today — this is the actual critical path to any launch date |

### 1.2 Important — should fix before submission

| # | Finding | Why it matters | Fix |
|---|---------|-----------------|-----|
| I1 | `docs/ARCHITECTURE.md` "Risks / Technical Debt" lists 2 stale-doc issues (`DEBUGGING_GUIDE.md` references removed components; README lists a removed "Orientation control" feature) | Minor, but reviewers occasionally skim linked docs from a GitHub-linked support URL; stale docs read as unmaintained | Quick doc pass — see checklist item C11 |
| I2 | `firebender.json` still contains the rule *"Do not introduce Accessibility services or Accessibility permissions unless explicitly instructed"* | This was the **old** constraint — it was explicitly reversed 2026-08-04 and `CLAUDE.md` was updated accordingly, but `firebender.json` was missed. Stale, contradicts current `CLAUDE.md`, and could steer future agent-assisted changes wrong | Update or remove that line in `firebender.json` to match the current `CLAUDE.md` accessibility-scoped-use policy |
| I3 | Two uncommitted files in the working tree (`TouchLockAccessibilityService.kt`, `SnapBackDecisionLogicTest.kt`) | Looks like a completed, well-tested bugfix (protected-package-adoption race) sitting uncommitted | Commit and push before cutting a release build, so the release branch reflects it |
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

---

## 2. Pre-Launch Checklist

Roughly sequenced — earlier items unblock later ones.

### This week (critical path)
- [ ] **C1** — Recruit ≥20 testers willing to opt in within 1–2 days (friends, family, r/androiddev, r/androidapps, Discord communities). No rush per your call — this is just what unlocks production later, whenever you're ready to flip that switch.
- [ ] **C2** — Commit the two uncommitted files (snap-back fix) and push (I3).
- [x] **C3** — ~~Generate an upload keystore; wire `signingConfigs.release`~~ — gradle wiring done (2026-08-07). You still need to run the `keytool` command in §1.4 yourself to generate the actual keystore file (B1).
- [x] **C4** — ~~Write and host the Privacy Policy~~ — written to `docs/privacy-policy.html` (2026-08-07). You still need to flip on GitHub Pages for this repo (Settings → Pages → `main` / `/docs`) to make the URL live (B2).
- [ ] **C5** — Create the app in Play Console; fill Store Listing (draft copy in §3.1).
- [ ] **C6** — Complete the Data Safety form (answers in §3.4).
- [ ] **C7** — Complete the Content Rating (IARC) questionnaire and Target Audience section per I5.
- [ ] **C8** — Complete the Accessibility Permissions Declaration Form (justification text in §3.3) — attach the screen recording from C9.
- [ ] **C9** — Record the 30–60s Strong Lock screen-capture demo (B4).
- [ ] **C10** — Build a signed release AAB (`./gradlew bundleRelease`), upload to the **Closed testing** track, add your tester list, publish the closed test.

### Before / during the 14-day testing window
- [ ] **C11** — Doc cleanup pass: fix `docs/DEBUGGING_GUIDE.md` stale component references and remove "Orientation control" from README's feature list (I1); update `firebender.json`'s stale no-accessibility rule (I2).
- [ ] **C12** — Capture Play Store screenshots (min 2, recommend 4–8) from a real device/emulator — reuse or refresh the three already in `docs/screenshots/`.
- [ ] **C13** — Produce a 1024×500 feature graphic.
- [ ] **C14** — Produce/confirm a 512×512 hi-res app icon (separate from the adaptive launcher icon assets already in `res/mipmap-*`).
- [ ] **C15** — Pick a Play Console category (suggest **Tools** or **Parenting**).
- [ ] **C16** — Smoke-test the signed release build end-to-end on a real device: default mode lock/unlock, Strong Lock disclosure → enable → nav-bar block → snap-back, notification toggle, rotation (given the recent rotation-safety fixes on this branch).
- [ ] **C17** — Watch closed-testing feedback and Android vitals (I4) for crashes/ANRs from real testers.

### At production release
- [ ] **C18** — Re-confirm 20 testers stayed opted in for 14 continuous days (Play Console shows this status directly — don't self-track).
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
> blocks navigation-bar taps (Home/Back/Recents) and automatically brings the protected app back to
> the front if it's swiped away. It's explained on a dedicated screen before you turn it on, and it
> never reads, records, or shares anything on your screen — only the name of the app in the
> foreground, processed entirely on-device.
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

**Status: written, not yet live.** The full policy is at `docs/privacy-policy.html` (self-contained,
light/dark aware, no external dependencies) rather than inline here, so it's the single source of
truth instead of drifting out of sync with a copy pasted into this doc.

**To make it live:** GitHub repo → Settings → Pages → Source: Deploy from branch → `main` / `/docs`.
Once enabled, it'll be reachable at:

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
> that gap: it listens for window-state-change events to (a) detect and block taps landing on the
> navigation bar while the lock is active, and (b) relaunch the protected app if the user manages to
> navigate away from it while locked. It is not used for any other purpose, and the app's default
> mode is fully functional without it.
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

## 4. Open questions for you

- **Privacy policy hosting**: resolved — GitHub Pages on this repo's `/docs` folder (§3.2). Just
  needs to be switched on in repo Settings.
- **Support email**: `docs/privacy-policy.html` currently lists `jm.mucientes.fayos@gmail.com` as
  the contact. Confirm that's the one you want public on the Play Store listing too, or swap it.
- **Screenshots**: the three in `docs/screenshots/` may be enough as a starting point, but confirm
  they're current after the recent theming/rotation fixes on this branch before reusing them.
