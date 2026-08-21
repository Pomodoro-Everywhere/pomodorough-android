from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"


class CIWorkflowTests(unittest.TestCase):
    def test_emulator_runner_release_smoke_uses_posix_shell_syntax(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        smoke = workflow.split(
            "      - name: Ephemerally sign, clean-install, and launch packaged APK", 1
        )[1].split("\n  dependency-review:", 1)[0]

        self.assertNotIn("pipefail", smoke)
        self.assertNotIn("shopt", smoke)
        self.assertNotIn("<<<", smoke)
        self.assertIn("set -- dist/*-release-unsigned.apk", smoke)
        self.assertIn("[ ! -f \"$1\" ]", smoke)
        self.assertIn("printf '%s\\n' \"$launch_output\" | grep -Fq 'Status: ok'", smoke)


if __name__ == "__main__":
    unittest.main()
