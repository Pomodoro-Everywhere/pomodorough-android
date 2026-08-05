#!/usr/bin/env bash

set -euo pipefail

results_dir="app/build/outputs/androidTest-results/connected"
font_scale="${FONT_SCALE:-1.0}"
original_font_scale="$(adb shell settings get system font_scale | tr -d '\r')"

restore_font_scale() {
  if [[ -z "$original_font_scale" || "$original_font_scale" == "null" ]]; then
    adb shell settings delete system font_scale >/dev/null
  else
    adb shell settings put system font_scale "$original_font_scale"
  fi
}

trap restore_font_scale EXIT
adb shell settings put system font_scale "$font_scale"
test "$(adb shell settings get system font_scale | tr -d '\r')" = "$font_scale"
rm -rf "$results_dir"

./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest

if ! grep -R '<testcase ' "$results_dir" >/dev/null 2>&1; then
  echo "Instrumentation completed without running tests" >&2
  exit 1
fi
