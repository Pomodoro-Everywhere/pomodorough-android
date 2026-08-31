#!/usr/bin/env python3

"""Bounded observations, not a whole-framework health or service-integrity proof.

The envelopes follow public AOSP emitters: ProcessList.dumpProcessesLSP and
ActivityManagerService.dumpOtherProcessesInfoLSP (android-16.0.0_r1), WMS
doDump/dumpWindowsNoHeaderLocked (be42921e05ba3d1946efc090054cd4a498f22b80),
RootWindowContainer traversal (e7627bd73223e4f20a49a92acf42f4275aaa8c5e),
DisplayContent.dump (android16-release blob deee44dd7f617b01197bd859689bb8eca5c76cb2),
and InputDispatcher.dump/dumpDispatchStateLocked
(18c754e18499acce28e8be58846879075ade72a7). Full framing also follows the eight
retained API 35/36 captures from hosted run 33357184785; offline replay is NOT
native health or release acceptance. Policy boot/system and current display
awake/screen/draw signals replace absent legacy-field assumptions, not equivalent
mDisplayFrozen/waitingForConfig semantics.

Only relevant sections are interpreted. Unrelated delegated record payloads,
payloads inside framed input-service and outer window-service sections are opaque.
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
                              "grammar": "retained-api35-api36-bounded-observations-v3",
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
    package_name = r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*"
    activity_name = r"[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*"
    if not re.fullmatch(rf"{package_name}/\.?{activity_name}", value):
        raise HealthFailure(f"Unknown component grammar: {value!r}")
    package, activity = value.split("/")
    return f"{package}/{package + activity if activity.startswith('.') else activity}"


def resolved_component(snapshot: str) -> str:
    integer = r"(?:0|-?[1-9][0-9]{0,9})"
    metadata = (rf"priority=(?P<priority>{integer}) preferredOrder=(?P<order>{integer}) "
                rf"match=0x[0-9a-f]{{1,8}} specificIndex=(?P<index>{integer}) isDefault=(?:true|false)\n")
    match = re.fullmatch(rf"(?:{metadata})?(?P<component>[^\n]+)\n", snapshot)
    if match is None:
        raise HealthFailure("Unknown component grammar in incomplete or ambiguous HOME resolver response")
    if any(match[name] is not None and not -2147483648 <= int(match[name]) <= 2147483647
           for name in ("priority", "order", "index")):
        raise HealthFailure("Out-of-range HOME resolver metadata")
    return canonical_component(match["component"])


def resolve_home(session: DeviceSession) -> str:
    return resolved_component(session.snapshot(
        "shell", "cmd", "package", "resolve-activity", "--brief", "--user", "0",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
    ))


def transient_home(component: str) -> bool:
    package = component.split("/")[0]
    return package in ("com.google.android.googlesdksetup", "com.google.android.setupwizard",
                       "com.android.provision") or component == "com.android.settings/com.android.settings.FallbackHome"


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
    snapshot = snapshot.removeprefix("\n")
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
    fields = ordered_fields(snapshot, (r"mGlobalConfiguration=.+", r"mHasPermanentDpad=(?:true|false)",
                                      r"mTopFocusedDisplayId=0", r"WINDOW MANAGER TRACE \(dumpsys window trace\)"))
    if snapshot[fields[-1].end():].strip():
        raise HealthFailure("Unknown window section trailer")
    require_legacy_window_fields(snapshot)
    return snapshot[len(header):fields[0].start()]


def require_legacy_window_fields(snapshot: str) -> None:
    patterns = {
        "mDisplayFrozen": r"mDisplayFrozen=false windows=0 client=false apps=0 mRotation=\S+ mLastOrientation=-?\d+",
        "waitingForConfig": r"waitingForConfig=false",
        "mDisplayEnabled": r"mDisplayEnabled=true",
        "mSystemBooted": r"mSystemBooted=true mDisplayEnabled=true",
        "mInTouchMode": r"mInTouchMode=(?:true|false)",
        "mTransactionSequence": r"mTransactionSequence=\d+",
        "Animation settings": r"Animation settings: disabled=(?:true|false) window=\d+(?:\.\d+)? "
                              r"transition=\d+(?:\.\d+)? animator=\d+(?:\.\d+)?",
    }
    for name, pattern in patterns.items():
        prefix = r"[^\n]*?(?<!\S)" if name in ("mDisplayFrozen", "waitingForConfig", "mDisplayEnabled") else " *"
        lines = re.findall(rf"^{prefix}{re.escape(name)}[^\n]*$", snapshot, re.MULTILINE)
        if name == "mDisplayEnabled":
            pattern = r"(?:mSystemBooted=true )?mDisplayEnabled=true"
        if len(lines) > 1 or any(re.fullmatch(rf" *{pattern}", line) is None for line in lines):
            raise HealthFailure(f"Active or malformed legacy window field: {name}")


def full_window_ownership(snapshot: str) -> str:
    dump_text(snapshot)
    prefix = "WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n"
    if not snapshot.removeprefix("\n").startswith(prefix):
        raise HealthFailure("Unknown full window dump prefix")
    policy_header = "WINDOW MANAGER POLICY STATE (dumpsys window policy)"
    policy = section_between(snapshot, policy_header, "WINDOW MANAGER ANIMATOR STATE (dumpsys window animator)")
    require_window_signals(policy, ("mSafeMode=false mSystemReady=true mSystemBooted=true",))
    current = snapshot[snapshot.index(policy_header):]
    sections = (policy_header, "WINDOW MANAGER ANIMATOR STATE (dumpsys window animator)",
                "WINDOW MANAGER SESSIONS (dumpsys window sessions)",
                "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)",
                "WINDOW MANAGER TOKENS (dumpsys window tokens)",
                "WINDOW MANAGER WINDOWS (dumpsys window windows)", "WINDOW MANAGER TRACE (dumpsys window trace)",
                "WINDOW MANAGER LOGGING (dumpsys window logging)",
                "WINDOW MANAGER HIGH REFRESH RATE BLACKLIST (dumpsys window refresh)",
                "INSTALLED PACKAGES HAVING APP-SPECIFIC CONFIGURATIONS",
                "WINDOW MANAGER CONSTANTS (dumpsys window constants):", "SystemPerformanceHinter:",
                "TrustedPresentationListenerController:", "SensitiveContentPackages:", "ScreenRecordingCallbackController:")
    ordered_fields(current, tuple(re.escape(header) for header in sections))
    if re.findall(r"^(?:WINDOW MANAGER|INSTALLED PACKAGES)[^\n]*", current, re.MULTILINE) != list(sections[:11]):
        raise HealthFailure("Unknown or duplicate current window sections")
    tail = current.split("ScreenRecordingCallbackController:\n", 1)[1]
    if tail != "  Registered callbacks:\n  Last invoked states:\n":
        raise HealthFailure("Unsupported or incomplete window callback trailer")
    require_legacy_window_fields(current)
    require_display_readiness(current)
    return sections[5] + "\n" + section_between(current, sections[5], sections[6]) + sections[6] + "\n"


def require_window_signals(snapshot: str, patterns: tuple[str, ...]) -> None:
    ordered_fields(snapshot, patterns)
    for pattern in patterns:
        for name in re.findall(r"(\w+)=", pattern):
            if len(re.findall(rf"(?<!\S){name}\w*\b", snapshot)) != 1:
                raise HealthFailure(f"Ambiguous or malformed window readiness signal: {name}")


def require_display_readiness(snapshot: str) -> None:
    display = section_between(snapshot, "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)",
                              "WINDOW MANAGER TOKENS (dumpsys window tokens)")
    policy = section_between(display, "  DisplayPolicy", "  DisplayRotation")
    require_window_signals(policy, ("mAwake=true mScreenOnEarly=true mScreenOnFully=true",
                                    "mKeyguardDrawComplete=true mWindowManagerDrawComplete=true"))
    orientation = re.findall(r"^ *ignoreOrientationRequest[^\n]*$", display, re.MULTILINE)
    if len(orientation) > 1 or any(re.fullmatch(r" *ignoreOrientationRequest=(?:true|false)", line) is None
                                 for line in orientation):
        raise HealthFailure("Malformed display orientation request flag")


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


def window_focus(snapshot: str) -> tuple[str, str] | None:
    ownership = full_window_ownership(snapshot)
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


def input_envelope(snapshot: str) -> tuple[str, str]:
    dump_text(snapshot)
    if not snapshot.startswith("INPUT MANAGER (dumpsys input)\n"):
        raise HealthFailure("Unknown full input dump prefix")
    prefix = section_between(snapshot, "INPUT MANAGER (dumpsys input)", "Input Dispatcher State:")
    headers = [line for line in prefix.splitlines() if line and not line.startswith(" ")]
    common = ["Input properties:", "Input Manager State:", "Event Hub State:"]
    reader = [header for header in headers if re.fullmatch(r"Input Reader State \(Nums of device: \d+\):", header)]
    if len(reader) != 1:
        raise HealthFailure("Missing or ambiguous input reader framing")
    common += reader + ["UnwantedInteractionBlocker:", "PointerChoreographer:"]
    suffix = ["Input Processor State:", "InputDeviceMetricsCollector:"]
    if headers == common + suffix + ["InputFilter:"]:
        version = "36"
    elif len(headers) == len(common) + 2 + len(suffix) and headers[:len(common)] == common and headers[-2:] == suffix:
        if not re.fullmatch(r"show touches: (?:true|false)\nstylus pointer icon enabled: (?:true|false)",
                            "\n".join(headers[len(common):-2])):
            raise HealthFailure("Malformed input pointer configuration")
        version = "35"
    else:
        raise HealthFailure("Unsupported or incomplete native input envelope")
    try:
        current = section_between(snapshot, "Input Dispatcher State:", "Input Manager Service (Java) State:")
    except HealthFailure as failure:
        raise HealthFailure("Missing, duplicate or reordered input sections") from failure
    java = snapshot.split("Input Manager Service (Java) State:\n", 1)[1]
    input_java_envelope(java, version)
    return current, version


def input_java_envelope(snapshot: str, version: str) -> None:
    patterns = (r"Gesture Monitors \(implemented as spy windows\):", r"mAdditionalDisplayInputProperties:",
                r"BatteryController:", r"KbdBacklightController: 0 keyboard backlights",
                r"KeyboardLedController: 0 keyboard mic mute lights")
    if version == "36":
        patterns += (r"KeyboardLedController: 0 keyboard volume mute lights", r"KeyboardGlyphManager: 0 glyph maps",
                     r"KeyGestureController:", r"Last handled KeyGestureEvents: *", r"KeyCombination rules:",
                     r"AppLaunchShortcutManager:", r"InputGestureManager:")
    headers = [line[2:] for line in snapshot.splitlines() if re.match(r"^  \S", line)]
    if len(headers) != len(patterns) or any(re.fullmatch(pattern, header) is None
                                          for header, pattern in zip(headers, patterns)):
        raise HealthFailure("Unknown or incomplete Java input sections")
    if any(line.strip() and not line.startswith("  ") for line in snapshot.splitlines()):
        raise HealthFailure("Unknown Java input trailer")
    terminal = "    Custom Gestures:\n" if version == "36" else "  KeyboardLedController: 0 keyboard mic mute lights\n"
    if not snapshot.endswith(terminal) or snapshot.count(terminal) != 1:
        raise HealthFailure("Missing or ambiguous version-bound input completion")
    if version == "36":
        gestures = section_between(snapshot, "  InputGestureManager:", "    Custom Gestures:")
        ordered_fields(gestures, (r"System Shortcuts:",))


def input_sections(snapshot: str) -> tuple[list[tuple[str, list[str]]], bool, str]:
    current, version = input_envelope(snapshot)
    history_header = "\nInput Dispatcher State at time of last ANR:\n"
    current, separator, history = current.partition(history_header)
    if history_header in history or (separator and not history.strip()):
        raise HealthFailure("Missing or ambiguous input history envelope")
    sections = []
    for line in current.splitlines():
        if re.fullmatch(r"  \S.*", line):
            sections.append((line[2:], []))
        elif line.startswith("    ") and sections:
            sections[-1][1].append(line[4:])
        elif line.strip():
            raise HealthFailure("Unknown input section/trailer")
    return sections, bool(separator), version


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


def input_connections(header: str, children: list[str], version: str) -> None:
    if header == "Connections: <none>" and not children:
        return
    if header != "Connections:" or not children:
        raise HealthFailure("Malformed input connections")
    records = []
    identifiers = set()
    for child in children:
        match = re.fullmatch(r"(\d+): channelName='[^']*', status=NORMAL, "
                             r"monitor=(?:true|false), responsive=true", child)
        if match and match[1] not in identifiers:
            identifiers.add(match[1])
            records.append([])
        elif child.startswith("  ") and records:
            records[-1].append(child[2:])
        else:
            raise HealthFailure("Unknown or unresponsive input connection")
    for queues in records:
        expected = ["OutboundQueue: <empty>", "WaitQueue: <empty>"] if version == "35" else []
        if queues != expected:
            raise HealthFailure("Unsupported nonempty or malformed input connection queues")


def input_display(header: str, children: list[str]) -> None:
    if header == "Displays: <none>" and not children:
        return
    if re.fullmatch(r"Display: -?\d+", header) is None:
        raise HealthFailure("Unknown input display header")
    if children == ["Windows: <none>"]:
        return
    if children and re.fullmatch(r"logicalSize=\d+x\d+", children[0]):
        if len(children) < 3 or not re.fullmatch(r"    transform \([^\n]+\) \([^\n]+\)", children[1]):
            raise HealthFailure("Incomplete input display transform")
        children = children[2:]
    if not children or children[0] != "Windows:":
        raise HealthFailure("Unknown input window list")
    pattern = (r"  (\d+): name=[^,\n]+, id=-?\d+, displayId=-?\d+, inputConfig=[^,\n]+, "
               r"alpha=\d+(?:\.\d+)?, frame=\[-?\d+,-?\d+\]\[-?\d+,-?\d+\], globalScale=\d+(?:\.\d+)?, "
               r"applicationInfo.name=[^,\n]*, applicationInfo.token=(?:<null>|0x[0-9a-f]+), "
               r"touchableRegion=(?:<empty>|(?:\[-?\d+,-?\d+\]\[-?\d+,-?\d+\])+), ownerPid=-?\d+, ownerUid=-?\d+, "
               r"dispatchingTimeout=\d+ms, token=0x[0-9a-f]+, touchOcclusionMode=\S+")
    count = 0
    for child in children[1:]:
        record = re.fullmatch(pattern, child)
        if record and int(record[1]) == count:
            count += 1
        elif count and (child.startswith("    ") or re.fullmatch(r" *(?:transform \([^\n]+\) \([^\n]+\))", child)):
            continue
        else:
            raise HealthFailure("Unknown or truncated input window record")
    if not count:
        raise HealthFailure("Missing input window records")


def input_monitors(header: str, children: list[str]) -> None:
    if header == "Monitors: <none>" and not children:
        return
    if re.fullmatch(r"(?:Global|Gesture) monitors on display -?\d+:", header) is None:
        raise HealthFailure("Unknown input monitor header")
    for index, child in enumerate(children):
        if re.fullmatch(rf"{index}: '[^']*', *", child) is None:
            raise HealthFailure("Malformed input monitor record")


def input_touch_states(header: str, children: list[str]) -> None:
    if header in ("TouchStates: <no displays touched>", "TouchStatesByDisplay: <no displays touched>") and not children:
        return
    state = r"-?\d+: down=(?:true|false), split=(?:true|false), deviceId=-?\d+, source=0x[0-9a-f]{8}\n"
    window = r"    \d+: name='[^']*', pointerIds=0x[0-9a-f]+, targetFlags=0x[0-9a-f]+\n"
    windows = rf"  Windows:(?: <none>\n|\n(?:{window})+)"
    portal = r"    \d+: name='[^']*'\n"
    portals = rf"(?:  Portal windows:\n(?:{portal})+)?"
    if header != "TouchStatesByDisplay:" or not re.fullmatch(rf"(?:{state}{windows}{portals})+",
                                                            "\n".join(children) + "\n"):
        raise HealthFailure("Unknown or incomplete input touch states")


def validate_input_section(header: str, children: list[str], version: str) -> str:
    scalar_patterns = (r"DispatchEnabled: (?:true|false)", r"DispatchFrozen: (?:true|false)",
                       r"InputFilterEnabled: (?:true|false)", r"FocusedDisplayId: -?\d+")
    for pattern in scalar_patterns:
        if re.fullmatch(pattern, header) and not children:
            return header.split(":", 1)[0]
    shapes = (("FocusedApplications", "<none>", r"displayId=-?\d+, name='[^']*', dispatchingTimeout=\d+ms"),
              ("FocusedWindows", "<none>", r"displayId=-?\d+(?:, name='[^']+'| has focused token without a window')"),
              ("FocusRequests", "<none>", r"displayId=-?\d+, name='[^']+' result='(?:OK|NO_WINDOW)'"))
    for name, empty, pattern in shapes:
        if header.startswith(name + ":"):
            input_section_shape(header, children, name, empty, pattern)
            return name
    return validate_input_tail(header, children, version)


def validate_input_tail(header: str, children: list[str], version: str) -> str:
    if header.startswith(("TouchStates:", "TouchStatesByDisplay:")):
        input_touch_states(header, children)
        return "TouchStates"
    if header.startswith(("Display:", "Displays:")):
        input_display(header, children)
        return "Display"
    if header.startswith(("Monitors:", "Global monitors ", "Gesture monitors ")):
        input_monitors(header, children)
        return "Monitors"
    for name in ("RecentQueue", "InboundQueue"):
        if header.startswith(name + ":"):
            input_queue(header, children, name)
            return name
    if header.startswith("PendingEvent:"):
        input_section_shape(header, children, "PendingEvent", "<none>", r"\S.*?, age=-?\d+ms")
        if len(children) > 1:
            raise HealthFailure("Multiple pending input events")
        return "PendingEvent"
    if header.startswith("Connections:"):
        input_connections(header, children, version)
        return "Connections"
    if re.fullmatch(r"AppSwitch: (?:not pending|pending, due in -?\d+ms)", header) and not children:
        return "AppSwitch"
    if header == "Configuration:":
        input_configuration(children, version)
        return "Configuration"
    return validate_input_current_fields(header, children, version)


def input_configuration(children: list[str], version: str) -> None:
    snapshot = "\n".join(children) + "\n"
    aggregator = "LatencyAggregatorWithHistograms:" if version == "36" else "LatencyAggregator:"
    ordered_fields(snapshot, (r"KeyRepeatDelay: \d+ms", r"KeyRepeatTimeout: \d+ms", r"LatencyTracker:",
                              re.escape(aggregator), r"mLastSlowEventTime=-?\d+",
                              r"mNumEventsSinceLastSlowEventReport = \d+", r"mNumSkippedSlowEvents = \d+"))
    headers = [line for line in children if line and not line.startswith(" ")]
    if headers != children[:2] + ["LatencyTracker:", aggregator]:
        raise HealthFailure("Unknown input configuration sections")
    if not re.fullmatch(r"  mNumSkippedSlowEvents = \d+", children[-1]):
        raise HealthFailure("Incomplete input latency telemetry")


def validate_input_current_fields(header: str, children: list[str], version: str) -> str:
    scalars = {"Pointer Capture Requested: false": "PointerCapture", "Current Window with Pointer Capture: None": "CaptureWindow",
               "CommandQueue: <empty>": "CommandQueue", "InputTracer: Enabled": "InputTracer"}
    if version == "36":
        scalars["CursorStatesByDisplay: <no displays touched by cursor>"] = "CursorStates"
        if re.fullmatch(r"mMaximumObscuringOpacityForTouch: (?:0\.\d+|1\.0+)", header) and not children:
            return "MaximumOpacity"
        if header == "DisplayTopologyGraph:" and children == ["PrimaryDisplayId: -1", "TopologyGraph:", "", "DisplaysDensity:", ""]:
            return "DisplayTopology"
    if header in scalars and not children:
        return scalars[header]
    if header == "TouchModePerDisplay:" and children:
        if all(re.fullmatch(r"Display: -?\d+ TouchMode: [01]", child) for child in children):
            if len(children) == len(set(child.split(" TouchMode:")[0] for child in children)):
                return "TouchMode"
    raise HealthFailure(f"Unknown input section: {header}")


def input_section_order(sections: list[tuple[str, list[str]]], version: str) -> None:
    ranks = [validate_input_section(header, children, version) for header, children in sections]
    collapsed = [rank for index, rank in enumerate(ranks)
                 if not index or rank not in ("Display", "Monitors") or rank != ranks[index - 1]]
    expected = ["DispatchEnabled", "DispatchFrozen", "InputFilterEnabled", "FocusedDisplayId",
                "FocusedApplications", "FocusedWindows", "FocusRequests", "PointerCapture", "CaptureWindow", "TouchStates"]
    if version == "36":
        expected += ["CursorStates", "Display", "MaximumOpacity", "DisplayTopology", "Monitors", "Connections"]
    else:
        expected += ["Display", "Monitors"]
    expected += ["RecentQueue", "PendingEvent", "InboundQueue", "CommandQueue"]
    if version == "35":
        expected += ["Connections"]
    if "AppSwitch" in ranks:
        expected += ["AppSwitch"]
    expected += ["TouchMode", "Configuration", "InputTracer"]
    headers = [header for header, _ in sections]
    if collapsed != expected or len(headers) != len(set(headers)):
        raise HealthFailure("Missing, duplicate or reordered input sections")
    if any(empty in headers and ranks.count(rank) != 1 for empty, rank in
           (("Displays: <none>", "Display"), ("Monitors: <none>", "Monitors"))):
        raise HealthFailure("Conflicting empty input sections")


def input_focus(snapshot: str) -> tuple[str, str] | None:
    sections, historical_anr, version = input_sections(snapshot)
    input_section_order(sections, version)
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
    following = re.search(r"^  (?:OOM levels:|Isolated process list \(sorted by uid\):|Active instrumentation:|"
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
    inputs = session.snapshot("shell", "dumpsys", "input")
    processes = session.snapshot("shell", "dumpsys", "activity", "processes")
    events = session.text("logcat", "-b", "events", "-d", "-v", "brief")
    require_clean_events(events)
    window = window_focus(windows)
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
    follow_home = expected == "HOME"
    expected = resolve_home(session) if follow_home else canonical_component(expected)
    session.record("expected", {"component": expected, "api": api})
    healthy_since = None
    observations = 0
    while time.monotonic() < session.deadline:
        if follow_home:
            current_home = resolve_home(session)
            if current_home != expected:
                healthy_since = None
                expected = current_home
        healthy = health_sample(session, expected)
        if follow_home:
            healthy = resolve_home(session) == expected and not transient_home(expected) and healthy
        now = time.monotonic()
        observations += 1
        session.record(f"sample-{observations:03d}", {"healthy": healthy, "monotonic": now,
                                                    "expected": expected})
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
