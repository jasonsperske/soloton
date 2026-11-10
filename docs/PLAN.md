# Soloton plan

Soloton is a home for sideloaded apps for a gen 1 Peloton bike tablet (Android 7.0, API 24, launched through OpenPelo). GitHub Actions builds an installable APK per app.

This file is the brief for implementing agents. Each work package (WP) lists what it owns, what it depends on, and how to prove it is done. Do not edit files owned by another WP; raise interface changes with the coordinator.

## Fixed decisions

| Topic | Decision | Why |
|---|---|---|
| Language / build | Kotlin, Gradle (Kotlin DSL), version catalog, Gradle wrapper committed | Standard, CI friendly |
| Android levels | `minSdk 24`, `compileSdk`/`targetSdk` current stable | Device is API 24; sideloading has no target requirement |
| Google services | None. No Play Services, Firebase, or Play-only APIs | The tablet has none |
| 3D | libGDX, with game code in a platform-free `core` module, an Android launcher, and a desktop (LWJGL3) launcher | The desktop launcher lets agents and CI run and screenshot the scene without the bike |
| Graphics budget | OpenGL ES 2.0 only, low-poly flat-shaded procedural geometry, no post-processing, 1920x1080 landscape, steady 30 fps minimum on device | Old tablet GPU; no art assets to license |
| ABIs | `armeabi-v7a` and `arm64-v8a` in one APK | Device ABI is unconfirmed until WP0 |
| Display | Landscape only, immersive fullscreen, `FLAG_KEEP_SCREEN_ON`, large touch targets (min 96 px), no hardware keys assumed | Bike tablet use |
| Signing | One release keystore shared by all apps, supplied to CI through secrets | A stable signature lets a new APK install over the old one |

## Known constraints and gotchas

