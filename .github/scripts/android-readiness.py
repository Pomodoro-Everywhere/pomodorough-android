#!/usr/bin/env python3

"""Bounded observations, not a whole-framework health or service-integrity proof.

The envelopes follow public AOSP emitters: ProcessList.dumpProcessesLSP and
ActivityManagerService.dumpOtherProcessesInfoLSP (android-16.0.0_r1), WMS
doDump/dumpWindowsNoHeaderLocked (be42921e05ba3d1946efc090054cd4a498f22b80),
RootWindowContainer traversal (e7627bd73223e4f20a49a92acf42f4275aaa8c5e),
DisplayContent.dump (android16-release blob deee44dd7f617b01197bd859689bb8eca5c76cb2),
and InputDispatcher.dump/dumpDispatchStateLocked
(18c754e18499acce28e8be58846879075ade72a7). These mixed-revision, tool-rendered
source observations are NOT authenticated API 35/36 dumps or compatibility proof.

Only relevant sections are interpreted. Unrelated delegated record payloads,
the input-service prefix and outer window-service sections are opaque.
Source-emitted following sections and postambles reject ordinary prefix
truncation; they cannot detect arbitrary
interior record deletion, forged output, power cuts after collection, or future
failures. Conditional process-error omission is meaningful only within that
framed traversal. Any emitted error branch rejects, including false flags with
dialog objects or bad=true. Retained ANR history rejects by evidence policy, NOT
because historical state establishes a current error. Never borrow its focus.
The supported input subset includes source-shaped display/monitor lists; queued
connection payloads remain unsupported rather than inventing their dump grammar.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time


class HealthFailure(RuntimeError):
    pass


class DeviceSession:
    def __init__(self, output: Path, phase: str, seconds: float):
        output.mkdir(parents=True, exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix=f"{phase}-", dir=output))
        self.deadline = time.monotonic() + seconds
        self.sequence = 0
        self.record("phase", {"phase": phase, "started_at": time.time(), "budget": seconds,
                              "grammar": "source-bounded-observations-unvalidated-api35-api36-v2",
                              "history_policy": "reject-retained-errors-not-active-error-proof"})

    def record(self, name: str, value: object) -> None:
        (self.directory / f"{name}.json").write_text(json.dumps(value, indent=2) + "\n")

    def capture(self, arguments: list[str], required: bool = True) -> bytes:
        self.sequence += 1
        stem = self.directory / f"{self.sequence:03d}"
        budget = min(10, self.deadline - time.monotonic())
        with stem.with_suffix(".stdout").open("wb") as stdout:
            with stem.with_suffix(".stderr").open("wb") as stderr:
                status = run_bounded(["adb", *arguments], stdout, stderr, budget)
        self.record(stem.name, {"command": ["adb", *arguments], "status": status,
                                "budget": max(0, budget), "finished_at": time.time()})
        if arguments == ["exec-out", "screencap", "-p"] and status == 0:
            stem.with_suffix(".png").hardlink_to(stem.with_suffix(".stdout"))
        if required and status:
            raise HealthFailure(f"Device command failed ({status}); evidence: {stem}")
        if not required:
            return b""
        if stem.with_suffix(".stdout").stat().st_size > 16 * 1024 * 1024:
            raise HealthFailure(f"Oversized device response; evidence: {stem}")
        return stem.with_suffix(".stdout").read_bytes()

    def text(self, *arguments: str) -> str:
        return self.snapshot(*arguments).strip()

    def snapshot(self, *arguments: str) -> str:
        try:
            return self.capture(list(arguments)).decode("utf-8").replace("\r\n", "\n")
        except UnicodeDecodeError as failure:
            raise HealthFailure(f"Invalid device text; evidence: {self.directory}") from failure


def run_bounded(command: list[str], stdout, stderr, budget: float) -> int:
    if budget <= 0:
        stderr.write(b"Absolute command deadline exhausted\n")
        return 124
    try:
        with subprocess.Popen(command, stdout=stdout, stderr=stderr, start_new_session=True) as process:
            try:
                return process.wait(timeout=budget)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                stderr.write(b"Command timed out; command process group killed\n")
                return 124
    except OSError as failure:
        stderr.write(f"Cannot execute command: {failure}\n".encode())
        return 127


def canonical_component(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z_][\w.]*/\.?[A-Za-z_][\w.$]*", value):
        raise HealthFailure(f"Unknown component grammar: {value!r}")
    package, activity = value.split("/")
    return f"{package}/{package + activity if activity.startswith('.') else activity}"


def resolve_home(session: DeviceSession) -> str:
    return canonical_component(session.text(
        "shell", "cmd", "package", "resolve-activity", "--brief", "--user", "0",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
    ))


def single_field(snapshot: str, name: str, separator: str = "=") -> str:
    values = re.findall(rf"^\s*{re.escape(name + separator)}([^\n]*)$", snapshot, re.MULTILINE)
    if len(values) != 1:
        raise HealthFailure(f"Unknown or ambiguous dump field: {name}")
    return values[0].strip()


def dump_text(snapshot: str) -> str:
    if not snapshot.endswith("\n") or re.search(r"[\x00-\x09\x0b-\x1f\x7f]", snapshot):
        raise HealthFailure("Unknown or unterminated dump text")
    if re.search(r"(?im)^\s*(?:DUMP TIMEOUT|DUMP OF SERVICE|Error dumping|Exception|Traceback|FAILED)\b",
                 snapshot):
        raise HealthFailure("Dump reports failed or unsupported collection")
    return snapshot


def section_between(snapshot: str, start: str, end: str) -> str:
    starts = list(re.finditer(rf"^{re.escape(start)}$", snapshot, re.MULTILINE))
    ends = list(re.finditer(rf"^{re.escape(end)}$", snapshot, re.MULTILINE))
    if len(starts) != 1 or len(ends) != 1 or starts[0].end() >= ends[0].start():
        raise HealthFailure(f"Missing, reordered or ambiguous section: {start}")
    return snapshot[starts[0].end() + 1:ends[0].start()]


def ordered_fields(snapshot: str, patterns: tuple[str, ...]) -> list[re.Match]:
    matches = []
    for pattern in patterns:
        found = list(re.finditer(rf"^ *{pattern} *$", snapshot, re.MULTILINE))
        if len(found) != 1 or (matches and found[0].start() <= matches[-1].end()):
            raise HealthFailure(f"Missing, malformed or reordered field: {pattern}")
        matches.append(found[0])
    return matches


def current_display_focus(snapshot: str) -> tuple[str, bool]:
    dump_text(snapshot)
    if not snapshot.startswith("WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n"):
        raise HealthFailure("Unknown full window dump prefix")
    history = section_between(snapshot, "WINDOW MANAGER LAST ANR (dumpsys window lastanr)",
                              "WINDOW MANAGER POLICY STATE (dumpsys window policy)")
    sessions = "WINDOW MANAGER SESSIONS (dumpsys window sessions)"
    if snapshot.count(sessions) != 1:
        raise HealthFailure("Missing current window session/display boundary")
    section_between(snapshot, "WINDOW MANAGER POLICY STATE (dumpsys window policy)", sessions)
    current = snapshot.split(sessions, 1)[1]
    displays = section_between(current, "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)",
                               "WINDOW MANAGER TOKENS (dumpsys window tokens)")
    headers = re.findall(r"^ *Display: mDisplayId=(\d+)(?: \(organized\))? *$", displays, re.MULTILINE)
    if headers != ["0"]:
        raise HealthFailure("Unknown or unsupported current display traversal")
    ordered_fields(displays, (r"Display: mDisplayId=0(?: \(organized\))?",
                             r"(?:mHasSetIgnoreOrientationRequest=true )?ignoreOrientationRequest=(?:true|false)",
                             r"mLayoutSeq=\d+", r"mCurrentFocus=.+", r"mFocusedApp=.+",
                             r"mHoldScreenWindow=.+", r"mObscuringWindow=.+",
                             r"mLastWakeLockHoldingWindow=.+", r"mLastWakeLockObscuringWindow=.+",
                             r"Display areas in top down Z order:", r"Task display areas in top down Z order:"))
    focus = single_field(displays, "mCurrentFocus")
    if not re.search(r"^ *mLayoutSeq=\d+\n *mCurrentFocus=[^\n]+\n *mFocusedApp=[^\n]+$",
                     displays, re.MULTILINE):
        raise HealthFailure("Malformed current focus region")
    if len(re.findall(r"^ *m(?:CurrentFocus|FocusedApp)\S*", displays, re.MULTILINE)) != 2:
        raise HealthFailure("Unknown current display focus field")
    history_lines = [line.strip() for line in history.splitlines() if line.strip().strip("-")]
    return focus, history_lines != ["<no ANR has occurred since boot>"]


def window_postamble(snapshot: str) -> str:
    dump_text(snapshot)
    header = "WINDOW MANAGER WINDOWS (dumpsys window windows)\n"
    if not snapshot.startswith(header) or snapshot.count(header) != 1:
        raise HealthFailure("Unknown scoped window dump header")
    fields = ordered_fields(snapshot, (
        r"mGlobalConfiguration=.+", r"mHasPermanentDpad=(?:true|false)", r"mTopFocusedDisplayId=0",
        r"mInTouchMode=(?:true|false)", r"mBlurEnabled=(?:true|false)", r"mLastDisplayFreezeDuration=.+",
        r"mLastWakeLockHoldingWindow=.+ mLastWakeLockObscuringWindow=.+",
        r"mSystemBooted=true mDisplayEnabled=true", r"mTransactionSequence=\d+",
        r"mDisplayFrozen=false windows=0 client=false apps=0 mRotation=\S+ mLastOrientation=-?\d+",
        r"waitingForConfig=false", r"Animation settings: disabled=(?:true|false) "
        r"window=\d+(?:\.\d+)? transition=\d+(?:\.\d+)? animator=\d+(?:\.\d+)?"))
    if snapshot[fields[-1].end():].strip():
        raise HealthFailure("Unknown window trailer or active recents animation")
    final_region = snapshot[fields[-5].end():fields[-1].end()]
    for line in final_region.splitlines():
        if line.strip() and not re.fullmatch(r" *(?:mLayoutNeeded on displays=\d+|mTransactionSequence=\d+|"
                                           r"mDisplayFrozen=.*|waitingForConfig=false|Animation settings:.*)", line):
            raise HealthFailure("Unknown window postamble structure")
    return snapshot[len(header):fields[0].start()]


def window_owners(snapshot: str) -> dict[str, tuple[int, str]]:
    traversal = window_postamble(snapshot)
    lists = r"^ *(?:Hiding System Alert Windows:|Remove pending for|Windows force removing|" \
            r"Windows waiting to destroy their surface:|Windows waiting to resize:|" \
            r"Clients waiting for these windows to be drawn:)"
    traversal = re.split(lists, traversal, maxsplit=1, flags=re.MULTILINE)[0]
    records = list(re.finditer(r"^( +)Window #(\d+) (Window\{[^\n{}]+\}):\n", traversal, re.MULTILINE))
    if not records or traversal[:records[0].start()].strip():
        raise HealthFailure("Missing window ownership traversal")
    owners = {}
    for index, record in enumerate(records):
        end = records[index + 1].start() if index + 1 < len(records) else len(traversal)
        body = traversal[record.end():end]
        if int(record[2]) != index or record[3] in owners:
            raise HealthFailure("Malformed or duplicate window record")
        if any(line.strip() and not line.startswith(record[1] + " ") for line in body.splitlines()):
            raise HealthFailure("Unknown window traversal boundary")
        owner = ordered_fields(body, (r"mOwnerUid=(\d+) showForAllUsers=(?:true|false) "
                                      r"package=([\w.]+) appop=\S+",))[0]
        if len(re.findall(r"^ *mOwner\S*", body, re.MULTILINE)) != 1:
            raise HealthFailure("Unknown window owner field")
        owners[record[3]] = (int(owner[1]), owner[2])
    return owners


def window_focus(snapshot: str, ownership: str) -> tuple[str, str] | None:
    focus, historical_anr = current_display_focus(snapshot)
    owners = window_owners(ownership)
    if historical_anr:
        raise HealthFailure("Retained window ANR history rejected by policy; not an active-error inference")
    if focus == "null":
        return None
    match = re.fullmatch(r"Window\{([0-9a-f]+) u0 ([^{}]+)\}", focus)
    if match is None:
        raise HealthFailure(f"Unsupported or obstructing focused window: {focus}")
    component = canonical_component(match[2])
    owner = owners.get(focus)
    if owner is None or owner[0] < 10000 or owner[1] != component.split("/")[0]:
        raise HealthFailure("Focused window owner is unknown or not the expected application owner")
    return match[1], component


def input_sections(snapshot: str) -> tuple[list[tuple[str, list[str]]], bool]:
    dump_text(snapshot)
    history_header = "\nInput Dispatcher State at time of last ANR:\n"
    current, separator, history = snapshot.partition(history_header)
    headers = list(re.finditer(r"^Input Dispatcher State:\n", current, re.MULTILINE))
    if len(headers) != 1 or (separator and not history.strip()):
        raise HealthFailure("Missing or ambiguous input dispatcher envelope")
    current = current[headers[0].end():]
    sections = []
    for line in current.splitlines():
        if re.fullmatch(r"  \S.*", line):
            sections.append((line[2:], []))
        elif line.startswith("    ") and sections:
            sections[-1][1].append(line[4:])
        elif line.strip():
            raise HealthFailure("Unknown input section/trailer")
    return sections, bool(separator)


def input_section_shape(header: str, children: list[str], pattern: str, empty: str,
                        child_pattern: str) -> None:
    if header == pattern + ": " + empty:
        if children:
            raise HealthFailure(f"Unexpected children for empty {pattern}")
    elif header == pattern + ":" and children:
        if any(re.fullmatch(child_pattern, child) is None for child in children):
            raise HealthFailure(f"Malformed {pattern} record")
    else:
        raise HealthFailure(f"Unknown {pattern} section")


def input_queue(header: str, children: list[str], name: str, empty: str = "<empty>") -> None:
    if header == f"{name}: {empty}" and not children:
        return
    match = re.fullmatch(rf"{name}: length=(\d+)", header)
    if match is None or int(match[1]) != len(children) or not children:
        raise HealthFailure(f"Malformed {name} queue")
    if any(re.fullmatch(r"\S.*?, age=-?\d+ms", child) is None for child in children):
        raise HealthFailure(f"Malformed {name} entry")


def input_connections(header: str, children: list[str]) -> None:
    if header == "Connections: <none>" and not children:
        return
    if header != "Connections:" or not children:
        raise HealthFailure("Malformed input connections")
    records = []
    for child in children:
        if re.fullmatch(r"\d+: channelName='[^']*', windowName='[^']*', status=NORMAL, "
                        r"monitor=(?:true|false), responsive=true", child):
            records.append([])
        elif child.startswith("  ") and records:
            records[-1].append(child[2:])
        else:
            raise HealthFailure("Unknown or unresponsive input connection")
    for queues in records:
        if queues != ["OutboundQueue: <empty>", "WaitQueue: <empty>"]:
            raise HealthFailure("Unsupported nonempty or malformed input connection queues")


def input_display(header: str, children: list[str]) -> None:
    if header == "Displays: <none>" and not children:
        return
    if re.fullmatch(r"Display: -?\d+", header) is None:
        raise HealthFailure("Unknown input display header")
    if children == ["Windows: <none>"]:
        return
    if not children or children[0] != "Windows:":
        raise HealthFailure("Unknown input window list")
    pattern = (r"  (\d+): name='[^']*', id=-?\d+, displayId=-?\d+, portalToDisplayId=-?\d+, "
               r"paused=(?:true|false), focusable=(?:true|false), hasWallpaper=(?:true|false), "
               r"visible=(?:true|false), alpha=\d+\.\d+, flags=.+, type=.+, "
               r"frame=\[-?\d+,-?\d+\]\[-?\d+,-?\d+\], globalScale=\d+\.\d+, "
               r"applicationInfo=.*, touchableRegion=.*, inputFeatures=.*, ownerPid=-?\d+, "
               r"ownerUid=-?\d+, dispatchingTimeout=\d+ms, trustedOverlay=(?:true|false), "
               r"hasToken=(?:true|false), touchOcclusionMode=\S+")
    count = 0
    for child in children[1:]:
        record = re.fullmatch(pattern, child)
        if record and int(record[1]) == count:
            count += 1
        elif child.startswith("    ") and count:
            continue
        else:
            raise HealthFailure("Unknown or truncated input window record")
    if not count:
        raise HealthFailure("Missing input window records")


def input_monitors(header: str, children: list[str]) -> None:
    if header == "Monitors: <none>" and not children:
        return
    if re.fullmatch(r"(?:Global|Gesture) monitors in display -?\d+:", header) is None:
        raise HealthFailure("Unknown input monitor header")
    for index, child in enumerate(children):
        if re.fullmatch(rf"{index}: '[^']*', *", child) is None:
            raise HealthFailure("Malformed input monitor record")


def input_touch_states(header: str, children: list[str]) -> None:
    if header == "TouchStates: <no displays touched>" and not children:
        return
    state = r"-?\d+: down=(?:true|false), split=(?:true|false), deviceId=-?\d+, source=0x[0-9a-f]{8}\n"
    window = r"    \d+: name='[^']*', pointerIds=0x[0-9a-f]+, targetFlags=0x[0-9a-f]+\n"
    windows = rf"  Windows:(?: <none>\n|\n(?:{window})+)"
    portal = r"    \d+: name='[^']*'\n"
    portals = rf"(?:  Portal windows:\n(?:{portal})+)?"
    if header != "TouchStatesByDisplay:" or not re.fullmatch(rf"(?:{state}{windows}{portals})+",
                                                            "\n".join(children) + "\n"):
        raise HealthFailure("Unknown or incomplete input touch states")


def validate_input_section(header: str, children: list[str]) -> int:
    scalar_patterns = (r"DispatchEnabled: (?:true|false)", r"DispatchFrozen: (?:true|false)",
                       r"InputFilterEnabled: (?:true|false)", r"FocusedDisplayId: -?\d+")
    for rank, pattern in enumerate(scalar_patterns):
        if re.fullmatch(pattern, header) and not children:
            return rank
    shapes = (("FocusedApplications", "<none>", r"displayId=-?\d+, name='[^']*', dispatchingTimeout=\d+ms"),
              ("FocusedWindows", "<none>", r"displayId=-?\d+(?:, name='[^']+'| has focused token without a window')"),
              ("mPendingFocusRequests", "<none>", r"displayId=-?\d+, token->.+, focusedToken->.+"))
    for rank, (name, empty, pattern) in enumerate(shapes, 4):
        if header.startswith(name + ":"):
            input_section_shape(header, children, name, empty, pattern)
            return rank
    return validate_input_tail(header, children)


def validate_input_tail(header: str, children: list[str]) -> int:
    if header.startswith(("TouchStates:", "TouchStatesByDisplay:")):
        input_touch_states(header, children)
        return 7
    if header.startswith(("Display:", "Displays:")):
        input_display(header, children)
        return 8
    if header.startswith(("Monitors:", "Global monitors ", "Gesture monitors ")):
        input_monitors(header, children)
        return 9
    for name, rank in (("RecentQueue", 10), ("InboundQueue", 12)):
        if header.startswith(name + ":"):
            input_queue(header, children, name)
            return rank
    if header.startswith("PendingEvent:"):
        input_section_shape(header, children, "PendingEvent", "<none>", r"\S.*?, age=-?\d+ms")
        if len(children) > 1:
            raise HealthFailure("Multiple pending input events")
        return 11
    if header.startswith("ReplacedKeys:"):
        input_section_shape(header, children, "ReplacedKeys", "<empty>",
                            r"originalKeyCode=-?\d+, deviceId=-?\d+ -> newKeyCode=-?\d+")
        return 13
    if header.startswith("Connections:"):
        input_connections(header, children)
        return 14
    if re.fullmatch(r"AppSwitch: (?:not pending|pending, due in -?\d+ms)", header) and not children:
        return 15
    if header == "Configuration:" and len(children) == 2:
        if re.fullmatch(r"KeyRepeatDelay: \d+ms", children[0]) and re.fullmatch(r"KeyRepeatTimeout: \d+ms", children[1]):
            return 16
    raise HealthFailure(f"Unknown input section: {header}")


def input_focus(snapshot: str) -> tuple[str, str] | None:
    sections, historical_anr = input_sections(snapshot)
    ranks = [validate_input_section(header, children) for header, children in sections]
    collapsed = [rank for index, rank in enumerate(ranks)
                 if not index or rank not in (8, 9) or rank != ranks[index - 1]]
    headers = [header for header, _ in sections]
    if collapsed != list(range(17)) or len(headers) != len(set(headers)):
        raise HealthFailure("Missing, duplicate or reordered input sections")
    if any(empty in headers and ranks.count(rank) != 1 for empty, rank in
           (("Displays: <none>", 8), ("Monitors: <none>", 9))):
        raise HealthFailure("Conflicting empty input sections")
    if historical_anr:
        raise HealthFailure("Retained input ANR history rejected by policy; not an active-error inference")
    for rank, expected in ((0, "DispatchEnabled: true"), (1, "DispatchFrozen: false"), (3, "FocusedDisplayId: 0")):
        if sections[rank][0] != expected:
            raise HealthFailure(f"Input dispatcher not ready: {sections[rank][0]}")
    header, entries = sections[5]
    if header == "FocusedWindows: <none>":
        return None
    if entries == ["displayId=0 has focused token without a window'"]:
        return None
    if len(entries) != 1:
        raise HealthFailure("Unknown or ambiguous input focus")
    match = re.fullmatch(r"displayId=0, name='([0-9a-f]+) ([^']+)'", entries[0])
    if match is None:
        raise HealthFailure(f"Unsupported or obstructing input window: {entries[0]}")
    return match[1], canonical_component(match[2])


def process_records(snapshot: str) -> list[str]:
    dump_text(snapshot)
    header = "ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)\n  All known processes:\n"
    if not snapshot.startswith(header) or snapshot.count(header) != 1:
        raise HealthFailure("Missing process traversal header")
    terminal = ordered_fields(snapshot, (r"mForceBackgroundCheck=(?:true|false)",))[0]
    if snapshot[terminal.end():].strip() or len(re.findall(r"^ *mForceBackgroundCheck\S*", snapshot, re.MULTILINE)) != 1:
        raise HealthFailure("Unknown process trailer")
    traversal = snapshot[len(header):terminal.start()]
    following = re.search(r"^  (?:Isolated process list \(sorted by uid\):|Active instrumentation:|"
                          r"UID states:|UID validation:|Raw LRU list \(dumpsys activity lru\):|"
                          r"Process LRU list \(sorted by oom_adj, \d+ total, non-act at \d+, non-svc at \d+\):|"
                          r"PID mappings:|Total persistent processes: \d+)\n",
                          traversal, re.MULTILINE)
    if following is None:
        raise HealthFailure("Missing process post-traversal section")
    traversal = traversal[:following.start()]
    records = list(re.finditer(r"^  \*(?:APP|PERS)\* UID \d+ ProcessRecord\{[^\n{}]+\}\n",
                               traversal, re.MULTILINE))
    if not records or traversal[:records[0].start()].strip():
        raise HealthFailure("Missing process records")
    bodies = []
    for index, record in enumerate(records):
        end = records[index + 1].start() if index + 1 < len(records) else len(traversal)
        body = traversal[record.end():end]
        if not body.strip() or any(line.strip() and not line.startswith("    ") for line in body.splitlines()):
            raise HealthFailure("Unknown process traversal boundary")
        ordered_fields(body, (r"user #\d+ uid=\d+.*", r"thread=.+", r"pid=\d+"))
        bodies.append(body)
    return bodies


def require_process_health(snapshot: str) -> None:
    for body in process_records(snapshot):
        if re.search(r"^[ \t]*(?:mCrashing\w*|mNotResponding\w*|mBad\w*|bad\w*|mCrashDialog\w*|"
                     r"mAnrDialog\w*|crashDialog\w*|anrDialog\w*)\b", body, re.MULTILINE):
            raise HealthFailure("Current process error/dialog branch present or malformed")


def require_clean_events(snapshot: str) -> None:
    lines = snapshot.splitlines()
    if not lines or lines[0] != "--------- beginning of events":
        raise HealthFailure("Unknown or missing events log header")
    for line in lines[1:]:
        match = re.fullmatch(r"[VDIWEF]/([\w.]+)\(\s*\d+\): .+", line)
        if match is None:
            raise HealthFailure("Unknown or truncated events log record")
        if match[1] in ("am_anr", "am_crash"):
            raise HealthFailure(f"Retained system error event: {match[1]}")


def health_sample(session: DeviceSession, expected: str) -> bool:
    boot = session.text("shell", "getprop", "sys.boot_completed")
    if boot not in ("", "0", "1"):
        raise HealthFailure(f"Unknown boot state: {boot!r}")
    packages = session.text("shell", "pm", "path", "android")
    if not re.fullmatch(r"package:/[^\n]+\.apk", packages):
        raise HealthFailure("Unknown platform package response")
    session.text("shell", "mkdir -p /sdcard/Android && touch /sdcard/Android/.pomodorough-ci-ready "
                 "&& rm /sdcard/Android/.pomodorough-ci-ready")
    windows = session.snapshot("shell", "dumpsys", "window")
    ownership = session.snapshot("shell", "dumpsys", "window", "windows")
    inputs = session.snapshot("shell", "dumpsys", "input")
    processes = session.snapshot("shell", "dumpsys", "activity", "processes")
    events = session.text("logcat", "-b", "events", "-d", "-v", "brief")
    require_clean_events(events)
    window = window_focus(windows, ownership)
    focused_input = input_focus(inputs)
    require_process_health(processes)
    return boot == "1" and window is not None and window == focused_input and window[1] == expected


def wait_for_health(session: DeviceSession, expected: str, startup: bool) -> None:
    if startup:
        session.text("wait-for-device")
        session.text("shell", "settings", "put", "global", "device_provisioned", "1")
        session.text("shell", "settings", "put", "secure", "user_setup_complete", "1")
    api = session.text("shell", "getprop", "ro.build.version.sdk")
    if api not in ("35", "36"):
        raise HealthFailure(f"Unsupported Android API: {api!r}")
    expected = resolve_home(session) if expected == "HOME" else canonical_component(expected)
    session.record("expected", {"component": expected, "api": api})
    healthy_since = None
    observations = 0
    while time.monotonic() < session.deadline:
        healthy = health_sample(session, expected)
        now = time.monotonic()
        observations += 1
        session.record(f"sample-{observations:03d}", {"healthy": healthy, "monotonic": now})
        if now >= session.deadline:
            break
        if not healthy:
            healthy_since = None
        elif healthy_since is None:
            healthy_since = now
        elif now - healthy_since >= (80 if startup else 1):
            print(f"Android {expected} passed {observations} readiness observations")
            return
        time.sleep(min(20 if startup else 1, max(0, session.deadline - time.monotonic())))
    raise HealthFailure("Expected active-window health did not stabilize before absolute deadline")


def retain_host_context(session: DeviceSession) -> None:
    context = {"captured_at": time.time(), "load_average": os.getloadavg()}
    for name in ("/proc/meminfo", "/proc/pressure/cpu", "/proc/pressure/memory"):
        try:
            context[name] = Path(name).read_text()
        except OSError as failure:
            context[name] = {"unavailable": str(failure)}
    session.record("host", context)


def collect_diagnostics(session: DeviceSession) -> None:
    retain_host_context(session)
    home = None
    try:
        home = resolve_home(session).split("/")[0]
    except HealthFailure as failure:
        session.record("home-unavailable", {"error": str(failure)})
    commands = [
        ["shell", "dumpsys", "window"],
        ["shell", "dumpsys", "input"],
        ["shell", "dumpsys", "activity", "processes"],
        ["exec-out", "screencap", "-p"],
        ["logcat", "-b", "all", "-d", "-v", "threadtime"],
        ["shell", "pm", "list", "instrumentation"],
    ]
    packages = ["me.egigoka.pomodorough", "me.egigoka.pomodorough.test"]
    commands += [["shell", "dumpsys", "activity", "exit-info", package]
                 for package in ([home] if home else []) + packages]
    commands += [["shell", "dumpsys", "dropbox", "--print", tag] for tag in
                 ("system_app_anr", "data_app_anr", "system_server_anr", "system_app_crash", "data_app_crash")]
    commands += [["shell", "getprop", prop] for prop in
                 ("ro.build.fingerprint", "ro.build.version.sdk", "ro.kernel.qemu.avd_name")]
    for command in commands:
        session.capture(command, required=False)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Fail-closed hosted Android readiness and evidence")
    parser.add_argument("--output", type=Path, required=True)
    commands = parser.add_subparsers(dest="operation", required=True)
    command = commands.add_parser("command")
    command.add_argument("phase")
    command.add_argument("device_arguments", nargs=argparse.REMAINDER)
    wait = commands.add_parser("wait")
    wait.add_argument("phase")
    wait.add_argument("expected")
    wait.add_argument("--startup", action="store_true")
    diagnostics = commands.add_parser("diagnostics")
    diagnostics.add_argument("phase", nargs="?", default="failure")
    return parser.parse_args()


def main() -> int:
    options = arguments()
    phase = getattr(options, "phase", "failure")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", phase):
        raise SystemExit("Invalid evidence phase")
    budget = 120 if options.operation == "diagnostics" else 10
    if options.operation == "wait":
        budget = 600 if options.startup else 30
    session = DeviceSession(options.output, phase, budget)
    try:
        if options.operation == "command":
            if not options.device_arguments:
                raise HealthFailure("Missing device command")
            sys.stdout.buffer.write(session.capture(options.device_arguments))
        elif options.operation == "diagnostics":
            collect_diagnostics(session)
        else:
            wait_for_health(session, options.expected, options.startup)
        session.record("result", {"status": "collected" if options.operation == "diagnostics" else "passed"})
        return 0
    except HealthFailure as failure:
        session.record("result", {"status": "failed", "reason": str(failure)})
        print(f"Android precondition failed: {failure}; evidence: {session.directory}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
