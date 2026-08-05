#!/usr/bin/env bash

set -euo pipefail

app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
font_scale="${FONT_SCALE:-1.0}"
scale_slug="${font_scale//./_}"
results_dir="app/build/outputs/androidTest-results/direct-font-$scale_slug"
diagnostics_dir="app/build/reports/androidTests/diagnostics-font-$scale_slug"
runner_output="$results_dir/instrumentation-output.txt"
original_font_scale="$(adb shell settings get system font_scale | tr -d '\r')"

wait_for_android_idle() {
  local deadline=$((SECONDS + 600))
  local previous_anr_count=-1
  local stable_checks=0

  adb wait-for-device
  adb shell settings put global device_provisioned 1
  adb shell settings put secure user_setup_complete 1
  adb logcat -b events -c

  while ((SECONDS < deadline)); do
    if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == "1" ]] \
      && adb shell pm path android >/dev/null \
      && adb shell 'mkdir -p /sdcard/Android && touch /sdcard/Android/.pomodorough-ci-ready && rm /sdcard/Android/.pomodorough-ci-ready'; then
      anr_count="$(adb logcat -b events -d -v brief | grep -c 'am_anr' || true)"
      if [[ "$anr_count" == "$previous_anr_count" ]]; then
        stable_checks=$((stable_checks + 1))
      else
        stable_checks=0
      fi
      previous_anr_count="$anr_count"

      if [[ $stable_checks -ge 4 ]]; then
        echo "Android remained ready without new ANRs for 80 seconds"
        return 0
      fi
    else
      previous_anr_count=-1
      stable_checks=0
    fi
    sleep 20
  done

  echo "Android did not reach a stable ready state within 10 minutes" >&2
  return 1
}

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
  adb logcat -b all -d -v threadtime > "$diagnostics_dir/logcat.txt"
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
wait_for_android_idle
adb shell settings put system font_scale "$font_scale"
test "$(adb shell settings get system font_scale | tr -d '\r')" = "$font_scale"
rm -rf "$results_dir"
rm -rf "$diagnostics_dir"
mkdir -p "$results_dir"
adb logcat -c

adb uninstall me.egigoka.pomodorough.test >/dev/null 2>&1 || true
adb uninstall me.egigoka.pomodorough >/dev/null 2>&1 || true
adb install -r "$app_apk"
adb install -r "$test_apk"
adb shell pm list instrumentation | grep -F \
  'instrumentation:me.egigoka.pomodorough.test/androidx.test.runner.AndroidJUnitRunner'

set +e
adb shell am instrument -w -r \
  me.egigoka.pomodorough.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$runner_output"
runner_status=${PIPESTATUS[0]}
set -e

announced_tests="$(grep -m 1 '^INSTRUMENTATION_STATUS: numtests=' "$runner_output" \
  | cut -d= -f2 | tr -d '\r')"
completed_tests="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$runner_output" || true)"

if [[ ! "$announced_tests" =~ ^[1-9][0-9]*$ ]]; then
  echo "Instrumentation completed without running tests" >&2
  exit 1
fi

if [[ "$completed_tests" -ne "$announced_tests" ]]; then
  echo "Instrumentation completed $completed_tests of $announced_tests tests" >&2
  exit 1
fi

if [[ $runner_status -ne 0 ]] \
  || grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[12]$|^FAILURES!!!$' "$runner_output" \
  || ! grep -q '^INSTRUMENTATION_CODE: -1$' "$runner_output"; then
  echo "Instrumentation runner failed" >&2
  exit 1
fi

echo "Instrumentation passed $completed_tests/$announced_tests tests"
