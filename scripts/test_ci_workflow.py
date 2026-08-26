from __future__ import annotations

import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
SMOKE_SCRIPT = ROOT / ".github" / "scripts" / "smoke-packaged-release.sh"
PROVENANCE_SCRIPT = ROOT / "scripts" / "verify_shared_core_provenance.py"
VALID_WASM = b"\0asm\x01\0\0\0"
DIFFERENT_VALID_WASM = VALID_WASM + b"\0\x01\0"


DEPENDENCY_LOCK = ROOT / "app" / "gradle.lockfile"


class CIWorkflowTests(unittest.TestCase):
    def test_chicory_dependencies_are_locked(self) -> None:
        lock = DEPENDENCY_LOCK.read_text(encoding="utf-8")
        self.assertIn("com.dylibso.chicory:runtime:1.7.5", lock)
        self.assertIn("com.dylibso.chicory:wasm:1.7.5", lock)

    def test_pinned_shared_core_is_rebuilt_and_contract_verified(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn(
            'CORE_COMMIT: "49efee8c5ac390d5dd7bd5c1a3537fb889fa6f10"',
            workflow,
        )
        self.assertIn(
            'CORE_SHA256: "50519a0c12b0e38d3281d2205f5597f03bb5e8cdd7e9e57f86bb4458fd0dad64"',
            workflow,
        )
        self.assertIn("repository: Pomodoro-Everywhere/pomodorough-core", workflow)
        self.assertIn("ref: ${{ env.CORE_COMMIT }}", workflow)
        self.assertIn(
            "cargo +1.97.1 build --release --target wasm32-unknown-unknown --locked",
            workflow,
        )
        self.assertIn("verify_wasm_artifact.py", workflow)
        self.assertIn('--sha256 "$CORE_SHA256"', workflow)
        self.assertIn("scripts/verify_shared_core_provenance.py", workflow)
        self.assertIn('"$rebuilt"', workflow)
        self.assertIn(
            'grep -Fx "CORE_COMMIT=$CORE_COMMIT" app/src/main/assets/shared_core.properties',
            workflow,
        )
        self.assertIn(
            'grep -Fx "CORE_SHA256=$CORE_SHA256" app/src/main/assets/shared_core.properties',
            workflow,
        )

    def test_provenance_rejects_different_valid_wasm(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rebuilt = root / "rebuilt.wasm"
            embedded = root / "embedded.wasm"
            rebuilt.write_bytes(VALID_WASM)
            embedded.write_bytes(DIFFERENT_VALID_WASM)
            result = subprocess.run(
                [
                    sys.executable,
                    str(PROVENANCE_SCRIPT),
                    "--sha256",
                    hashlib.sha256(VALID_WASM).hexdigest(),
                    str(rebuilt),
                    str(embedded),
                ],
                capture_output=True,
                check=False,
                text=True,
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("differs from rebuild", result.stderr)


    def test_release_packages_contain_exact_shared_core(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("Verify shared core in unsigned release APK", workflow)
        self.assertIn("unzip -p \"$release_apk\" assets/pomodorough_core.wasm", workflow)
        self.assertIn("unzip -p \"$release_aab\" base/assets/pomodorough_core.wasm", workflow)
        self.assertIn('test "$packaged_core_sha" = "$CORE_SHA256"', workflow)

    def test_emulator_runner_invokes_checked_in_release_smoke_script_once(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        release_job = workflow.split("  release-smoke:", 1)[1].split(
            "\n  dependency-review:", 1
        )[0]
        smoke = release_job.split(
            "      - name: Ephemerally sign, clean-install, and launch packaged APK", 1
        )[1]

        self.assertIn("- name: Check out repository", release_job)
        self.assertIn("script: bash .github/scripts/smoke-packaged-release.sh", smoke)
        self.assertNotIn("script: |", smoke)

        script = SMOKE_SCRIPT.read_text(encoding="utf-8")
        self.assertTrue(script.startswith("#!/usr/bin/env bash\nset -euo pipefail\n"))
        self.assertIn("release_apks=(dist/*-release-unsigned.apk)", script)
        self.assertIn("adb install \"$smoke_apk\"", script)
        self.assertIn("grep -Fq 'Status: ok'", script)


if __name__ == "__main__":
    unittest.main()
