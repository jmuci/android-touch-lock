# Release Process — Building & Uploading to Google Play

Repeatable steps for shipping a new version of Touch Lock. For the one-time initial setup
(keystore generation, Play Console account creation, store listing copy, compliance
declarations), see [PLAY_STORE_LAUNCH.md](PLAY_STORE_LAUNCH.md) — that doc covers the first
submission; this one covers every release after that.

## Prerequisites (one-time, already done)

- `keystore.properties` exists at the repo root (gitignored) and points at the upload keystore —
  see [PLAY_STORE_LAUNCH.md §1.4](PLAY_STORE_LAUNCH.md#14-how-to-generate-the-release-keystore)
  if it's missing on this machine.
- Play App Signing is enrolled, so the keystore above is only the *upload* key — Google holds the
  final signing key.

## 1. Bump the version

In [`app/build.gradle.kts`](../app/build.gradle.kts), under `defaultConfig`:

```kotlin
versionCode = 4   // must strictly increase every release, no exceptions
versionName = "1.1"  // user-facing, semver-ish, your call
```

`versionCode` is what Play Console actually enforces — a duplicate or lower value gets the
upload rejected outright.

Commit the bump, then tag it (existing convention in this repo — `v{versionName}-vc-{versionCode}`,
see `git tag -l`):

```bash
git tag v1.1-vc-4
git push origin v1.1-vc-4
```

Tag *before* uploading, on the exact commit you're about to build from — that way the tag always
traces back to what's actually live on Play Console, not a commit that got amended afterward.

## 2. Build the signed AAB

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`, signed with the upload key via the
`release` signing config (wired in `app/build.gradle.kts`, populated from `keystore.properties`).
`isMinifyEnabled`/`isShrinkResources` are on for this build type, so also smoke-test the release
build itself (not just debug) before uploading — R8 obfuscation can occasionally break reflection-
or annotation-based code paths that debug builds don't exercise.

Sanity-check the signature if you want extra confidence:

```bash
jarsigner -verify -verbose app/build/outputs/bundle/release/app-release.aab
```

## 3. Upload to Play Console

1. [Play Console](https://play.google.com/console) → Touch Lock → **Release → Testing → Closed
   testing** (or **Production**, once you're past closed testing).
2. **Create new release**.
3. Upload `app-release.aab`.
4. Write release notes (what changed, user-facing).
5. Save → **Review release** → **Start rollout**.

Google scans the binary (a few minutes to a few hours) before it's actually available to testers
or production users — the console shows status.

## 4. Track promotion

Internal testing → Closed testing → Open testing → Production. Promote the *same* build (Play
Console lets you promote a release from one track to the next) rather than rebuilding, so what
you tested is exactly what ships.

## Checklist (per release)

- [ ] `versionCode` incremented, `versionName` updated
- [ ] Commit tagged (`v{versionName}-vc-{versionCode}`) and pushed
- [ ] `./gradlew bundleRelease` succeeds
- [ ] Release build smoke-tested on a device (not just debug)
- [ ] Release notes written
- [ ] Uploaded to the correct track
- [ ] Android vitals checked a day or two after rollout for new crashes/ANRs
