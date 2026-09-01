#!/usr/bin/env bash

set -euo pipefail

app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
package_name="me.egigoka.pomodorough"
font_scale="${FONT_SCALE:-1.0}"
scale_slug="${font_scale//./_}"
test_locale="${TEST_LOCALE:-en-US}"
test_class="${TEST_CLASS:-}"
instrumentation_timeout_seconds="${INSTRUMENTATION_TIMEOUT_SECONDS:-600}"
locale_slug="${test_locale//-/_}"
results_dir="app/build/outputs/androidTest-results/direct-font-$scale_slug-locale-$locale_slug"
diagnostics_dir="app/build/reports/androidTests/diagnostics-font-$scale_slug-locale-$locale_slug"
screenshots_dir="app/build/reports/androidTests/screenshots-font-$scale_slug-locale-$locale_slug"
runner_output="$results_dir/instrumentation-output.txt"
readiness_adapter="$(dirname "${BASH_SOURCE[0]}")/android-readiness.py"
original_font_scale=""
font_scale_captured=0
logcat_pid=""

if [[ ! "$instrumentation_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "INSTRUMENTATION_TIMEOUT_SECONDS must be a positive integer" >&2
  exit 1
fi

device_command() {
  python3 "$readiness_adapter" --output "$diagnostics_dir" command "$@"
}

require_android_health() {
  python3 "$readiness_adapter" --output "$diagnostics_dir" wait "$@" || return $?
  if ! kill -0 "$logcat_pid" 2>/dev/null; then
    echo "Device log capture exited before readiness completed" >&2
    return 1
  fi
}

restore_font_scale() {
  if [[ $font_scale_captured -eq 0 ]]; then
    return 0
  fi
  if [[ -z "$original_font_scale" || "$original_font_scale" == "null" ]]; then
    device_command restore-font shell settings delete system font_scale >/dev/null
  else
    device_command restore-font shell settings put system font_scale "$original_font_scale"
  fi
}

collect_failure_diagnostics() {
  python3 "$readiness_adapter" --output "$diagnostics_dir" diagnostics "${1:-failure}"
}

stop_log_capture() {
  if [[ -z "$logcat_pid" ]]; then
    return 0
  fi
  kill "$logcat_pid" 2>/dev/null || true
  for _ in {1..10}; do
    kill -0 "$logcat_pid" 2>/dev/null || break
    sleep 0.1
  done
  kill -KILL "$logcat_pid" 2>/dev/null || true
  wait "$logcat_pid" 2>/dev/null || true
}

cleanup() {
  local exit_status=$?
  trap - EXIT
  set +e
  if [[ $exit_status -eq 0 && -n "$logcat_pid" ]] && ! kill -0 "$logcat_pid" 2>/dev/null; then
    echo "Device log capture exited unexpectedly" >&2
    exit_status=1
  fi
  if [[ $exit_status -ne 0 ]]; then
    collect_failure_diagnostics
  fi
  local restore_status=0
  restore_font_scale || restore_status=$?
  stop_log_capture
  if [[ $exit_status -eq 0 ]]; then
    exit_status=$restore_status
  fi
  exit "$exit_status"
}

trap cleanup EXIT
rm -rf "$results_dir"
rm -rf "$diagnostics_dir"
rm -rf "$screenshots_dir"
mkdir -p "$results_dir" "$diagnostics_dir" "$screenshots_dir"
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$diagnostics_dir/logcat-capture-start.txt"
adb logcat -b all -v threadtime > "$diagnostics_dir/logcat-live.txt" \
  2> "$diagnostics_dir/logcat-live.stderr" &
logcat_pid=$!
require_android_health boot HOME --startup
original_font_scale="$(device_command original-font shell settings get system font_scale | tr -d '\r')"
font_scale_captured=1
device_command set-font shell settings put system font_scale "$font_scale"
test "$(device_command verify-font shell settings get system font_scale | tr -d '\r')" = "$font_scale"

device_command uninstall-tests uninstall me.egigoka.pomodorough.test >/dev/null 2>&1 || true
device_command uninstall-app uninstall "$package_name" >/dev/null 2>&1 || true
device_command install-app install -r "$app_apk"
device_command install-tests install -r "$test_apk"
device_command set-locale shell cmd locale set-app-locales "$package_name" --user 0 --locales "$test_locale"
locale_output="$(device_command verify-locale shell cmd locale get-app-locales "$package_name" --user 0 | tr -d '\r')"
expected_locale_line="Locales for $package_name for user 0 are [$test_locale]"
test "$locale_output" = "$expected_locale_line"
require_android_health configured HOME --startup
device_command launch shell am start -W -n me.egigoka.pomodorough/.MainActivity >/dev/null
device_command launch-screenshot exec-out screencap -p > "$screenshots_dir/launch.png"
require_android_health launched me.egigoka.pomodorough/.MainActivity
device_command instrumentation-registration shell pm list instrumentation | grep -F \
  'instrumentation:me.egigoka.pomodorough.test/androidx.test.runner.AndroidJUnitRunner'

runner_status=0
announced_tests=0
completed_tests=0
: > "$runner_output"

bounded_force_stop() {
  local target_package="$1"
  adb shell am force-stop "$target_package" >/dev/null 2>&1 &
  local adb_pid=$!
  local remaining_checks=50
  while kill -0 "$adb_pid" 2>/dev/null; do
    remaining_checks=$((remaining_checks - 1))
    if [[ $remaining_checks -eq 0 ]]; then
      kill "$adb_pid" 2>/dev/null || true
      sleep 1
      kill -KILL "$adb_pid" 2>/dev/null || true
      wait "$adb_pid" 2>/dev/null || true
      return 124
    fi
    sleep 0.1
  done
  wait "$adb_pid"
}

prepare_instrumentation() {
  local phase="$(basename "$1" .txt)"
  if ! device_command "$phase-stop" shell am force-stop "$package_name"; then
    echo "Failed to force-stop target process" >&2
    return 1
  fi
  local prior_pid
  if ! prior_pid="$(device_command "$phase-pid" shell "pidof '$package_name' || true" | tr -d '\r')"; then
    echo "Failed to query target PID after force-stop" >&2
    return 1
  fi
  if [[ -n "$prior_pid" ]]; then
    echo "Target process remained alive after force-stop" >&2
    return 1
  fi
  require_android_health "$phase-home" HOME
}

watch_instrumentation() {
  local instrumentation_pid="$1"
  local timeout_marker="$2"
  sleep "$instrumentation_timeout_seconds"
  if kill -0 "$instrumentation_pid" 2>/dev/null; then
    : > "$timeout_marker"
    kill "$instrumentation_pid" 2>/dev/null || true
    for _ in {1..20}; do
      kill -0 "$instrumentation_pid" 2>/dev/null || break
      sleep 0.1
    done
    kill -KILL "$instrumentation_pid" 2>/dev/null || true
    bounded_force_stop "$package_name" || true
  fi
}

observe_instrumentation_process() {
  local instrumentation_pid="$1"
  local output_file="$2"
  local target_pid=""
  for _ in {1..300}; do
    if ! target_pid="$(device_command running-pid shell "pidof '$package_name' || true" | tr -d '\r')"; then
      return 1
    fi
    if [[ -n "$target_pid" ]]; then
      printf '%s\n' "$target_pid" > "$output_file.pid"
      return 0
    fi
    if ! kill -0 "$instrumentation_pid" 2>/dev/null; then
      break
    fi
    sleep 0.2
  done
  echo "Instrumentation target PID was not observed for $output_file" >&2
  return 1
}

execute_instrumentation() {
  local output_file="$1"
  shift
  set +e
  adb shell am instrument -w -r -e expectedLocale "$test_locale" "$@" \
    me.egigoka.pomodorough.test/androidx.test.runner.AndroidJUnitRunner \
    > "$output_file" &
  local instrumentation_pid=$!
  local timeout_marker="$output_file.timeout"
  rm -f "$timeout_marker"
  watch_instrumentation "$instrumentation_pid" "$timeout_marker" &
  local watchdog_pid=$!
  local status=0
  observe_instrumentation_process "$instrumentation_pid" "$output_file" || status=$?
  wait "$instrumentation_pid"
  local instrumentation_status=$?
  kill "$watchdog_pid" 2>/dev/null || true
  wait "$watchdog_pid" 2>/dev/null || true
  if [[ -f "$timeout_marker" ]]; then
    echo "Instrumentation exceeded ${instrumentation_timeout_seconds}s for $output_file" >&2
    instrumentation_status=124
  fi
  if [[ $status -eq 0 ]]; then
    status=$instrumentation_status
  fi
  set -e
  return "$status"
}

verify_instrumentation_result() {
  local output_file="$1"
  local status="$2"
  local announced
  local completed
  announced="$(grep -m 1 '^INSTRUMENTATION_STATUS: numtests=' "$output_file" \
    | cut -d= -f2 | tr -d '\r' || true)"
  completed="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$output_file" || true)"
  completed_tests=$((completed_tests + completed))
  if [[ "$announced" =~ ^[1-9][0-9]*$ ]]; then
    announced_tests=$((announced_tests + announced))
  fi
  if ! tee -a "$runner_output" < "$output_file"; then
    echo "Failed to append $output_file to $runner_output" >&2
    return 1
  fi
  if [[ ! "$announced" =~ ^[1-9][0-9]*$ ]] \
    || [[ "$completed" -ne "$announced" ]] \
    || [[ $status -ne 0 ]] \
    || grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[12]$|^FAILURES!!!$' "$output_file" \
    || ! grep -q '^INSTRUMENTATION_CODE: -1$' "$output_file"; then
    echo "Instrumentation runner failed for $output_file ($completed/${announced:-0} tests)" >&2
    return 1
  fi
}

run_instrumentation() {
  local output_file="$1"
  shift
  prepare_instrumentation "$output_file" || return $?
  local status=0
  execute_instrumentation "$output_file" "$@" || status=$?
  if ! verify_instrumentation_result "$output_file" "$status"; then
    collect_failure_diagnostics "$(basename "$output_file" .txt)-failure" || true
    return 1
  fi
}

if [[ -n "$test_class" ]]; then
  run_instrumentation "$results_dir/instrumentation-focused.txt" \
    -e class "$test_class" \
    || runner_status=$?
else
  shard_count="${TEST_SHARD_COUNT:-8}"
  expected_test_count="${EXPECTED_TEST_COUNT:-331}"
  if [[ ! "$shard_count" =~ ^[1-9][0-9]*$ ]]; then
    echo "TEST_SHARD_COUNT must be a positive integer" >&2
    exit 1
  fi
  if [[ ! "$expected_test_count" =~ ^[1-9][0-9]*$ ]]; then
    echo "EXPECTED_TEST_COUNT must be a positive integer" >&2
    exit 1
  fi
  for ((shard_index = 0; shard_index < shard_count; shard_index++)); do
    shard_status=0
    run_instrumentation "$results_dir/instrumentation-shard-$shard_index.txt" \
      -e numShards "$shard_count" -e shardIndex "$shard_index" \
      || shard_status=$?
    if [[ $runner_status -eq 0 && $shard_status -ne 0 ]]; then
      runner_status=$shard_status
    fi
  done
fi
device_command after-tests-screenshot exec-out screencap -p > "$screenshots_dir/after-tests.png" || true

echo "Instrumentation completed $completed_tests/$announced_tests tests across ${shard_count:-1} shard(s)"
if [[ $runner_status -ne 0 ]]; then
  exit "$runner_status"
fi

if [[ "$completed_tests" -ne "$announced_tests" ]]; then
  exit 1
fi
if [[ -z "$test_class" && "$announced_tests" -ne "$expected_test_count" ]]; then
  echo "Expected $expected_test_count instrumentation tests, discovered $announced_tests" >&2
  exit 1
fi

echo "Instrumentation passed $completed_tests/$announced_tests tests"
