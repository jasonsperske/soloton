# Installing on the bike

Soloton apps are plain APKs for Android 7.0 (API 24) and newer. You can install them from a computer with `adb`, or download them on the bike itself. Each app has the application ID `dev.soloton.<app>`, so installing a newer APK replaces the old one and keeps its settings.

## Where to get an APK

| Source | URL | Use it for |
|---|---|---|
| `latest` prerelease | `https://github.com/<owner>/soloton/releases/download/latest/<app>.apk` | The newest build of `main`. The URL never changes. |
| Tagged release | `https://github.com/<owner>/soloton/releases/tag/<app>-v<version>` | A fixed version, as `<app>-<version>.apk` |
| CI artifact | the run's page in the **Actions** tab, `<app>-<short sha>.apk` | Testing a branch or pull request (needs a GitHub login; debug build) |
| Your own build | `apps/<app>/android/build/outputs/apk/debug/android-debug.apk` | See [BUILDING.md](BUILDING.md) |

For the main repo, `<owner>` is `jasonsperske`; for example `https://github.com/jasonsperske/soloton/releases/download/latest/roadwork.apk`.

## Option 1: from a computer with adb

With the bike connected and listed by `adb devices`:

```bash
curl -LO https://github.com/jasonsperske/soloton/releases/download/latest/roadwork.apk
adb install -r roadwork.apk
```

`-r` replaces an installed copy and keeps its data. Then start the app from OpenPelo, or with `adb shell monkey -p dev.soloton.roadwork 1`.

## Option 2: on the bike

1. Allow installing APKs from outside an app store. On Android 7 this is one global switch: **Settings → Security → Unknown sources**. Open Settings through OpenPelo if the Peloton launcher hides it.
2. In the bike's browser, open `https://github.com/<owner>/soloton/releases/tag/latest` (or type the `.../releases/download/latest/<app>.apk` URL directly) and download the APK. Releases of a public repo download without a GitHub login.
3. Open the downloaded file, from the browser's download notification, OpenPelo, or a file manager (it is usually in `Download/`), and tap **Install**.

You can turn **Unknown sources** off again afterwards; installed apps keep working.

## Updating, and when an install fails

Install the new APK the same way; it replaces the old one.

| Error | Meaning | Fix |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (adb), or "App not installed" (on the bike) | The installed copy is signed with a different key, usually an earlier debug-signed build or someone else's fork | `adb uninstall dev.soloton.<app>` (or uninstall in Settings → Apps), then install again. This erases the app's settings. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The APK was built from an older commit than the installed one | Uninstall first, as above |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | The tablet's CPU type is not in the APK | Report it; see `docs/DEVICE.md` |

Release notes on GitHub say whether an APK was signed with the release key. See [CI_SETUP.md](CI_SETUP.md) for why the signing key matters.

## Copying music to the tablet

Apps with music play files you copy onto the tablet. They never use the network. Supported formats: mp3, ogg, opus, m4a, aac and wav. FLAC is scanned but Android 7.0 has no FLAC decoder, so FLAC files are skipped on the bike; convert them to ogg or mp3 first. Sub-folders are scanned too.

1. Copy a folder of music to the default location, `/sdcard/Music/soloton/`:

   ```bash
   adb shell mkdir -p /sdcard/Music/soloton
   adb push ~/Music/workout/. /sdcard/Music/soloton/
   ```

   The trailing `/.` copies the folder's contents. Without it, `adb push ~/Music/workout /sdcard/Music/soloton/` creates `/sdcard/Music/soloton/workout/`, which also works because sub-folders are scanned.

2. Start the app and allow access to storage when it asks. It asks on first launch and whenever you change the folder.
3. To use a different folder, tap the gear on the app's screen and pick the folder in the settings screen's folder browser, or type its path. The settings screen shows the current folder and how many tracks it found. The change takes effect without restarting.

If the folder is missing, empty or unreadable, the app shows the path with a button to open settings and keeps running without music.

To check what is on the tablet: `adb shell ls -R /sdcard/Music/soloton`.
