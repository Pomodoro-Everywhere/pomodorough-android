#!/usr/bin/env bash

set -euo pipefail

results_dir="app/build/outputs/androidTest-results/connected"
rm -rf "$results_dir"

./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest

if ! grep -R '<testcase ' "$results_dir" >/dev/null 2>&1; then
  echo "Instrumentation completed without running tests" >&2
  exit 1
fi
