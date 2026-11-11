# Building Soloton

Everything builds with the Gradle wrapper from a fresh clone. You need a JDK and the Android SDK; Gradle itself downloads on first run.

## Quick start

```bash
scripts/doctor.sh          # check everything; changes nothing
scripts/doctor.sh --fix    # install what is missing into ~/.local/share/soloton-toolchain
. ~/.local/share/soloton-toolchain/env.sh   # this shell; or add --shell above to load it in every new shell
./gradlew :apps:roadwork:android:assembleDebug
```

`./gradlew` needs `JAVA_HOME` (or Java 17+ on `PATH`) in your shell. The other repo scripts (`scripts/gen1-emulator.sh`, `apps/*/screenshots.sh`) find the toolchain on their own through `scripts/lib/toolchain.sh`, so they work from any shell. `--fix --shell` adds one line to `~/.bashrc`, `~/.zshrc` or `~/.profile` that loads `env.sh`; it is opt-in because it sets `JAVA_HOME` for everything you run.

`scripts/doctor.sh` checks the tools below and what Gradle can see: required items fail, optional ones (emulator, screenshots, GitHub) only warn, and it exits non-zero while anything required is missing. Each problem comes with the command that fixes it.

With `--fix` it applies what it can without root:
- installs a Temurin JDK 17 (from Adoptium) and the Android command-line tools (from Google) into the private toolchain directory, checking both against their published checksums;
- installs the SDK packages with `sdkmanager`, accepting the Android SDK licences;
- writes `sdk.dir` to `local.properties` and the toolchain `env.sh`;
- creates the gen 1 tablet emulator (`--no-emulator` skips it and its ~1 GB download).

