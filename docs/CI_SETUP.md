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

## 5. What CI does

| Trigger | Result |
|---|---|
| Push or pull request | Runs checks, builds every app, uploads each APK as a workflow artifact |
| Push to `main` | Also refreshes the `latest` prerelease with the newest APK of every app |
| Tag `<app>-v<version>`, for example `roadwork-v0.1.0` | Builds that app and attaches the APK to a GitHub Release |

To cut a release:

```bash
git tag roadwork-v0.1.0
git push origin roadwork-v0.1.0
```

## 6. Get the APK onto the bike

- From a computer: download the APK from the `latest` release, then `adb install -r roadwork.apk`.
- On the bike: open the `latest` release page in the browser, download the APK, and open it. 

## Checking it worked

1. After the first push, the **Actions** tab shows a green run with an APK artifact.
2. The build log does not contain the "debug signing" warning.
3. Installing a second build over the first succeeds without uninstalling.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy was signed with a different key, usually an earlier debug build. Uninstall it once, then install the signed build. |
| "Keystore was tampered with, or password was incorrect" | `SOLOTON_KEYSTORE_PASSWORD` is wrong, or the base64 value was line-wrapped. Re-run the `base64 -w0` command. |
| Release step fails with 403 | Workflow permissions are still read-only (step 4). |
