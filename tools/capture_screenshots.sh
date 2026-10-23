#!/usr/bin/env bash
# Boots a fresh, headless AVD. All demo records live in instrumentation tests only.
set -euo pipefail
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_dir" ]] || { echo 'Set ANDROID_HOME to your Android SDK.' >&2; exit 1; }
avd_name="sms-forwarder-docs"
avdmanager="$sdk_dir/cmdline-tools/latest/bin/avdmanager"
# setup-android may install the tools into a versioned directory.
if [[ ! -x "$avdmanager" ]]; then avdmanager="$(command -v avdmanager)"; fi
adb="$sdk_dir/platform-tools/adb"
serial="emulator-5580"
export ANDROID_SERIAL="$serial"
if [[ -n "$("$adb" devices | awk 'NR>1 && NF {print $1}')" ]]; then
  echo "Disconnect other devices and emulators before capturing screenshots." >&2
  exit 1
fi
printf 'no\n' | "$avdmanager" create avd --force --name "$avd_name" --package 'system-images;android-35;google_apis;x86_64' --device pixel_6
"$sdk_dir/emulator/emulator" -avd "$avd_name" -port 5580 -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -gpu swiftshader > /tmp/sms-forwarder-emulator.log 2>&1 &
emulator_pid=$!
trap '"$adb" -s "$serial" emu kill >/dev/null 2>&1 || true; wait "$emulator_pid" 2>/dev/null || true' EXIT
booted=false
for ((attempt=0;attempt<120;attempt++)); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then cat /tmp/sms-forwarder-emulator.log; exit 1; fi
  if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  sleep 2
done
[[ "$booted" == true ]] || { echo 'Emulator boot timed out.' >&2; exit 1; }
"$adb" shell settings put global window_animation_scale 0
"$adb" shell settings put global transition_animation_scale 0
"$adb" shell settings put global animator_duration_scale 0
"$adb" shell settings put system font_scale 1.0
"$adb" shell input keyevent 82
for theme in light dark; do
  if [[ "$theme" == light ]]; then "$adb" shell cmd uimode night no; else "$adb" shell cmd uimode night yes; fi
  ./gradlew --no-daemon connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=ua.smsforwarder.ReadmeScreenshotsTest \
    -Pandroid.testInstrumentationRunnerArguments.captureScreenshots=true \
    "-Pandroid.testInstrumentationRunnerArguments.screenshotTheme=$theme"
done
mkdir -p artifacts/screenshots
"$adb" pull /data/local/tmp/sms-forwarder-screenshots/. artifacts/screenshots/
python3 - <<'CHECK_IMAGES'
from pathlib import Path
names = ('status', 'webhooks', 'rules', 'history', 'settings', 'about', 'rule-editor')
for language in ('en', 'uk'):
    for theme in ('light', 'dark'):
        for name in names:
            path = Path('artifacts/screenshots') / f'{language}-{theme}' / f'{name}.png'
            if not path.is_file() or path.read_bytes()[:8] != b'\x89PNG\r\n\x1a\n':
                raise SystemExit(f'Missing or invalid screenshot: {path}')
print('All 28 emulator screenshots are present.')
CHECK_IMAGES
