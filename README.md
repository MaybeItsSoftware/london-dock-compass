# London Dock Compass

Native Kotlin + Jetpack Compose apps that point you to the Santander Cycles dock you actually need,
with live availability. On **Wear OS** that is a compass needle on your wrist, a tile and a watch
face complication; on **Android** it is a list of what is around you before you leave the house.
The TfL BikePoint API supplies real-time bike, e-bike and space counts to both.

One Play listing serves both: same `applicationId`, two bundles, and Play hands each device the one
that fits.

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

**The phone knows what the watch knows.** Save a dock or pin a destination on either device and it
turns up on the other over the Wear Data Layer — so you can pin where you are riding *to* on the
phone, at home, before you have touched a bike. Ride mode deliberately stays local: it answers
"what am I doing in the next ten minutes", which belongs to the device in your hand.

## Getting started

**Requirements:** Android Studio (current stable), JDK 17.

1. Open the repo root in Android Studio — it's a standard Gradle project (no nested `android/`
   directory), so it should sync immediately.
2. Run the watch app on a Wear OS emulator (Device Manager, e.g. a "Wear OS Large Round" API 36
   image) or a physical watch with Developer Options/ADB debugging on; run the phone app on any
   API 26+ device or emulator.
3. Or from the command line:
   ```
   ./gradlew :app:installDebug       # the watch
   ./gradlew :mobile:installDebug    # the phone
   ```

Installing directly skips the Play Store entirely, which is the fastest way to check a change —
closed testing is for validating *distribution*, not for the edit-run loop.

Build config: `compileSdk = 36` and `targetSdk = 36` throughout; `minSdk = 30` for the watch
(Wear OS 3) and `26` for the phone. Both share the `applicationId`
`uk.co.maybeitssoftware.londondockcompass`.

## Project structure

Three Gradle modules. `:app` is the Wear app — it keeps the bare name because the release pipeline
addresses it by path.

```
core/    domain/    Pure Kotlin: geometry, ranking, ride modes, proximity bands, destination
                    health. No Android imports, so it is all unit-testable on the JVM.
         data/      TflBikePointApi (radius query), DockRepository (live → cache → bundled),
                    CachePolicy, SnapshotStore, RiderPreferences, RiderLocation, RiderSync
         theme/     Brand: every colour literal in the project, and the count-to-colour rule

app/     presentation/  MainActivity, CompassViewModel, CompassScreen, CompassSensor, Haptics
         complication/  NearestDockComplicationService
         tile/          NearbyDocksTileService

mobile/  mobile/    MainActivity, DockListViewModel, DockListScreen, Material 3 theme
```

`:core` carries no UI toolkit at all — the watch draws with Wear Compose and protolayout, the phone
with Material 3, and neither opinion belongs underneath both. Every ranking decision, freshness rule
and fallback is shared verbatim; the two apps differ only in how they render the answer.

### Where dock data comes from

One request does the work: `GET /BikePoint?lat=&lon=&radius=` returns every dock within the radius
*with* its live counts, its position, and the timestamp TfL last observed it. That single call
replaced a bundled coordinate lookup plus one status request per dock, and it means new docks appear
without shipping an app update.

Three tiers, in order: the live radius query; a short-lived snapshot cache shared by the app, the
tile and the complication (in memory, and on disk so cold-started surfaces render immediately); and
finally the bundled `core/src/main/res/raw/docklocations.json`, which is refreshed weekly from TfL by
a scheduled workflow (see below) and exists purely so the arrow still points somewhere sensible in a
tunnel or against a rate limit.

