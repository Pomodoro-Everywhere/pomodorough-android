#!/usr/bin/env bash

select_release_runtime_abi() {
  local runtime_abi supported_abis
  runtime_abi="${RUNTIME_ABI:-$(adb shell getprop ro.product.cpu.abi | tr -d '\r')}"
  case "$runtime_abi" in
    arm64-v8a|armeabi-v7a|x86|x86_64) ;;
    *) echo "Unsupported release ABI: $runtime_abi" >&2; return 1 ;;
  esac
  supported_abis="$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"
  if [[ ",$supported_abis," != *",$runtime_abi,"* ]]; then
    echo "Device cannot execute $runtime_abi (supported: $supported_abis)" >&2
    return 1
  fi
  printf '%s\n' "$runtime_abi"
}

verify_installed_release_abi() {
  local expected_abi="$1" package_report="$2" installed_abi
  adb shell dumpsys package me.egigoka.pomodorough > "$package_report"
  installed_abi="$(sed -n 's/^[[:space:]]*primaryCpuAbi=//p' "$package_report" | tr -d '\r')"
  if [[ "$installed_abi" != "$expected_abi" ]]; then
    echo "Release installed for $installed_abi, expected $expected_abi" >&2
    return 1
  fi
}
