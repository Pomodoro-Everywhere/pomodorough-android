#!/usr/bin/env python3
"""Passive, bounded Linux proc evidence; never Android health/native acceptance.

Start before launching the emulator; stop from an always-run workflow step using
the same fresh, dedicated output directory. No ADB or device commands are run.
Files and directory traversal are bounded per sample. The global byte budget
reserves space for every lifecycle file and the observer's OS-limited stdio log.
Proc reads are not atomic: identity validation and timestamp brackets expose races.
"""

from __future__ import annotations

import argparse
import errno
import json
import math
import os
from pathlib import Path
import re
import resource
import stat
import subprocess
import sys
import time


MAX_SECONDS = 1200
MAX_SAMPLES = 600
MAX_BYTES = 64 * 1024 * 1024
FILE_BYTES = 256 * 1024
METADATA_BYTES = 4096
METADATA_RESERVE = 64 * 1024
MIN_BYTES = 1024 * 1024
MAX_READ_BYTES = 128 * 1024
MAX_READS = 512
MAX_ENTRIES = 2048
MAX_PROCESSES = 16
MAX_THREADS = 64
MAX_FDS = 64
MAX_ERRORS = 32
STOP_WAIT = 10
POLL_SECONDS = 0.25
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
FILE_FLAGS = os.O_NOFOLLOW | os.O_NONBLOCK
SELECTED_COMM = re.compile(r"(?:adb|emulator(?:64)?(?:-[A-Za-z0-9_-]+)?|qemu(?:-[A-Za-z0-9_-]+)?)")
SYSTEM_FILES = ("stat", "pressure/cpu", "pressure/io", "pressure/memory",
                "meminfo", "loadavg", "uptime")


class CollectionEnded(Exception):
    pass


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=True, separators=(",", ":")) + "\n").encode()


def failure_record(failure):
    return {"kind": type(failure).__name__, "message": str(failure)[:240],
            "errno": getattr(failure, "errno", None)}


def bracket():
    return {"wall_seconds": time.time(), "monotonic_seconds": time.monotonic()}


def open_directory(path, create=False, fresh=False):
    absolute = Path(os.path.abspath(path))
    descriptor = os.open(absolute.anchor, DIRECTORY_FLAGS)
    try:
        for index, part in enumerate(absolute.parts[1:]):
            if create:
                try:
                    os.mkdir(part, 0o700, dir_fd=descriptor)
                except FileExistsError:
                    if fresh and index == len(absolute.parts) - 2:
                        raise
            child = os.open(part, DIRECTORY_FLAGS, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        if fresh and len(absolute.parts) == 1:
            raise FileExistsError("Output must be a fresh dedicated directory")
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def open_relative(directory, path, flags):
    parts = path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError("Unsafe relative path")
    parent = os.dup(directory)
    try:
        for part in parts[:-1]:
            child = os.open(part, DIRECTORY_FLAGS, dir_fd=parent)
            os.close(parent)
            parent = child
        return os.open(parts[-1], flags, dir_fd=parent)
    finally:
        os.close(parent)


def read_regular(directory, path, limit):
    descriptor = open_relative(directory, path, os.O_RDONLY | FILE_FLAGS)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise OSError(errno.EINVAL, "Not a regular proc/metadata file")
        return os.read(descriptor, limit + 1)
    finally:
        os.close(descriptor)


class Output:
    def __init__(self, path, fresh=False):
        self.path = Path(os.path.abspath(path))
        self.directory = open_directory(self.path, create=fresh, fresh=fresh)

    def close(self):
        os.close(self.directory)

    def exists(self, name):
        try:
            entry = os.stat(name, dir_fd=self.directory, follow_symlinks=False)
        except FileNotFoundError:
            return False
        if not stat.S_ISREG(entry.st_mode) or entry.st_nlink != 1:
            raise ValueError("Invalid lifecycle marker: " + name)
        return True

    def write(self, name, payload, limit=METADATA_BYTES):
        if len(payload) > limit:
            raise ValueError("Artifact byte limit exceeded: " + name)
        descriptor = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | FILE_FLAGS,
                             0o600, dir_fd=self.directory)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)

    def record(self, name, value):
        self.write(name, json_bytes(value))

    def publish(self, name, value):
        temporary = name + ".pending"
        self.record(temporary, value)
        os.replace(temporary, name, src_dir_fd=self.directory, dst_dir_fd=self.directory)

    def load(self, name):
        payload = read_regular(self.directory, name, METADATA_BYTES)
        if len(payload) > METADATA_BYTES:
            raise ValueError("Oversized lifecycle metadata: " + name)
        value = json.loads(payload)
        if not isinstance(value, dict):
            raise ValueError("Lifecycle metadata must be an object")
        return value

    def sample(self, sequence, snapshot):
        payload = bounded_snapshot(snapshot)
        self.write(f"sample-{sequence:04d}.json", payload, FILE_BYTES)
        return len(payload)