- creates `.env` from `.env.example` if you have none (see [Your own music](#your-own-music-env)).

It never uses `sudo`. KVM access, Xvfb and Mesa are listed with the command for you to run. Set `SOLOTON_TOOLCHAIN` to install somewhere else. An existing JDK or SDK (from `JAVA_HOME`, `ANDROID_HOME`, `local.properties` or `~/Android/Sdk`) is used as is. CI runs the doctor on every push.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 or newer (17 is what CI uses) | Set `JAVA_HOME`. Temurin works. |
| Android SDK | `platforms;android-36`, `build-tools;36.0.0` | Set `ANDROID_HOME` (or write `sdk.dir=...` in `local.properties`, which is git-ignored). Install with `sdkmanager "platforms;android-36" "build-tools;36.0.0"`. |
| `xvfb-run` + Mesa | any | Only for desktop screenshots on a machine without a display (`sudo apt-get install xvfb libgl1-mesa-dri`). |

No NDK, emulator, or Android Studio is needed. The first build downloads Gradle, the Android Gradle Plugin, Kotlin and libGDX (a few hundred MB) into `~/.gradle`.

Versions of every plugin and library are in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml).

## Commands

Run from the repo root.

| What | Command | Output |
|---|---|---|
| Debug APK | `./gradlew :apps:roadwork:android:assembleDebug` | `apps/roadwork/android/build/outputs/apk/debug/android-debug.apk` |
| Release APK | `./gradlew :apps:roadwork:android:assembleRelease` | `apps/roadwork/android/build/outputs/apk/release/android-release.apk` |
| Tests and lint, all modules | `./gradlew check` | reports under each module's `build/reports/` |
| Desktop window | `./gradlew :apps:roadwork:desktop:run` | a 1280x720 window on the environment picker (`--args="--env riverfront"` rides at once) |
| One desktop screenshot | `./gradlew :apps:roadwork:desktop:run --args="--screenshot /tmp/shot.png --at 30"` | a 1920x1080 PNG |
| Screenshot of the picker | `./gradlew :apps:roadwork:desktop:run --args="--picker --screenshot /tmp/picker.png"` | a 1920x1080 PNG |
| All committed screenshots | `bash apps/roadwork/screenshots.sh /abs/output/dir` | PNGs in that directory |
| Script tests | `scripts/test.sh` | the `.env` loader and music sync planning; CI runs it |
| Gen 1 emulator | `scripts/gen1-emulator.sh` | boots `emulator-5590` and copies your music to it (see below) |
| Install on a device | `adb install -r apps/roadwork/android/build/outputs/apk/debug/android-debug.apk` | |

The APK holds native libraries for `armeabi-v7a`, `arm64-v8a` and `x86_64` (emulators), runs on Android 7.0 (API 24) and newer, and has the application ID `dev.soloton.<app>`.

## Desktop screenshot mode

The desktop launcher accepts `--screenshot <png> --at <seconds> [--size WxH]`. It advances simulated time to that second in fixed 1/60 s steps, renders one frame offscreen at 1920x1080 (the bike's display), writes the PNG and exits; it exits non-zero on failure. The same build gives the same image. With `--picker` the frame is the environment picker instead of a ride.

`apps/<app>/screenshots.sh` wraps this for every shot the app commits under `apps/<app>/docs/`. It uses the current display when `DISPLAY` is set, and otherwise runs under `xvfb-run` with software Mesa. Set `SOLOTON_FORCE_XVFB=1` to use Xvfb even when a display exists. The script unsets `SOLOTON_MUSIC_DIR`, so the images always show the built-in demo track names and are the same on every machine.

## Your own music (`.env`)

To try the app with your own music, tell the repo where it is on this computer. The setting lives in `.env` at the repo root, which git ignores, so neither the path nor anything about your files is ever committed.

```bash
cp .env.example .env      # or: scripts/doctor.sh --fix
# then edit .env:
SOLOTON_MUSIC_DIR=/path/to/your/music      # a folder holding one sub-folder per game
```

| Variable | Use | Default |
|---|---|---|
| `SOLOTON_MUSIC_DIR` | Folder on this computer with one sub-folder per game | unset: no music is copied, and the desktop demo uses made-up names |
| `SOLOTON_MUSIC_DEVICE_DIR` | Where it goes on the emulator | `/sdcard/Music/soloton`, the app's default music root |

`.env` holds plain `KEY=VALUE` lines (quotes optional, `#` comments). It is parsed, never executed; only names starting `SOLOTON_` are read; a variable already set in your shell wins. `scripts/lib/dotenv.sh` is the loader and `scripts/test.sh` tests it.

What uses it:

- **`scripts/gen1-emulator.sh`** copies the folder to the emulator after boot: audio files only (mp3, ogg, opus, m4a, aac, wav, flac), sub-folders kept, files already there with the same size skipped. It prints one line, for example `Music sync: 388 copied, 0 already there`. A first copy of a 400 MB library takes a couple of seconds; a repeat is instant. Nothing on the emulator is deleted.

  | Flag | Effect |
  |---|---|
  | `--sync-music` | copy to an emulator that is already running, then exit |
  | `--no-music` | boot without copying |
  | `--window`, `--create`, `--stop` | as before; see [DEVICE.md](DEVICE.md) |

- **`./gradlew :apps:roadwork:desktop:run`** passes the folder to the desktop demo, so the picker and HUD show your real song names and counts (there is still no sound, and every song in an environment's default folder counts as ticked). Running the installed launcher directly reads only the environment: `SOLOTON_MUSIC_DIR=... roadwork-desktop`.
- **`scripts/doctor.sh`** reports whether `.env` exists, whether the folder is readable, and how many game folders and songs it holds (counts only).

## Release signing and versions

Every app's release build reads these environment variables (see [CI_SETUP.md](CI_SETUP.md)):

| Variable | Use | When unset |
|---|---|---|
| `SOLOTON_KEYSTORE_B64` | base64 of the release keystore, decoded to `build/signing/release.jks` | release is signed with the debug key and Gradle prints a warning |
| `SOLOTON_KEYSTORE_PASSWORD`, `SOLOTON_KEY_ALIAS`, `SOLOTON_KEY_PASSWORD` | keystore credentials | same |
| `SOLOTON_VERSION_CODE` | `versionCode` | `1` |
| `SOLOTON_VERSION_NAME` | `versionName` | `0.1.0-dev` |

To test signing locally:

```sh
keytool -genkeypair -keystore /tmp/test.jks -storepass testpass -keypass testpass \
  -alias soloton -keyalg RSA -keysize 2048 -validity 1000 -dname CN=Test
SOLOTON_KEYSTORE_B64=$(base64 -w0 /tmp/test.jks) SOLOTON_KEYSTORE_PASSWORD=testpass \
SOLOTON_KEY_ALIAS=soloton SOLOTON_KEY_PASSWORD=testpass \
  ./gradlew :apps:roadwork:android:assembleRelease
```

## Build settings

`gradle.properties` caps the Gradle daemon at 2 GB (`-Xmx2g`) and turns on parallel execution, the build cache and the configuration cache. If the configuration cache gets in your way, run with `--no-configuration-cache`.

## Layout

```
build-logic/        convention plugins (see ADDING_AN_APP.md)
libs/platform/      Android display helpers: landscape, immersive fullscreen, keep screen on
libs/api/           shared interfaces: MusicController, PlaylistRef, PlatformActions, PaceSource
libs/music/         music library: playback, per-playlist folder and song selection, settings screen
apps/roadwork/
  core/             libGDX game code, no Android imports, unit tests
  android/          launcher Activity and manifest -> the APK
  desktop/          LWJGL3 launcher and screenshot mode
  docs/             committed screenshots
  screenshots.sh
scripts/            doctor.sh, gen1-emulator.sh, test.sh, lib/ (toolchain, .env loader, music sync)
.env.example        template for the git-ignored .env (your own music folder)
```
