#!/usr/bin/env bash

set -euo pipefail

expected_api=36
device_count=0
device_serial=""

while read -r serial state _; do
  if [[ "$state" == "device" ]]; then
    device_count=$((device_count + 1))
    device_serial="$serial"
  fi
done < <(adb devices -l | tail -n +2)

if [[ $device_count -ne 1 ]]; then
  echo "Expected exactly one connected Android device; found $device_count" >&2
  exit 1
fi

actual_api="$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$actual_api" != "$expected_api" ]]; then
  echo "Release gate requires API $expected_api; $device_serial reports API $actual_api" >&2
  exit 1
fi

echo "Running Android release gate on $device_serial (API $actual_api)"

python3 -m unittest scripts/test_check_localization.py -v
python3 scripts/check_localization.py

./gradlew --no-daemon --stacktrace \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  :app:assembleRelease \
  :app:bundleRelease

for font_scale in 1.0 2.0; do
  echo "Running API $actual_api instrumentation at ${font_scale}x font scale"
  FONT_SCALE="$font_scale" bash .github/scripts/run-instrumented-tests.sh
done

echo "Android local release gate passed at 1.0x and 2.0x font scale"
