from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ABI_SCRIPT = Path(__file__).parents[1] / ".github/scripts/release-runtime-abi.sh"
MOCK_ADB = r'''
set -euo pipefail
source "$1"
adb() {
  case "$*" in
    'shell getprop ro.product.cpu.abi') printf 'x86_64\r\n' ;;
    'shell getprop ro.product.cpu.abilist') printf '%s\r\n' "$DEVICE_ABIS" ;;
    'shell dumpsys package me.egigoka.pomodorough')
      printf '  primaryCpuAbi=%s\r\n' "$INSTALLED_ABI" ;;
    *) return 1 ;;
  esac
}
'''


class ReleaseRuntimeAbiTests(unittest.TestCase):
    def run_probe(self, command: str, **environment: str) -> subprocess.CompletedProcess[str]:
        env = {**os.environ, "RUNTIME_ABI": "", "DEVICE_ABIS": "x86_64,x86,arm64-v8a,armeabi-v7a",
               "INSTALLED_ABI": "arm64-v8a", **environment}
        return subprocess.run(["bash", "-c", MOCK_ADB + command, "probe", str(ABI_SCRIPT)],
                              env=env, text=True, capture_output=True, check=False)

    def test_default_uses_device_abi(self) -> None:
        result = self.run_probe("select_release_runtime_abi")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "x86_64\n")

    def test_explicit_abi_accepts_native_and_translated_libraries(self) -> None:
        for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
            with self.subTest(abi=abi):
                result = self.run_probe("select_release_runtime_abi", RUNTIME_ABI=abi)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, abi + "\n")

    def test_missing_abi_does_not_fall_back_to_device_default(self) -> None:
        result = self.run_probe("select_release_runtime_abi", RUNTIME_ABI="armeabi-v7a",
                                DEVICE_ABIS="x86_64,arm64-v8a")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Device cannot execute armeabi-v7a", result.stderr)

    def test_unknown_and_partial_abi_names_fail_closed(self) -> None:
        for abi in ("arm", "x86_6", "mips", "arm64-v8a,x86"):
            with self.subTest(abi=abi):
                result = self.run_probe("select_release_runtime_abi", RUNTIME_ABI=abi)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")

    def test_installed_abi_must_match_request(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            for installed in ("arm64-v8a", "x86_64", "null", ""):
                with self.subTest(installed=installed):
                    result = self.run_probe('verify_installed_release_abi arm64-v8a "$REPORT"',
                                            REPORT=str(Path(directory) / "package.txt"),
                                            INSTALLED_ABI=installed)
                    self.assertEqual(result.returncode == 0, installed == "arm64-v8a")
                    self.assertIn("primaryCpuAbi=", (Path(directory) / "package.txt").read_text())


if __name__ == "__main__":
    unittest.main()