The BikePoint endpoints work unauthenticated but are rate limited per IP. Register at
[api-portal.tfl.gov.uk](https://api-portal.tfl.gov.uk/) and put the key in `tfl_app_key`
(`core/src/main/res/values/strings.xml`) for the higher quota.

### Tests

`./gradlew test` runs 65 JVM unit tests across `:core` and `:app`.

Over `domain`: haversine distance (cross-checked against a figure TfL returns for the same pair),
bearings, the angle wraparound that a naive lerp gets wrong at 359°, mode-dependent ranking,
proximity hysteresis, destination-alert escalation, and the spoken accessibility descriptions.

Over `data` — the layer with an external contract it does not control, and so the one that fails
silently: `CachePolicy`'s live/cached/bundled thresholds, a BikePoint response parsed through the
real serializer configuration (prefixed ids, counts nested in `additionalProperties`, missing counts
decoding as *unknown* rather than as zero), and the stored-snapshot round trip.

`./gradlew lint` is enforced rather than counted — `warningsAsErrors`, minus the dependency-currency
checks, which start failing the day an unrelated library publishes a release.

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

Versioning is fully automatic via [semantic-release](https://semantic-release.gitbook.io/) — nobody should hand-edit `versionCode` / `versionName` in either app module.

On every merge to `main`, semantic-release:
1. Analyzes conventional commit messages since the last release (`fix:` → patch, `feat:` → minor, `BREAKING CHANGE:` → major). Note that `ci:`, `chore:`, `docs:`, `build:` and friends deliberately cut **no** release.
2. Computes the next semver version.
3. Regenerates [`CHANGELOG.md`](./CHANGELOG.md).
4. Runs `scripts/set-gradle-version.sh`, which bumps `versionCode`/`versionName` in **both** `app/build.gradle.kts` and `mobile/build.gradle.kts`, and writes the Play release notes.
5. Commits the changes (`[skip ci]`) and publishes a GitHub Release + git tag.

Step 3 runs before step 4 on purpose: the script reads the newest `CHANGELOG.md` section to produce the release notes, so reversing them would quietly ship the *previous* release's notes. The plugin order in [`.releaserc.json`](./.releaserc.json) is what enforces it.

`versionCode` is derived deterministically as `(MAJOR*10000 + MINOR*100 + PATCH) * 10`, plus a per-form-factor slot — `0` for the phone, `1` for the watch. One `applicationId` ships two bundles and Play demands a unique code for each; the watch takes the higher slot so a device matching both is handed the watch build. Because semantic-release only bumps upward, both codes increase monotonically as Play requires.

Play shows testers whatever sits in `fastlane/metadata/android/en-GB/changelogs/<versionCode>.txt`, one file per code, so the same notes are written twice. They are trimmed to Play's 500-character limit at a line boundary. If no `CHANGELOG.md` section matches the version being released, the script fails the release rather than shipping empty notes.

## Building a release locally

```
./gradlew bundleRelease
```

This requires a `keystore.properties` file at the repo root (gitignored, never committed) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword` properties pointing at your release keystore.

## CI/CD pipeline

Five GitHub Actions workflows chain together:

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | PR opened against `main` | `./gradlew lint` + `./gradlew test` + a minified `assembleRelease` |
| `release.yml` | Push to `main` | Re-runs lint/test, then `npx semantic-release` (versions both modules, tags, publishes the GitHub Release) |
| `deploy.yml` | `release.yml` completes successfully | Decodes the release keystore from a secret, builds **both** signed AABs, and uploads each to its internal track — watch to `wear:internal`, phone to `internal` — via `bundle exec fastlane android beta` |
| `promote.yml` | Manual (`workflow_dispatch`) | Promotes already-uploaded builds off the internal tracks to a chosen stage and form factor via `bundle exec fastlane android promote` |
| `play-tracks.yml` | Manual (`workflow_dispatch`) | Read-only. Asks Play which tracks exist and what sits on them, via `bundle exec fastlane android list_tracks` |

Promotion is intentionally manual — it's never triggered automatically. It takes a **stage** (`production`, `closed`, `open`), not a raw Play track id, because the two form factors do not share ids: closed testing is `wear:Alpha` on the watch and `alpha` on the phone, despite both reading "Closed testing - Alpha" in the Console. `TRACKS` in [`fastlane/Fastfile`](./fastlane/Fastfile) maps stage + form to the real id; run `play-tracks.yml` rather than guessing if Play ever disagrees.

Two things worth knowing before promoting:

- **Pass `version`** (e.g. `v1.3.0`) to pin exactly which release moves. Left blank, supply promotes whatever happens to be sitting on the internal tracks at that moment. The workflow always runs `main`'s Fastfile regardless, so a pipeline fix applies to promoting older releases too.
- **A track that has never had a live rollout only accepts `status: draft`.** `completed` is rejected there. Finish that first rollout by hand in Play Console — a release left in Draft is invisible to testers however healthy the bundle is.

Installing the phone app does **not** put the watch app on a paired watch; Play has no such delivery. The watch install is initiated separately, from the Wear OS section of the phone's Play listing or from the Play Store on the watch. Closed-track builds are generally not discoverable by search on the watch, so use the phone listing.

There's also a scheduled `reseed-dock-locations.yml` workflow (weekly) that refreshes `docklocations.json` from the live TfL API and opens a PR if anything changed.

## One-time setup

The pipeline is live — this section is what a fresh clone or a new app would need, not outstanding work.

1. **A reachable baseline tag.** `release.yml` deliberately fails loudly if no reachable `vX.Y.Z` tag exists, rather than letting semantic-release silently default to `1.0.0` and ship a wrong version:
   ```
   git tag v1.0.0
   git push origin v1.0.0
   ```
2. **The following GitHub repo secrets** (Settings → Secrets and variables → Actions):
   - `ANDROID_KEYSTORE_BASE64`
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
   - `PLAY_STORE_SERVICE_ACCOUNT_JSON`
3. **Manually submit the first build to Play Console.** A brand-new app listing's very first APK/AAB must be uploaded by hand through the Play Console UI — the Play Developer API (and therefore fastlane) only accepts uploads to an app that already has an existing listing. The automated `deploy`/`promote` workflows are for shipping updates to that existing listing, not for the initial store submission.
