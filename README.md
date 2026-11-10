# Soloton

Homemade apps for a first-generation Peloton bike tablet (Android 7.0), sideloaded with tools such as [OpenPelo](https://github.com/doudar/Openpelo). GitHub Actions builds an installable APK for each app.

Not affiliated with or endorsed by Peloton. Sideloading is at your own risk.

## Status

Scaffold in place. The Gradle monorepo builds an installable `roadwork` APK (API 24+) holding a placeholder spinning cube, and a desktop launcher renders screenshots. CI workflows, the real scene and the music library are next; see [docs/PLAN.md](docs/PLAN.md).

![Roadwork placeholder](apps/roadwork/docs/cube-at-2s.png)

## Apps

| App | What it is | Status |
|---|---|---|
| `roadwork` | First-person dawn run behind a coach on a bicycle, with shuffled music from a folder on the tablet | Placeholder scene builds |

## Using it

- **Install on a bike:** once builds exist, download an APK from the `latest` release and install it with `adb install -r`, or download and open it on the bike.
- **Build your own:** fork the repo; CI builds APKs with no setup. See [docs/CI_SETUP.md](docs/CI_SETUP.md) for signing so updates install over each other.
- **Build locally:** run `scripts/doctor.sh --fix` to check and install the JDK and Android SDK, then `./gradlew :apps:roadwork:android:assembleDebug`. See [docs/BUILDING.md](docs/BUILDING.md).
- **Music:** copy your own audio files to a folder on the tablet (default `/sdcard/Music/soloton`) and point the app at it in settings. No music is bundled or downloaded.

## Docs

- [docs/PLAN.md](docs/PLAN.md): architecture and work packages
- [docs/BUILDING.md](docs/BUILDING.md): prerequisites and build commands
- [docs/ADDING_AN_APP.md](docs/ADDING_AN_APP.md): adding an app to the monorepo
- [docs/CI_SETUP.md](docs/CI_SETUP.md): CI, signing, and releases

## License

[MIT](LICENSE)
