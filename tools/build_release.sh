#!/usr/bin/env bash
# semantic-release prepare hook: finish and verify the signed APK before a tag is created.
set +x
set -euo pipefail
repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo_dir"
version="${1:-}"
if [[ ! "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo 'Expected a stable semantic version such as 2.0.0.' >&2
  exit 1
fi
if [[ ! "${GITHUB_RUN_NUMBER:-}" =~ ^[1-9][0-9]*$ ]]; then
  echo 'GITHUB_RUN_NUMBER must be a positive integer.' >&2
  exit 1
fi
version_code="$((1000 + GITHUB_RUN_NUMBER))"
if ((version_code <= 1000 || version_code > 2100000000)); then
  echo 'Android versionCode is outside its supported range.' >&2
  exit 1
fi
for name in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing Actions secret: $name. See docs/releases.md." >&2
    exit 1
  fi
done
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$sdk_dir" ]] || { echo 'ANDROID_HOME must point to an Android SDK.' >&2; exit 1; }
umask 077
keystore="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/sms-release-signing.XXXXXX")"
trap 'rm -f "$keystore"' EXIT
export ANDROID_KEYSTORE_PATH="$keystore"
python3 - <<'DECODE_KEY'
import base64, os
from pathlib import Path
value = ''.join(os.environ['ANDROID_KEYSTORE_BASE64'].split())
Path(os.environ['ANDROID_KEYSTORE_PATH']).write_bytes(base64.b64decode(value, validate=True))
DECODE_KEY
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease \
  "-PreleaseVersionName=$version" "-PreleaseVersionCode=$version_code"
apk=app/build/outputs/apk/release/app-release.apk
"$sdk_dir/build-tools/35.0.0/apksigner" verify --verbose "$apk"
mkdir -p dist
cp "$apk" "dist/sms-forwarder-$version.apk"
python3 - "$version" <<'CHECKSUM'
import hashlib, sys
from pathlib import Path
apk = Path('dist') / f'sms-forwarder-{sys.argv[1]}.apk'
Path('dist/SHA256SUMS').write_text(f'{hashlib.sha256(apk.read_bytes()).hexdigest()}  {apk.name}\n')
CHECKSUM
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'version=%s\n' "$version" >> "$GITHUB_OUTPUT"
fi
