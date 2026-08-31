"""Private temporary proc fixtures and mocked launches only; not native acceptance."""

from __future__ import annotations

import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / ".github/scripts/android-startup-diagnostics.py"
SPEC = importlib.util.spec_from_file_location("android_startup_diagnostics", SOURCE)
DIAGNOSTICS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DIAGNOSTICS)


def process_stat(pid, comm, starttime=100):
    fields = ["S", *(["0"] * 18), str(starttime), *(["0"] * 5)]
    return f"{pid} ({comm}) " + " ".join(fields) + "\n"


class FixtureClock:
    def __init__(self):
        self.now = 1000.0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def wall(self):
        return 1700000000 + self.now

    def sleep(self, seconds):
        if not 0 <= seconds <= DIAGNOSTICS.POLL_SECONDS:
            raise AssertionError("Unbounded sleep")
        self.sleeps.append(seconds)
        self.now += seconds


class StartupDiagnosticsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="startup-diagnostics-", dir=Path("/tmp").resolve())
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.proc = self.root / "proc"
        self.proc.mkdir()
        self.output_path = self.root / "reports/startup-api-fixture"
        self.clock = FixtureClock()
        self.patch(DIAGNOSTICS.time, "monotonic", self.clock.monotonic)
        self.patch(DIAGNOSTICS.time, "time", self.clock.wall)
        self.patch(DIAGNOSTICS.time, "sleep", self.clock.sleep)
        self.launch = self.patch(DIAGNOSTICS.subprocess, "Popen")
        original_directory = DIAGNOSTICS.open_directory

        def fixture_directory(path, *arguments, **keywords):
            if not Path(os.path.abspath(path)).is_relative_to(self.root):
                raise AssertionError("Only private temporary fixtures may be opened")
            return original_directory(path, *arguments, **keywords)

        self.patch(DIAGNOSTICS, "open_directory", fixture_directory)
        self.system_fixture()

    def patch(self, owner, name, *arguments, **keywords):
        patcher = mock.patch.object(owner, name, *arguments, **keywords)
        self.addCleanup(patcher.stop)
        return patcher.start()

    def write(self, relative, text):
        path = self.proc / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def system_fixture(self):
        for name in DIAGNOSTICS.SYSTEM_FILES:
            self.write(name, "fixture " + name + "\n")
        self.write("stat", "cpu 1 2 3 4 5 6 7 88 9 10\ncpu0 1 2 3 4 5 6 7 88 9 10\n")
        for name in ("tcp", "tcp6", "unix"):
            self.write(f"{os.getpid()}/net/{name}", "raw fixture socket:[123]\n")
        self.write(f"{os.getpid()}/comm", "python3\n")

    def process_fixture(self, pid="901", comm="adb", starttime=100, threads=1):
        self.write(f"{pid}/comm", comm + "\n")
        self.write(f"{pid}/stat", process_stat(pid, comm, starttime))
        self.write(f"{pid}/status", f"Name:\t{comm}\nThreads:\t{threads}\n")
        self.write(f"{pid}/schedstat", "10 20 30\n")
        (self.proc / pid / "task").mkdir(exist_ok=True)
        (self.proc / pid / "fd").mkdir(exist_ok=True)
        for offset in range(threads):
            thread = str(int(pid) + offset)
            self.write(f"{pid}/task/{thread}/stat", process_stat(thread, "worker", starttime + offset))
            self.write(f"{pid}/task/{thread}/schedstat", "1 2 3\n")

    def output(self, fresh=True):
        output = DIAGNOSTICS.Output(self.output_path, fresh=fresh)
        self.addCleanup(output.close)
        return output

    def start_fixture(self, **settings):
        output = self.output()
        self.assertEqual(DIAGNOSTICS.start(output, DIAGNOSTICS.limits(**settings), self.proc), 0)
        return output

    def snapshot(self, deadline=None, stopped=lambda: False):
        deadline = self.clock.now + 30 if deadline is None else deadline
        return DIAGNOSTICS.Sampler(self.proc, deadline, stopped).snapshot("fixture")

    def test_system_snapshot_raw_steal_pressure_and_network_brackets(self):
        snapshot = self.snapshot()
        self.assertEqual(set(snapshot["files"]), {*DIAGNOSTICS.SYSTEM_FILES, "net/tcp", "net/tcp6", "net/unix"})
        self.assertIn("7 88 9 10", snapshot["files"]["stat"]["text"])
        self.assertEqual(snapshot["files"]["net/tcp"]["text"], "raw fixture socket:[123]\n")
        self.assertEqual(snapshot["started"]["monotonic_seconds"], self.clock.now)
        self.assertEqual(snapshot["finished"]["wall_seconds"], self.clock.wall())
        self.assertTrue(snapshot["diagnostics_only"])
        self.assertLessEqual(snapshot["read_bytes"], DIAGNOSTICS.MAX_READ_BYTES)

    def test_linux_net_self_symlinks_are_bypassed_with_explicit_collector_pid(self):
        (self.proc / "self").symlink_to(str(os.getpid()))
        (self.proc / "net").symlink_to("self/net")
        snapshot = self.snapshot()
        self.assertEqual(snapshot["collector_pid"], os.getpid())
        self.assertEqual(snapshot["network_source"], f"{os.getpid()}/net")
        for name in ("tcp", "tcp6", "unix"):
            self.assertEqual(snapshot["files"][f"net/{name}"]["text"], "raw fixture socket:[123]\n")

    def test_missing_and_truncated_socket_tables_are_explicit(self):
        (self.proc / f"{os.getpid()}/net/tcp").unlink()
        self.write(f"{os.getpid()}/net/tcp6", "x" * 9000)
        snapshot = self.snapshot()
        self.assertEqual(snapshot["files"]["net/tcp"]["error"]["kind"], "FileNotFoundError")
        self.assertTrue(snapshot["files"]["net/tcp6"]["truncated"])

    def test_only_selected_comm_and_adb_socket_links_are_retained(self):
        names = ("adb", "emulator", "qemu-system-x86", "bash", "adbd", "notemulator", "qemurunner")
        for offset, name in enumerate(names):
            self.process_fixture(str(901 + offset), name)
        (self.proc / "901/fd/4").symlink_to("socket:[123]")
        (self.proc / "901/fd/5").symlink_to(self.root / "private-do-not-read")
        (self.proc / "902/fd/6").symlink_to("socket:[456]")
        self.write("901/cmdline", "DO NOT COLLECT CMDLINE")
        self.write("901/environ", "DO NOT COLLECT ENVIRON")
        snapshot = self.snapshot()
        self.assertEqual([item["before"]["comm"] for item in snapshot["processes"]], list(names[:3]))
        self.assertEqual(snapshot["processes"][0]["socket_links"], [{"fd": 4, "socket": "socket:[123]"}])
        self.assertNotIn("socket_links", snapshot["processes"][1])
        serialized = json.dumps(snapshot)
        for excluded in ("DO NOT COLLECT", "private-do-not-read", "socket:[456]", "903/fd", "904/comm"):
            self.assertNotIn(excluded, serialized)

    def test_pid_replacement_invalidates_capture_even_with_same_comm(self):
        self.process_fixture()
        original = DIAGNOSTICS.read_regular
        reads = 0

        def replaced(directory, path, limit):
            nonlocal reads
            if path == "901/stat":
                reads += 1
                if reads == 2:
                    self.write(path, process_stat("901", "adb", 999))
            return original(directory, path, limit)

        self.patch(DIAGNOSTICS, "read_regular", replaced)
        process = self.snapshot()["processes"][0]
        self.assertFalse(process["valid"])
        self.assertEqual(process["before"]["starttime"], "100")
        self.assertEqual(process["after"]["starttime"], "999")
        self.assertEqual(process["reason"], "identity_changed_or_disappeared")

    def test_pid_disappearance_retains_error_and_invalid_identity(self):
        self.process_fixture()
        original = DIAGNOSTICS.read_regular
        reads = 0

        def disappeared(directory, path, limit):
            nonlocal reads
            if path == "901/stat":
                reads += 1
                if reads == 2:
                    raise FileNotFoundError(2, "fixture process disappeared")
            return original(directory, path, limit)

        self.patch(DIAGNOSTICS, "read_regular", disappeared)
        snapshot = self.snapshot()
        self.assertFalse(snapshot["processes"][0]["valid"])
        self.assertIsNone(snapshot["processes"][0]["after"])
        self.assertEqual(snapshot["files"]["901/stat.after"]["error"]["kind"], "FileNotFoundError")

    def test_comm_change_before_stat_does_not_collect_details(self):
        self.process_fixture()
        self.write("901/stat", process_stat("901", "bash"))
        snapshot = self.snapshot()
        self.assertFalse(snapshot["processes"][0]["valid"])
        self.assertNotIn("901/status", snapshot["files"])

    def test_stat_identity_rejects_malformed_or_mismatched_pid(self):
        self.process_fixture()
        for text in ("", "901 (adb) S 0\n", process_stat("902", "adb"), process_stat("901", "adb", "-1")):
            with self.subTest(text=text):
                self.write("901/stat", text)
                snapshot = self.snapshot()
                self.assertFalse(snapshot["processes"][0]["valid"])
                self.assertNotIn("901/status", snapshot["files"])

    def test_thread_names_with_parentheses_and_replacement(self):
        self.process_fixture()
        self.write("901/task/901/stat", process_stat("901", "worker ) (name"))
        original = DIAGNOSTICS.read_regular
        reads = 0

        def replaced(directory, path, limit):
            nonlocal reads
            if path == "901/task/901/stat":
                reads += 1
                if reads == 2:
                    self.write(path, process_stat("901", "worker ) (name", 555))
            return original(directory, path, limit)

        self.patch(DIAGNOSTICS, "read_regular", replaced)
        thread = self.snapshot()["processes"][0]["threads"][0]
        self.assertFalse(thread["valid"])
        self.assertEqual(thread["before"]["comm"], "worker ) (name")
        self.assertEqual(thread["after"]["starttime"], "555")

    def test_file_truncation_and_invalid_encoding_explicit(self):
        self.write("meminfo", "x" * 10000)
        (self.proc / "uptime").write_bytes(b"bad\xff\n")
        snapshot = self.snapshot()
        self.assertTrue(snapshot["files"]["meminfo"]["truncated"])
        self.assertEqual(len(snapshot["files"]["meminfo"]["text"]), 4096)
        self.assertIn("file_bytes", snapshot["limits"])
        self.assertTrue(snapshot["files"]["uptime"]["decode_error"])
        self.assertIn("UnicodeDecodeError", [error["kind"] for error in snapshot["errors"]])

    def test_permissions_missing_files_and_error_retention_cap(self):
        self.patch(DIAGNOSTICS, "MAX_ERRORS", 2)
        original = DIAGNOSTICS.read_regular

        def denied(directory, path, limit):
            if path in DIAGNOSTICS.SYSTEM_FILES:
                raise PermissionError(13, "fixture denied")
            return original(directory, path, limit)

        self.patch(DIAGNOSTICS, "read_regular", denied)
        snapshot = self.snapshot()
        self.assertEqual(len(snapshot["errors"]), 2)
        self.assertEqual(snapshot["errors_dropped"], 5)
        self.assertEqual(snapshot["files"]["stat"]["error"]["errno"], 13)

    def test_symlinked_files_directories_and_pid_entries_are_not_followed(self):
        secret = self.write("secret", "NEVER READ SECRET")
        (self.proc / "stat").unlink()
        (self.proc / "stat").symlink_to(secret)
        (self.proc / "pressure/cpu").unlink()
        (self.proc / "pressure/io").unlink()
        (self.proc / "pressure/memory").unlink()
        (self.proc / "pressure").rmdir()
        (self.proc / "pressure").symlink_to(self.proc)
        (self.proc / "999").symlink_to(self.proc)
        snapshot = self.snapshot()
        self.assertIn("error", snapshot["files"]["stat"])
        self.assertIn("error", snapshot["files"]["pressure/cpu"])
        self.assertNotIn("NEVER READ SECRET", json.dumps(snapshot))
        self.assertEqual(snapshot["processes"], [])

    def test_fifo_is_rejected_without_reading_or_blocking(self):
        (self.proc / "stat").unlink()
        os.mkfifo(self.proc / "stat")
        original = DIAGNOSTICS.os.read

        def regular_only(descriptor, count):
            self.assertTrue(stat.S_ISREG(os.fstat(descriptor).st_mode))
            return original(descriptor, count)

        self.patch(DIAGNOSTICS.os, "read", regular_only)
        snapshot = self.snapshot()
        self.assertEqual(snapshot["files"]["stat"]["error"]["errno"], 22)

    def test_device_file_type_is_rejected_before_read(self):
        information = mock.Mock(st_mode=stat.S_IFCHR)
        read = self.patch(DIAGNOSTICS.os, "read")
        self.patch(DIAGNOSTICS.os, "fstat", return_value=information)
        descriptor = os.open(self.proc, DIAGNOSTICS.DIRECTORY_FLAGS)
        try:
            with self.assertRaises(OSError):
                DIAGNOSTICS.read_regular(descriptor, "stat", 16)
        finally:
            os.close(descriptor)
        read.assert_not_called()

    def test_read_operations_and_bytes_are_global_per_snapshot(self):
        self.patch(DIAGNOSTICS, "MAX_READ_BYTES", 100)
        self.patch(DIAGNOSTICS, "MAX_READS", 3)
        snapshot = self.snapshot()
        self.assertLessEqual(snapshot["read_operations"], 3)
        self.assertLessEqual(snapshot["read_bytes"], 100)
        self.assertIn("read_budget", snapshot["limits"])

    def test_thread_and_fd_caps_shared_across_processes(self):
        self.patch(DIAGNOSTICS, "MAX_THREADS", 3)
        self.patch(DIAGNOSTICS, "MAX_FDS", 2)
        for pid in ("901", "911"):
            self.process_fixture(pid, threads=4)
            for number in range(4):
                (self.proc / pid / "fd" / str(number)).symlink_to(f"socket:[{number}]")
        snapshot = self.snapshot()
        self.assertEqual(sum(len(process["threads"]) for process in snapshot["processes"]), 3)
        self.assertEqual(sum(len(process["socket_links"]) for process in snapshot["processes"]), 2)
        self.assertIn("threads_or_reads", snapshot["limits"])
        self.assertIn("fd_links", snapshot["limits"])

    def test_directory_budget_counts_non_pid_entries_without_unbounded_listing(self):
        self.patch(DIAGNOSTICS, "MAX_ENTRIES", 5)
        for number in range(30):
            (self.proc / f"nonnumeric-{number}").touch()
        snapshot = self.snapshot()
        self.assertEqual(snapshot["directory_entries"], 5)
        self.assertIn("directory_entries", snapshot["limits"])

    def test_process_limit_is_global(self):
        self.patch(DIAGNOSTICS, "MAX_PROCESSES", 2)
        for number in range(5):
            self.process_fixture(str(901 + number))
        snapshot = self.snapshot()
        self.assertEqual(len(snapshot["processes"]), 2)
        self.assertIn("processes_or_reads", snapshot["limits"])

    def test_expired_deadline_performs_no_reads(self):
        read = self.patch(DIAGNOSTICS, "read_regular")
        snapshot = self.snapshot(deadline=self.clock.now)
        read.assert_not_called()
        self.assertEqual(snapshot["interrupted"], "deadline")
        self.assertEqual(snapshot["started"], snapshot["finished"])

    def test_deadline_checked_between_reads_retains_partial_sample(self):
        original = DIAGNOSTICS.read_regular

        def advancing(directory, path, limit):
            self.clock.now += 1
            return original(directory, path, limit)

        self.patch(DIAGNOSTICS, "read_regular", advancing)
        snapshot = self.snapshot(deadline=self.clock.now + 2)
        self.assertEqual(snapshot["interrupted"], "deadline")
        self.assertEqual(snapshot["read_operations"], 2)
        self.assertIn("stat", snapshot["files"])
        self.assertEqual(snapshot["finished"]["monotonic_seconds"] - snapshot["started"]["monotonic_seconds"], 2)

    def test_deadline_crossed_by_last_read_is_retained(self):
        original = DIAGNOSTICS.read_regular

        def last_read(directory, path, limit):
            if path == f"{os.getpid()}/comm":
                self.clock.now += 1
            return original(directory, path, limit)

        self.patch(DIAGNOSTICS, "read_regular", last_read)
        snapshot = self.snapshot(deadline=self.clock.now + 1)
        self.assertEqual(snapshot["interrupted"], "deadline")
        self.assertEqual(snapshot["finished"]["monotonic_seconds"], 1001)

    def test_stop_checked_inside_directory_scan(self):
        checks = 0

        def stopped():
            nonlocal checks
            checks += 1
            return checks >= 13

        snapshot = self.snapshot(stopped=stopped)
        self.assertEqual(snapshot["interrupted"], "stop_requested")
        self.assertLessEqual(snapshot["directory_entries"], 2)

    def test_json_escape_expansion_truncates_payload_not_error_metadata(self):
        snapshot = self.snapshot()
        snapshot["files"]["stat"]["text"] = "\x01" * (DIAGNOSTICS.FILE_BYTES // 2)
        snapshot["errors"].append({"kind": "fixture-error", "path": "stat"})
        payload = DIAGNOSTICS.bounded_snapshot(snapshot)
        parsed = json.loads(payload)
        self.assertLessEqual(len(payload), DIAGNOSTICS.FILE_BYTES)
        self.assertIn("sample_bytes", parsed["limits"])
        self.assertTrue(parsed["files"]["stat"]["truncated"])
        self.assertEqual(parsed["errors"][-1]["kind"], "fixture-error")

    def test_baseline_exists_before_detached_launch_with_all_stdio_redirected(self):
        def verify_launch(command, **keywords):
            self.assertTrue((self.output_path / "sample-0000.json").is_file())
            baseline = json.loads((self.output_path / "sample-0000.json").read_text())
            self.assertEqual(baseline["phase"], "baseline")
            self.assertEqual(command, [DIAGNOSTICS.sys.executable, str(SOURCE), "observe", "--output", str(self.output_path)])
            self.assertTrue(keywords["start_new_session"])
            self.assertTrue(keywords["close_fds"])
            self.assertIs(keywords["stdin"], keywords["stdout"])
            self.assertIs(keywords["stdout"], keywords["stderr"])
            self.assertIs(keywords["preexec_fn"], DIAGNOSTICS.install_file_limit)
            self.assertTrue(stat.S_ISREG(os.fstat(keywords["stdout"].fileno()).st_mode))
            return mock.Mock()

        self.launch.side_effect = verify_launch
        output = self.start_fixture()
        self.assertEqual(output.load("manifest.json")["health_assessment"], "not_performed")
        self.launch.assert_called_once()

    def test_log_file_limit_installed_only_in_child_preexec(self):
        resource_limit = self.patch(DIAGNOSTICS.resource, "setrlimit")
        self.start_fixture()
        resource_limit.assert_not_called()
        self.launch.call_args.kwargs["preexec_fn"]()
        resource_limit.assert_called_once_with(DIAGNOSTICS.resource.RLIMIT_FSIZE,
                                               (DIAGNOSTICS.FILE_BYTES, DIAGNOSTICS.FILE_BYTES))

    def test_start_rejects_existing_output_without_touching_contents(self):
        output = self.start_fixture()
        before = {path.name: path.read_bytes() for path in self.output_path.iterdir()}
        with self.assertRaises(FileExistsError):
            self.output()
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.output_path.iterdir()})
        self.assertFalse(output.exists("stop.json"))

    def test_empty_existing_output_and_symlinked_parent_rejected(self):
        self.output_path.mkdir(parents=True)
        with self.assertRaises(FileExistsError):
            self.output()
        self.output_path.rmdir()
        self.output_path.parent.rmdir()
        self.output_path.parent.symlink_to(self.proc)
        with self.assertRaises(OSError):
            self.output()
        self.assertFalse((self.proc / self.output_path.name).exists())

    def test_spawn_failure_is_retained_and_stop_fails(self):
        self.launch.side_effect = OSError(12, "fixture launch failed")
        output = self.output()
        self.assertEqual(DIAGNOSTICS.start(output, DIAGNOSTICS.limits(), self.proc), 1)
        self.assertEqual(output.load("done.json")["reason"], "observer_launch_failed")
        self.assertEqual(DIAGNOSTICS.stop(output), 1)
        self.assertEqual(output.load("stop-result.json")["collection_status"], "failed")

    def test_baseline_deadline_failure_never_launches_observer(self):
        original = DIAGNOSTICS.read_regular

        def advancing(directory, path, limit):
            self.clock.now += 1
            return original(directory, path, limit)

        self.patch(DIAGNOSTICS, "read_regular", advancing)
        output = self.output()
        self.assertEqual(DIAGNOSTICS.start(output, DIAGNOSTICS.limits(duration=1), self.proc), 1)
        self.launch.assert_not_called()
        self.assertEqual(output.load("done.json")["reason"], "baseline_deadline")

    def test_samples_include_baseline_and_observer_is_finite_without_stop(self):
        output = self.start_fixture(max_samples=3, interval=2)
        self.assertEqual(DIAGNOSTICS.observe(output, self.proc), 0)
        result = output.load("done.json")
        self.assertEqual(result["reason"], "sample_count")
        self.assertEqual(result["samples"], 3)
        self.assertEqual(len(list(self.output_path.glob("sample-*.json"))), 3)
        self.assertEqual(self.clock.now, 1004)
        self.assertTrue(result["diagnostics_only"])
        self.assertEqual(result["health_assessment"], "not_performed")

    def test_default_deadline_includes_baseline_and_detached_startup_delay(self):
        output = self.start_fixture(interval=1200)
        self.clock.now += DIAGNOSTICS.MAX_SECONDS
        read = self.patch(DIAGNOSTICS, "Sampler")
        self.assertEqual(DIAGNOSTICS.observe(output, self.proc), 0)
        read.assert_not_called()
        self.assertEqual(output.load("done.json")["reason"], "deadline")
        self.assertEqual(output.load("done.json")["samples"], 1)

    def test_byte_budget_includes_baseline_observe_files_and_full_log(self):
        output = self.start_fixture(max_bytes=DIAGNOSTICS.MIN_BYTES, interval=2)
        snapshot = self.snapshot()
        snapshot["files"]["stat"]["text"] = "x" * (128 * 1024)
        sampler = self.patch(DIAGNOSTICS, "Sampler")
        sampler.return_value.snapshot.side_effect = lambda phase: copy.deepcopy(snapshot)
        self.assertEqual(DIAGNOSTICS.observe(output, self.proc), 0)
        (self.output_path / "observer.log").write_bytes(b"x" * DIAGNOSTICS.FILE_BYTES)
        self.assertEqual(DIAGNOSTICS.stop(output), 0)
        total = sum(path.stat().st_size for path in self.output_path.iterdir())
        self.assertLessEqual(total, DIAGNOSTICS.MIN_BYTES)
        self.assertEqual(output.load("done.json")["reason"], "output_bytes")
        sample_bytes = sum(path.stat().st_size for path in self.output_path.glob("sample-*.json"))
        self.assertEqual(output.load("done.json")["sample_bytes"], sample_bytes)

    def test_stop_marker_prevents_observer_reads_without_signaling_any_pid(self):
        output = self.start_fixture()
        output.record("stop.json", {"requested": DIAGNOSTICS.bracket()})
        sampler = self.patch(DIAGNOSTICS, "Sampler")
        kill = self.patch(DIAGNOSTICS.os, "kill")
        self.assertEqual(DIAGNOSTICS.observe(output, self.proc), 0)
        self.assertEqual(DIAGNOSTICS.stop(output), 0)
        sampler.assert_not_called()
        kill.assert_not_called()
        self.assertEqual(output.load("done.json")["reason"], "stop_requested")

    def test_stop_polling_wait_is_bounded_and_dead_observer_is_failure(self):
        output = self.start_fixture()
        self.assertEqual(DIAGNOSTICS.stop(output), 1)
        self.assertEqual(self.clock.now, 1000 + DIAGNOSTICS.STOP_WAIT)
        self.assertLessEqual(DIAGNOSTICS.STOP_WAIT, 20)
        self.assertEqual(output.load("stop-result.json")["reason"], "observer_done_timeout")
        self.assertTrue(output.exists("stop.json"))
        self.assertFalse(output.exists("done.json"))

    def test_stop_wait_accepts_atomic_completion_and_repeated_stop(self):
        output = self.start_fixture()

        def finish_during_wait(seconds):
            self.clock.sleep(seconds)
            output.publish("done.json", DIAGNOSTICS.completion("completed", "stop_requested", 1, 100))

        self.patch(DIAGNOSTICS.time, "sleep", finish_during_wait)
        self.assertEqual(DIAGNOSTICS.stop(output), 0)
        self.assertEqual(DIAGNOSTICS.stop(output), 0)
        self.assertEqual(self.clock.now, 1000.25)

    def test_observer_exception_retained_and_not_blanket_success(self):
        output = self.start_fixture(interval=2)
        sampler = self.patch(DIAGNOSTICS, "Sampler", side_effect=RuntimeError("fixture observer failure"))
        self.assertEqual(DIAGNOSTICS.observe(output, self.proc), 1)
        sampler.assert_called_once()
        self.assertEqual(output.load("done.json")["error"]["kind"], "RuntimeError")
        self.assertEqual(DIAGNOSTICS.stop(output), 1)
        self.assertEqual(output.load("stop-result.json")["reason"], "observer_failed")

    def test_duplicate_observer_rejected_without_replacing_first_claim(self):
        output = self.start_fixture()
        output.record("observer.json", {"fixture": "first observer"})
        with self.assertRaises(FileExistsError):
            DIAGNOSTICS.observe(output, self.proc)
        self.assertEqual(output.load("observer.json"), {"fixture": "first observer"})
        self.assertFalse(output.exists("done.json"))

    def test_not_started_output_has_structured_stop_failure(self):
        output = self.output()
        self.assertEqual(DIAGNOSTICS.stop(output), 1)
        self.assertEqual(output.load("stop-result.json")["reason"], "missing_or_invalid_start")
        self.assertEqual(self.clock.sleeps, [])

    def test_missing_output_cli_reports_failure_without_creating_directory(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            self.assertEqual(DIAGNOSTICS.main(["stop", "--output", str(self.output_path)]), 1)
        self.assertFalse(self.output_path.exists())
        self.assertEqual(json.loads(stderr.getvalue())["collection_status"], "failed")

    def test_cli_start_failure_retained_and_no_proc_access(self):
        original_start = DIAGNOSTICS.start
        self.patch(DIAGNOSTICS, "start", side_effect=lambda output, settings: original_start(output, settings, self.root / "missing-proc"))
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            self.assertEqual(DIAGNOSTICS.main(["start", "--output", str(self.output_path)]), 1)
        self.assertEqual(json.loads(stderr.getvalue())["collection_status"], "failed")
        self.assertTrue((self.output_path / "start-failure.json").is_file())
        self.launch.assert_not_called()

    def test_unknown_cli_limits_rejected_before_output_creation(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            self.assertEqual(DIAGNOSTICS.main(["start", "--output", str(self.output_path), "--duration", "nan"]), 1)
        self.assertFalse(self.output_path.exists())
        self.launch.assert_not_called()

    def test_invalid_limits_rejected_and_hard_maxima_preserved(self):
        invalid = ({"duration": 1201}, {"duration": 0}, {"duration": -1},
                   {"duration": float("nan")}, {"duration": float("inf")},
                   {"interval": 1.99}, {"interval": float("nan")},
                   {"max_samples": 601}, {"max_samples": 0}, {"max_samples": 1.5},
                   {"max_samples": True}, {"max_bytes": DIAGNOSTICS.MAX_BYTES + 1},
                   {"max_bytes": DIAGNOSTICS.MIN_BYTES - 1})
        for settings in invalid:
            with self.subTest(settings=settings), self.assertRaises(ValueError):
                DIAGNOSTICS.limits(**settings)
        self.assertEqual(DIAGNOSTICS.limits()["duration"], 1200)
        self.assertEqual(DIAGNOSTICS.limits(interval=2)["max_bytes"], 64 * 1024 * 1024)

    def test_tampered_manifest_cannot_extend_duration_or_byte_budget(self):
        output = self.start_fixture()
        manifest = output.load("manifest.json")
        alterations = ({"deadline": float("inf")}, {"deadline": manifest["deadline"] + 1},
                       {"limits": {**manifest["limits"], "max_bytes": DIAGNOSTICS.MAX_BYTES + 1}},
                       {"baseline_bytes": manifest["baseline_bytes"] + 1}, {"schema": 2})
        for alteration in alterations:
            with self.subTest(alteration=alteration):
                (self.output_path / "manifest.json").write_bytes(DIAGNOSTICS.json_bytes({**manifest, **alteration}))
                with self.assertRaises(ValueError):
                    DIAGNOSTICS.load_manifest(output)

    def test_oversized_or_symlinked_metadata_rejected_before_unbounded_read(self):
        output = self.start_fixture()
        manifest = self.output_path / "manifest.json"
        manifest.write_bytes(b"x" * (DIAGNOSTICS.METADATA_BYTES + 1))
        with self.assertRaises(ValueError):
            output.load("manifest.json")
        manifest.unlink()
        manifest.symlink_to(self.proc / "stat")
        self.assertEqual(DIAGNOSTICS.stop(output), 1)
        self.assertEqual(output.load("stop-result.json")["collection_status"], "failed")

    def test_symlinked_stop_marker_and_forged_health_completion_fail(self):
        output = self.start_fixture()
        (self.output_path / "stop.json").symlink_to(self.proc / "stat")
        self.assertEqual(DIAGNOSTICS.stop(output), 1)
        (self.output_path / "stop.json").unlink()
        output.record("done.json", {"schema": 1, "collection_status": "completed", "diagnostics_only": True,
                                    "health_assessment": "healthy", "reason": "fixture", "finished": {}})
        self.assertEqual(DIAGNOSTICS.stop(output), 1)

    def test_partial_completion_marker_fails_instead_of_succeeding(self):
        output = self.start_fixture()
        output.write("done.json", b'{"collection_status":"completed"')
        self.assertEqual(DIAGNOSTICS.stop(output), 1)
        self.assertEqual(output.load("stop-result.json")["collection_status"], "failed")

    def test_metadata_and_sample_writers_enforce_per_file_limits(self):
        output = self.output()
        with self.assertRaises(ValueError):
            output.write("oversized.json", b"x" * (DIAGNOSTICS.METADATA_BYTES + 1))
        self.assertFalse(output.exists("oversized.json"))
        with self.assertRaises(ValueError):
            output.write("sample-0000.json", b"x" * (DIAGNOSTICS.FILE_BYTES + 1), DIAGNOSTICS.FILE_BYTES)
        self.assertFalse(output.exists("sample-0000.json"))


if __name__ == "__main__":
    unittest.main()
