#!/usr/bin/env bash

set -euo pipefail

app_apk="app/build/outputs/apk/debug/app-debug.apk"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
package_name="me.egigoka.pomodorough"
font_scale="${FONT_SCALE:-1.0}"
scale_slug="${font_scale//./_}"
test_locale="${TEST_LOCALE:-en-US}"
test_class="${TEST_CLASS:-}"
instrumentation_timeout_seconds="${INSTRUMENTATION_TIMEOUT_SECONDS:-180}"
locale_slug="${test_locale//-/_}"
results_dir="app/build/outputs/androidTest-results/direct-font-$scale_slug-locale-$locale_slug"
diagnostics_dir="app/build/reports/androidTests/diagnostics-font-$scale_slug-locale-$locale_slug"
screenshots_dir="app/build/reports/androidTests/screenshots-font-$scale_slug-locale-$locale_slug"
runner_output="$results_dir/instrumentation-output.txt"
original_font_scale="$(adb shell settings get system font_scale | tr -d '\r')"

if [[ ! "$instrumentation_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "INSTRUMENTATION_TIMEOUT_SECONDS must be a positive integer" >&2
  exit 1
fi

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
  local exit_status=$?
  trap - EXIT
  set +e
  if [[ $exit_status -ne 0 ]]; then
    collect_failure_diagnostics
  fi
  restore_font_scale
  return "$exit_status"
}

trap cleanup EXIT
wait_for_android_idle
adb shell settings put system font_scale "$font_scale"
test "$(adb shell settings get system font_scale | tr -d '\r')" = "$font_scale"
rm -rf "$results_dir"
rm -rf "$diagnostics_dir"
rm -rf "$screenshots_dir"
mkdir -p "$results_dir"
mkdir -p "$screenshots_dir"
adb logcat -c

adb uninstall me.egigoka.pomodorough.test >/dev/null 2>&1 || true
adb uninstall "$package_name" >/dev/null 2>&1 || true
adb install -r "$app_apk"
adb install -r "$test_apk"
wait_for_android_idle
adb shell cmd locale set-app-locales "$package_name" --user 0 --locales "$test_locale"
locale_output="$(adb shell cmd locale get-app-locales "$package_name" --user 0 | tr -d '\r')"
expected_locale_line="Locales for $package_name for user 0 are [$test_locale]"
test "$locale_output" = "$expected_locale_line"
adb shell am start -W -n me.egigoka.pomodorough/.MainActivity >/dev/null
adb exec-out screencap -p > "$screenshots_dir/launch.png"
adb shell pm list instrumentation | grep -F \
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

run_instrumentation() {
  local output_file="$1"
  shift
  if ! adb shell am force-stop "$package_name"; then
    echo "Failed to force-stop target process" >&2
    return 1
  fi
  local prior_pid
  if ! prior_pid="$(adb shell "pidof '$package_name' || true" | tr -d '\r')"; then
    echo "Failed to query target PID after force-stop" >&2
    return 1
  fi
  if [[ -n "$prior_pid" ]]; then
    echo "Target process remained alive after force-stop" >&2
    return 1
  fi

  set +e
  adb shell am instrument -w -r -e expectedLocale "$test_locale" "$@" \
    me.egigoka.pomodorough.test/androidx.test.runner.AndroidJUnitRunner \
    > "$output_file" &
  local instrumentation_pid=$!
  local timeout_marker="$output_file.timeout"
  rm -f "$timeout_marker"
  (
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
  ) &
  local watchdog_pid=$!
  local target_pid=""
  local status=0
  for _ in {1..300}; do
    if ! target_pid="$(adb shell "pidof '$package_name' || true" 2>/dev/null | tr -d '\r')"; then
      status=1
      break
    fi
    if [[ -n "$target_pid" ]]; then
      break
    fi
    if ! kill -0 "$instrumentation_pid" 2>/dev/null; then
      break
    fi
    sleep 0.2
  done
  if [[ -n "$target_pid" ]]; then
    printf '%s\n' "$target_pid" > "$output_file.pid"
  fi
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
  if [[ -z "$target_pid" ]]; then
    echo "Instrumentation target PID was not observed for $output_file" >&2
    status=1
  fi
  local announced
  local completed
  announced="$(grep -m 1 '^INSTRUMENTATION_STATUS: numtests=' "$output_file" \
    | cut -d= -f2 | tr -d '\r' || true)"
  completed="$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$output_file" || true)"
  completed_tests=$((completed_tests + completed))
  if [[ "$announced" =~ ^[1-9][0-9]*$ ]]; then
    announced_tests=$((announced_tests + announced))
  fi
  if [[ ! "$announced" =~ ^[1-9][0-9]*$ ]] \
    || [[ "$completed" -ne "$announced" ]] \
    || [[ $status -ne 0 ]] \
    || grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[12]$|^FAILURES!!!$' "$output_file" \
    || ! grep -q '^INSTRUMENTATION_CODE: -1$' "$output_file"; then
    echo "Instrumentation runner failed for $output_file ($completed/${announced:-0} tests)" >&2
    return 1
  fi
  if ! tee -a "$runner_output" < "$output_file"; then
    echo "Failed to append $output_file to $runner_output" >&2
    return 1
  fi
}

if [[ -n "$test_class" ]]; then
  run_instrumentation "$results_dir/instrumentation-focused.txt" \
    -e class "$test_class" \
    || runner_status=$?
else
  shard_count="${TEST_SHARD_COUNT:-8}"
  expected_test_count="${EXPECTED_TEST_COUNT:-282}"
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
adb exec-out screencap -p > "$screenshots_dir/after-tests.png" || true

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
