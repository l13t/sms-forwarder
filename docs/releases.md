# Signed APK releases

The **Signed release APK** workflow builds an installable, signed release APK and a `SHA256SUMS` file. It runs release unit tests and lint, builds the APK, and verifies its signature before uploading artifacts or publishing a release.

Every push or merge to `master` runs **Android checks and APK**. After debug checks pass, the release workflow uses **semantic-release** to analyze all commits since the last release tag. If a new release is needed, its prepare hook runs release tests and lint, builds and signs the APK, and verifies its signature. Only after preparation succeeds does semantic-release create the version tag and publish the APK, checksum, and generated release notes. Pull requests and other branches run checks without publishing.

## Conventional Commits

| Commit | Version change from `1.4.2` |
| --- | --- |
| `fix(webhook): preserve event IDs` | `1.4.3` (patch) |
| `perf: reduce database reads` | `1.4.3` (patch) |
| `feat: add notification filters` | `1.5.0` (minor) |
| `feat(ui)!: replace navigation` | `2.0.0` (major) |
| Any type with a `BREAKING CHANGE:` or `BREAKING-CHANGE:` footer | Major |
| Non-breaking `docs:`, `chore:`, `ci:`, `test:`, `style:`, `refactor:` | No release |

Both `!` after the type/scope and a breaking-change footer are supported by the Conventional Commits preset. The highest-impact change among all commits since the previous release wins. A documentation commit pushed after an unreleased feature still includes that feature in the next release.

Example major release:

```sh
git add -A
git commit -m "feat(ui)!: adopt Material 3 navigation"
git push origin master
```

If the latest release is `v1.x.y`, this creates `v2.0.0` after successful checks. With no existing release tag, the first release is `v1.0.0`. Existing reachable `vMAJOR.MINOR.PATCH` tags, including previous counter-based tags, are respected as the release baseline. No manual version edits or tags are required. For squash merges, make the PR title a Conventional Commit because the squash commit message is what reaches `master`.

The version name embedded in the APK is exactly the semantic release version. Android's numeric `versionCode` remains `1000 + CI run number`, independent of major/minor/patch, to keep device updates increasing across releases. Preserve the CI workflow run counter. Local debug builds use `0.0.0-dev` unless a version is supplied explicitly.

You can rerun analysis through **Actions → Android checks and APK → Run workflow** on `master`. When there are no release-worthy commits since the latest tag, it succeeds without building or publishing another release APK. Signing secrets are checked inside the prepare hook only when an APK release is actually needed.

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

Download the resulting `sms-forwarder-<semantic-version>.apk` from the repository's **Releases** page. The artifact contains the same APK and checksum. Release notes are generated from Conventional Commits. There are no generated version/changelog commits and no npm package is published. Automatic issue/PR comments and released labels are disabled.

Debug and release checks must both succeed before tagging or publication. CI runs for the same ref are serialized without canceling an active run; GitHub may replace a pending run with a newer push. The next successful analysis includes all unreleased commits. Failed preparation removes its temporary signing key and does not create a tag. A verified APK is retained as an artifact if a later publication step fails.

The release tools are installed at pinned versions in the workflow using Node.js 24. The config is in `.releaserc.json`; `tools/build_release.sh` is the prepare hook. Configuration tests run against the actual commit analyzer in CI:

```sh
node --test tools/release.test.mjs
python3 tools/test_build_release.py
```

The Node test requires the tooling dependencies installed by the workflow. The Python tests exercise the real prepare script with isolated mock Gradle and SDK executables; they do not create a production APK or require signing secrets.

## Install and update

```sh
adb install -r sms-forwarder-2.0.0.apk
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

References: [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/), [semantic-release](https://semantic-release.org/), [Reusable GitHub Actions workflows](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows), [Android app signing](https://developer.android.com/studio/publish/app-signing) and [GitHub CLI release creation](https://cli.github.com/manual/gh_release_create).
