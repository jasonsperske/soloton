# Soloton

Homemade apps for a first-generation Peloton bike tablet (Android 7.0), sideloaded with tools such as [OpenPelo](https://github.com/doudar/Openpelo). GitHub Actions builds an installable APK for each app.

Not affiliated with or endorsed by Peloton. Sideloading is at your own risk.

![Roadwork: riding along the riverfront behind a jogger](docs/images/roadwork.png)

## Status

The first app, `roadwork`, is complete and runs on an emulator shaped like the gen 1 tablet (Android 7.0, 1920x1080). Every push builds an APK and publishes it to the [`latest` release](../../releases/tag/latest). Progress, screenshots and downloads are on the [progress site](https://jasonsperske.github.io/soloton/). It has not yet been measured on a real bike.

## Apps

| App | What it is | Status |
|---|---|---|
| `roadwork` | First-person bike ride through an [environment](docs/ENVIRONMENTS.md): a 3D scene paired with music you choose for it from the folders on the tablet, shuffled. It opens on a picker of environments. The first is a sunny city riverfront, pacing a jogger out on their roadwork | Working on the gen 1 emulator |

## Using it

- **Install on a bike:** download `roadwork.apk` from the [`latest` release](../../releases/tag/latest) and install it with `adb install -r`, or download and open it on the bike. See [docs/INSTALL.md](docs/INSTALL.md).
- **Build your own:** fork the repo; CI builds APKs with no setup. See [docs/CI_SETUP.md](docs/CI_SETUP.md) for signing so updates install over each other.
- **Build locally:** run `scripts/doctor.sh --fix` to check and install the JDK and Android SDK, then `./gradlew :apps:roadwork:android:assembleDebug`. See [docs/BUILDING.md](docs/BUILDING.md).
- **Music:** copy your own audio files to the tablet (default `/sdcard/Music/soloton`, one folder per game), then pick the folders and songs each environment plays in the app's music settings. No music is bundled or downloaded. See [docs/INSTALL.md](docs/INSTALL.md#copying-music-to-the-tablet).
- **Your music on the emulator:** name a local folder in a git-ignored `.env` and `scripts/gen1-emulator.sh` copies it over. See [docs/BUILDING.md](docs/BUILDING.md#your-own-music-env).

## Docs

- [docs/PLAN.md](docs/PLAN.md): architecture and work packages
- [docs/BUILDING.md](docs/BUILDING.md): prerequisites and build commands
- [docs/ADDING_AN_APP.md](docs/ADDING_AN_APP.md): adding an app to the monorepo
- [docs/CI_SETUP.md](docs/CI_SETUP.md): CI, signing, and releases
- [docs/INSTALL.md](docs/INSTALL.md): installing on the bike and copying music
- [docs/DEVICE.md](docs/DEVICE.md): the gen 1 tablet spec and its emulator

## License

[MIT](LICENSE)
