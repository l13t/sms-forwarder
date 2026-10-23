# README screenshots

Screenshots are captured from the real Android app on a headless Android 15 (API 35) Pixel 6 emulator. No physical phone is needed. They use synthetic demo records, with forwarding disabled and scheduled work canceled. No real SMS, phone numbers, credentials, or webhook requests are involved.

## GitHub Actions

The **README screenshots** workflow runs after UI and screenshot-test changes on `master`, on matching pull requests, or manually through **Actions → README screenshots → Run workflow**.

It captures Status, Webhooks, Rules, History, Settings, About, and the rule editor in English and Ukrainian, in both light and dark modes. The `readme-screenshots` artifact contains the PNG files. After a successful run on `master`, a separate publish job commits the images to the `screenshots` branch. It does not modify `master`, trigger a release, or need signing credentials. Pull-request runs only upload artifacts and cannot publish images.

README image URLs reference that branch. Images become available after the first successful screenshot workflow run. `docs/screenshots/SOURCE.txt` on the image branch records the source commit used to render them. The images document an example configuration, not live delivery activity.

The workflow pins command-line tools 22.0 (build 15859902), and the capture script uses the `avdmanager` selected on PATH by SDK setup. It avoids an older preinstalled `cmdline-tools/latest`. Creation and boot logs are retained in the diagnostics artifact when a run fails.

The SDK setup installs only the specified platform, build tools, emulator, and API 35 Google APIs system image. It does not run the global SDK license sweep.

The capture test streams PNG bytes directly into shell-owned storage on the emulator, then verifies each file's size before the script pulls it. Export failures include command stderr in the test report. This avoids copying through app external storage and does not depend on shell operators being interpreted by `UiAutomation`.

## Local capture

On a Linux x86_64 computer with JDK 17, Android SDK, working emulator acceleration, and these SDK packages installed:

```sh
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0' \
  emulator 'system-images;android-35;google_apis;x86_64'
bash tools/capture_screenshots.sh
```

The script creates a fresh `sms-forwarder-docs` AVD, boots it headlessly on port 5580, runs the screenshot instrumentation test, pulls PNG files into `artifacts/screenshots`, and stops the emulator. Each run uses a private temporary AVD registry shared by `avdmanager` and the emulator; existing user AVDs are left untouched. The AVD index and emulator visibility are checked before boot, and the temporary registry is removed on exit. The script refuses to start while any other device or emulator is connected, so the capture test cannot replace data on another installation. On an Apple Silicon Mac, use GitHub Actions for this x86_64 workflow or adapt the system image to the host architecture.

The instrumentation test requires the explicit `captureScreenshots=true` runner argument and emulator hardware. It clears that emulator installation's local app data to create the documented demo scenario. Ordinary instrumentation runs skip the capture test. Demo seeding code lives only under `src/androidTest` and is not included in release APKs.

For a Compose UI check without generating documentation images, keep using the standard test tasks. APK compilation and emulator execution are still required to verify actual screen layout; the offline Kotlin syntax checker does not render UI.
