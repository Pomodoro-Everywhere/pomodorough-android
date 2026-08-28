#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

results_dir="release-smoke-results"
runner_temp="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
android_home="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
android_build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
rm -rf "$results_dir"
mkdir -p "$results_dir"

retain_release_smoke_diagnostics() {
  trap - EXIT
  if [[ -n "${instrumentation_output:-}" && -f "$instrumentation_output" ]]; then
    cp "$instrumentation_output" "$results_dir/instrumentation.txt"
  fi
  if command -v adb >/dev/null 2>&1; then
    adb shell getprop > "$results_dir/device-properties.txt" 2>&1 || true
    adb logcat -d > "$results_dir/logcat.txt" 2>&1 || true
  fi
}
trap retain_release_smoke_diagnostics EXIT

release_apks=(dist/*-release-unsigned.apk)
release_test_apks=(release-test/*-release-androidTest.apk)
if (( ${#release_apks[@]} != 1 )); then
  echo "Expected exactly one unsigned release APK" >&2
  exit 1
fi
if (( ${#release_test_apks[@]} != 1 )); then
  echo "Expected exactly one release Android-test APK" >&2
  exit 1
fi

unsigned_apk="${release_apks[0]}"
unsigned_test_apk="${release_test_apks[0]}"
smoke_apk="$runner_temp/pomodorough-release-smoke.apk"
smoke_test_apk="$runner_temp/pomodorough-release-smoke-androidTest.apk"
smoke_keystore="$runner_temp/pomodorough-release-smoke.jks"
instrumentation_output="$runner_temp/release-iroh-instrumentation.txt"
native_entries="$runner_temp/release-native-entries.txt"
test_native_entries="$runner_temp/release-test-native-entries.txt"
installed_apk="$runner_temp/pomodorough-installed-base.apk"
(cd dist && sha256sum -c SHA256SUMS.txt)
cp "$unsigned_apk" "$smoke_apk"
cp "$unsigned_test_apk" "$smoke_test_apk"
unzip -Z1 "$unsigned_apk" > "$native_entries"
unzip -Z1 "$unsigned_test_apk" > "$test_native_entries"

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  grep -Fx "lib/$abi/libiroh_ffi.so" "$native_entries"
done
runtime_abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
grep -Fx "lib/$runtime_abi/libiroh_ffi.so" "$native_entries"
if grep -F 'libiroh_ffi.so' "$test_native_entries"; then
  echo "Release Iroh probe APK must not package the Iroh native library" >&2
  exit 1
fi

rm -f "$smoke_keystore"
keytool -genkeypair -noprompt -keystore "$smoke_keystore" -storepass smoke-only \
  -keypass smoke-only -alias smoke -keyalg RSA -keysize 2048 -validity 1 \
  -dname 'CN=Ephemeral CI release smoke'
for apk in "$smoke_apk" "$smoke_test_apk"; do
  "$android_home/build-tools/$android_build_tools_version/apksigner" sign \
    --ks "$smoke_keystore" --ks-pass pass:smoke-only \
    --key-pass pass:smoke-only "$apk"
  "$android_home/build-tools/$android_build_tools_version/apksigner" verify "$apk"
done
app_signer="$("$android_home/build-tools/$android_build_tools_version/apksigner" verify --print-certs "$smoke_apk" \
  | grep -F 'Signer #1 certificate SHA-256 digest:' | cut -d: -f2- | xargs)"
test_signer="$("$android_home/build-tools/$android_build_tools_version/apksigner" verify --print-certs "$smoke_test_apk" \
  | grep -F 'Signer #1 certificate SHA-256 digest:' | cut -d: -f2- | xargs)"
test -n "$app_signer"
test "$app_signer" = "$test_signer"

adb uninstall me.egigoka.pomodorough.test >/dev/null 2>&1 || true
adb uninstall me.egigoka.pomodorough >/dev/null 2>&1 || true
adb install "$smoke_apk"
adb install "$smoke_test_apk"
installed_path="$(adb shell pm path me.egigoka.pomodorough | sed -n 's/^package://p' | tr -d '\r')"
test -n "$installed_path"
adb pull "$installed_path" "$installed_apk"
cmp "$smoke_apk" "$installed_apk"
launch_output="$(adb shell am start -W -n me.egigoka.pomodorough/.MainActivity)"
printf '%s\n' "$launch_output"
grep -Fq 'Status: ok' <<< "$launch_output"

set +e
adb shell am instrument -w -r \
  me.egigoka.pomodorough.test/me.egigoka.pomodorough.releaseiroh.ReleaseIrohSmokeInstrumentation \
  | tee "$instrumentation_output"
runner_status=${PIPESTATUS[0]}
set -e

test "$runner_status" -eq 0
grep -Fq 'INSTRUMENTATION_STATUS: numtests=1' "$instrumentation_output"
grep -Fq 'INSTRUMENTATION_STATUS_CODE: 0' "$instrumentation_output"
grep -Fq 'INSTRUMENTATION_CODE: -1' "$instrumentation_output"
