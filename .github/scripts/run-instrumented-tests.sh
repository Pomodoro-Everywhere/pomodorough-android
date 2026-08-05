#!/usr/bin/env bash

set -euo pipefail

results_dir="app/build/outputs/androidTest-results/connected"
diagnostics_dir="app/build/reports/androidTests/diagnostics"
font_scale="${FONT_SCALE:-1.0}"
original_font_scale="$(adb shell settings get system font_scale | tr -d '\r')"

restore_font_scale() {
  if [[ -z "$original_font_scale" || "$original_font_scale" == "null" ]]; then
    adb shell settings delete system font_scale >/dev/null
  else
    adb shell settings put system font_scale "$original_font_scale"
  fi
}

collect_failure_diagnostics() {
  mkdir -p "$diagnostics_dir"
  adb shell pm list instrumentation > "$diagnostics_dir/instrumentation.txt"
  adb shell dumpsys activity exit-info me.egigoka.pomodorough.test \
    > "$diagnostics_dir/test-process-exit-info.txt"
  adb shell dumpsys activity exit-info me.egigoka.pomodorough \
    > "$diagnostics_dir/app-process-exit-info.txt"
  adb shell dumpsys dropbox --print data_app_crash \
    > "$diagnostics_dir/data-app-crashes.txt"
  adb logcat -b all -d -v threadtime > "$diagnostics_dir/logcat-gradle.txt"

  adb logcat -c
  adb shell am instrument -w -r -e log true \
    me.egigoka.pomodorough.test/androidx.test.runner.AndroidJUnitRunner \
    > "$diagnostics_dir/runner-discovery.txt" 2>&1
  adb logcat -b all -d -v threadtime > "$diagnostics_dir/logcat-discovery.txt"
}

cleanup() {
  status=$?
  set +e
  if [[ $status -ne 0 ]]; then
    collect_failure_diagnostics
  fi
  restore_font_scale
  exit "$status"
}

trap cleanup EXIT
adb shell settings put system font_scale "$font_scale"
test "$(adb shell settings get system font_scale | tr -d '\r')" = "$font_scale"
rm -rf "$results_dir"
rm -rf "$diagnostics_dir"
adb logcat -c

./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest

if ! grep -R '<testcase ' "$results_dir" >/dev/null 2>&1; then
  echo "Instrumentation completed without running tests" >&2
  exit 1
fi
