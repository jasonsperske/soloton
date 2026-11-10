# Building Soloton

Everything builds with the Gradle wrapper from a fresh clone. You need a JDK and the Android SDK; Gradle itself downloads on first run.

## Quick start

```bash
scripts/doctor.sh          # check everything; changes nothing
scripts/doctor.sh --fix    # install what is missing into ~/.local/share/soloton-toolchain
. ~/.local/share/soloton-toolchain/env.sh
./gradlew :apps:roadwork:android:assembleDebug
```

`scripts/doctor.sh` checks the tools below and what Gradle can see: required items fail, optional ones (emulator, screenshots, GitHub) only warn, and it exits non-zero while anything required is missing. Each problem comes with the command that fixes it.

With `--fix` it applies what it can without root:
- installs a Temurin JDK 17 (from Adoptium) and the Android command-line tools (from Google) into the private toolchain directory, checking both against their published checksums;
- installs the SDK packages with `sdkmanager`, accepting the Android SDK licences;
- writes `sdk.dir` to `local.properties` and the toolchain `env.sh`;
- creates the gen 1 tablet emulator (`--no-emulator` skips it and its ~1 GB download).

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
| Desktop window | `./gradlew :apps:roadwork:desktop:run` | a 1280x720 window |
| One desktop screenshot | `./gradlew :apps:roadwork:desktop:run --args="--screenshot /tmp/shot.png --at 30"` | a 1920x1080 PNG |
| All committed screenshots | `bash apps/roadwork/screenshots.sh /abs/output/dir` | PNGs in that directory |
| Install on a device | `adb install -r apps/roadwork/android/build/outputs/apk/debug/android-debug.apk` | |

The APK holds native libraries for `armeabi-v7a`, `arm64-v8a` and `x86_64` (emulators), runs on Android 7.0 (API 24) and newer, and has the application ID `dev.soloton.<app>`.

## Desktop screenshot mode

The desktop launcher accepts `--screenshot <png> --at <seconds> [--size WxH]`. It advances simulated time to that second in fixed 1/60 s steps, renders one frame offscreen at 1920x1080 (the bike's display), writes the PNG and exits; it exits non-zero on failure. The same build gives the same image.

`apps/<app>/screenshots.sh` wraps this for every shot the app commits under `apps/<app>/docs/`. It uses the current display when `DISPLAY` is set, and otherwise runs under `xvfb-run` with software Mesa. Set `SOLOTON_FORCE_XVFB=1` to use Xvfb even when a display exists.

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
libs/music/         music library (WP4)
apps/roadwork/
  core/             libGDX game code, no Android imports, unit tests
  android/          launcher Activity and manifest -> the APK
  desktop/          LWJGL3 launcher and screenshot mode
  docs/             committed screenshots
  screenshots.sh
```
