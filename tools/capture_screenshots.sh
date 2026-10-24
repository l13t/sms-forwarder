#!/usr/bin/env bash
# Boots a fresh, headless AVD. All demo records live in instrumentation tests only.
set -euo pipefail
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_dir" ]] || { echo 'Set ANDROID_HOME to your Android SDK.' >&2; exit 1; }
avd_name="sms-forwarder-docs"
# setup-android prepends its selected version; a preinstalled `latest` can be older.
avdmanager="$(command -v avdmanager || true)"
if [[ -z "$avdmanager" ]]; then avdmanager="$sdk_dir/cmdline-tools/latest/bin/avdmanager"; fi
[[ -x "$avdmanager" ]] || { echo 'avdmanager is unavailable. Install Android command-line tools.' >&2; exit 1; }
emulator="$sdk_dir/emulator/emulator"
adb="$sdk_dir/platform-tools/adb"
serial="emulator-5580"
export ANDROID_SERIAL="$serial"
if [[ -n "$("$adb" devices | awk 'NR>1 && NF {print $1}')" ]]; then
  echo "Disconnect other devices and emulators before capturing screenshots." >&2
  exit 1
fi
runtime_dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
work_dir="$(mktemp -d "$runtime_dir/sms-forwarder-docs.XXXXXX")"
export ANDROID_USER_HOME="$work_dir/.android"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
# Legacy SDK home overrides must not redirect one tool to another AVD registry.
unset ANDROID_SDK_HOME
mkdir -p "$ANDROID_AVD_HOME"
avd_log="$runtime_dir/sms-forwarder-avd.log"
emulator_log="$runtime_dir/sms-forwarder-emulator.log"
emulator_pid=""
cleanup() {
  local result_code=$?
  if [[ -n "$emulator_pid" ]]; then
    if kill -0 "$emulator_pid" 2>/dev/null; then
      "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
      kill "$emulator_pid" 2>/dev/null || true
    fi
    wait "$emulator_pid" 2>/dev/null || true
  fi
  rm -rf "$work_dir"
  return "$result_code"
}
trap cleanup EXIT
printf 'avdmanager: %s\nAVD directory: %s\n' "$avdmanager" "$ANDROID_AVD_HOME"
if ! printf 'no\n' | "$avdmanager" create avd --force --name "$avd_name" \
  --path "$ANDROID_AVD_HOME/$avd_name.avd" \
  --package 'system-images;android-35;google_apis;x86_64' --device pixel_6 > "$avd_log" 2>&1; then
  cat "$avd_log"
  exit 1
fi
cat "$avd_log"
if [[ ! -s "$ANDROID_AVD_HOME/$avd_name.ini" || ! -s "$ANDROID_AVD_HOME/$avd_name.avd/config.ini" ]]; then
  echo 'avdmanager did not create the expected AVD index and config files.' >&2
  "$avdmanager" list avd
  exit 1
fi
available_avds="$("$emulator" -list-avds)"
if ! grep -Fxq "$avd_name" <<< "$available_avds"; then
  printf 'Emulator cannot resolve the created AVD. Visible AVDs:\n%s\n' "$available_avds" >&2
  exit 1
fi
# Do the CPU/memory-heavy compilation before Android starts its first boot.
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
"$emulator" -avd "$avd_name" -port 5580 -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -gpu swiftshader > "$emulator_log" 2>&1 &
emulator_pid=$!
booted=false
for ((attempt=0;attempt<120;attempt++)); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then cat "$emulator_log"; exit 1; fi
  if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  sleep 2
done
[[ "$booted" == true ]] || { echo 'Emulator boot timed out.' >&2; cat "$emulator_log"; exit 1; }
# This AVD is disposable and tests launch the activity explicitly. Prevent the
# unused Pixel Launcher from starting again and putting an ANR over the app.
launcher='com.google.android.apps.nexuslauncher'
if "$adb" shell pm list packages "$launcher" | tr -d '\r' | grep -Fxq "package:$launcher"; then
  launcher_state="$("$adb" shell pm disable-user --user 0 "$launcher" | tr -d '\r')"
  [[ "$launcher_state" == *'new state: disabled-user'* ]] || {
    printf 'Could not disable the disposable emulator launcher: %s\n' "$launcher_state" >&2
    exit 1
  }
  "$adb" shell am force-stop --user 0 "$launcher"
fi
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