def bounded_snapshot(snapshot):
    payload = json_bytes(snapshot)
    records = sorted(snapshot["files"].values(), key=lambda item: len(item.get("text", "")), reverse=True)
    for record in records:
        if len(payload) <= FILE_BYTES:
            return payload
        if "text" in record:
            record["omitted_characters"] = len(record.pop("text"))
            record["truncated"] = True
            snapshot["limits"] = sorted(set(snapshot["limits"]) | {"sample_bytes"})
            payload = json_bytes(snapshot)
    if len(payload) > FILE_BYTES:
        raise ValueError("Sample metadata exceeded fixed bounds")
    return payload


class Sampler:
    def __init__(self, proc_root, deadline, stopped=lambda: False):
        self.root = open_directory(proc_root)
        self.deadline = deadline
        self.stopped = stopped
        self.read_bytes = MAX_READ_BYTES
        self.reads = MAX_READS
        self.entries = MAX_ENTRIES
        self.threads = MAX_THREADS
        self.fds = MAX_FDS
        self.files = {}
        self.errors = []
        self.errors_dropped = 0
        self.limits = set()

    def checkpoint(self):
        if time.monotonic() >= self.deadline:
            raise CollectionEnded("deadline")
        if self.stopped():
            raise CollectionEnded("stop_requested")

    def problem(self, path, failure):
        if len(self.errors) < MAX_ERRORS:
            self.errors.append({"path": path, **failure_record(failure)})
        else:
            self.errors_dropped += 1

    def read(self, path, limit=4096, retain=True, label=None):
        self.checkpoint()
        record = {}
        if self.reads <= 0 or self.read_bytes <= 1:
            self.limits.add("read_budget")
            record["error"] = {"kind": "read_budget"}
        else:
            self.reads -= 1
            limit = min(limit, self.read_bytes - 1)
            try:
                payload = read_regular(self.root, path, limit)
                self.read_bytes -= len(payload)
                record = {"text": payload[:limit].decode("utf-8", errors="replace"),
                          "truncated": len(payload) > limit}
                try:
                    payload[:limit].decode("utf-8")
                except UnicodeDecodeError as failure:
                    record["decode_error"] = True
                    self.problem(path, failure)
                if record["truncated"]:
                    self.limits.add("file_bytes")
            except (OSError, ValueError) as failure:
                record["error"] = failure_record(failure)
                self.problem(path, failure)
        if retain:
            self.files[label or path] = record
        return record

    def numeric_entries(self, path):
        self.checkpoint()
        descriptor = None
        names = []
        try:
            descriptor = os.dup(self.root) if not path else open_relative(self.root, path, DIRECTORY_FLAGS)
            with os.scandir(descriptor) as entries:
                while self.entries > 0:
                    self.checkpoint()
                    entry = next(entries, None)
                    if entry is None:
                        break
                    self.entries -= 1
                    if re.fullmatch(r"[0-9]{1,10}", entry.name):
                        names.append(entry.name)
                if self.entries == 0:
                    self.limits.add("directory_entries")
        except (OSError, ValueError) as failure:
            self.problem(path, failure)
        finally:
            if descriptor is not None:
                os.close(descriptor)
        return sorted(names, key=int)

    def identity(self, path, pid, label=None):
        record = self.read(path, label=label)
        try:
            if record.get("truncated") or "error" in record:
                raise ValueError("Incomplete process/thread stat")
            prefix, separator, fields = record["text"].rpartition(") ")
            numeric_pid, comm = prefix.split(" (", 1)
            starttime = fields.split()[19]
            if not separator or numeric_pid != pid or not starttime.isascii() or not starttime.isdecimal():
                raise ValueError("Invalid process/thread identity")
            return {"pid": int(pid), "starttime": starttime, "comm": comm}
        except (ValueError, IndexError, KeyError) as failure:
            self.problem(path, failure)
            return None

    def collect_threads(self, pid):
        identities = []
        for thread in self.numeric_entries(f"{pid}/task"):
            if self.threads == 0 or self.reads <= 0:
                self.limits.add("threads_or_reads")
                break
            self.threads -= 1
            prefix = f"{pid}/task/{thread}"
            before = self.identity(prefix + "/stat", thread)
            self.read(prefix + "/schedstat")
            after = self.identity(prefix + "/stat", thread, prefix + "/stat.after")
            identities.append({"before": before, "after": after,
                               "valid": before is not None and before == after})
        return identities

    def socket_link(self, pid, number):
        self.checkpoint()
        directory = open_relative(self.root, f"{pid}/fd", DIRECTORY_FLAGS)
        try:
            target = os.readlink(number, dir_fd=directory)
            if re.fullmatch(r"socket:\[[0-9]{1,20}\]", target):
                return {"fd": int(number), "socket": target}
            return None
        finally:
            os.close(directory)

    def collect_sockets(self, pid):
        sockets = []
        for number in self.numeric_entries(f"{pid}/fd"):
            if self.fds == 0:
                self.limits.add("fd_links")
                break
            self.fds -= 1
            try:
                link = self.socket_link(pid, number)
                if link is not None:
                    sockets.append(link)
            except (OSError, ValueError) as failure:
                self.problem(f"{pid}/fd/{number}", failure)
        return sockets

    def process(self, pid, comm):
        self.files[f"{pid}/comm"] = {"text": comm, "truncated": False}
        before = self.identity(f"{pid}/stat", pid)
        result = {"before": before, "after": None, "valid": False}
        if before is None or before["comm"] != comm:
            result["reason"] = "identity_unavailable_or_comm_changed"
            return result
        for name in ("status", "schedstat"):
            self.read(f"{pid}/{name}")
        result["threads"] = self.collect_threads(pid)
        if comm == "adb":
            result["socket_links"] = self.collect_sockets(pid)
        after = self.identity(f"{pid}/stat", pid, f"{pid}/stat.after")
        result.update(after=after, valid=before == after)
        if not result["valid"]:
            result["reason"] = "identity_changed_or_disappeared"
        return result

    def processes(self, destination):
        for pid in self.numeric_entries(""):
            if len(destination) >= MAX_PROCESSES or self.reads <= 0 or self.read_bytes <= 1:
                self.limits.add("processes_or_reads")
                break
            comm = self.read(f"{pid}/comm", 64, retain=False)
            name = comm.get("text", "").rstrip("\n")
            if not comm.get("truncated") and SELECTED_COMM.fullmatch(name):
                destination.append(self.process(pid, name))

    def snapshot(self, phase):
        snapshot = {"phase": phase, "started": bracket(), "files": self.files,
                    "processes": [], "errors": self.errors, "diagnostics_only": True,
                    "collector_pid": os.getpid(), "network_source": f"{os.getpid()}/net"}
        try:
            for name in SYSTEM_FILES:
                self.read(name, 8192 if name == "stat" else 4096)
            for name in ("tcp", "tcp6", "unix"):
                self.read(f"{os.getpid()}/net/{name}", 8192, label=f"net/{name}")
            self.processes(snapshot["processes"])
            self.checkpoint()
        except CollectionEnded as ended:
            snapshot["interrupted"] = str(ended)
        finally:
            snapshot.update(finished=bracket(), limits=sorted(self.limits),
                            errors_dropped=self.errors_dropped,
                            read_bytes=MAX_READ_BYTES - self.read_bytes,
                            read_operations=MAX_READS - self.reads,
                            directory_entries=MAX_ENTRIES - self.entries)
            os.close(self.root)
        return snapshot


