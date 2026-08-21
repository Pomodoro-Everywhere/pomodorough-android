from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
SMOKE_SCRIPT = ROOT / ".github" / "scripts" / "smoke-packaged-release.sh"


class CIWorkflowTests(unittest.TestCase):
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
