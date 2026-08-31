from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "diagnostic_capture_readiness", ROOT / ".github/scripts/android-readiness.py")
READINESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(READINESS)


class DiagnosticCaptureTests(unittest.TestCase):
    def test_capture_brackets_success_and_failure_without_changing_status(self):
        for status in (0, 1, 124, 255):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                session = READINESS.DeviceSession(Path(directory), "clock", 10)
                with patch.object(READINESS, "run_bounded", return_value=status) as command:
                    result = session.capture(["shell", "date", "+%s.%N"], required=False)
                record = json.loads((session.directory / "001.json").read_text())
                self.assertEqual(result, b"")
                self.assertEqual(record["status"], status)
                self.assertEqual(record["command"], ["adb", "shell", "date", "+%s.%N"])
                self.assertLessEqual(record["started_at"], record["finished_at"])
                self.assertLessEqual(record["started_monotonic"], record["finished_monotonic"])
                self.assertEqual(command.call_count, 1)
                self.assertGreater(record["budget"], 0)
                self.assertLessEqual(record["budget"], 10)

    def test_required_command_failure_still_rejects_and_retains_clock(self):
        with tempfile.TemporaryDirectory() as directory:
            session = READINESS.DeviceSession(Path(directory), "failure", 10)
            with patch.object(READINESS, "run_bounded", return_value=255):
                with self.assertRaises(READINESS.HealthFailure):
                    session.capture(["shell", "getprop", "sys.boot_completed"])
            record = json.loads((session.directory / "001.json").read_text())
            self.assertEqual(record["status"], 255)
            self.assertLessEqual(record["started_monotonic"], record["finished_monotonic"])

    def test_failure_capture_prioritizes_clocks_and_gms_without_health_decision(self):
        session = Mock()
        with patch.object(READINESS, "retain_host_context") as host:
            with patch.object(READINESS, "resolve_home", return_value="launcher/.Home"):
                READINESS.collect_diagnostics(session)
        commands = [call.args[0] for call in session.capture.call_args_list]
        self.assertEqual(commands[:3], [
            ["shell", "date", "+%s.%N"], ["shell", "cat", "/proc/uptime"],
            ["shell", "dumpsys", "activity", "exit-info", "com.google.android.gms"],
        ])
        self.assertIn(["shell", "dumpsys", "activity", "exit-info", "launcher"], commands)
        self.assertIn(["shell", "dumpsys", "dropbox", "--print", "system_app_anr"], commands)
        self.assertTrue(all(call.kwargs == {"required": False}
                            for call in session.capture.call_args_list))
        host.assert_called_once_with(session)
        session.record.assert_not_called()

    def test_unavailable_home_does_not_hide_guest_clock_or_gms_diagnostics(self):
        session = Mock()
        with patch.object(READINESS, "retain_host_context"):
            with patch.object(READINESS, "resolve_home", side_effect=READINESS.HealthFailure("offline")):
                READINESS.collect_diagnostics(session)
        session.record.assert_called_once_with("home-unavailable", {"error": "offline"})
        self.assertIn(["shell", "dumpsys", "activity", "exit-info", "com.google.android.gms"],
                      [call.args[0] for call in session.capture.call_args_list])


class StartupWorkflowTests(unittest.TestCase):
    def test_preboot_observer_and_always_finish_share_retained_directory(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        connected = workflow.split("  connected:\n", 1)[1].split("  release-smoke:\n", 1)[0]
        adapter = "python3 .github/scripts/android-startup-diagnostics.py"
        self.assertIn(f'pre-emulator-launch-script: {adapter} start --output "$STARTUP_DIAGNOSTICS"', connected)
        self.assertIn("STARTUP_DIAGNOSTICS: app/build/reports/androidTests/startup-api-", connected)
        self.assertIn("scripts/test_android_diagnostic_capture.py -v", connected)
        finish = connected.split("      - name: Finish Android startup diagnostics\n", 1)[1]
        finish = finish.split("      - name:", 1)[0]
        self.assertIn("        if: always()", finish)
        self.assertIn("        timeout-minutes: 1", finish)
        self.assertIn(f'run: {adapter} stop --output "$STARTUP_DIAGNOSTICS"', finish)
        self.assertNotIn("continue-on-error", connected)
        self.assertLess(connected.index("Finish Android startup diagnostics"),
                        connected.index("Retain connected reports, screenshots, and diagnostics"))
        self.assertIn("app/build/reports/androidTests/", connected)

    def test_observation_keeps_emulator_and_native_selection_unchanged(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        connected = workflow.split("  connected:\n", 1)[1].split("  release-smoke:\n", 1)[0]
        self.assertEqual(connected.count("- api-level:"), 8)
        self.assertEqual(connected.count('test-class: ""'), 4)
        self.assertEqual(connected.count("test-class: me.egigoka.pomodorough.ui.PomodoroughRtlAccessibilityTest"), 4)
        self.assertIn("          cores: 2\n          disable-animations: true", connected)
        self.assertIn("          target: google_apis\n          arch: x86_64\n          profile: pixel_6", connected)
        for forbidden in ("ADB_TRACE", "emulator-options:", "ram-size:", "emulator-build:",
                          "continue-on-error:", "retry", "kill-server", "reconnect"):
            self.assertNotIn(forbidden, connected)
        self.assertIn("FONT_SCALE=${{ matrix.font-scale }} TEST_LOCALE=${{ matrix.locale }} "
                      "TEST_CLASS=${{ matrix.test-class }} .github/scripts/run-instrumented-tests.sh", connected)


if __name__ == "__main__":
    unittest.main()