- **No network use.** Apps in this repo work offline. If a later app needs HTTPS, note that API 24 does not trust the ISRG Root X1 (Let's Encrypt) root, so it must be bundled through `network_security_config`.
- **The repo is public** (`github.com/jasonsperske/soloton`). Anyone must be able to clone, build, and fork it: no secrets, keystores, personal paths, device serials, or machine-specific config in the repo; the build works with only a JDK and the Android SDK; CI passes on forks with no secrets set. Agents commit locally when the coordinator says so and do not push.
- **Device facts are unverified.** Screen size, ABI, GL version, and free storage come from WP0, not from memory.
- **Originality.** The first app is inspired by the idea of a boxer doing dawn roadwork behind a coach on a bicycle. It must be an original scene: do not reproduce the characters, names, outfits, palette, skyline composition, sprites, or music of Punch-Out!! or any other game.

## Progress site and coordinator files

A static progress site is published to GitHub Pages from `site/` by `.github/workflows/pages.yml`, assembled by `scripts/build-site.sh`. It shows `site/status.json` (work package status and log), screenshots, CI runs, commits and APK downloads. The coordinator owns `site/`, `scripts/build-site.sh` and `pages.yml`; agents do not edit them.

Screenshot contract for every app:
- Committed screenshots go in `apps/<app>/docs/*.png` with descriptive kebab-case names (for example `scene-at-30s.png`). They appear on the site automatically.
- An app with a desktop launcher provides `apps/<app>/screenshots.sh <absolute output dir>`, run from the repo root. It builds and runs the desktop screenshot mode under `xvfb-run` (software Mesa is fine) and writes PNGs into the output dir. It must work on a stock `ubuntu-latest` runner after the steps in `pages.yml`, finish in under ten minutes, and exit non-zero on failure.

## Local toolchain

This machine has no system JDK or Android SDK. A private one is installed at `~/.local/share/soloton-toolchain`. Run `source ~/.local/share/soloton-toolchain/env.sh` before Gradle; it sets `JAVA_HOME` (Temurin 17), `ANDROID_HOME`, and `PATH`. It has `platforms;android-35`, `platforms;android-36`, `build-tools;35.0.0` and `build-tools;36.0.0` (AGP 9.3 needs 36); install other SDK packages with `sdkmanager` into the same SDK. Never commit `local.properties` or machine paths. `xvfb-run` and `adb` are available; there is no emulator or system image yet (WP5 installs them).

## Repository layout

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew
build-logic/            convention plugins: soloton.android.app, soloton.android.library, soloton.gdx.android,
                        soloton.gdx.core, soloton.gdx.desktop, soloton.kotlin.jvm
libs/
  platform/             Android helpers: immersive mode, keep-awake, base Activity
  music/                music player library (pure Kotlin API + Android impl)
  bike/                 bike sensor access (WP6, optional)
apps/
  roadwork/
    core/               libGDX game code, no Android imports
    android/            launcher Activity, manifest, icons -> the APK
    desktop/            LWJGL3 launcher + screenshot mode
docs/                   PLAN.md, DEVICE.md, INSTALL.md, ADDING_AN_APP.md
.github/workflows/      ci.yml, release.yml
```

Adding an app later means adding `apps/<name>/` and one line in the CI app matrix. Application IDs are `dev.soloton.<name>`.

## Shared interfaces (`libs/api`)

`libs/api` is a pure Kotlin module owned by the coordinator. It holds `MusicController`, `MusicState`, `Track`, `SilentMusicController`, `PaceSource`, `PlatformActions` and `NoPlatformActions`. WP3 consumes them from `core`; WP4 implements `MusicController` on Android; WP5 wires them; WP6 implements `PaceSource`. These replace the `StateFlow`-based sketch in WP4 below: the render loop polls `state` each frame. Propose changes in your report instead of editing it.

## Work packages

### WP0 Device survey (needs the bike on adb; can run any time)
Record in `docs/DEVICE.md`: `ro.build.version.sdk`, `ro.product.cpu.abilist`, display size and density, GL renderer and version (`dumpsys SurfaceFlinger`), free storage, whether `/sdcard/Music` is readable, and installed Peloton packages (`pm list packages | grep -i peloton`). If the bike is unreachable, say so and leave the file as a checklist. Read-only: install nothing, change no settings.

### WP1 Monorepo scaffold (first; everything depends on it)
Owns root Gradle files, `build-logic/`, `libs/platform/`, `.gitignore`, `README.md`, `docs/BUILDING.md`, `docs/ADDING_AN_APP.md`. `docs/BUILDING.md` gives a newcomer the exact prerequisites (JDK version, Android SDK packages) and commands to build and run each app from a fresh clone; keep the README status section current. Creates `apps/roadwork` with the three modules and a spinning-cube placeholder.
Done when: `./gradlew :apps:roadwork:android:assembleDebug` produces an APK, `./gradlew :apps:roadwork:desktop:run` opens a window, `./gradlew check` passes.

### WP2 CI and releases (after WP1)
Owns `.github/workflows/`, `docs/CI_SETUP.md`, and `docs/INSTALL.md`.
- `ci.yml`: on push and pull request, run `check`, build every app in the matrix, upload each APK as a workflow artifact named `<app>-<short sha>.apk`.
- `release.yml`: on tag `<app>-v<semver>`, build the signed release APK and attach it to a GitHub Release. On every push to `main`, refresh a rolling `latest` prerelease holding the newest APK of every app, so the bike has one stable download URL.
- Signing reads `SOLOTON_KEYSTORE_B64`, `SOLOTON_KEYSTORE_PASSWORD`, `SOLOTON_KEY_ALIAS`, `SOLOTON_KEY_PASSWORD`. When the secrets are absent (forks, first run) the build falls back to debug signing and prints a warning rather than failing. `versionCode` comes from the run number.
- `docs/CI_SETUP.md` already exists and is the contract: secret names, tag format, and the `latest` release must match it. Update it if anything changes.
- `docs/INSTALL.md`: how to install on the bike (`adb install -r`, or download on the bike and open from OpenPelo) and how to copy music over.
- Fork friendly: no step may hard-code the owner or repo name (use `${{ github.repository }}`), and pull requests from forks must build without secrets. Pin third-party actions to a commit SHA and give each job the minimum `permissions`.
Done when: workflows pass `actionlint`, the same Gradle commands the workflow runs succeed locally, and the first pushed run is green.

### WP3 Roadwork scene (after WP1; parallel with WP4)
Owns `apps/roadwork/core` and `apps/roadwork/desktop`.
First-person view moving along a waterfront path at dawn. Ahead, a coach rides a bicycle, pedalling, occasionally glancing back or waving you on. Contents:
- Endless path built from recycled chunks: path, railing, lamp posts, benches, trees, distant original city silhouette, water, gradient sky that brightens slowly over a session.
- Coach and bicycle built from primitives with procedural animation (pedal cycle, wheel spin, body sway). Original design.
- Camera bob tied to pace. Pace comes from a `PaceSource` interface (`metersPerSecond: Float`); the default implementation is a steady jog with on-screen slower/faster buttons. WP6 may supply a cadence-driven one.
- HUD: elapsed time, distance, now-playing line, skip button, settings gear. The HUD talks to music only through the `MusicController` interface from WP4; until that lands, use a fake.
- Deterministic seed and a `--screenshot <path> --at <seconds>` desktop mode for visual checks.
Done when: desktop screenshots at 0 s, 30 s and 300 s are committed under `apps/roadwork/docs/` and look right, the frame-time log shows no per-frame allocation in the render loop, and unit tests cover chunk recycling and pace integration.

### WP4 Music library (after WP1; parallel with WP3)
Owns `libs/music`.
Music is files the user copies onto the tablet. There is no network source, so the app needs no `INTERNET` permission.

- API: `MusicController` with `start()`, `skip()`, `pause()`, `resume()`, `nowPlaying: StateFlow<Track?>`, and `setFolder(path)`.
- Playback: Media3 ExoPlayer (confirm the chosen version still supports API 24). Formats: mp3, ogg, flac, m4a, wav.
- Library: scan the configured folder recursively, off the main thread. Shuffle without repeats until every track has played, then reshuffle. Track title and album come from tags, falling back to file and folder names.
- Folder setting: default `/sdcard/Music/soloton`, stored in `SharedPreferences`. A settings screen (gear button on the HUD) shows the current path and track count, and offers an in-app folder browser plus a text field for typing a path. Use the in-app browser, not the system folder picker, because the tablet may not ship the system documents UI. Requires the `READ_EXTERNAL_STORAGE` runtime permission; request it on first launch and when the folder changes.
- Failure handling: an unreadable file auto-skips, capped at five consecutive failures. A missing, empty, or unreadable folder shows a plain message with the path and a button to open settings; the scene keeps running silently.
- Audio focus and pause on Activity stop are handled.

Done when: unit tests cover the scan, shuffle-without-repeat, and the failure cap against a temp folder; an instrumented test on an API 24 emulator plays a bundled test tone from a configured folder and survives the folder being changed and being emptied.

### WP5 Integration and device pass (after WP3 and WP4)
Owns `apps/roadwork/android`. Wires the scene, music, immersive mode, icon, and app label. Runs on an API 24 emulator in CI (boot, launch, screenshot, no crash for 60 s). If the bike is on adb: install, record frame rate, check touch targets, confirm audio, and note results in `docs/DEVICE.md`.
Done when: the CI-built APK installs and runs on API 24, the skip button changes tracks, and changing the music folder in settings takes effect without restarting.

### WP6 Bike cadence as pace (optional, after WP0)
Research spike first, code second. Find out whether the gen 1 tablet exposes cadence, resistance, or power to third-party apps, using open-source projects such as Grupetto as references and the package list from WP0. Deliver a one-page finding in `docs/DEVICE.md`. Only if there is a supported route, implement `libs/bike` with a `PaceSource` that maps cadence to speed and falls back to the default pace when the service is missing.

## Order of work

```
WP1 ──┬── WP2
      ├── WP3 ──┐
      └── WP4 ──┴── WP5
WP0 (any time) ──── WP6 (optional)
```

Wave 1: WP1 alone. Wave 2: WP2, WP3, WP4 in parallel, each in its own worktree. Wave 3: WP5. WP0 and WP6 whenever the bike is reachable.

## Rules for every agent

- Stay inside the paths your WP owns. Shared files (`libs.versions.toml`, `settings.gradle.kts`) change only through WP1 or the coordinator.
- Verify library versions and minimum API levels against current documentation instead of memory.
- Report what was run and what the output was. A step that could not be run (no device, no network) is reported as not run.
- No pushing, no repo creation, no secrets in the repo, nothing installed on the bike outside WP5 and WP0's read-only survey.

## Open decisions for the user

1. **App name.** `roadwork` is a working name.
2. **Signing secrets.** Steps are in `docs/CI_SETUP.md`. No Google Play account or Play credentials are involved.