def limits(duration=MAX_SECONDS, interval=5, max_samples=MAX_SAMPLES, max_bytes=MAX_BYTES):
    values = {"duration": duration, "interval": interval,
              "max_samples": max_samples, "max_bytes": max_bytes}
    if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
           for value in values.values()):
        raise ValueError("Limits must be finite numbers")
    if not 0 < duration <= MAX_SECONDS or not 2 <= interval <= MAX_SECONDS:
        raise ValueError("Duration must be (0, 1200]; interval must be [2, 1200] seconds")
    if type(max_samples) is not int or not 1 <= max_samples <= MAX_SAMPLES:
        raise ValueError("Sample limit must be an integer in [1, 600], including baseline")
    if type(max_bytes) is not int or not MIN_BYTES <= max_bytes <= MAX_BYTES:
        raise ValueError("Byte limit must be an integer in [1048576, 67108864]")
    return values


def completion(status, reason, samples, sample_bytes, errors=0):
    return {"schema": 1, "collection_status": status, "reason": reason,
            "samples": samples, "sample_bytes": sample_bytes, "observed_errors": errors,
            "finished": bracket(), "diagnostics_only": True, "health_assessment": "not_performed"}


def install_file_limit():
    resource.setrlimit(resource.RLIMIT_FSIZE, (FILE_BYTES, FILE_BYTES))


