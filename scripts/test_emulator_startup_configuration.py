"""Static startup configuration regressions; not emulator or release acceptance."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml"
EMULATOR_ACTION = "ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d"
EMULATOR_STEPS = {
    "connected": "Run connected tests",
    "release-smoke": "Ephemerally sign, clean-install, and launch packaged APK",
}


class EmulatorStartupConfigurationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = WORKFLOW.read_text(encoding="utf-8")

    def job(self, name: str) -> str:
        match = re.search(
            rf"(?ms)^  {re.escape(name)}:\n(.*?)(?=^  [\w-]+:\n|\Z)",
            self.workflow,
        )
        self.assertIsNotNone(match, f"Missing job: {name}")
        return match.group(1)

    def step(self, job: str, name: str) -> str:
        match = re.search(
            rf"(?ms)^      - name: {re.escape(name)}\n(.*?)(?=^      - name: |\Z)",
            self.job(job),
        )
        self.assertIsNotNone(match, f"Missing step: {job}/{name}")
        return match.group(1)

    def emulator(self, job: str) -> str:
        step = self.step(job, EMULATOR_STEPS[job])
        self.assertIn(f"        uses: {EMULATOR_ACTION} # v2\n", step)
        return step

    def input_value(self, step: str, name: str) -> str:
        values = re.findall(rf"(?m)^          {re.escape(name)}: ([^\n]+)$", step)
        self.assertEqual(len(values), 1, f"Expected exactly one input: {name}")
        return values[0]

    def literal_commands(self, step: str, name: str) -> list[str]:
        self.assertEqual(self.input_value(step, name), "|")
        match = re.search(
            rf"(?m)^          {re.escape(name)}: \|\n((?:            [^\n]*\n)+)",
            step,
        )
        self.assertIsNotNone(match, f"Missing literal commands: {name}")
        return [line[12:] for line in match.group(1).splitlines()]

    def test_emulators_use_four_cores_on_same_host_class(self) -> None:
        for job in EMULATOR_STEPS:
            with self.subTest(job=job):
                self.assertIn("    runs-on: ubuntu-24.04\n", self.job(job))
                self.assertEqual(self.input_value(self.emulator(job), "cores"), "4")

    def test_emulator_memory_is_explicit(self) -> None:
        for job in EMULATOR_STEPS:
            with self.subTest(job=job):
                self.assertEqual(self.input_value(self.emulator(job), "ram-size"), "4096M")

    def test_connected_starts_host_adb_before_passive_diagnostics(self) -> None:
        self.assertEqual(
            self.literal_commands(self.emulator("connected"), "pre-emulator-launch-script"),
            [
                "adb start-server",
                'python3 .github/scripts/android-startup-diagnostics.py start --output "$STARTUP_DIAGNOSTICS"',
            ],
        )

    def test_release_starts_host_adb_before_emulator_launch(self) -> None:
        self.assertEqual(
            self.input_value(self.emulator("release-smoke"), "pre-emulator-launch-script"),
            "adb start-server",
        )

    def test_pinned_action_inputs_do_not_override_existing_boot_behavior(self) -> None:
        expected = {
            "api-level", "target", "arch", "profile", "cores", "ram-size",
            "disable-animations", "pre-emulator-launch-script", "script",
        }
        for job in EMULATOR_STEPS:
            with self.subTest(job=job):
                step = self.emulator(job)
                self.assertCountEqual(re.findall(r"(?m)^          ([\w-]+):", step), expected)
                self.assertEqual(self.input_value(step, "api-level"), "${{ matrix.api-level }}")
                self.assertEqual(self.input_value(step, "target"), "google_apis")
                self.assertEqual(self.input_value(step, "profile"), "pixel_6")
                self.assertEqual(self.input_value(step, "disable-animations"), "true")
        self.assertEqual(self.input_value(self.emulator("connected"), "arch"), "x86_64")
        self.assertEqual(self.input_value(self.emulator("release-smoke"), "arch"), "${{ matrix.architecture }}")

    def test_all_eight_connected_cells_remain_unchanged(self) -> None:
        matrix = self.job("connected").split("    steps:\n", 1)[0]
        cells = re.findall(
            r'^          - api-level: (\d+)\n'
            r'            font-scale: "([^"]+)"\n'
            r'            locale: "([^"]+)"\n'
            r'            test-class: ([^\n]+)$',
            matrix, re.MULTILINE,
        )
        rtl_class = "me.egigoka.pomodorough.ui.PomodoroughRtlAccessibilityTest"
        expected = [
            (api, scale, locale, '""' if locale == "en-US" else rtl_class)
            for locale in ("en-US", "ar-XB")
            for api in ("35", "36")
            for scale in ("1.0", "2.0")
        ]
        self.assertEqual(cells, expected)
        self.assertEqual(matrix.count("          - api-level:"), 8)
        self.assertIn("      fail-fast: false\n", matrix)

    def test_all_four_release_abis_and_guest_images_remain_unchanged(self) -> None:
        matrix = self.job("release-smoke").split("    steps:\n", 1)[0]
        cells = re.findall(
            r"^          - api-level: (\d+)\n"
            r"            architecture: (\S+)\n"
            r"            runtime-abi: (\S+)$",
            matrix, re.MULTILINE,
        )
        self.assertEqual(cells, [
            ("30", "x86", "x86"), ("36", "x86_64", "x86_64"),
            ("30", "x86", "armeabi-v7a"), ("30", "x86_64", "arm64-v8a"),
        ])
        self.assertEqual(matrix.count("          - api-level:"), 4)
        self.assertIn("      fail-fast: false\n", matrix)

    def test_checked_in_strict_runners_are_invoked_once_without_overrides(self) -> None:
        connected_command = (
            "FONT_SCALE=${{ matrix.font-scale }} TEST_LOCALE=${{ matrix.locale }} "
            "TEST_CLASS=${{ matrix.test-class }} .github/scripts/run-instrumented-tests.sh"
        )
        release_command = "RUNTIME_ABI=${{ matrix.runtime-abi }} bash .github/scripts/smoke-packaged-release.sh"
        self.assertEqual(self.literal_commands(self.emulator("connected"), "script"), [connected_command])
        self.assertEqual(self.input_value(self.emulator("release-smoke"), "script"), release_command)
        for command in (connected_command, release_command):
            self.assertEqual(self.workflow.count(command), 1)
        self.assertEqual(self.workflow.count(f"uses: {EMULATOR_ACTION}"), 2)
        self.assertNotRegex(self.workflow, r"(?i)continue-on-error|\bretr(?:y|ies)\b|\|\|\s*(?:true|:)")
        self.assertNotRegex(self.workflow, r"adb (?:kill-server|reconnect)|logcat[^\n]* -c\b")
        self.assertNotRegex(self.workflow, r"EXPECTED_TEST_COUNT|TEST_SHARD_COUNT|INSTRUMENTATION_TIMEOUT_SECONDS")

    def test_prerequisite_gates_and_diagnostic_retention_remain_strict(self) -> None:
        connected = self.job("connected")
        self.assertIn("    needs: candidate-source\n", connected)
        self.assertIn("    timeout-minutes: 60\n", connected)
        self.assertIn("python3 -m unittest scripts/test_android_readiness.py -v", connected)
        self.assertIn("python3 -m unittest discover -s scripts -p test_android_startup_phase.py -v", connected)
        self.assertIn("python3 -m unittest scripts/test_android_startup_diagnostics.py scripts/test_android_diagnostic_capture.py -v", connected)
        self.assertIn('assert os.environ["GITHUB_RUN_ATTEMPT"] == "1"', self.job("candidate-source"))
        release = self.job("release-smoke")
        self.assertIn("    needs: [verify, connected]\n", release)
        self.assertIn("    if: inputs.upload-release-bundle\n", release)
        self.assertIn("    timeout-minutes: 30\n", release)
        finish = self.step("connected", "Finish Android startup diagnostics")
        self.assertIn("        if: always()\n", finish)
        self.assertIn('run: python3 .github/scripts/android-startup-diagnostics.py stop --output "$STARTUP_DIAGNOSTICS"', finish)
        for job, name, path in (
            ("connected", "Retain connected reports, screenshots, and diagnostics", "app/build/reports/androidTests/"),
            ("release-smoke", "Retain release-Iroh instrumentation and diagnostics", "release-smoke-results/"),
        ):
            with self.subTest(job=job):
                step = self.step(job, name)
                self.assertIn("        if: always()\n", step)
                self.assertIn("          if-no-files-found: error\n", step)
                self.assertIn("          retention-days: 14\n", step)
                self.assertIn(path, step)

    def test_documentation_checks_run_old_and_new_regressions_in_both_jobs(self) -> None:
        command = (
            "python3 -m unittest scripts/test_check_localization.py scripts/test_ci_workflow.py "
            "scripts/test_release_runtime_abi.py scripts/test_emulator_startup_configuration.py -v"
        )
        for job in ("verify", "connected"):
            with self.subTest(job=job):
                documentation = self.step(job, "Check documentation links")
                self.assertIn(f"          {command}\n", documentation)
                self.assertIn("          python3 scripts/check_workflow_pins.py\n", documentation)


if __name__ == "__main__":
    unittest.main()
