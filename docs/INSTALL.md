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

Apps with music play files you copy onto the tablet. They never use the network. Supported formats: mp3, ogg, opus, m4a, aac and wav. FLAC is scanned but Android 7.0 has no FLAC decoder, so FLAC files are skipped on the bike; convert them to ogg or mp3 first.

### Layout: one folder per game under the music root

Roadwork plays different music in each [environment](ENVIRONMENTS.md). Each environment starts with one folder, and you can then [choose folders and songs](#choosing-folders-and-songs-on-the-tablet) for it on the tablet. Music lives under one **music root**, by default `/sdcard/Music/soloton/`, one sub-folder per game:

```
/sdcard/Music/soloton/                  <- the music root (set in the app's music settings)
    Mike Tyson's Punch-Out!!/           <- one folder per game, named exactly as the environment expects
        01 - Demo Song One.mp3
        02 - Demo Song Two.mp3
        game.json                       <- other files are ignored
    Night Circuit/
        01 - Demo Tune One.mp3
```

| Environment | Folder it plays by default, under the music root |
|---|---|
| Riverfront Roadwork (`riverfront`) | `Mike Tyson's Punch-Out!!` |
| Skylift Mesa (`skylift-mesa`) | `Space Harrier II` |
| Pedalcraft Island (`pedalcraft-island`) | `Pilotwings` |
| Night Streets (`night-streets`) | `Sim City` |

Until you choose otherwise, an environment plays only its default folder; other folders under the root are left alone, so you can copy your whole library once. The default folder's name must match exactly, including capitals, the apostrophe and the `!!`. Files without tags show their file name as the title with a leading track number removed (`01 - Song.mp3`, `1. Song.mp3` and `01_Song.mp3` all show as "Song"), and the folder name as the album.

### Copying with adb

1. Copy each game's folder into the music root. `adb push` of a folder creates that folder on the tablet:

   ```bash
   adb shell mkdir -p /sdcard/Music/soloton
   adb push 'Mike Tyson'\''s Punch-Out!!' /sdcard/Music/soloton/
   ```

   Quoting matters for this name. Use single quotes so that an interactive shell does not treat `!!` as history expansion, and write the apostrophe as `'\''` (close the quote, an escaped apostrophe, reopen the quote). To copy a whole library of game folders at once: `adb push ~/Music/games/. /sdcard/Music/soloton/` (the trailing `/.` copies the folder's contents).

2. Start the app and allow access to storage when it asks. It asks on first launch.

If an environment has nothing to play (its folder is missing, empty or unreadable, or no songs are ticked), its card on the picker says so in amber, and during a ride the music panel shows the folder and what is wrong; the app keeps running without music. After copying the folder over, tap the skip button to try again, or open and close settings; no restart is needed.

### Choosing folders and songs on the tablet

Each environment has its own music. Tap **Music** on its card on the picker, or the gear during a ride, to open its settings ([screenshot](../apps/roadwork/docs/android-emulator-settings.png)):

- **Left: the folders that play in this environment**, each with how many of its songs are ticked. Tap a folder to see its songs. **Remove** takes a folder off the list (nothing is deleted from the tablet). **Reset to default** goes back to the environment's default folder with every song ticked.
- **Right: the selected folder's songs**, each with a tick box. Tap a song to tick or untick it; **All** and **None** do the whole folder. Only ticked songs play. Songs you copy into the folder later start ticked.
- **Add folder** opens a browser that starts at the music root and lists its sub-folders with their song counts ([screenshot](../apps/roadwork/docs/android-emulator-add-folder.png)). Tap a folder to go into it, then **Add this folder**. Any number of folders can be added, from anywhere on the tablet; adding one that is already on the list changes nothing. A folder's songs are the audio files directly in it: for a folder that only holds sub-folders, settings offers the sub-folders to add instead.
- **Music root** (bottom left, **Change**): the folder your game folders live in. Browse to it and tap **Use as the root**, or type its path. Folders inside the root are remembered relative to it, so after moving your library and changing the root your folders and ticks still apply.

Every change is saved as you make it, per environment, and survives restarts and app updates. A change made during a ride takes effect when you return to it, and a song that is still ticked keeps playing. A folder on the list that is no longer on the tablet is shown greyed as "missing" and skipped; copy it back and it plays again. If you untick everything the app tells you: "No songs selected for Riverfront Roadwork".

To check what is on the tablet:

```bash
adb shell ls -R /sdcard/Music/soloton
adb shell 'ls "/sdcard/Music/soloton/Mike Tyson'\''s Punch-Out!!"'
```

The second command runs through the tablet's shell as well, so the path is wrapped in double quotes inside the single-quoted command.
