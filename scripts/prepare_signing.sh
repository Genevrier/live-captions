#!/usr/bin/env bash
# GitHub Actions only. Never echo secret values or enable shell tracing.
set -euo pipefail
for name in LIVE_CAPTIONS_KEYSTORE_B64 LIVE_CAPTIONS_STORE_PASSWORD LIVE_CAPTIONS_KEY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    echo "::error::Missing repository secret $name. See docs/SIGNING.md for setup commands."
    exit 1
  fi
done
: "${RUNNER_TEMP:?}" "${GITHUB_ENV:?}"
umask 077
key="$RUNNER_TEMP/live-captions.jks"
trap 'rm -f "$key"' EXIT
printf '%s' "$LIVE_CAPTIONS_KEYSTORE_B64" | base64 --decode > "$key"
# Validate the alias/password and public identity before starting a costly build.
actual=$(keytool -exportcert -keystore "$key" -alias live-captions \
  -storepass:env LIVE_CAPTIONS_STORE_PASSWORD | sha256sum | cut -d ' ' -f 1)
expected=$(cat scripts/release-certificate.sha256)
if [[ "$actual" != "$expected" ]]; then
  echo '::error::Signing certificate changed. Restore the existing release keystore; see docs/SIGNING.md.'
  exit 1
fi
echo "LIVE_CAPTIONS_KEYSTORE=$key" >> "$GITHUB_ENV"
trap - EXIT
