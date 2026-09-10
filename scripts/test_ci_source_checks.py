"""Execute workflow source gates with command doubles; reject gate-bypass mutations."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).parents[1]
WORKFLOW = ROOT / ".github/workflows/ci.yml"
STEPS = ("Check documentation links", "Check Android readiness adapter",
         "Check Android startup diagnostics")
COMMANDS = (
    "scripts/check_docs.py",
    "scripts/check_protocol_fixture.py",
    "scripts/check_workflow_pins.py",
    "-m unittest scripts/test_check_localization.py scripts/test_ci_workflow.py "
    "scripts/test_ci_source_checks.py scripts/test_release_runtime_abi.py "
    "scripts/test_emulator_startup_configuration.py scripts/test_android_emulator_lifecycle.py -v",
    "scripts/check_localization.py",
    "-m unittest scripts/test_android_readiness.py -v",
    "-m unittest discover -s scripts -p test_android_startup_phase.py -v",
    "-m unittest scripts/test_android_startup_diagnostics.py scripts/test_android_diagnostic_capture.py -v",
)


def job_text(workflow, name):
    return re.search(rf"(?ms)^  {name}:\n(.*?)(?=^  [\w-]+:|\Z)", workflow)[1]


def source_step(verify, name):
    return re.search(rf"(?ms)^      - name: {name}\n(.*?)(?=^      - name:|\Z)", verify)[1]


class SourceCheckTests(unittest.TestCase):
    def assert_source_gates(self, workflow):
        verify = job_text(workflow, "verify")
        connected = job_text(workflow, "connected")
        for job in (verify, connected):
            self.assertIn("    needs: candidate-source\n", job)
            self.assertNotRegex(job, r"(?m)^    (?:if|continue-on-error):")
            self.assertIn('run: test "$(git rev-parse HEAD)" = "$GITHUB_SHA"', job)
        commands = []
        for name in STEPS:
            self.assertEqual(workflow.count(f"- name: {name}\n"), 1)
            step = source_step(verify, name)
            self.assertNotRegex(step, r"(?m)^        (?:if|continue-on-error):")
            commands.extend(re.findall(r"(?:run: |^          )python3 ([^\n]+)", step, re.M))
        self.assertEqual(commands, list(COMMANDS))
        self.assertLess(verify.index(STEPS[-1]), verify.index("Run unit tests and lint"))
        self.assertNotIn("continue-on-error:", verify)
        self.assertNotIn("continue-on-error:", connected)
        release = (ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn("\n    needs: ci\n", release)
        self.assertIn("uses: ./.github/workflows/ci.yml", release)

    def test_source_gates_run_once_without_serializing_connected_jobs(self):
        self.assert_source_gates(WORKFLOW.read_text())

    def test_gate_bypass_mutations_are_rejected(self):
        workflow = WORKFLOW.read_text()
        mutations = [(command, command + " || true") for command in COMMANDS]
        mutations += [("python3 " + command, "true # omitted") for command in COMMANDS]
        mutations += [
            ("  verify:\n    needs: candidate-source", "  verify:\n    needs: candidate-source\n    if: false"),
            ("  connected:\n    needs: candidate-source", "  connected:\n    needs: verify"),
            ('run: test "$(git rev-parse HEAD)" = "$GITHUB_SHA"', "run: true"),
        ]
        for name in STEPS:
            marker = f"- name: {name}\n"
            mutations.extend((marker, marker + f"        {field}\n")
                             for field in ("if: false", "continue-on-error: true"))
        for before, after in mutations:
            with self.subTest(before=before, after=after), self.assertRaises(AssertionError):
                self.assert_source_gates(workflow.replace(before, after, 1))

    def test_actual_shell_steps_stop_on_each_failed_source_check(self):
        verify = job_text(WORKFLOW.read_text(), "verify")
        scripts = []
        for name in STEPS:
            run = source_step(verify, name).split("        run: ", 1)[1]
            scripts.append(textwrap.dedent(run[2:]) if run.startswith("|\n") else run)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            command = root / "python3"
            command.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> "$COMMAND_LOG"\n'
                               '[[ "$*" != "$FAIL_COMMAND" ]]\n')
            command.chmod(0o755)
            for failure in ("", *COMMANDS):
                with self.subTest(failure=failure):
                    log = root / "commands"
                    log.write_text("")
                    env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}",
                               COMMAND_LOG=str(log), FAIL_COMMAND=failure)
                    for script in scripts:
                        result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", script],
                                                env=env, capture_output=True, text=True, timeout=10)
                        if result.returncode:
                            break
                    expected = list(COMMANDS[:COMMANDS.index(failure) + 1] if failure else COMMANDS)
                    self.assertEqual(log.read_text().splitlines(), expected)
                    self.assertEqual(result.returncode == 0, not failure)


if __name__ == "__main__":
    unittest.main()
