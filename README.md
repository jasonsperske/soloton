# Soloton

Homemade apps for a first-generation Peloton bike tablet (Android 7.0), sideloaded with tools such as [OpenPelo](https://github.com/doudar/Openpelo). GitHub Actions builds an installable APK for each app.

Not affiliated with or endorsed by Peloton. Sideloading is at your own risk.

## Status

Planning. There is no buildable code yet; the scaffold, CI workflows, and first app are specified in [docs/PLAN.md](docs/PLAN.md) and are being built from it.

## Apps

| App | What it is | Status |
|---|---|---|
| `roadwork` | First-person dawn run behind a coach on a bicycle, with shuffled music from a folder on the tablet | Planned |

## Using it

- **Install on a bike:** once builds exist, download an APK from the `latest` release and install it with `adb install -r`, or download and open it on the bike.
- **Build your own:** fork the repo; CI builds APKs with no setup. See [docs/CI_SETUP.md](docs/CI_SETUP.md) for signing so updates install over each other.
- **Music:** copy your own audio files to a folder on the tablet (default `/sdcard/Music/soloton`) and point the app at it in settings. No music is bundled or downloaded.

## Docs

- [docs/PLAN.md](docs/PLAN.md): architecture and work packages
- [docs/CI_SETUP.md](docs/CI_SETUP.md): CI, signing, and releases

## License

[MIT](LICENSE)
