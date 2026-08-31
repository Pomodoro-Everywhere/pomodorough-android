"""Offline source-fixture regressions, not native readiness or ADB-cause proof.

Tests use existing documented framing projections. Only transport and time are
simulated in readiness tests; runner tests execute exact phase/cleanup source with
shell command doubles, never the bare runner or a real device. Exact baseline and
hosted-capture replay tests are retained separately in the private evidence packet.
"""

from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest import mock

import test_android_readiness as fixtures


READINESS = fixtures.READINESS
FALLBACK = "com.android.settings/com.android.settings.FallbackHome"
SETUP = READINESS.resolved_component(fixtures.HOSTED_SETUP)
WINDOW_COMMAND = ("shell", "dumpsys", "window")
INPUT_COMMAND = ("shell", "dumpsys", "input")
PROCESS_COMMAND = ("shell", "dumpsys", "activity", "processes")
EVENT_COMMAND = ("logcat", "-b", "events", "-d", "-v", "brief")
RESOLVER_COMMAND = ("shell", "cmd", "package", "resolve-activity", "--brief", "--user", "0",
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")


def fallback_window():
    return fixtures.window_snapshot().replace("com.example.launcher/.Home", FALLBACK).replace(
        "mOwnerUid=10101 showForAllUsers=false package=com.example.launcher",
        "mOwnerUid=1000 showForAllUsers=false package=com.android.settings")


def unfocused_input(api="35"):
    return fixtures.input_snapshot(api).replace(
        "  FocusedWindows:\n    displayId=0, name='a1 com.example.launcher/.Home'",
        "  FocusedWindows: <none>").replace("  FocusRequests: <none>",
        f"  FocusRequests:\n    displayId=0, name='a1 {FALLBACK}' result='NO_WINDOW'")


class StartupFixture:
    def __init__(self, root, module=READINESS):
        self.root = root
        self.module = module
        self.clock = fixtures.Clock()
        self.commands = []
        self.responses = {
            ("wait-for-device",): "",
            ("shell", "settings", "put", "global", "device_provisioned", "1"): "",
            ("shell", "settings", "put", "secure", "user_setup_complete", "1"): "",
            ("shell", "getprop", "ro.build.version.sdk"): "35",
            ("shell", "getprop", "sys.boot_completed"): "1",
            ("shell", "pm", "path", "android"): "package:/system/framework/framework-res.apk",
            tuple(fixtures.STORAGE_COMMAND): "",
            RESOLVER_COMMAND: fixtures.HOME + "\n",
            WINDOW_COMMAND: fallback_window(), INPUT_COMMAND: unfocused_input(),
            PROCESS_COMMAND: fixtures.PROCESSES, EVENT_COMMAND: fixtures.EVENTS,
        }

    def capture(self, command, stdout, stderr, budget):
        if command[0] != "adb" or budget <= 0:
            raise AssertionError(f"Unexpected command or budget: {command!r}, {budget}")
        arguments = tuple(command[1:])
        self.commands.append(arguments)
        response = self.responses[arguments]
        response = response() if callable(response) else response
        status, content = response if isinstance(response, tuple) else (0, response)
        stdout.write(content.encode() if isinstance(content, str) else content)
        return status

    def install(self, testcase):
        for attribute, replacement in (("time", self.clock), ("run_bounded", self.capture)):
            patch = mock.patch.object(self.module, attribute, replacement)
            patch.start()
            testcase.addCleanup(patch.stop)
        return self

    def session(self, seconds=600):
        return self.module.DeviceSession(self.root, "startup-fixture", seconds)

    def transition_at(self, second):
        self.responses[WINDOW_COMMAND] = lambda: (
            fallback_window() if self.clock.now < second else fixtures.window_snapshot())
        self.responses[INPUT_COMMAND] = lambda: (
            unfocused_input() if self.clock.now < second else fixtures.INPUT)
        self.responses[RESOLVER_COMMAND] = lambda: (
            fixtures.HOSTED_SETUP if self.clock.now < second else fixtures.HOME + "\n")


class StartupTestCase(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="android-startup-phase-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.device = StartupFixture(self.root).install(self)

    def observations(self, session):
        return [json.loads(member.read_text()) for member in sorted(session.directory.glob("sample-*.json"))]

    def assert_startup_failure(self, expected_message):
        session = self.device.session()
        with self.assertRaisesRegex(READINESS.HealthFailure, expected_message):
            READINESS.wait_for_health(session, "HOME", True)
        self.assertFalse(session.startup_home)
        self.assertEqual(self.observations(session), [])


class StartupPhaseTests(StartupTestCase):
    def test_authenticated_fallback_is_never_a_healthy_window(self):
        self.assertIsNone(READINESS.window_focus(fallback_window(), startup_home=True))
        with self.assertRaisesRegex(READINESS.HealthFailure, "startup HOME"):
            READINESS.window_focus(fallback_window())
        session = self.device.session()
        session.startup_home = True
        for expected in (fixtures.HOME, SETUP, FALLBACK):
            with self.subTest(expected=expected):
                self.assertFalse(READINESS.health_sample(session, expected))
        self.device.responses[INPUT_COMMAND] = fixtures.INPUT.replace("com.example.launcher/.Home", FALLBACK)
        self.assertFalse(READINESS.health_sample(session, FALLBACK))

    def test_transient_then_real_home_requires_all_eighty_stable_seconds(self):
        self.device.transition_at(40)
        session = self.device.session()
        READINESS.wait_for_health(session, "HOME", True)
        self.assertEqual(self.device.clock.now, 120)
        self.assertEqual(session.deadline, 600)
        self.assertFalse(session.startup_home)
        observations = self.observations(session)
        self.assertEqual([sample["healthy"] for sample in observations], [False] * 2 + [True] * 5)
        self.assertEqual([sample["expected"] for sample in observations], [SETUP] * 2 + [fixtures.HOME] * 5)

    def test_perpetual_fallback_times_out_at_original_absolute_deadline(self):
        session = self.device.session()
        with self.assertRaisesRegex(READINESS.HealthFailure, "absolute deadline"):
            READINESS.wait_for_health(session, "HOME", True)
        self.assertEqual(self.device.clock.now, 600)
        self.assertEqual(session.deadline, 600)
        self.assertFalse(session.startup_home)
        self.assertEqual([sample["healthy"] for sample in self.observations(session)], [False] * 30)
        self.assertEqual(self.device.commands.count(PROCESS_COMMAND), 30)

    def test_fallback_resets_accumulated_stability_without_extending_deadline(self):
        self.device.responses[WINDOW_COMMAND] = lambda: (
            fallback_window() if self.device.clock.now == 60 else fixtures.window_snapshot())
        self.device.responses[INPUT_COMMAND] = lambda: (
            unfocused_input() if self.device.clock.now == 60 else fixtures.INPUT)
        session = self.device.session()
        READINESS.wait_for_health(session, "HOME", True)
        self.assertEqual(self.device.clock.now, 160)
        self.assertEqual(session.deadline, 600)
        self.assertEqual([sample["healthy"] for sample in self.observations(session)],
                         [True] * 3 + [False] + [True] * 5)

    def test_eighty_seconds_reached_at_deadline_is_not_accepted(self):
        self.device.transition_at(40)
        session = self.device.session(120)
        with self.assertRaisesRegex(READINESS.HealthFailure, "absolute deadline"):
            READINESS.wait_for_health(session, "HOME", True)
        self.assertEqual(self.device.clock.now, 120)
        self.assertEqual(session.deadline, 120)
        self.assertFalse(session.startup_home)

    def test_context_does_not_leak_to_application_or_nonstartup_checks(self):
        self.device.transition_at(0)
        session = self.device.session()
        READINESS.wait_for_health(session, "HOME", True)
        self.device.responses[WINDOW_COMMAND] = fallback_window()
        for expected, startup in (("HOME", False), (fixtures.HOME, False), (fixtures.HOME, True),
                                  (FALLBACK, False), (FALLBACK, True)):
            with self.subTest(expected=expected, startup=startup):
                with self.assertRaisesRegex(READINESS.HealthFailure, "startup HOME"):
                    READINESS.wait_for_health(session, expected, startup)
                self.assertFalse(session.startup_home)
        with self.assertRaisesRegex(READINESS.HealthFailure, "startup HOME"):
            READINESS.health_sample(session, fixtures.HOME)

    def test_unknown_mismatched_and_forged_owners_remain_fatal(self):
        window = fallback_window()
        cases = {
            "uid999": window.replace("mOwnerUid=1000", "mOwnerUid=999"),
            "uid1001": window.replace("mOwnerUid=1000", "mOwnerUid=1001"),
            "app_uid": window.replace("mOwnerUid=1000", "mOwnerUid=10101"),
            "mismatch": window.replace("package=com.android.settings", "package=com.attacker"),
            "unknown": window.replace("Window #0 Window{a1", "Window #0 Window{a2"),
            "arbitrary_system": fixtures.window_snapshot().replace("mOwnerUid=10101", "mOwnerUid=1000"),
            "other_settings": window.replace(".FallbackHome", ".Settings"),
            "fake_component": window.replace(FALLBACK, "com.attacker/com.android.settings.FallbackHome"),
            "fake_suffix": window.replace(".FallbackHome", ".FallbackHomeFake"),
            "owner_grammar": window.replace("mOwnerUid=1000", "mOwnerUid=system"),
            "duplicate": window.replace("    mOwnerUid=1000", "    mOwnerUid=1000\n    mOwnerUid=1000"),
            "wrong_user": window.replace("u0 ", "u1 "),
            "bad_component": window.replace(FALLBACK, "com.android.settings//FallbackHome"),
            "truncated": window[:-1],
        }
        for name, snapshot in cases.items():
            with self.subTest(name=name), self.assertRaises(READINESS.HealthFailure):
                READINESS.window_focus(snapshot, startup_home=True)

    def test_transient_keeps_every_downstream_input_process_and_event_veto(self):
        cases = (
            (INPUT_COMMAND, unfocused_input().replace("DispatchFrozen: false", "DispatchFrozen: true")),
            (INPUT_COMMAND, unfocused_input().replace("responsive=true", "responsive=false")),
            (INPUT_COMMAND, unfocused_input().replace("  FocusedWindows: <none>", "  FocusedWindows: garbage")),
            (INPUT_COMMAND, unfocused_input().split("  Connections:", 1)[0]),
            (INPUT_COMMAND, fixtures.input_history(unfocused_input(), "Input Dispatcher State at time of last ANR:\n")),
            (PROCESS_COMMAND, fixtures.PROCESSES.replace("    mountMode=DEFAULT", "    bad=true")),
            (PROCESS_COMMAND, fixtures.PROCESSES.replace("    mountMode=DEFAULT", "    mCrashing=false mCrashDialog=Dialog{}")),
            (PROCESS_COMMAND, fixtures.PROCESSES.replace("    mountMode=DEFAULT", "    mNotResponding=true")),
            (PROCESS_COMMAND, fixtures.PROCESSES[:-1]),
            (EVENT_COMMAND, fixtures.EVENTS + "I/am_anr  ( 123): [0,123,com.android.settings]\n"),
            (EVENT_COMMAND, fixtures.EVENTS + "I/am_crash( 123): [0,123,com.android.settings]\n"),
            (EVENT_COMMAND, fixtures.EVENTS[:-1]),
            (WINDOW_COMMAND, fallback_window().replace("<no ANR has occurred since boot>", "ANR in com.android.settings")),
            (WINDOW_COMMAND, fallback_window().replace("mScreenOnFully=true", "mScreenOnFully=false")),
        )
        for command, snapshot in cases:
            original = self.device.responses[command]
            with self.subTest(command=command, snapshot=snapshot[:100]):
                self.device.responses[command] = snapshot
                self.assert_startup_failure(".")
            self.device.responses[command] = original

    def test_nonready_boot_still_validates_input_and_processes(self):
        self.device.responses[("shell", "getprop", "sys.boot_completed")] = "0"
        self.device.responses[PROCESS_COMMAND] = ""
        self.assert_startup_failure("process|dump")

    def test_transport_failure_is_fatal_once_and_clears_startup_context(self):
        for command in (WINDOW_COMMAND, INPUT_COMMAND, PROCESS_COMMAND, EVENT_COMMAND):
            original = self.device.responses[command]
            self.device.commands.clear()
            with self.subTest(command=command):
                self.device.responses[command] = (255, "")
                self.assert_startup_failure(r"Device command failed \(255\)")
                self.assertEqual(self.device.commands.count(command), 1)
            self.device.responses[command] = original

    def test_both_supported_input_profiles_keep_transient_nonready(self):
        for api in ("35", "36"):
            with self.subTest(api=api):
                self.device.responses[INPUT_COMMAND] = unfocused_input(api)
                session = self.device.session()
                session.startup_home = True
                self.assertFalse(READINESS.health_sample(session, fixtures.HOME))


def shell_function(source, name):
    matches = re.findall(rf"^{name}\(\) \{{\n.*?^\}}", source, re.MULTILINE | re.DOTALL)
    if len(matches) != 1:
        raise AssertionError(f"Missing or ambiguous runner function: {name}")
    return matches[0] + "\n"


def startup_block(source):
    return source.split("logcat_pid=$!\n", 1)[1].split("\ndevice_command uninstall-tests", 1)[0]


SHELL_DOUBLES = r"""
original_font_scale=""
font_scale_captured=0
font_scale=2.0
logcat_pid=""
require_android_health() {
  printf 'health %s\n' "$*" >> trace
  if [[ "$BOOT_STATUS" -ne 0 ]]; then return "$BOOT_STATUS"; fi
  : > booted
}
device_command() {
  printf 'device %s\n' "$*" >> trace
  case "$1" in
    original-font)
      if [[ ! -f booted ]]; then echo 'transport 255' >> trace; return 1; fi
      if [[ "$READ_STATUS" -ne 0 ]]; then echo "transport $READ_STATUS" >> trace; return 1; fi
      printf '%s\r\n' "$READ_VALUE" ;;
    set-font) return "$SET_STATUS" ;;
    verify-font)
      if [[ "$VERIFY_STATUS" -ne 0 ]]; then return 1; fi
      printf '2.0\r\n' ;;
    restore-font) return "$RESTORE_STATUS" ;;
    *) return 97 ;;
  esac
}
collect_failure_diagnostics() { echo diagnostics >> trace; return 79; }
stop_log_capture() { echo stop >> trace; }
"""


class FontPhaseTests(unittest.TestCase):
    def setUp(self):
        self.source = fixtures.RUNNER.read_text()

    def run_phase(self, source=None, **options):
        source = self.source if source is None else source
        script = "set -euo pipefail\n" + SHELL_DOUBLES
        script += shell_function(source, "restore_font_scale") + shell_function(source, "cleanup")
        script += "trap cleanup EXIT\n" + startup_block(source) + "\necho completed >> trace\n"
        environment = {"BOOT_STATUS": "0", "READ_STATUS": "0", "READ_VALUE": "1.3",
                       "SET_STATUS": "0", "VERIFY_STATUS": "0", "RESTORE_STATUS": "0", **options}
        with tempfile.TemporaryDirectory(prefix="android-font-phase-") as temporary:
            root = Path(temporary)
            binary = root / "bin"
            binary.mkdir()
            (binary / "tr").symlink_to("/usr/bin/tr")
            environment.update(PATH=str(binary), HOME=str(root), TMPDIR=str(root))
            result = subprocess.run(["/bin/bash", "-c", script], cwd=root, env=environment,
                                    capture_output=True, text=True, timeout=10)
            trace = (root / "trace").read_text().splitlines()
        self.assertEqual(result.stderr, "")
        return result.returncode, trace

    def test_font_read_and_captured_flag_follow_boot_before_first_mutation(self):
        status, trace = self.run_phase()
        self.assertEqual(status, 0)
        self.assertEqual(trace, ["health boot HOME --startup",
                                "device original-font shell settings get system font_scale",
                                "device set-font shell settings put system font_scale 2.0",
                                "device verify-font shell settings get system font_scale", "completed",
                                "device restore-font shell settings put system font_scale 1.3", "stop"])
        block = startup_block(self.source)
        self.assertLess(block.index("require_android_health boot"), block.index("original_font_scale="))
        self.assertLess(block.index("original_font_scale="), block.index("font_scale_captured=1"))
        self.assertLess(block.index("font_scale_captured=1"), block.index("device_command set-font"))

    def test_boot_failure_never_reads_captures_changes_or_restores_font(self):
        status, trace = self.run_phase(BOOT_STATUS="42")
        self.assertEqual(status, 42)
        self.assertEqual(trace, ["health boot HOME --startup", "diagnostics", "stop"])

    def test_postboot_transport_failure_is_fatal_without_retry_mutation_or_restore(self):
        status, trace = self.run_phase(READ_STATUS="255")
        self.assertEqual(status, 1)
        self.assertEqual(trace, ["health boot HOME --startup",
                                "device original-font shell settings get system font_scale",
                                "transport 255", "diagnostics", "stop"])

    def test_cleanup_restores_or_deletes_captured_value_and_preserves_failures(self):
        for value in ("1.3", "null", ""):
            for failed_phase in ("SET_STATUS", "VERIFY_STATUS"):
                for restore_status in ("0", "83"):
                    with self.subTest(value=value, failed_phase=failed_phase, restore=restore_status):
                        status, trace = self.run_phase(READ_VALUE=value, RESTORE_STATUS=restore_status,
                                                      **{failed_phase: "42"})
                        self.assertEqual(status, 42 if failed_phase == "SET_STATUS" else 1)
                        restore = ("device restore-font shell settings delete system font_scale"
                                   if value in ("null", "") else
                                   "device restore-font shell settings put system font_scale 1.3")
                        self.assertEqual(trace[-3:], ["diagnostics", restore, "stop"])
                        self.assertNotIn("completed", trace)

    def test_restore_failure_turns_success_into_failure(self):
        status, trace = self.run_phase(RESTORE_STATUS="83")
        self.assertEqual(status, 83)
        self.assertEqual(trace[-3:], ["completed",
                                    "device restore-font shell settings put system font_scale 1.3", "stop"])

if __name__ == "__main__":
    unittest.main()
