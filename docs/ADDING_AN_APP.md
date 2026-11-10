# Adding an app

Each app lives in `apps/<name>/` and gets the application ID `dev.soloton.<name>`. The build settings are in convention plugins in `build-logic/`, so a module's build file is only a few lines. The example below adds a libGDX app called `climb`; an app without 3D skips `core` and `desktop`.

## Convention plugins

| Plugin ID | For | What it sets up |
|---|---|---|
| `soloton.android.app` | an APK module | `com.android.application`, minSdk/compileSdk/targetSdk from the catalog, Java/Kotlin 17, JUnit, lint, `versionCode`/`versionName` from env, release signing from env with debug fallback |
| `soloton.gdx.android` | a libGDX APK module | `soloton.android.app` plus `gdx-backend-android` and the libGDX `.so` files for `armeabi-v7a`, `arm64-v8a`, `x86_64` |
| `soloton.android.library` | an Android library (`libs/*`) | `com.android.library` with the same SDK, Java and test settings |
| `soloton.kotlin.jvm` | a plain Kotlin/JVM module | Kotlin JVM, Java 17, JUnit |
| `soloton.gdx.core` | platform-free game code | `soloton.kotlin.jvm` plus `api` on `gdx` |
| `soloton.gdx.desktop` | an LWJGL3 desktop launcher | `soloton.kotlin.jvm`, `application`, LWJGL3 backend and desktop natives; start script named `<app>-desktop` |

Versions come only from `gradle/libs.versions.toml`.

## Steps

1. Register the modules in `settings.gradle.kts`:

   ```kotlin
   include(":apps:climb:core")
   include(":apps:climb:android")
   include(":apps:climb:desktop")
   ```

2. `apps/climb/core/build.gradle.kts`:

   ```kotlin
   plugins { id("soloton.gdx.core") }
   ```

   Put the `ApplicationListener` in `src/main/kotlin/dev/soloton/climb/`. No `android.*` imports here. Tests go in `src/test/kotlin`.

3. `apps/climb/android/build.gradle.kts`:

   ```kotlin
   plugins { id("soloton.gdx.android") }

   android {
       namespace = "dev.soloton.climb.android"
       defaultConfig { applicationId = "dev.soloton.climb" }
   }

   dependencies {
       implementation(project(":apps:climb:core"))
       implementation(project(":libs:platform"))
   }
   ```

   Add `src/main/AndroidManifest.xml` (copy `apps/roadwork/android`'s: GLES 2.0 feature, `sensorLandscape`, fullscreen theme) and `res/values/strings.xml` with `app_name`. In the Activity, call `applyBikeScreen()` in `onCreate` and `reapplyImmersive()` when focus returns (from `dev.soloton.platform`). For a non-libGDX screen, extend `BikeActivity` instead.

4. `apps/climb/desktop/build.gradle.kts`:

   ```kotlin
   plugins { id("soloton.gdx.desktop") }

   dependencies { implementation(project(":apps:climb:core")) }

   application { mainClass.set("dev.soloton.climb.desktop.DesktopLauncherKt") }
   ```

   Support `--screenshot <png> --at <seconds>`; copy roadwork's `DesktopLauncher.kt` and `ScreenshotRunner.kt`.

5. Add `apps/climb/screenshots.sh` (copy roadwork's, change the shot list and install path) and commit its output under `apps/climb/docs/*.png`. The progress site picks them up.

6. Nothing to change in CI. `.github/workflows/ci.yml` builds every directory that has `apps/<name>/android/build.gradle.kts`, uploads `climb-<short sha>.apk` as a workflow artifact, and adds `climb.apk` to the `latest` prerelease on pushes to `main`. Tag `climb-v0.1.0` to publish a release (see [CI_SETUP.md](CI_SETUP.md)). The directory name must be lowercase letters, digits, `-` or `_`.

7. Check: `./gradlew :apps:climb:android:assembleDebug check` and `bash apps/climb/screenshots.sh "$PWD/build/shots"`.

## Rules

- No Google Play Services, Firebase, or network access unless the plan says otherwise.
- No machine paths, keystores, or `local.properties` in git.
- Change `settings.gradle.kts` and `libs.versions.toml` through the coordinator.