def launch_observer(output):
    descriptor = os.open("observer.log", os.O_RDWR | os.O_CREAT | os.O_EXCL | FILE_FLAGS,
                         0o600, dir_fd=output.directory)
    with os.fdopen(descriptor, "r+b", buffering=0) as log:
        subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "observe",
                          "--output", str(output.path)], stdin=log, stdout=log, stderr=log,
                         start_new_session=True, close_fds=True, preexec_fn=install_file_limit)


def start(output, settings, proc_root="/proc"):
    started = bracket()
    deadline = started["monotonic_seconds"] + settings["duration"]
    snapshot = Sampler(proc_root, deadline).snapshot("baseline")
    sample_bytes = output.sample(0, snapshot)
    manifest = {"schema": 1, "started": started, "deadline": deadline, "limits": settings,
                "baseline_bytes": sample_bytes, "baseline_errors": len(snapshot["errors"]) + snapshot["errors_dropped"],
                "diagnostics_only": True, "health_assessment": "not_performed",
                "bounds": {"file_bytes": FILE_BYTES, "log_bytes": FILE_BYTES,
                           "metadata_reserve": METADATA_RESERVE, "read_bytes_per_sample": MAX_READ_BYTES,
                           "reads_per_sample": MAX_READS, "entries_per_sample": MAX_ENTRIES,
                           "processes_per_sample": MAX_PROCESSES, "threads_per_sample": MAX_THREADS,
                           "fd_links_per_sample": MAX_FDS, "stop_wait_seconds": STOP_WAIT}}
    output.record("manifest.json", manifest)
    if "interrupted" in snapshot or time.monotonic() >= deadline:
        output.publish("done.json", completion("failed", "baseline_deadline", 1, sample_bytes))
        return 1
    try:
        launch_observer(output)
    except (OSError, subprocess.SubprocessError) as failure:
        result = completion("failed", "observer_launch_failed", 1, sample_bytes)
        result["error"] = failure_record(failure)
        output.publish("done.json", result)
        return 1
    return 0


def load_manifest(output):
    manifest = output.load("manifest.json")
    if manifest.get("schema") != 1 or manifest.get("diagnostics_only") is not True:
        raise ValueError("Not a started diagnostics output")
    manifest["limits"] = limits(**manifest["limits"])
    deadline = manifest["deadline"]
    started = manifest["started"]["monotonic_seconds"]
    duration = manifest["limits"]["duration"]
    if not all(type(value) in (int, float) and math.isfinite(value) for value in (deadline, started)):
        raise ValueError("Invalid monotonic deadline")
    if deadline != started + duration or started > time.monotonic():
        raise ValueError("Invalid absolute collection deadline")
    baseline = os.stat("sample-0000.json", dir_fd=output.directory, follow_symlinks=False)
    if not stat.S_ISREG(baseline.st_mode) or baseline.st_nlink != 1 or not 0 < baseline.st_size <= FILE_BYTES:
        raise ValueError("Invalid baseline artifact")
    if baseline.st_size != manifest["baseline_bytes"]:
        raise ValueError("Baseline artifact size changed")
    return manifest


def wait_interval(output, deadline, next_sample):
    while True:
        if output.exists("stop.json"):
            return "stop_requested"
        remaining = min(deadline, next_sample) - time.monotonic()
        if remaining <= 0:
            return "deadline" if time.monotonic() >= deadline else None
        time.sleep(min(POLL_SECONDS, remaining))


