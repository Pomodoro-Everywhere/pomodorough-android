from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
SMOKE_SCRIPT = ROOT / ".github" / "scripts" / "smoke-packaged-release.sh"


DEPENDENCY_LOCK = ROOT / "app" / "gradle.lockfile"


class CIWorkflowTests(unittest.TestCase):
    def test_chicory_dependencies_are_locked(self) -> None:
        lock = DEPENDENCY_LOCK.read_text(encoding="utf-8")
        self.assertIn("com.dylibso.chicory:runtime:1.7.5", lock)
        self.assertIn("com.dylibso.chicory:wasm:1.7.5", lock)

    def test_pinned_shared_core_is_rebuilt_and_byte_compared(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn(
            'CORE_COMMIT: "a78a312314dd9466557c3dbdd12184b698c3d156"',
            workflow,
        )
        self.assertIn(
            'CORE_SHA256: "89fb6300324042b61d62070242cccad10e30f125885bb1b7a05af67b077bac83"',
            workflow,
        )
        self.assertIn("repository: Pomodoro-Everywhere/pomodorough-core", workflow)
        self.assertIn("ref: ${{ env.CORE_COMMIT }}", workflow)
        self.assertIn(
            "cargo +1.97.1 build --release --target wasm32-unknown-unknown --locked",
            workflow,
        )
        self.assertIn(
            'printf \'%s  %s\\n\' "$CORE_SHA256" app/src/main/assets/pomodorough_core.wasm',
            workflow,
        )
        self.assertIn(
            'grep -Fx "CORE_COMMIT=$CORE_COMMIT" app/src/main/assets/shared_core.properties',
            workflow,
        )
        self.assertIn(
            'grep -Fx "CORE_SHA256=$CORE_SHA256" app/src/main/assets/shared_core.properties',
            workflow,
        )
        self.assertIn(
            "cmp pomodorough-core-source/target/wasm32-unknown-unknown/release/pomodorough_core.wasm app/src/main/assets/pomodorough_core.wasm",
            workflow,
        )

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
