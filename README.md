# London Dock Compass

A native Kotlin + Jetpack Compose **Wear OS** app that points you to nearby Santander Cycles docking stations and shows live bike/dock availability, right on your wrist. It uses on-watch location + a compass heading to find the closest dock, then queries the TfL BikePoint API for real-time bike/e-bike/empty-dock counts — surfaced in the main app UI and as a watch face complication.

## Getting started

**Requirements:** Android Studio (current stable), JDK 17.

1. Open the repo root in Android Studio — it's a standard Gradle project (no nested `android/` directory), so it should sync immediately.
2. Run on a Wear OS emulator (create one via Device Manager, e.g. a "Wear OS Large Round" image) or a physical Wear OS watch with Developer Options/ADB debugging enabled.
3. Or from the command line:
   ```
   ./gradlew installDebug
   ```

Build config (from `app/build.gradle.kts`): `compileSdk = 35`, `minSdk = 30`, `targetSdk = 35`. `applicationId` / `namespace` is `uk.co.maybeitssoftware.londondockcompass`.

## Project structure

```
app/src/main/java/uk/co/maybeitssoftware/londondockcompass/
├── presentation/        MainActivity.kt (Compose UI, location + sensor handling, ambient mode) and theme
├── data/                BikePointRepository.kt (TfL BikePoint API client), TfLModels.kt
└── complication/        MainComplicationService.kt (watch face complication)
```

Dock station coordinates are bundled as a raw resource (`app/src/main/res/raw/docklocations.json`) and refreshed periodically from the live TfL API by a scheduled workflow (see below).

## Conventional Commits

All commits must follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `chore:`, `refactor:`, etc.) — this drives the automatic versioning described below. It's enforced locally by a husky `commit-msg` git hook.

This tooling (commitlint, semantic-release, husky) is Node-based even though the app itself isn't a Node project. To activate the hook:

```
npm install
```

Run this once after cloning; after that every `git commit` is checked automatically.

## Versioning & releases

Versioning is fully automatic via [semantic-release](https://semantic-release.gitbook.io/) — nobody should hand-edit `versionCode` / `versionName` in `app/build.gradle.kts`.

On every merge to `main`, semantic-release:
1. Analyzes conventional commit messages since the last release (`fix:` → patch, `feat:` → minor, `BREAKING CHANGE:` → major).
2. Computes the next semver version.
3. Runs `scripts/set-gradle-version.sh` to bump `versionCode`/`versionName` in `app/build.gradle.kts` — `versionCode` is derived deterministically as `MAJOR*10000 + MINOR*100 + PATCH`, which is guaranteed to increase monotonically as required by Play Store.
4. Regenerates [`CHANGELOG.md`](./CHANGELOG.md).
5. Commits the changes (`[skip ci]`) and publishes a GitHub Release + git tag.

## Building a release locally

```
./gradlew bundleRelease
```

This requires a `keystore.properties` file at the repo root (gitignored, never committed) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword` properties pointing at your release keystore.

## CI/CD pipeline

Four GitHub Actions workflows chain together:

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | PR opened against `main` | `./gradlew lint` + `./gradlew test` |
| `release.yml` | Push to `main` | Re-runs lint/test, then `npx semantic-release` (versions the app, tags, publishes the GitHub Release) |
| `deploy.yml` | `release.yml` completes successfully | Decodes the release keystore from a secret, builds a signed AAB (`./gradlew bundleRelease`), uploads it to the Play Store **internal testing** track via `bundle exec fastlane android beta` |
| `promote.yml` | Manual (`workflow_dispatch`) | Promotes the current internal-track build to **production** via `bundle exec fastlane android promote` |

Promotion to production is intentionally a manual, explicit action — it's never triggered automatically.

There's also a scheduled `reseed-dock-locations.yml` workflow (weekly) that refreshes `docklocations.json` from the live TfL API and opens a PR if anything changed.

## One-time setup required

The pipeline above is wired up but not yet live. Before it will work, a repo admin needs to:

1. **Tag a baseline release**, matching the current `versionCode = 2` / `versionName = "1.0"`:
   ```
   git tag v1.0.0
   git push origin v1.0.0
   ```
   `release.yml` deliberately fails loudly if no reachable `vX.Y.Z` tag exists, rather than letting semantic-release silently default to `1.0.0`.
2. **Add the following GitHub repo secrets** (Settings → Secrets and variables → Actions):
   - `ANDROID_KEYSTORE_BASE64`
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
   - `PLAY_STORE_SERVICE_ACCOUNT_JSON`
3. **Manually submit the first build to Play Console.** A brand-new app listing's very first APK/AAB must be uploaded by hand through the Play Console UI — the Play Developer API (and therefore fastlane) only accepts uploads to an app that already has an existing listing. The automated `deploy`/`promote` workflows are for shipping updates to that existing listing, not for the initial store submission.
