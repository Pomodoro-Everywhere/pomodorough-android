#!/usr/bin/env python3
"""Own one fresh CI emulator; boot completion is not Android health acceptance.

The workflow supplies Java, licensed SDK command-line tools on PATH, and
platform-tools. --startup-diagnostics is the existing passive observer's output
directory; the workflow remains responsible for its always-run stop step.
The required command after -- performs downstream strict health/test gates.
All subprocesses have fixed deadlines and separate process groups. Artifacts
must use a fresh directory that the downstream command does not remove.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time


PORT = 5554
SERIAL = f"emulator-{PORT}"
PROFILE = "pixel_6"
CORES = 4
MEMORY_MB = 4096
TARGET = "google_apis"
BUILD_TOOLS = "37.0.0"
SETUP_SECONDS = 600
AVD_SECONDS = 120
BOOT_SECONDS = 600
COMMAND_SECONDS = 30
CHILD_SECONDS = 2700
STOP_SECONDS = 5
POLL_SECONDS = 2
ANIMATIONS = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
EMULATOR_FLAGS = ("-no-window", "-gpu", "swiftshader_indirect", "-no-snapshot",
                  "-noaudio", "-no-boot-anim")
DIAGNOSTICS = (("events", ("logcat", "-b", "events", "-d", "-v", "threadtime", "*:V")),
               ("logcat-all", ("logcat", "-b", "all", "-d", "-v", "threadtime", "*:V")),
               ("window", ("shell", "dumpsys", "window")),
               ("input", ("shell", "dumpsys", "input")))


class LifecycleFailure(Exception):
    def __init__(self, message, exit_status=1):
        super().__init__(message)
        self.exit_status = exit_status if exit_status >= 0 else 128 - exit_status


class Cancelled(BaseException):
    def __init__(self, signum):
        super().__init__(f"Cancelled by signal {signum}")
        self.exit_status = 128 + signum


def timestamp():
    return {"wall_seconds": time.time(), "monotonic_seconds": time.monotonic()}


def error_record(failure):
    return {"kind": type(failure).__name__, "message": str(failure)}


def write_json(destination, value):
    pending = destination.with_suffix(".pending")
    pending.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    pending.replace(destination)


def cancel(signum, frame):
    for interrupted in (signal.SIGINT, signal.SIGTERM):
        signal.signal(interrupted, signal.SIG_IGN)
    raise Cancelled(signum)


@contextmanager
def cancellation_signals(handler=cancel):
    previous = {signum: signal.signal(signum, handler) for signum in (signal.SIGINT, signal.SIGTERM)}
    try:
        yield
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def group_exists(process):
    process.poll()
    try:
        os.killpg(process.pid, 0)
        return True
    except ProcessLookupError:
        return False


def stop_group(process):
    result = {"started": timestamp(), "signals": [], "errors": []}
    for signum in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, signum)
            result["signals"].append(signum)
            if signum == signal.SIGTERM:
                deadline = time.monotonic() + STOP_SECONDS
                while group_exists(process) and time.monotonic() < deadline:
                    time.sleep(min(0.1, max(0, deadline - time.monotonic())))
        except ProcessLookupError:
            break
        except OSError as failure:
            result["errors"].append(error_record(failure))
    try:
        process.wait(timeout=STOP_SECONDS)
    except (OSError, subprocess.SubprocessError) as failure:
        result["errors"].append(error_record(failure))
    result.update(finished=timestamp(), returncode=process.returncode)
    return result


class Command:
    def __init__(self, directory, arguments, timeout):
        self.directory = directory
        directory.mkdir()
        self.process = None
        self.record = {"argv": list(arguments), "timeout_seconds": timeout,
                       "started": timestamp(), "finished": None, "status": "starting",
                       "returncode": None, "stdout": "stdout.txt", "stderr": "stderr.txt"}
        write_json(directory / "command.json", self.record)

    def launch(self, environment, standard_input):
        try:
            with (self.directory / "stdout.txt").open("xb") as stdout:
                with (self.directory / "stderr.txt").open("xb") as stderr:
                    self.process = subprocess.Popen(
                        self.record["argv"], env=environment, stdout=stdout, stderr=stderr,
                        stdin=subprocess.PIPE if standard_input is not None else subprocess.DEVNULL,
                        start_new_session=True, close_fds=True)
            self.record.update(status="running", pid=self.process.pid)
            write_json(self.directory / "command.json", self.record)
        except BaseException as failure:
            if self.process is not None:
                self.stop()
            self.finish("launch_failed", failure)
            raise

    def stop(self):
        with cancellation_signals(signal.SIG_IGN):
            try:
                self.record["cleanup"] = stop_group(self.process)
            except Exception as failure:
                self.record["cleanup"] = {"errors": [error_record(failure)]}
        return self.record["cleanup"]["errors"]

    def finish(self, status, failure=None):
        self.record.update(status=status, finished=timestamp(),
                           returncode=self.process.returncode if self.process else None)
        if failure is not None:
            self.record["error"] = error_record(failure)
        try:
            write_json(self.directory / "command.json", self.record)
        except OSError as recording_failure:
            if failure is None:
                raise
            print(f"Artifact recording failed: {recording_failure}", file=sys.stderr)


class Commands:
    def __init__(self, output):
        self.output = output
        self.environment = os.environ.copy()
        self.sequence = 0

    def launch(self, name, arguments, timeout, standard_input=None):
        self.sequence += 1
        command = Command(self.output / f"{self.sequence:04d}-{name}", arguments, timeout)
        command.launch(self.environment, standard_input)
        return command

    def run(self, name, arguments, timeout=COMMAND_SECONDS, standard_input=None):
        command = self.launch(name, arguments, timeout, standard_input)
        try:
            command.process.communicate(input=standard_input, timeout=timeout)
            if command.process.returncode:
                raise LifecycleFailure(f"{name} exited {command.process.returncode}", command.process.returncode)
        except BaseException as failure:
            command.stop()
            status = "cancelled" if isinstance(failure, Cancelled) else "failed"
            timed_out = isinstance(failure, subprocess.TimeoutExpired)
            command.finish("timeout" if timed_out else status, failure)
            if timed_out:
                raise LifecycleFailure(f"{name} exceeded {timeout:g}s", 124) from failure
            raise
        command.finish("completed")
        return command


def executable(filename):
    if not filename.is_file() or not os.access(filename, os.X_OK):
        raise LifecycleFailure(f"Missing executable: {filename}")
    return str(filename)


def available_ports():
    with socket.socket() as console, socket.socket() as transport:
        console.bind(("127.0.0.1", PORT))
        transport.bind(("127.0.0.1", PORT + 1))


class Lifecycle:
    def __init__(self, options, output):
        self.options = options
        self.commands = Commands(output)
        self.output = output
        self.avd_root = None
        self.emulator = None
        self.adb = None
        self.child_started = False
        self.result = {"started": timestamp(), "health_assessment": "not_performed",
                       "boot_completed": False, "child_invoked": False, "cleanup_errors": []}

    def configure(self):
        environment = self.commands.environment
        if not environment.get("ANDROID_HOME"):
            raise LifecycleFailure("ANDROID_HOME must identify the workflow SDK")
        sdk = Path(environment["ANDROID_HOME"]).resolve()
        for name in ("ADB_SERVER_SOCKET", "ANDROID_ADB_SERVER_ADDRESS", "ANDROID_ADB_SERVER_PORT"):
            if environment.get(name):
                raise LifecycleFailure(f"Unsupported ADB redirection: {name}")
        self.adb = executable(sdk / "platform-tools/adb")
        self.avd_root = Path(tempfile.mkdtemp(prefix="pomodorough-emulator-"))
        for name in ("avd", "user", "emulator"):
            (self.avd_root / name).mkdir()
        environment.update(ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
                           ANDROID_AVD_HOME=str(self.avd_root / "avd"),
                           ANDROID_USER_HOME=str(self.avd_root / "user"),
                           ANDROID_EMULATOR_HOME=str(self.avd_root / "emulator"),
                           ANDROID_SDK_HOME=str(self.avd_root),
                           ANDROID_SERIAL=SERIAL, EMULATOR_PORT=str(PORT))
        environment["PATH"] = os.pathsep.join((str(sdk / "platform-tools"), str(sdk / "emulator"),
                                               environment.get("PATH", os.defpath)))
        self.result.update(avd_root=str(self.avd_root), avd_name=self.avd_root.name, sdk=str(sdk))
        return sdk

    def setup(self):
        deadline = time.monotonic() + SETUP_SECONDS
        sdk = self.configure()
        tools = {}
        for name in ("sdkmanager", "avdmanager"):
            found = shutil.which(name, path=self.commands.environment["PATH"])
            if found is None:
                raise LifecycleFailure(f"Workflow must supply {name} on PATH")
            tools[name] = found
        image = f"system-images;android-{self.options.api_level};{TARGET};{self.options.architecture}"
        self.result["system_image"] = image
        self.commands.run("sdk-install", [tools["sdkmanager"], f"--sdk_root={sdk}",
                          f"platforms;android-{self.options.api_level}", "emulator", image,
                          f"build-tools;{BUILD_TOOLS}"], self.remaining(deadline))
        self.commands.run("avd-create", [tools["avdmanager"], "create", "avd", "--name", self.avd_root.name,
                          "--package", image, "--device", PROFILE, "--path",
                          str(self.avd_root / "avd" / f"{self.avd_root.name}.avd")],
                          min(AVD_SECONDS, self.remaining(deadline)), b"no\n")
        self.remaining(deadline)
        return executable(sdk / "emulator/emulator")

    def remaining(self, deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise LifecycleFailure("Lifecycle deadline exceeded", 124)
        return remaining

    def require_running(self):
        status = self.emulator.process.poll()
        if status is not None:
            raise LifecycleFailure(f"Owned emulator exited unexpectedly: {status}")

    def boot(self, deadline):
        self.commands.run("wait-for-device", [self.adb, "-s", SERIAL, "wait-for-device"],
                          self.remaining(deadline))
        while True:
            self.require_running()
            command = self.commands.run("boot-property", [self.adb, "-s", SERIAL, "shell", "getprop",
                                        "sys.boot_completed"], min(COMMAND_SECONDS, self.remaining(deadline)))
            self.remaining(deadline)
            self.require_running()
            with (command.directory / "stdout.txt").open("rb") as stdout:
                payload = stdout.read(65)
            if len(payload) > 64:
                raise LifecycleFailure("Oversized sys.boot_completed response")
            value = payload.strip()
            if value == b"1":
                self.result["boot_completed"] = True
                return
            if value not in (b"", b"0"):
                raise LifecycleFailure(f"Unexpected sys.boot_completed: {value!r}")
            time.sleep(min(POLL_SECONDS, self.remaining(deadline)))

    def startup(self, emulator):
        self.commands.run("adb-start", [self.adb, "start-server"])
        if self.options.startup_diagnostics:
            self.commands.run("startup-diagnostics", [sys.executable,
                              str(Path(__file__).with_name("android-startup-diagnostics.py")),
                              "start", "--output", str(self.options.startup_diagnostics)])
        available_ports()
        self.emulator = self.commands.launch("emulator-console", [emulator, "-avd", self.avd_root.name,
                                            "-port", str(PORT), "-cores", str(CORES),
                                            "-memory", str(MEMORY_MB), *EMULATOR_FLAGS], BOOT_SECONDS)
        self.boot(self.emulator.record["started"]["monotonic_seconds"] + BOOT_SECONDS)
        self.commands.run("dismiss-keyguard", [self.adb, "-s", SERIAL, "shell", "wm", "dismiss-keyguard"])
        for name in ANIMATIONS:
            self.commands.run(name, [self.adb, "-s", SERIAL, "shell", "settings", "put", "global", name, "0"])
        self.require_running()

    def capture_startup_failure(self):
        if self.emulator is None:
            self.result["guest_diagnostics"] = {"status": "unavailable", "reason": "Owned emulator not launched"}
            return
        failures = []
        for name, arguments in DIAGNOSTICS:
            try:
                self.commands.run(f"failure-{name}", [self.adb, "-s", SERIAL, *arguments])
            except Exception as failure:
                failures.append({"capture": name, **error_record(failure)})
        self.result["guest_diagnostics"] = {"status": "failed" if failures else "captured", "errors": failures}

    def cleanup(self):
        if self.emulator is not None:
            try:
                self.result["cleanup_errors"].extend(self.emulator.stop())
                self.emulator.finish("stopped")
            except Exception as failure:
                self.result["cleanup_errors"].append(error_record(failure))
        if self.avd_root is not None:
            try:
                self.commands.run("avd-remove", [sys.executable, "-c",
                                  "import shutil, sys; shutil.rmtree(sys.argv[1])", str(self.avd_root)])
            except Exception as failure:
                self.result["cleanup_errors"].append(error_record(failure))

    def run(self):
        exit_status = 0
        with cancellation_signals():
            try:
                self.startup(self.setup())
                self.child_started = True
                self.result["child_invoked"] = True
                self.commands.run("strict-command", self.options.command, CHILD_SECONDS)
                self.require_running()
            except (Exception, Cancelled) as failure:
                exit_status = getattr(failure, "exit_status", 1)
                self.result["error"] = error_record(failure)
            finally:
                for signum in (signal.SIGINT, signal.SIGTERM):
                    signal.signal(signum, signal.SIG_IGN)
                try:
                    if exit_status and not self.child_started:
                        self.capture_startup_failure()
                except Exception as failure:
                    self.result["guest_diagnostics"] = {"status": "failed", "error": error_record(failure)}
                finally:
                    self.cleanup()
                if self.result["cleanup_errors"] and not exit_status:
                    exit_status = 1
                self.result.update(finished=timestamp(), exit_status=exit_status,
                                   status="failed" if exit_status else "completed")
                try:
                    write_json(self.output / "lifecycle.json", self.result)
                except OSError as failure:
                    print(f"Lifecycle recording failed: {failure}", file=sys.stderr)
                    exit_status = exit_status or 1
        return exit_status


def arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-level", required=True, type=int, choices=(30, 35, 36))
    parser.add_argument("--architecture", required=True, choices=("x86", "x86_64"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--startup-diagnostics", type=Path)
    separator = argv.index("--") if "--" in argv else len(argv)
    options = parser.parse_args(argv[:separator])
    options.command = argv[separator + 1:]
    if not options.command:
        parser.error("A downstream strict command is required after --")
    return options


def main(argv=None):
    options = arguments(sys.argv[1:] if argv is None else argv)
    try:
        output = options.output.resolve()
        output.mkdir(parents=True, exist_ok=False)
        return Lifecycle(options, output).run()
    except (Exception, Cancelled) as failure:
        print(json.dumps(error_record(failure)), file=sys.stderr)
        return getattr(failure, "exit_status", 1)


if __name__ == "__main__":
    sys.exit(main())
