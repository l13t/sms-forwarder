#!/usr/bin/env bash
# Create/reuse a private signing key and upload signing credentials to GitHub Actions.
set +x
set -euo pipefail
umask 077

for command in gh keytool openssl base64; do
  command -v "$command" >/dev/null || { echo "Required command not found: $command" >&2; exit 1; }
done
gh auth status >/dev/null
repo="${1:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"
remote_secrets="$(gh secret list --repo "$repo" --json name --jq '.[].name')"
signing_dir="${SMS_FORWARDER_SIGNING_DIR:-${XDG_CONFIG_HOME:-$HOME/.config}/sms-forwarder}"
keystore="$signing_dir/release.keystore"
password_file="$signing_dir/release-password"

if [[ -e "$keystore" || -e "$password_file" ]]; then
  if [[ ! -f "$keystore" || ! -f "$password_file" ]]; then
    echo "Incomplete signing backup in $signing_dir. Restore both files before continuing." >&2
    exit 1
  fi
elif [[ "$remote_secrets" == *ANDROID_KEYSTORE_BASE64* ]]; then
  echo "This repository already has a signing key. Restore its original local backup; refusing to rotate it." >&2
  exit 1
else
  mkdir -p "$signing_dir"
  chmod 700 "$signing_dir"
  openssl rand -base64 32 > "$password_file"
  export ANDROID_KEYSTORE_PASSWORD="$(cat "$password_file")"
  keytool -genkeypair -noprompt -keystore "$keystore" -storetype PKCS12 \
    -alias sms-forwarder -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=SMS Forwarder" -storepass:env ANDROID_KEYSTORE_PASSWORD \
    -keypass:env ANDROID_KEYSTORE_PASSWORD
fi

export ANDROID_KEYSTORE_PASSWORD="$(cat "$password_file")"
keytool -list -keystore "$keystore" -alias sms-forwarder \
  -storepass:env ANDROID_KEYSTORE_PASSWORD >/dev/null
base64 < "$keystore" | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$ANDROID_KEYSTORE_PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' 'sms-forwarder' | gh secret set ANDROID_KEY_ALIAS --repo "$repo"
printf '%s' "$ANDROID_KEYSTORE_PASSWORD" | gh secret set ANDROID_KEY_PASSWORD --repo "$repo"
unset ANDROID_KEYSTORE_PASSWORD
printf 'Signing secrets configured for %s.\nBack up BOTH files in %s and keep this key for every update.\n' "$repo" "$signing_dir"
