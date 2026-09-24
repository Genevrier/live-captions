#!/usr/bin/env bash
# Verify the actual signed binary, then derive the delivery name from its manifest.
set -euo pipefail
apk=app/build/outputs/apk/release/app-release.apk
build_tools="${ANDROID_HOME:?}/build-tools/35.0.0"
unzip -t "$apk" | tail -1
certificates=$("$build_tools/apksigner" verify --verbose --print-certs "$apk")
printf '%s\n' "$certificates"
actual=$(printf '%s\n' "$certificates" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
test "$actual" = "$(cat scripts/release-certificate.sha256)" || {
  echo '::error::APK signer does not match the persistent release identity.'
  exit 1
}
"$build_tools/zipalign" -c -P 16 4 "$apk"
python3 scripts/verify_apk.py "$apk"
badging=$("$build_tools/aapt" dump badging "$apk")
printf '%s\n' "$badging" | sed -n '1,12p'
package=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
version=$(printf '%s\n' "$badging" | sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p")
test "$package" = com.asr.live
[[ "$version" =~ ^[0-9]+(\.[0-9]+)*$ ]]
mkdir -p out
name="LiveTranslate-MagicV5-v${version}.apk"
cp "$apk" "out/$name"
(cd out && sha256sum "$name" > "$name.sha256")
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "artifact_name=LiveTranslate-MagicV5-v${version}" >> "$GITHUB_OUTPUT"
fi