def observe_samples(output, manifest, result, proc_root):
    settings = manifest["limits"]
    budget = settings["max_bytes"] - FILE_BYTES - METADATA_RESERVE
    next_sample = manifest["started"]["monotonic_seconds"] + settings["interval"]
    while result["samples"] < settings["max_samples"]:
        reason = wait_interval(output, manifest["deadline"], next_sample)
        if reason:
            return reason
        if budget - result["sample_bytes"] < FILE_BYTES:
            return "output_bytes"
        snapshot = Sampler(proc_root, manifest["deadline"], lambda: output.exists("stop.json")).snapshot("observe")
        result["sample_bytes"] += output.sample(result["samples"], snapshot)
        result["samples"] += 1
        result["observed_errors"] += len(snapshot["errors"]) + snapshot["errors_dropped"]
        if "interrupted" in snapshot:
            return snapshot["interrupted"]
        next_sample = time.monotonic() + settings["interval"]
    return "sample_count"


def observe(output, proc_root="/proc"):
    manifest = load_manifest(output)
    if output.exists("done.json"):
        raise ValueError("Collection already finished")
    output.record("observer.json", {"started": bracket(), "diagnostics_only": True})
    result = completion("completed", "pending", 1, manifest["baseline_bytes"], manifest["baseline_errors"])
    try:
        result["reason"] = observe_samples(output, manifest, result, proc_root)
    except Exception as failure:
        result.update(collection_status="failed", reason="observer_failed", error=failure_record(failure))
    result["finished"] = bracket()
    output.publish("done.json", result)
    return 0 if result["collection_status"] == "completed" else 1


def stop(output):
    try:
        load_manifest(output)
        if not output.exists("stop.json"):
            try:
                output.record("stop.json", {"requested": bracket(), "diagnostics_only": True})
            except FileExistsError:
                pass
        deadline = time.monotonic() + STOP_WAIT
        while not output.exists("done.json") and time.monotonic() < deadline:
            time.sleep(min(POLL_SECONDS, max(0, deadline - time.monotonic())))
        result = output.load("done.json") if output.exists("done.json") else completion(
            "failed", "observer_done_timeout", 0, 0)
        if result.get("schema") != 1 or result.get("collection_status") not in ("completed", "failed"):
            raise ValueError("Invalid observer completion marker")
        if result.get("diagnostics_only") is not True or result.get("health_assessment") != "not_performed":
            raise ValueError("Invalid observer completion marker")
        if not isinstance(result.get("reason"), str) or not isinstance(result.get("finished"), dict):
            raise ValueError("Invalid observer completion marker")
    except (OSError, ValueError, KeyError, TypeError) as failure:
        result = completion("failed", "missing_or_invalid_start", 0, 0)
        result["error"] = failure_record(failure)
    output.publish("stop-result.json", result)
    return 0 if result["collection_status"] == "completed" else 1


def arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("start", "stop", "observe"):
        child = commands.add_parser(command)
        child.add_argument("--output", required=True)
        if command == "start":
            child.add_argument("--duration", type=float, default=MAX_SECONDS)
            child.add_argument("--interval", type=float, default=5)
            child.add_argument("--max-samples", type=int, default=MAX_SAMPLES)
            child.add_argument("--max-bytes", type=int, default=MAX_BYTES)
    return parser.parse_args(argv)


def main(argv=None):
    options = arguments(argv)
    output = None
    try:
        settings = limits(options.duration, options.interval, options.max_samples, options.max_bytes) if options.command == "start" else None
        output = Output(options.output, fresh=options.command == "start")
        if options.command == "start":
            return start(output, settings)
        return stop(output) if options.command == "stop" else observe(output)
    except Exception as failure:
        result = completion("failed", "command_failed_or_not_started", 0, 0)
        result["error"] = failure_record(failure)
        if output is not None and options.command == "start":
            try:
                output.publish("start-failure.json", result)
            except OSError as recording_failure:
                result["recording_error"] = failure_record(recording_failure)
        print(json_bytes(result).decode(), end="", file=sys.stderr)
        return 1
    finally:
        if output is not None:
            output.close()


if __name__ == "__main__":
    sys.exit(main())
