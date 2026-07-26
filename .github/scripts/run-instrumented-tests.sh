#!/usr/bin/env bash

set -euo pipefail

adb wait-for-device
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1"

results_dir="app/build/outputs/androidTest-results/connected"
rm -rf "$results_dir"

set +e
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest
first_status=$?
set -e
if grep -R '<testcase ' "$results_dir" >/dev/null 2>&1; then
  exit "$first_status"
fi

echo "Instrumentation process produced no tests; retrying once" >&2
rm -rf "$results_dir"
adb wait-for-device
set +e
./gradlew --no-daemon --stacktrace --rerun-tasks :app:connectedDebugAndroidTest
second_status=$?
set -e
if ! grep -R '<testcase ' "$results_dir" >/dev/null 2>&1; then
  echo "Instrumentation retry also produced no tests" >&2
  exit 1
fi
exit "$second_status"
