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
printf '%s\n' "$badging" | grep -E "^(package:|application-label:'LiveTranslate'|application-icon-(160|240|320|480|640):|application:|launchable-activity:)"
grep -F "application-label:'LiveTranslate'" <<< "$badging" >/dev/null
resources=$("$build_tools/aapt" dump resources "$apk")
grep -F 'mipmap/ic_launcher_round' <<< "$resources" >/dev/null
launcher=$(grep '^launchable-activity:' <<< "$badging")
[[ "$launcher" == *"name='com.asr.live.MainActivity'"* ]]
[[ "$launcher" == *"label='LiveTranslate'"* ]]
[[ "$launcher" == *"icon='res/"* ]]
for density in 160 240 320 480 640; do
  grep -E "^application-icon-${density}:'res/[^']+'$" <<< "$badging" >/dev/null
done
package=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
version=$(printf '%s\n' "$badging" | sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p")
test "$package" = com.asr.live
[[ "$version" =~ ^[0-9]+(\.[0-9]+)*$ ]]
mkdir -p out
name="LiveTranslate-MagicV5.apk"
cp "$apk" "out/$name"
(cd out && sha256sum "$name" > "$name.sha256")
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "artifact_name=LiveTranslate-MagicV5" >> "$GITHUB_OUTPUT"
fi
