# Signed APK releases

The **Signed release APK** workflow builds an installable, signed release APK and a `SHA256SUMS` file. It runs release unit tests and lint, builds the APK, and verifies its signature before uploading artifacts or publishing a release.

Every push or merge to `master` automatically runs the **Android checks and APK** workflow. Once its debug checks pass, it calls the signed release workflow, which runs release checks, builds and signs the APK, verifies the signature, and publishes a GitHub Release. Pull requests and other branches run checks without publishing.

No manual version tags are required. The pipeline creates a tag pointing to the exact commit that passed the checks and attaches the APK and checksum. Releases use version `1.0.<CI run number>`, for example `1.0.42` with tag `v1.0.42`. Version codes are `1000 + CI run number`. Keep the CI workflow's run counter when making future changes so version codes continue increasing.

To start another build manually, use **Actions → Android checks and APK → Run workflow** and select `master`. It follows the same automatic release process without a version input.

The workflow needs signing secrets configured once. It fails with the missing secret names if signing is unavailable; it never publishes an unsigned APK or silently substitutes a debug key.

## One-time signing setup

Requirements: JDK 17 (`keytool`), OpenSSL, and GitHub CLI authenticated with access to manage the repository's Actions secrets.

From a trusted local terminal in this repository, run:

```sh
bash tools/setup_release_signing.sh l13t/sms-forwarder
```

The helper generates a password-protected signing key in `~/.config/sms-forwarder/` (or `$XDG_CONFIG_HOME/sms-forwarder`), then uploads four repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `sms-forwarder` for the generated key |
| `ANDROID_KEY_PASSWORD` | Private key password |

Keep a secure backup of **both** `release.keystore` and `release-password`. The helper reuses an existing local key and refuses to generate a replacement when the repository already has a key but its local backup is missing. You can set `SMS_FORWARDER_SIGNING_DIR` to use another private backup directory.

If you already have a signing key, configure those four secrets under **Settings → Secrets and variables → Actions** instead of generating another one. The keystore must contain the private key, not just a public certificate. Use that same key for all updates. Secrets are restored only into a temporary runner file, which is removed after the build; keystores and passwords are excluded from uploaded artifacts.

## Automatic publishing

After the one-time signing setup, commit and push changes to `master` as usual. GitHub Actions performs all release steps automatically. There is no `git tag` step and no need to create a release in the GitHub UI.

Download the resulting `sms-forwarder-1.0.<CI run number>.apk` from the repository's **Releases** page. The build artifact contains the same APK and checksum. A rerun of an already published run retains the same version and leaves its existing release assets unchanged. A new workflow run receives a new version.

Publication requires both the debug checks and the signed release checks to succeed. A failed build does not publish a release. CI runs for the same ref are serialized without canceling an active run; GitHub may replace a pending run with a newer push. If an older run is rerun after a newer release, it does not replace the newer release as **Latest**.

## Install and update

```sh
adb install -r sms-forwarder-1.0.42.apk
```

APK updates require the same package name and signing certificate and a version code at least as high as the installed version. An existing debug APK uses a different certificate and must be uninstalled before the first release APK can be installed. Uninstalling removes that installation's local settings and queued events, so finish pending deliveries and record your configuration first.

Verify a downloaded APK against its checksum from the directory containing both files:

```sh
sha256sum -c SHA256SUMS
```

On macOS, use `shasum -a 256 -c SHA256SUMS` instead.

## Local signed build

Set the following environment variables from your existing signing backup:

```sh
export ANDROID_KEYSTORE_PATH="$HOME/.config/sms-forwarder/release.keystore"
export ANDROID_KEY_ALIAS=sms-forwarder
read -r -s -p 'Keystore password: ' ANDROID_KEYSTORE_PASSWORD
printf '\n'
export ANDROID_KEYSTORE_PASSWORD
export ANDROID_KEY_PASSWORD="$ANDROID_KEYSTORE_PASSWORD"
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease \
  -PreleaseVersionName=1.0.0 -PreleaseVersionCode=1001
```

Run this example in Bash. If using an existing key with a different private-key password, set `ANDROID_KEY_PASSWORD` separately. Choose a version code higher than the version installed on your phones. The APK is written to `app/build/outputs/apk/release/app-release.apk`.

Release signing is configured only when all four environment variables are present. Debug builds continue to use the normal debug key. Creating an actual APK requires the Android SDK and downloadable Gradle/Maven dependencies; the release workflow must complete successfully before an APK is available.

References: [Reusable GitHub Actions workflows](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows), [Android app signing](https://developer.android.com/studio/publish/app-signing) and [GitHub CLI release creation](https://cli.github.com/manual/gh_release_create).
