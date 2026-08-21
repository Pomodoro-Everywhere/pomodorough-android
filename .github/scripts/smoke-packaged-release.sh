#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

release_apks=(dist/*-release-unsigned.apk)
if (( ${#release_apks[@]} != 1 )); then
  echo "Expected exactly one unsigned release APK" >&2
  exit 1
fi

unsigned_apk="${release_apks[0]}"
smoke_apk="$RUNNER_TEMP/pomodorough-release-smoke.apk"
smoke_keystore="$RUNNER_TEMP/pomodorough-release-smoke.jks"
cp "$unsigned_apk" "$smoke_apk"
keytool -genkeypair -noprompt -keystore "$smoke_keystore" -storepass smoke-only \
  -keypass smoke-only -alias smoke -keyalg RSA -keysize 2048 -validity 1 \
  -dname 'CN=Ephemeral CI release smoke'
"$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS_VERSION/apksigner" sign \
  --ks "$smoke_keystore" --ks-pass pass:smoke-only \
  --key-pass pass:smoke-only "$smoke_apk"
adb uninstall me.egigoka.pomodorough >/dev/null 2>&1 || true
adb install "$smoke_apk"
launch_output="$(adb shell am start -W -n me.egigoka.pomodorough/.MainActivity)"
printf '%s\n' "$launch_output"
grep -Fq 'Status: ok' <<< "$launch_output"
