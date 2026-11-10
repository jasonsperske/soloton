# CI setup

GitHub Actions builds an APK for every app in this repo. This page covers the one-time setup you do by hand: creating the GitHub repo, creating a signing key, and storing it as repository secrets.

**No Google Play account or Play credentials are needed.** The apps are sideloaded, so nothing is uploaded to Play. The only secret is a signing key that you generate yourself on your own machine.

## Why a signing key matters

Android only installs an update over an existing app if both APKs are signed with the same key. Without a fixed key, every CI build is signed with a throwaway debug key and you would have to uninstall the app (losing its settings) before each update. With the secrets below in place, every build installs straight over the last one.

Until the secrets exist, CI still builds; it falls back to debug signing and prints a warning.

## Building from a fork

Fork the repo and enable Actions on the fork. CI builds with no setup at all: without the secrets below it signs with a debug key and prints a warning. That is enough to try an app. Follow steps 2 to 4 on your fork if you want builds that install over each other. Use your own keystore; a build signed with your key cannot update an app installed from someone else's build, and the reverse.

## 1. The GitHub repo

The main repo is public at `github.com/jasonsperske/soloton`, so release downloads work from the bike's browser without logging in. For your own copy, fork it or run:

```bash
gh repo create soloton --public --source=. --remote=origin --push
```

## 2. Generate the keystore

Run this once per repo owner, outside the repo, and choose a password when prompted. Requires a JDK (`keytool` ships with it).

```bash
mkdir -p ~/.soloton && cd ~/.soloton
keytool -genkeypair -v \
  -keystore soloton-release.jks \
  -alias soloton \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Soloton"
```

Use the same password for the keystore and the key when asked.

Back up `soloton-release.jks` and its password somewhere safe, such as a password manager. If you lose them you cannot update installed apps; you would have to uninstall and reinstall. Never commit the keystore.

## 3. Add the repository secrets

| Secret | Value |
|---|---|
| `SOLOTON_KEYSTORE_B64` | The keystore file, base64 encoded |
| `SOLOTON_KEYSTORE_PASSWORD` | The keystore password |
| `SOLOTON_KEY_ALIAS` | `soloton` |
| `SOLOTON_KEY_PASSWORD` | The key password (same as the keystore password if you followed step 2) |

From the repo directory:

```bash
base64 -w0 ~/.soloton/soloton-release.jks | gh secret set SOLOTON_KEYSTORE_B64
gh secret set SOLOTON_KEY_ALIAS --body soloton
gh secret set SOLOTON_KEYSTORE_PASSWORD   # prompts for the value
gh secret set SOLOTON_KEY_PASSWORD        # prompts for the value
gh secret list
```

Or in the browser: repo **Settings → Secrets and variables → Actions → New repository secret**.

## 4. Allow the workflow to publish releases

Repo **Settings → Actions → General → Workflow permissions → Read and write permissions**. The release workflow uses the built-in `GITHUB_TOKEN`; no personal access token is needed.

Only the two jobs that publish (`publish-latest` in `ci.yml`, `publish` in `release.yml`) ask for `contents: write`; every other job, including the ones that run Gradle, gets a read-only token. If an organization policy caps workflow tokens at read-only, those two jobs fail with 403 until this setting is changed.

## 5. What CI does

Two workflows in `.github/workflows/`:

| Trigger | Workflow | Result |
|---|---|---|
| Push to any branch, or pull request | `ci.yml` | Runs `./gradlew check`, builds a debug APK of every app, uploads each as a workflow artifact named `<app>-<short sha>.apk` |
| Push to `main` | `ci.yml` | Also builds release APKs of every app and refreshes the `latest` prerelease (below) |
| Tag `<app>-v<semver>`, for example `roadwork-v0.1.0` | `release.yml` | Builds that app's release APK and attaches it as `<app>-<semver>.apk` to a GitHub Release named after the tag |

Apps are found automatically: every `apps/<app>/android/build.gradle.kts` is an app. Adding one needs no workflow change.

To cut a release:

```bash
git tag roadwork-v0.1.0
git push origin roadwork-v0.1.0
```

The tag must be `<app>-v<semver>`: the app name as its directory under `apps/`, then `-v`, then a semantic version such as `0.1.0`, `1.2.3-beta.1` or `1.0.0+build.5`. A tag with a pre-release part (`-beta.1`) becomes a GitHub pre-release. A tag naming an app that does not exist, or a version that is not semver, fails the run with an error saying why. The version becomes the APK's `versionName`.

### The `latest` prerelease

Every push to `main` that passes checks moves the `latest` tag to that commit and replaces the assets of the `latest` prerelease. Its assets are exactly one `<app>.apk` per app, with no version in the name, so this URL always serves the newest build:

```
https://github.com/<owner>/soloton/releases/download/latest/roadwork.apk
```

The release notes give the commit SHA, the commit date and whether the APKs were signed with the release key. Because it is a prerelease, GitHub's own "Latest release" link does not point to it; use `.../releases/tag/latest`. A run on `main` is never cancelled once started; if several pushes land while it runs, only the newest waits for it (GitHub drops the older waiting runs), so `latest` always ends at the newest commit. Re-running an old workflow does not move it back.

### Version codes

Android refuses to install an APK whose `versionCode` is lower than the installed one, so the code must only go up. Both workflows set `SOLOTON_VERSION_CODE` to the number of commits in the built commit's history (`git rev-list --count HEAD`, which is why they check out with `fetch-depth: 0`). This was chosen over `github.run_number`, which counts separately per workflow, so a tag release and a `main` build would collide or go backwards, and which restarts on a fork.

Consequences:

- Every commit on `main` has a higher code than the one before, in both workflows, and a tag release and the `latest` build of the same commit get the same code.
- A release tagged on an older commit has a lower code than a newer `latest` build. To go back to it, uninstall first (`adb uninstall dev.soloton.<app>`).
- Rewriting `main`'s history (force-pushing fewer commits) lowers the count. Don't.

`versionName` is the semver from the tag for releases and `dev-<commit count>-<short sha>` for CI and `latest` builds.

## 6. Get the APK onto the bike

- From a computer: download the APK from the `latest` release, then `adb install -r roadwork.apk`.
- On the bike: open the `latest` release page in the browser, download the APK, and open it.

[INSTALL.md](INSTALL.md) has the details, including copying music to the tablet.

## Checking it worked

1. After the first push, the **Actions** tab shows a green **CI** run with an `<app>-<short sha>.apk` artifact per app.
2. After a push to `main`, the **Releases** page has a `latest` prerelease with `<app>.apk` assets, and its notes say "Signed with the repository release key".
3. The "Release APKs" job has no "Debug signing" warning annotation.
4. Installing a second build over the first succeeds without uninstalling.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy was signed with a different key, usually an earlier debug build. Uninstall it once, then install the signed build. |
| "Keystore was tampered with, or password was incorrect" | `SOLOTON_KEYSTORE_PASSWORD` is wrong, or the base64 value was line-wrapped. Re-run the `base64 -w0` command. |
| Release step fails with 403 | Workflow permissions are still read-only (step 4). |
| Release step fails moving the `latest` tag | A tag protection rule or ruleset covers `latest`. Exempt it, or allow GitHub Actions to update it. |
| Tag pushed but no release, run failed in "Parse tag" | The tag is not `<app>-v<semver>` or names an app with no `apps/<app>/android`. Delete the tag (`git push origin :refs/tags/<tag>`) and push a correct one. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The APK is from an older commit than the installed one (see Version codes). Uninstall first. |
