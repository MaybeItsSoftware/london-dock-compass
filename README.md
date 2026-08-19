# London Dock Compass

A native Kotlin + Jetpack Compose **Wear OS** app that points you to the Santander Cycles dock you
actually need, with live availability, right on your wrist. On-watch location and a compass heading
find the docks around you; the TfL BikePoint API supplies real-time bike, e-bike and space counts —
surfaced in the app, on a tile, and as a watch face complication.

## What it does

**Bikes, e-bikes or spaces — one tap apart.** A dock with nineteen bikes and no spaces is the best
dock on the street if you need a bike, and the worst one on it if you are trying to end a journey.
The mode chip at the top of the screen switches what "nearest" means: docks that can help come
first, docks that cannot sink to the back of the deck marked EMPTY or FULL, and docks that are
locked or uninstalled are dropped entirely.

**A destination that watches itself.** Pin the dock you are riding to and it leads the deck, with
its space count checked on every refresh. When it drops to the last couple your wrist buzzes; when
it fills, it buzzes differently and turns raspberry — early enough that diverting is still cheap.
Arriving to find a full dock is the defining frustration of London hire bikes, and it is the one
thing this app exists to prevent.

**Alerts you can feel.** You cannot read a watch at fifteen miles an hour in traffic. Two taps at
100m, a longer settle on arrival, an insistent triple if the destination fills up. Each fires once
per crossing, with hysteresis, so a fix wobbling on a threshold does not buzz your wrist off.

**Glanceable surfaces.** A tile lists the three nearest docks with live counts, one swipe from the
watch face. The complication puts bikes-or-spaces and distance on the face itself, as short text,
long text, a ranged arc, or an icon — because eleven bikes out of a rack of twelve reads very
differently from eleven out of sixty.

**Saved docks, the crown, and a screen reader.** Tap a card to save a dock or pin it as your
destination. Saved docks keep their place at the end of the deck with live counts wherever you are,
not just when you are standing next to one — which is how you check your home dock and pin it as
the destination *before* setting off. The rotary crown pages the deck. Every card carries a spoken
description with the direction given relative to the way you are facing — "140m ahead and to your
right, 19 bikes" — because an absolute bearing is no use to anyone who cannot see the arrow.

**Honest when it is guessing.** Counts come with the time TfL observed them, and the app says
CACHED, NO LIVE DATA or how many minutes old a figure is rather than presenting a stale number as a
live one. With no network it falls back to the bundled dock coordinates and shows availability as
unknown instead of as zero.

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
├── domain/              Pure Kotlin: geometry, ranking, ride modes, proximity bands, destination
│                        health. No Android imports, so it is all unit-testable on the JVM.
├── data/                TflBikePointApi (radius query), DockRepository (live → cache → bundled),
│                        SnapshotStore, RiderPreferences, RiderLocation
├── presentation/        MainActivity, CompassViewModel, CompassScreen, CompassSensor, Haptics, theme
├── complication/        NearestDockComplicationService
└── tile/                NearbyDocksTileService
```

### Where dock data comes from

One request does the work: `GET /BikePoint?lat=&lon=&radius=` returns every dock within the radius
*with* its live counts, its position, and the timestamp TfL last observed it. That single call
replaced a bundled coordinate lookup plus one status request per dock, and it means new docks appear
without shipping an app update.

Three tiers, in order: the live radius query; a short-lived snapshot cache shared by the app, the
tile and the complication (in memory, and on disk so cold-started surfaces render immediately); and
finally the bundled `app/src/main/res/raw/docklocations.json`, which is refreshed weekly from TfL by
a scheduled workflow (see below) and exists purely so the arrow still points somewhere sensible in a
tunnel or against a rate limit.

The BikePoint endpoints work unauthenticated but are rate limited per IP. Register at
[api-portal.tfl.gov.uk](https://api-portal.tfl.gov.uk/) and put the key in `tfl_app_key`
(`app/src/main/res/values/strings.xml`) for the higher quota.

### Tests

`./gradlew test` runs JVM unit tests over the `domain` package and the presentation-layer
formatters: haversine distance (cross-checked against a figure TfL returns for the same pair),
bearings, the angle wraparound that a naive lerp gets wrong at 359°, mode-dependent ranking,
proximity hysteresis, destination-alert escalation, and the spoken accessibility descriptions.

## Branding

The mark is a compass needle set in a bicycle wheel, drawn in raspberry
(`#D62246`). It is defined once, as geometry, in `scripts/generate_branding.py`;
everything else is generated from it:

```
python3 scripts/generate_branding.py       # needs librsvg + webp: brew install librsvg webp
```

That writes the vector sources into `branding/`, then renders the launcher
mipmaps (adaptive foreground, legacy square and round, every density), the Play
Store icon, `res/drawable/splash_icon.xml`, and the store feature graphic. It
also re-stamps the mark and wordmark onto the phone/tablet screenshots in
`fastlane/`, which are otherwise real device captures. Re-running is idempotent.

Edit the constants at the top of the script rather than the generated files —
`splash_icon.xml` in particular is overwritten wholesale.

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
