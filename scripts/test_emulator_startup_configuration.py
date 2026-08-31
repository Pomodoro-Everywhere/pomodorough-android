"""Static startup configuration regressions; not emulator or release acceptance."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml"
EMULATOR_COMMAND = "python3 .github/scripts/run-android-emulator.py"
SDK_ACTION = "android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407"
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
        self.assertIn("        run: |\n", step)
        self.assertEqual(step.count(EMULATOR_COMMAND), 1)
        self.assertNotIn("uses:", step)
        return step

    def input_value(self, step: str, name: str) -> str:
        values = re.findall(rf"(?m)^          {re.escape(name)}: ([^\n]+)$", step)
        self.assertEqual(len(values), 1, f"Expected exactly one input: {name}")
        return values[0]

    def test_emulators_use_owned_lifecycle_on_same_host_class(self) -> None:
        for job in EMULATOR_STEPS:
            with self.subTest(job=job):
                self.assertIn("    runs-on: ubuntu-24.04\n", self.job(job))
                self.emulator(job)

    def test_fixed_resources_cannot_be_overridden_by_workflow(self) -> None:
        for job in EMULATOR_STEPS:
            with self.subTest(job=job):
                step = self.emulator(job)
                self.assertNotRegex(step, r"--(?:cores|ram-size|memory|target|profile|emulator-options)\b")
                self.assertNotIn("        env:", step)

    def test_connected_passes_passive_diagnostics_to_lifecycle(self) -> None:
        step = self.emulator("connected")
        self.assertEqual(step.count('--startup-diagnostics "$STARTUP_DIAGNOSTICS"'), 1)
        self.assertLess(step.index("--startup-diagnostics"), step.index("-- env "))
        self.assertIn("--output app/build/reports/androidTests/emulator-api-", step)

    def test_release_startup_evidence_survives_smoke_output_cleanup(self) -> None:
        step = self.emulator("release-smoke")
        self.assertEqual(step.count("--output release-emulator-results"), 1)
        self.assertNotIn("--startup-diagnostics", step)
        retention = self.step("release-smoke", "Retain release-Iroh instrumentation and diagnostics")
        self.assertIn("            release-emulator-results/\n", retention)

    def test_pinned_sdk_and_existing_guest_architectures_are_explicit(self) -> None:
        for job, name in (("connected", "Set up emulator SDK"), ("release-smoke", "Set up Android SDK")):
            with self.subTest(job=job):
                setup = self.step(job, name)
                self.assertIn(f"        uses: {SDK_ACTION} # v3\n", setup)
                self.assertEqual(self.input_value(setup, "cmdline-tools-version"), "'14742923'")
                self.assertEqual(self.input_value(setup, "packages"), "platform-tools")
                self.assertLess(self.job(job).index(name), self.job(job).index(EMULATOR_STEPS[job]))
        self.assertIn("--api-level ${{ matrix.api-level }} --architecture x86_64", self.emulator("connected"))
        self.assertIn("--api-level ${{ matrix.api-level }} --architecture ${{ matrix.architecture }}", self.emulator("release-smoke"))

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
        self.assertIn(f"            -- env {connected_command}\n", self.emulator("connected"))
        self.assertIn(f"            -- env {release_command}\n", self.emulator("release-smoke"))
        for command in (connected_command, release_command):
            self.assertEqual(self.workflow.count(command), 1)
        self.assertEqual(self.workflow.count(EMULATOR_COMMAND), 2)
        self.assertNotIn("ReactiveCircus/android-emulator-runner@", self.workflow)
        self.assertNotRegex(self.workflow, r"(?i)continue-on-error|\bretr(?:y|ies)\b|\|\|\s*(?:true|:)")
        self.assertNotRegex(self.workflow, r"adb (?:kill-server|reconnect)|logcat[^\n]* -c\b")
        self.assertNotRegex(self.workflow, r"EXPECTED_TEST_COUNT|TEST_SHARD_COUNT|INSTRUMENTATION_TIMEOUT_SECONDS")

    def test_lifecycle_command_blocks_have_no_additional_commands_or_options(self) -> None:
        expected = {
            "connected": [
                "        run: |",
                f"          {EMULATOR_COMMAND} \\",
                "            --api-level ${{ matrix.api-level }} --architecture x86_64 \\",
                "            --output app/build/reports/androidTests/emulator-api-${{ matrix.api-level }}-font-${{ matrix.font-scale }}-locale-${{ matrix.locale }} \\",
                '            --startup-diagnostics "$STARTUP_DIAGNOSTICS" \\',
                "            -- env FONT_SCALE=${{ matrix.font-scale }} TEST_LOCALE=${{ matrix.locale }} TEST_CLASS=${{ matrix.test-class }} .github/scripts/run-instrumented-tests.sh",
            ],
            "release-smoke": [
                "        run: |",
                f"          {EMULATOR_COMMAND} \\",
                "            --api-level ${{ matrix.api-level }} --architecture ${{ matrix.architecture }} \\",
                "            --output release-emulator-results \\",
                "            -- env RUNTIME_ABI=${{ matrix.runtime-abi }} bash .github/scripts/smoke-packaged-release.sh",
            ],
        }
        for job, lines in expected.items():
            with self.subTest(job=job):
                self.assertEqual(self.emulator(job).rstrip().splitlines(), lines)

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
            "scripts/test_release_runtime_abi.py scripts/test_emulator_startup_configuration.py "
            "scripts/test_android_emulator_lifecycle.py -v"
        )
        for job in ("verify", "connected"):
            with self.subTest(job=job):
                documentation = self.step(job, "Check documentation links")
                self.assertIn(f"          {command}\n", documentation)
                self.assertIn("          python3 scripts/check_workflow_pins.py\n", documentation)


if __name__ == "__main__":
    unittest.main()
