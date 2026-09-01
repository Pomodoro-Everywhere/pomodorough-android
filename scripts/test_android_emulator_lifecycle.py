"""Exercise the real lifecycle with mocked processes, signals, sockets and time.

Only private temporary SDK markers/artifacts are created. No SDK command, ADB,
emulator, Gradle, package installation, network connection or account is used.
These checks are orchestration regressions, not native Android acceptance.
"""

from __future__ import annotations

import ast
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SOURCE = Path(__file__).resolve().parents[1] / ".github/scripts/run-android-emulator.py"
SPEC = importlib.util.spec_from_file_location("owned_android_emulator", SOURCE)
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)
STRICT = ["env", "FONT_SCALE=1.0", "TEST_LOCALE=en-US", "TEST_CLASS=",
          ".github/scripts/run-instrumented-tests.sh"]
ANR = "Input dispatching timed out: no focused window; KEY waited 5000ms\n"


class FixtureClock:
    def __init__(self):
        self.now = 1000.0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def wall(self):
        return 1700000000 + self.now

    def sleep(self, seconds):
        if not 0 <= seconds <= RUNNER.POLL_SECONDS:
            raise AssertionError(f"Unbounded sleep: {seconds}")
        self.sleeps.append(seconds)
        self.now += seconds


class FixtureProcess:
    def __init__(self, runtime, name, argv, outcome, streams):
        self.runtime = runtime
        self.name = name
        self.args = list(argv)
        self.outcome = outcome
        self.pid = 900000 + len(runtime.processes)
        self.returncode = None
        self.group_alive = True
        self.communications = []
        streams["stdout"].write(outcome.get("stdout", f"{name} stdout\n".encode()))
        streams["stderr"].write(outcome.get("stderr", f"{name} stderr\n".encode()))

    def communicate(self, input=None, timeout=None):
        if timeout is None or not 0 < timeout <= RUNNER.CHILD_SECONDS:
            raise AssertionError(f"Unbounded command: {self.args}")
        self.communications.append({"input": input, "timeout": timeout})
        self.runtime.events.append(("wait", self.name, timeout))
        if self.outcome.get("timeout"):
            self.runtime.clock.now += timeout
            raise subprocess.TimeoutExpired(self.args, timeout)
        self.runtime.clock.now += self.outcome.get("elapsed", 0)
        if "cancel" in self.outcome:
            RUNNER.cancel(self.outcome["cancel"], None)
        if "error" in self.outcome:
            raise self.outcome["error"]
        if "emulator_exit" in self.outcome:
            emulator = self.runtime.named("emulator-console")[-1]
            emulator.returncode = self.outcome["emulator_exit"]
            emulator.group_alive = False
        self.returncode = self.outcome.get("returncode", 0)
        self.group_alive = self.outcome.get("descendants", False)
        return None, None

    def poll(self):
        if "exit_on_poll" in self.outcome:
            self.returncode = self.outcome["exit_on_poll"]
            self.group_alive = False
        return self.returncode

    def wait(self, timeout=None):
        if timeout != RUNNER.STOP_SECONDS:
            raise AssertionError("Cleanup wait must be bounded")
        if self.outcome.get("wait_timeout"):
            self.runtime.clock.now += timeout
            raise subprocess.TimeoutExpired(self.args, timeout)
        return self.returncode


class FixtureRuntime:
    def __init__(self, clock):
        self.clock = clock
        self.processes = []
        self.calls = []
        self.events = []
        self.outcomes = {}
        self.injected_keys = 0

    def named(self, name):
        return [process for process in self.processes if process.name == name]

    def popen(self, argv, **keywords):
        if keywords.get("shell") or not keywords.get("start_new_session") or not keywords.get("close_fds"):
            raise AssertionError("Subprocess must use an argv array in its own session")
        name = Path(keywords["stdout"].name).parent.name.split("-", 1)[1]
        self.calls.append({"name": name, "argv": list(argv), "env": dict(keywords["env"])})
        self.events.append(("launch", name))
        outcomes = self.outcomes.get(name, {})
        outcome = outcomes.pop(0) if isinstance(outcomes, list) else outcomes
        if name == "boot-property":
            outcome = {"stdout": b"1\n", **outcome}
        if any(token in argv for token in ("keyevent", "sendevent", "monkey")) or "shell input" in " ".join(argv):
            self.injected_keys += 1
            outcome = {"timeout": True, "stderr": ANR.encode()}
        if "launch_error" in outcome:
            raise outcome["launch_error"]
        process = FixtureProcess(self, name, argv, outcome, keywords)
        self.processes.append(process)
        return process

    def killpg(self, pid, signum):
        matching = [process for process in self.processes if process.pid == pid]
        if not matching:
            raise AssertionError("Attempted to signal an unowned process group")
        process = matching[0]
        self.events.append(("signal", process.name, signum))
        if process.outcome.get("signal_error"):
            raise PermissionError("fixture cleanup permission denied")
        if not process.group_alive:
            raise ProcessLookupError("fixture group gone")
        if signum == 0:
            return
        if signum == signal.SIGTERM and process.outcome.get("stubborn"):
            process.returncode = 0
            return
        process.group_alive = False
        if process.returncode is None:
            process.returncode = -signum


class EmulatorLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="emulator-lifecycle-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.sdk = self.root / "sdk"
        self.bin = self.sdk / "cmdline-tools/14742923/bin"
        for filename in ("platform-tools/adb", "emulator/emulator", "cmdline-tools/14742923/bin/sdkmanager",
                         "cmdline-tools/14742923/bin/avdmanager"):
            destination = self.sdk / filename
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text("fixture marker; must never execute\n", encoding="utf-8")
            destination.chmod(0o700)
        self.clock = FixtureClock()
        self.runtime = FixtureRuntime(self.clock)
        self.patch(RUNNER.subprocess, "Popen", side_effect=self.runtime.popen)
        self.patch(RUNNER.os, "killpg", side_effect=self.runtime.killpg)
        self.sockets = self.patch(RUNNER.socket, "socket")
        self.patch(RUNNER.time, "monotonic", self.clock.monotonic)
        self.patch(RUNNER.time, "time", self.clock.wall)
        self.patch(RUNNER.time, "sleep", self.clock.sleep)
        self.signals = self.patch(RUNNER.signal, "signal", return_value=signal.SIG_DFL)
        original_mkdtemp = tempfile.mkdtemp
        self.patch(RUNNER.tempfile, "mkdtemp", side_effect=lambda **options: original_mkdtemp(dir=self.root, **options))
        environment = {"ANDROID_HOME": str(self.sdk), "PATH": str(self.bin), "JAVA_HOME": str(self.root / "java"),
                       "HOME": str(self.root / "home"), "FONT_SCALE": "2.0", "TEST_LOCALE": "ar-XB",
                       "TEST_CLASS": "fixture.Test", "RUNTIME_ABI": "arm64-v8a"}
        self.environ = mock.patch.dict(os.environ, environment, clear=True)
        self.environ.start()
        self.addCleanup(self.environ.stop)
        self.output = self.root / "reports/emulator"
        self.stderr = io.StringIO()

    def patch(self, owner, name, *arguments, **keywords):
        patcher = mock.patch.object(owner, name, *arguments, **keywords)
        self.addCleanup(patcher.stop)
        return patcher.start()

    def invoke(self, api=35, architecture="x86_64", diagnostics=False, command=None):
        argv = ["--api-level", str(api), "--architecture", architecture, "--output", str(self.output)]
        if diagnostics:
            argv.extend(["--startup-diagnostics", str(self.root / "passive")])
        with contextlib.redirect_stderr(self.stderr):
            return RUNNER.main([*argv, "--", *(STRICT if command is None else command)])

    def records(self, name=None):
        paths = sorted(self.output.glob("*/command.json"))
        if name is not None:
            paths = [filename for filename in paths if filename.parent.name.split("-", 1)[1] == name]
        return [json.loads(filename.read_text(encoding="utf-8")) for filename in paths]

    def result(self):
        return json.loads((self.output / "lifecycle.json").read_text(encoding="utf-8"))

    def labels(self):
        return [call["name"] for call in self.runtime.calls]

    def assert_failure(self, status, diagnostics=True):
        self.assertEqual(self.result()["exit_status"], status)
        self.assertEqual(self.result()["status"], "failed")
        self.assertNotIn("strict-command", self.labels())
        if diagnostics:
            self.assertEqual([name for name in self.labels() if name.startswith("failure-")],
                             [f"failure-{name}" for name, _ in RUNNER.DIAGNOSTICS])
            stop = self.runtime.events.index(("signal", "emulator-console", signal.SIGTERM))
            last_capture = self.runtime.events.index(("launch", "failure-input"))
            self.assertLess(last_capture, stop)
        self.assertFalse(any(process.group_alive for process in self.runtime.processes))

    def test_exact_startup_order_arguments_and_retained_streams(self):
        self.assertEqual(self.invoke(diagnostics=True), 0)
        self.assertEqual(self.labels(), ["sdk-install", "avd-create", "adb-start", "startup-diagnostics",
                         "emulator-console", "wait-for-device", "boot-property", "dismiss-keyguard",
                         *RUNNER.ANIMATIONS, "strict-command", "avd-remove"])
        result = self.result()
        avd_root = Path(result["avd_root"])
        image = "system-images;android-35;google_apis;x86_64"
        expected = [[str(self.bin / "sdkmanager"), f"--sdk_root={self.sdk}", "platforms;android-35",
                     "emulator", image, "build-tools;37.0.0"],
                    [str(self.bin / "avdmanager"), "create", "avd", "--name", avd_root.name, "--package", image,
                     "--device", "pixel_6", "--path", str(avd_root / "avd" / f"{avd_root.name}.avd")],
                    [str(self.sdk / "platform-tools/adb"), "start-server"],
                    [sys.executable, str(SOURCE.with_name("android-startup-diagnostics.py")),
                     "start", "--output", str(self.root / "passive")],
                    [str(self.sdk / "emulator/emulator"), "-avd", avd_root.name, "-port", "5554", "-cores", "4",
                     "-memory", "4096", "-no-window", "-gpu", "swiftshader_indirect", "-no-snapshot",
                     "-noaudio", "-no-boot-anim"]]
        adb = [str(self.sdk / "platform-tools/adb"), "-s", "emulator-5554"]
        expected.extend([[*adb, "wait-for-device"], [*adb, "shell", "getprop", "sys.boot_completed"],
                         [*adb, "shell", "wm", "dismiss-keyguard"]])
        expected.extend([*adb, "shell", "settings", "put", "global", name, "0"] for name in RUNNER.ANIMATIONS)
        expected.extend([STRICT, [sys.executable, "-c", "import shutil, sys; shutil.rmtree(sys.argv[1])", str(avd_root)]])
        self.assertEqual([call["argv"] for call in self.runtime.calls], expected)
        for record, call in zip(self.records(), self.runtime.calls):
            self.assertEqual(record["argv"], call["argv"])
            self.assertIsNotNone(record["returncode"])
            self.assertLessEqual(record["started"]["monotonic_seconds"], record["finished"]["monotonic_seconds"])
            self.assertIn("wall_seconds", record["started"])
            self.assertIn("wall_seconds", record["finished"])
        for directory in self.output.iterdir():
            if directory.is_dir():
                name = directory.name.split("-", 1)[1]
                for stream in ("stdout", "stderr"):
                    expected = "1\n" if (name, stream) == ("boot-property", "stdout") else f"{name} {stream}\n"
                    self.assertEqual((directory / f"{stream}.txt").read_text(encoding="utf-8"), expected)

    def test_fixed_hardware_flags_and_timeout_constants(self):
        self.assertEqual((RUNNER.CORES, RUNNER.MEMORY_MB, RUNNER.PROFILE, RUNNER.TARGET), (4, 4096, "pixel_6", "google_apis"))
        self.assertEqual(RUNNER.EMULATOR_FLAGS, ("-no-window", "-gpu", "swiftshader_indirect", "-no-snapshot", "-noaudio", "-no-boot-anim"))
        self.assertEqual((RUNNER.PORT, RUNNER.SERIAL, RUNNER.BOOT_SECONDS), (5554, "emulator-5554", 600))
        self.assertEqual(RUNNER.ANIMATIONS, ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"))
        self.assertEqual(self.invoke(), 0)
        for name, seconds in (("sdk-install", 600), ("avd-create", 120), ("wait-for-device", 600),
                              ("boot-property", 30), ("strict-command", 2700), ("avd-remove", 30)):
            self.assertEqual(self.records(name)[0]["timeout_seconds"], seconds)

    def test_all_eight_connected_cells_preserve_image_and_command_environment(self):
        for locale in ("en-US", "ar-XB"):
            for api in (35, 36):
                for scale in ("1.0", "2.0"):
                    with self.subTest(locale=locale, api=api, scale=scale):
                        self.output = self.root / f"connected-{locale}-{api}-{scale}"
                        test_class = "" if locale == "en-US" else "me.egigoka.pomodorough.ui.PomodoroughRtlAccessibilityTest"
                        command = ["env", f"FONT_SCALE={scale}", f"TEST_LOCALE={locale}", f"TEST_CLASS={test_class}", STRICT[-1]]
                        self.assertEqual(self.invoke(api=api, command=command), 0)
                        self.assertEqual(self.result()["system_image"], f"system-images;android-{api};google_apis;x86_64")
                        self.assertEqual(self.records("strict-command")[0]["argv"], command)
                        self.assertEqual(len(self.records("emulator-console")), 1)

    def test_all_four_release_cells_preserve_host_image_and_runtime_abi(self):
        for api, architecture, abi in ((30, "x86", "x86"), (36, "x86_64", "x86_64"),
                                       (30, "x86", "armeabi-v7a"), (30, "x86_64", "arm64-v8a")):
            with self.subTest(abi=abi):
                self.output = self.root / f"release-emulator-results-{abi}"
                command = ["env", f"RUNTIME_ABI={abi}", "bash", ".github/scripts/smoke-packaged-release.sh"]
                self.assertEqual(self.invoke(api, architecture, command=command), 0)
                self.assertEqual(self.result()["system_image"], f"system-images;android-{api};google_apis;{architecture}")
                self.assertEqual(self.records("strict-command")[0]["argv"], command)
                self.assertEqual(len(self.records("strict-command")), 1)

    def test_environment_uses_actual_sdk_preserves_workflow_values_and_isolates_avd(self):
        os.environ.update(ANDROID_SERIAL="physical-device", EMULATOR_PORT="5580", ANDROID_SDK_ROOT="/unused-sdk",
                          ANDROID_AVD_HOME="/stale-avd", ANDROID_USER_HOME="/stale-user",
                          ANDROID_EMULATOR_HOME="/stale-emulator", ANDROID_SDK_HOME="/stale-sdk-home")
        self.assertEqual(self.invoke(), 0)
        for call in self.runtime.calls:
            environment = call["env"]
            self.assertEqual(environment["ANDROID_SERIAL"], "emulator-5554")
            self.assertEqual(environment["EMULATOR_PORT"], "5554")
            self.assertEqual(environment["ANDROID_SDK_ROOT"], str(self.sdk))
            self.assertEqual(environment["PATH"].split(os.pathsep),
                             [str(self.sdk / "platform-tools"), str(self.sdk / "emulator"), str(self.bin)])
            self.assertEqual([environment[name] for name in ("FONT_SCALE", "TEST_LOCALE", "TEST_CLASS", "RUNTIME_ABI")],
                             ["2.0", "ar-XB", "fixture.Test", "arm64-v8a"])
            for name in ("ANDROID_AVD_HOME", "ANDROID_USER_HOME", "ANDROID_EMULATOR_HOME"):
                self.assertTrue(Path(environment[name]).is_relative_to(Path(self.result()["avd_root"])))

    def test_conflicting_preference_aliases_removed_without_changing_unrelated_environment(self):
        aliases = {"ANDROID_USER_HOME": str(self.root / "inherited-user"),
                   "ANDROID_SDK_HOME": str(self.root / "inherited-sdk-home"),
                   "ANDROID_PREFS_ROOT": str(self.root / "inherited-prefs-root")}
        for folder in aliases.values():
            Path(folder).mkdir()
        os.environ.update(aliases, ANDROID_UNRELATED="keep unrelated value",
                          ANDROID_SDK_HOME_EXTRA="keep sdk prefix", ANDROID_PREFS_ROOT_EXTRA="keep prefs prefix")
        inherited = os.environ.copy()
        self.assertEqual(self.invoke(diagnostics=True), 0)
        self.assertEqual(dict(os.environ), inherited)
        avd_root = Path(self.result()["avd_root"])
        for call in self.runtime.calls:
            with self.subTest(command=call["name"]):
                environment = call["env"]
                self.assertNotIn("ANDROID_SDK_HOME", environment)
                self.assertNotIn("ANDROID_PREFS_ROOT", environment)
                self.assertEqual(environment["ANDROID_USER_HOME"], str(avd_root / "user"))
                self.assertEqual(environment["ANDROID_AVD_HOME"], str(avd_root / "avd"))
                self.assertEqual(environment["ANDROID_EMULATOR_HOME"], str(avd_root / "emulator"))
                self.assertEqual(environment["ANDROID_HOME"], str(self.sdk))
                self.assertEqual(environment["ANDROID_SDK_ROOT"], str(self.sdk))
                for name, value in inherited.items():
                    if name not in {*aliases, "PATH"}:
                        self.assertEqual(environment[name], value)

    def test_new_private_avd_each_run_never_reuses_stale_home(self):
        stale = self.root / "home/.android/avd/ci.avd/config.ini"
        stale.parent.mkdir(parents=True)
        stale.write_text("untouched stale AVD", encoding="utf-8")
        self.assertEqual(self.invoke(), 0)
        first = self.result()["avd_root"]
        self.output = self.root / "second"
        self.assertEqual(self.invoke(), 0)
        self.assertNotEqual(first, self.result()["avd_root"])
        self.assertEqual(stale.read_text(encoding="utf-8"), "untouched stale AVD")
        for process in self.runtime.named("avd-create"):
            self.assertNotIn("--force", process.args)
            self.assertEqual(process.communications[0]["input"], b"no\n")

    def test_existing_output_fails_without_modification_or_launch(self):
        self.output.mkdir(parents=True)
        marker = self.output / "existing"
        marker.write_text("preserve", encoding="utf-8")
        self.assertEqual(self.invoke(), 1)
        self.assertEqual(marker.read_text(encoding="utf-8"), "preserve")
        self.assertEqual(self.runtime.calls, [])

    def test_no_focus_does_not_trigger_injected_key_timeout_or_relaunch(self):
        self.runtime.outcomes["dismiss-keyguard"] = {"stdout": b"Direct WM dismiss; no focused window\n"}
        self.assertEqual(self.invoke(), 0)
        self.assertEqual(self.runtime.injected_keys, 0)
        self.assertEqual(self.clock.now, 1000)
        self.assertEqual(len(self.runtime.named("emulator-console")), 1)
        self.assertEqual(len(self.runtime.named("strict-command")), 1)
        self.assertTrue(self.result()["boot_completed"])
        self.assertEqual(self.result()["health_assessment"], "not_performed")

    def test_nonready_empty_and_zero_then_ready_only_poll_boot_property(self):
        self.runtime.outcomes["boot-property"] = [{"stdout": b"\r\n"}, {"stdout": b"0\r\n"}, {"stdout": b"1\r\n"}]
        self.assertEqual(self.invoke(), 0)
        self.assertEqual(self.clock.sleeps, [2, 2])
        self.assertEqual(len(self.records("boot-property")), 3)
        self.assertEqual(len(self.records("wait-for-device")), 1)
        self.assertEqual(len(self.records("dismiss-keyguard")), 1)

    def test_benign_nonready_has_absolute_600_second_deadline(self):
        self.runtime.outcomes["boot-property"] = {"stdout": b"0\n"}
        self.assertEqual(self.invoke(), 124)
        self.assertEqual(self.clock.now, 1600)
        self.assertEqual(len(self.records("boot-property")), 300)
        self.assertNotIn("dismiss-keyguard", self.labels())
        self.assert_failure(124)

    def test_transport_wait_consumes_same_boot_deadline(self):
        self.runtime.outcomes["wait-for-device"] = {"elapsed": 599}
        self.runtime.outcomes["boot-property"] = {"timeout": True}
        self.assertEqual(self.invoke(), 124)
        self.assertEqual(self.records("boot-property")[0]["timeout_seconds"], 1)
        self.assertEqual(self.clock.now, 1600)
        self.assert_failure(124)

    def test_late_ready_response_cannot_pass_deadline(self):
        self.runtime.outcomes["wait-for-device"] = {"elapsed": 599}
        self.runtime.outcomes["boot-property"] = {"elapsed": 1, "stdout": b"1\n"}
        self.assertEqual(self.invoke(), 124)
        self.assertFalse(self.result()["boot_completed"])
        self.assert_failure(124)

    def test_invalid_and_offline_boot_responses_fail_closed_without_retry(self):
        for index, response in enumerate((b"offline\n", b"error: device offline\n", b"2\n", b"1\n0\n", b" " * 65)):
            with self.subTest(response=response):
                self.output = self.root / f"bad-boot-{index}"
                self.runtime.outcomes["boot-property"] = {"stdout": response}
                self.assertEqual(self.invoke(), 1)
                self.assertEqual(len(self.records("boot-property")), 1)
                self.assertFalse(self.result()["child_invoked"])

    def test_nonzero_offline_with_ready_stdout_still_fails(self):
        self.runtime.outcomes["boot-property"] = {"returncode": 7, "stdout": b"1\n", "stderr": b"error: device offline\n"}
        self.assertEqual(self.invoke(), 7)
        self.assertEqual(len(self.records("boot-property")), 1)
        self.assertEqual(self.records("boot-property")[0]["status"], "failed")
        self.assert_failure(7)

    def test_boot_command_timeout_never_retries_or_injects_input(self):
        self.runtime.outcomes["boot-property"] = {"timeout": True}
        self.assertEqual(self.invoke(), 124)
        self.assertEqual(self.clock.now, 1030)
        self.assertEqual(len(self.records("boot-property")), 1)
        self.assertEqual(self.runtime.injected_keys, 0)
        self.assert_failure(124)

    def test_transport_nonzero_and_timeout_stop_before_property_poll(self):
        for label, outcome, status in (("offline", {"returncode": 3}, 3), ("deadline", {"timeout": True}, 124)):
            with self.subTest(label=label):
                self.output = self.root / label
                self.runtime.outcomes["wait-for-device"] = outcome
                self.assertEqual(self.invoke(), status)
                self.assertEqual(self.records("boot-property"), [])
                self.assertFalse(self.result()["child_invoked"])

    def test_sdk_and_avd_failures_never_launch_emulator_or_child(self):
        for name in ("sdk-install", "avd-create"):
            for outcome, status in (({"returncode": 19}, 19), ({"timeout": True}, 124),
                                    ({"launch_error": OSError("fixture launch failed")}, 1)):
                with self.subTest(name=name, status=status):
                    self.output = self.root / f"{name}-{status}"
                    self.runtime.outcomes = {name: outcome}
                    self.assertEqual(self.invoke(), status)
                    self.assertEqual(self.records("emulator-console"), [])
                    self.assertEqual(self.records("strict-command"), [])
                    self.assertEqual(len(self.records("avd-remove")), 1)
                    self.assertEqual(self.result()["guest_diagnostics"]["status"], "unavailable")

    def test_sdk_install_and_avd_share_setup_budget(self):
        self.runtime.outcomes["sdk-install"] = {"elapsed": 599}
        self.runtime.outcomes["avd-create"] = {"timeout": True}
        self.assertEqual(self.invoke(), 124)
        self.assertEqual(self.records("avd-create")[0]["timeout_seconds"], 1)
        self.assert_failure(124, diagnostics=False)

    def test_missing_home_fails_before_commands(self):
        del os.environ["ANDROID_HOME"]
        self.assertEqual(self.invoke(), 1)
        self.assertEqual(self.runtime.calls, [])
        self.assertEqual(self.result()["status"], "failed")

    def test_missing_versioned_tools_and_required_sdk_executables_fail_closed(self):
        for index, filename in enumerate((self.bin / "sdkmanager", self.bin / "avdmanager",
                                          self.sdk / "platform-tools/adb", self.sdk / "emulator/emulator")):
            with self.subTest(filename=filename):
                self.output = self.root / f"missing-{index}"
                filename.chmod(0o600)
                try:
                    self.assertEqual(self.invoke(), 1)
                    self.assertEqual(self.records("emulator-console"), [])
                    self.assertEqual(self.records("strict-command"), [])
                finally:
                    filename.chmod(0o700)

    def test_adb_start_and_diagnostics_hook_failure_stop_before_emulator(self):
        for name in ("adb-start", "startup-diagnostics"):
            for outcome, status in (({"returncode": 21}, 21), ({"timeout": True}, 124)):
                with self.subTest(name=name, status=status):
                    self.output = self.root / f"{name}-{status}"
                    self.runtime.outcomes = {name: outcome}
                    self.assertEqual(self.invoke(diagnostics=True), status)
                    self.assertEqual(self.records("emulator-console"), [])
                    self.assertEqual(self.records("strict-command"), [])
                    self.assertEqual(len(self.records("avd-remove")), 1)
                    if name == "adb-start":
                        self.assertEqual(self.records("startup-diagnostics"), [])

    def test_emulator_launch_failure_records_console_and_does_not_retry(self):
        self.runtime.outcomes["emulator-console"] = {"launch_error": OSError("fixture emulator failed")}
        self.assertEqual(self.invoke(), 1)
        self.assertEqual(len(self.records("emulator-console")), 1)
        self.assertEqual(self.records("emulator-console")[0]["status"], "launch_failed")
        self.assertEqual(self.records("wait-for-device"), [])
        self.assert_failure(1, diagnostics=False)

    def test_emulator_exits_even_zero_cannot_pass_boot(self):
        self.runtime.outcomes["emulator-console"] = {"exit_on_poll": 0}
        self.assertEqual(self.invoke(), 1)
        self.assertEqual(self.records("boot-property"), [])
        self.assert_failure(1)

    def test_emulator_exit_after_ready_property_cannot_reach_wm_or_child(self):
        self.runtime.outcomes["boot-property"] = {"emulator_exit": 13}
        self.assertEqual(self.invoke(), 1)
        self.assertEqual(self.records("dismiss-keyguard"), [])
        self.assert_failure(1)

    def test_occupied_port_fails_without_launch_or_signalling_existing_emulator(self):
        self.sockets.return_value.__enter__.return_value.bind.side_effect = OSError("Address already in use")
        self.assertEqual(self.invoke(), 1)
        self.assertEqual(self.records("emulator-console"), [])
        self.assertEqual([event for event in self.runtime.events if event[0] == "signal"], [])
        self.assert_failure(1, diagnostics=False)

    def test_wm_and_each_animation_failures_abort_without_retry(self):
        stages = ["dismiss-keyguard", *RUNNER.ANIMATIONS]
        for index, name in enumerate(stages):
            for outcome, status in (({"returncode": 25}, 25), ({"timeout": True}, 124)):
                with self.subTest(name=name, status=status):
                    self.output = self.root / f"{name}-{status}"
                    self.runtime.outcomes = {name: outcome}
                    self.assertEqual(self.invoke(), status)
                    self.assertEqual(len(self.records(name)), 1)
                    self.assertEqual(self.records("strict-command"), [])
                    for remaining in stages[index + 1:]:
                        self.assertEqual(self.records(remaining), [])
                    self.assertEqual(self.result()["guest_diagnostics"]["status"], "captured")

    def test_startup_failure_keeps_key_wait_trace_and_every_readonly_capture(self):
        self.runtime.outcomes = {"dismiss-keyguard": {"returncode": 26},
                                 "failure-events": {"stdout": ANR.encode()},
                                 "failure-logcat-all": {"stdout": ANR.encode()}}
        self.assertEqual(self.invoke(), 26)
        self.assert_failure(26)
        events = next(self.output.glob("*-failure-events/stdout.txt"))
        self.assertEqual(events.read_text(encoding="utf-8"), ANR)
        expected = [("logcat", "-b", "events", "-d", "-v", "threadtime", "*:V"),
                    ("logcat", "-b", "all", "-d", "-v", "threadtime", "*:V"),
                    ("shell", "dumpsys", "window"), ("shell", "dumpsys", "input")]
        self.assertEqual([tuple(call["argv"][3:]) for call in self.runtime.calls if call["name"].startswith("failure-")], expected)

    def test_diagnostic_errors_and_timeouts_never_replace_original_failure(self):
        self.runtime.outcomes = {"dismiss-keyguard": {"returncode": 27}, "failure-events": {"timeout": True},
                                 "failure-logcat-all": {"returncode": 28},
                                 "failure-window": {"launch_error": OSError("fixture capture failed")},
                                 "failure-input": {"timeout": True}}
        self.assertEqual(self.invoke(), 27)
        self.assertEqual(len(self.result()["guest_diagnostics"]["errors"]), 4)
        self.assertEqual(self.result()["guest_diagnostics"]["status"], "failed")
        self.assertEqual(self.records("failure-events")[0]["status"], "timeout")
        self.assertEqual(self.records("failure-window")[0]["status"], "launch_failed")
        self.assert_failure(27)

    def test_child_failure_is_preserved_and_never_retried_or_reclassified_health(self):
        self.runtime.outcomes["strict-command"] = {"returncode": 42}
        self.assertEqual(self.invoke(), 42)
        self.assertEqual(len(self.records("strict-command")), 1)
        self.assertEqual(self.result()["exit_status"], 42)
        self.assertEqual(self.result()["health_assessment"], "not_performed")
        self.assertTrue(self.result()["child_invoked"])
        self.assertNotIn("guest_diagnostics", self.result())
        self.assertFalse(any(process.group_alive for process in self.runtime.processes))

    def test_child_timeout_cleans_child_group_then_emulator(self):
        self.runtime.outcomes["strict-command"] = {"timeout": True, "stubborn": True}
        self.assertEqual(self.invoke(), 124)
        self.assertEqual(self.records("strict-command")[0]["status"], "timeout")
        events = self.runtime.events
        self.assertLess(events.index(("signal", "strict-command", signal.SIGKILL)),
                        events.index(("signal", "emulator-console", signal.SIGTERM)))
        self.assertFalse(any(process.group_alive for process in self.runtime.processes))

    def test_sigint_and_sigterm_cancel_commands_and_restore_handlers_after_cleanup(self):
        for name in ("sdk-install", "boot-property", "strict-command"):
            for signum in (signal.SIGINT, signal.SIGTERM):
                with self.subTest(name=name, signum=signum):
                    self.output = self.root / f"cancel-{name}-{signum}"
                    self.runtime.outcomes = {name: {"cancel": signum}}
                    self.assertEqual(self.invoke(), 128 + signum)
                    self.assertEqual(self.records(name)[0]["status"], "cancelled")
                    self.assertEqual(self.result()["exit_status"], 128 + signum)
                    self.assertEqual(self.signals.call_args_list[-2:],
                                     [mock.call(signal.SIGINT, signal.SIG_DFL), mock.call(signal.SIGTERM, signal.SIG_DFL)])
                    self.assertIn(mock.call(signal.SIGTERM, signal.SIG_IGN), self.signals.call_args_list)
                    self.assertFalse(any(process.group_alive for process in self.runtime.processes))

    def test_cleanup_kills_descendants_even_after_emulator_leader_exits(self):
        self.runtime.outcomes["emulator-console"] = {"stubborn": True}
        self.assertEqual(self.invoke(), 0)
        self.assertIn(("signal", "emulator-console", signal.SIGKILL), self.runtime.events)
        self.assertEqual(self.clock.now, 1005)
        self.assertFalse(self.runtime.named("emulator-console")[0].group_alive)

    def test_cancellation_during_benign_boot_poll_sleep_still_captures_and_stops(self):
        self.runtime.outcomes["boot-property"] = {"stdout": b"0\n"}

        def cancelled_sleep(seconds):
            RUNNER.cancel(signal.SIGTERM, None)

        self.patch(RUNNER.time, "sleep", cancelled_sleep)
        self.assertEqual(self.invoke(), 143)
        self.assert_failure(143)

    def test_first_cancel_ignores_further_signals_before_group_cleanup(self):
        original_stop = RUNNER.stop_group

        def checked_stop(process):
            if process.name == "strict-command":
                self.assertEqual(self.signals.call_args_list[-2:],
                                 [mock.call(signal.SIGINT, signal.SIG_IGN), mock.call(signal.SIGTERM, signal.SIG_IGN)])
            return original_stop(process)

        self.patch(RUNNER, "stop_group", side_effect=checked_stop)
        self.runtime.outcomes["strict-command"] = {"cancel": signal.SIGINT}
        self.assertEqual(self.invoke(), 130)

    def test_child_failure_cleanup_ignores_cancellation_without_losing_original_error(self):
        original_stop = RUNNER.stop_group

        def checked_stop(process):
            if process.name == "strict-command":
                self.assertEqual(self.signals.call_args_list[-2:],
                                 [mock.call(signal.SIGINT, signal.SIG_IGN), mock.call(signal.SIGTERM, signal.SIG_IGN)])
            return original_stop(process)

        self.patch(RUNNER, "stop_group", side_effect=checked_stop)
        self.runtime.outcomes["strict-command"] = {"returncode": 47, "descendants": True, "stubborn": True}
        self.assertEqual(self.invoke(), 47)
        self.assertFalse(any(process.group_alive for process in self.runtime.processes))

    def test_journal_failure_after_emulator_spawn_stops_unreturned_process(self):
        original_write = RUNNER.write_json

        def broken_write(filename, value):
            if filename.parent.name.endswith("-emulator-console") and value["status"] == "running":
                raise OSError("fixture running record failed")
            return original_write(filename, value)

        self.patch(RUNNER, "write_json", side_effect=broken_write)
        self.assertEqual(self.invoke(), 1)
        self.assert_failure(1, diagnostics=False)
        self.assertEqual(self.records("emulator-console")[0]["status"], "launch_failed")
        self.assertEqual(len(self.records("avd-remove")), 1)

    def test_cleanup_errors_fail_success_but_preserve_child_error(self):
        for child_status in (0, 43):
            with self.subTest(child_status=child_status):
                self.output = self.root / f"cleanup-{child_status}"
                self.runtime.outcomes = {"strict-command": {"returncode": child_status},
                                         "emulator-console": {"signal_error": True, "wait_timeout": True},
                                         "avd-remove": {"returncode": 44}}
                self.assertEqual(self.invoke(), child_status or 1)
                self.assertEqual(len(self.records("avd-remove")), 1)
                self.assertEqual(len(self.result()["cleanup_errors"]), 4)
                self.assertEqual(self.result()["exit_status"], child_status or 1)

    def test_command_cleanup_exception_does_not_replace_original_child_failure(self):
        original_stop = RUNNER.stop_group

        def broken_stop(process):
            if process.name == "strict-command":
                raise OSError("fixture command cleanup failed")
            return original_stop(process)

        self.patch(RUNNER, "stop_group", side_effect=broken_stop)
        self.runtime.outcomes["strict-command"] = {"returncode": 45}
        self.assertEqual(self.invoke(), 45)
        self.assertEqual(self.records("strict-command")[0]["cleanup"]["errors"][0]["kind"], "OSError")

    def test_lifecycle_recording_failure_still_preserves_child_exit_status(self):
        original_write = RUNNER.write_json

        def broken_write(filename, value):
            if filename.name == "lifecycle.json":
                raise OSError("fixture disk full")
            return original_write(filename, value)

        self.patch(RUNNER, "write_json", side_effect=broken_write)
        self.runtime.outcomes["strict-command"] = {"returncode": 46}
        self.assertEqual(self.invoke(), 46)
        self.assertFalse(any(process.group_alive for process in self.runtime.processes))
        self.assertIn("Lifecycle recording failed", self.stderr.getvalue())

    def test_negative_child_signal_status_is_mapped_to_shell_exit_status(self):
        self.runtime.outcomes["strict-command"] = {"returncode": -signal.SIGKILL}
        self.assertEqual(self.invoke(), 137)
        self.assertEqual(self.records("strict-command")[0]["returncode"], -signal.SIGKILL)

    def test_environment_cannot_override_hardware_flags_deadlines_or_skip_child(self):
        os.environ.update(EMULATOR_OPTIONS="-snapshot stale -cores 1", EMULATOR_BOOT_TIMEOUT="0",
                          CORES="1", RAM_SIZE="512M", PROFILE="stale", SKIP_ANDROID_HEALTH="1")
        self.assertEqual(self.invoke(), 0)
        command = self.records("emulator-console")[0]
        self.assertEqual(command["timeout_seconds"], 600)
        self.assertEqual(command["argv"][command["argv"].index("-cores") + 1], "4")
        self.assertNotIn("stale", command["argv"])
        self.assertEqual(len(self.records("strict-command")), 1)

    def test_remote_adb_environment_is_rejected(self):
        for name in ("ADB_SERVER_SOCKET", "ANDROID_ADB_SERVER_ADDRESS", "ANDROID_ADB_SERVER_PORT"):
            with self.subTest(name=name), mock.patch.dict(os.environ, {name: "unsafe-fixture-target"}):
                self.output = self.root / name
                self.assertEqual(self.invoke(), 1)
                self.assertIn(name, self.result()["error"]["message"])
                self.assertEqual(self.records(), [])

    def test_no_unbounded_or_mutating_recovery_commands(self):
        self.runtime.outcomes["dismiss-keyguard"] = {"returncode": 1}
        self.assertEqual(self.invoke(), 1)
        for record in self.records():
            argv = record["argv"]
            self.assertGreater(record["timeout_seconds"], 0)
            self.assertFalse({"kill-server", "reconnect", "keyevent", "sendevent", "monkey"}.intersection(argv))
            if "logcat" in argv:
                self.assertNotIn("-c", argv)
            self.assertNotIn("shell input", " ".join(argv))

    def test_cli_requires_strict_command_and_rejects_unsafe_options(self):
        base = ["--api-level", "35", "--architecture", "x86_64", "--output", str(self.output)]
        invalid = [base, [*base, "--"], [*base, "--boot-timeout", "900", "--", "true"],
                   [*base, "--emulator-options", "-snapshot", "--", "true"],
                   [*base, "--api-level", "34", "--", "true"],
                   [*base, "--architecture", "arm64-v8a", "--", "true"], [*base, "true"]]
        for argv in invalid:
            with self.subTest(argv=argv), contextlib.redirect_stderr(self.stderr):
                with self.assertRaises(SystemExit) as failure:
                    RUNNER.arguments(argv)
                self.assertEqual(failure.exception.code, 2)
        self.assertEqual(self.runtime.calls, [])

    def test_command_arguments_remain_opaque_without_extra_shell(self):
        command = ["env", "TEST_CLASS=literal; no shell expansion $(anything)", "fixture command", "--flag"]
        self.assertEqual(self.invoke(command=command), 0)
        self.assertEqual(self.records("strict-command")[0]["argv"], command)

    def test_owned_production_functions_stay_under_fifty_physical_lines(self):
        tree = ast.parse(SOURCE.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                with self.subTest(function=node.name, line=node.lineno):
                    self.assertLessEqual(node.end_lineno - node.lineno + 1, 50)


if __name__ == "__main__":
    unittest.main()
