"""Source-shaped projections, never native/API compatibility fixtures.

Provenance (parent-retained numbered web renderings, not raw authenticated bytes):
https://android.googlesource.com/platform/frameworks/base/+/be42921e05ba3d1946efc090054cd4a498f22b80/services/core/java/com/android/server/wm/WindowManagerService.java
  dumpWindowsNoHeaderLocked 6384-6556; doDump 6652-6808 (current displays then tokens).
https://android.googlesource.com/platform/frameworks/base/+/e7627bd73223e4f20a49a92acf42f4275aaa8c5e/services/core/java/com/android/server/wm/RootWindowContainer.java
  dumpWindowsNoHeader 1059-1068; window enumeration precedes WMS global postamble.
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/wm/DisplayContent.java
  blob deee44dd7f617b01197bd859689bb8eca5c76cb2, dump 3665-3789, focus 3705-3706.
https://android.googlesource.com/platform/frameworks/native/+/18c754e18499acce28e8be58846879075ade72a7/services/inputflinger/dispatcher/InputDispatcher.cpp
  focus 3910-3927; complete current 3946-4141; current/history envelope 4804-4812.
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r1/services/core/java/com/android/server/am/ProcessList.java
  dumpProcessesLSP 4201-4282 (APP and PERS traversal then delegate).
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java
  dumpOtherProcessesInfoLSP 10442-10668, final mForceBackgroundCheck at 10667.
https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r1/services/core/java/com/android/server/am/ProcessErrorStateRecord.java
  dump 737-755, conditional flags/dialog slots/bad, per parent's inspected excerpt.
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ProcessRecord.java
  dump 397-496, error/services/providers/receivers/optimizer/windows delegation.

Unrelated delegated payloads are omitted from these projections, not asserted
absent in Android. Footer/next-section evidence detects prefix truncation, not
arbitrary interior deletion. SDK selector tests do not validate target grammar.
"""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
ADAPTER = ROOT / ".github/scripts/android-readiness.py"
RUNNER = ROOT / ".github/scripts/run-instrumented-tests.sh"
SPEC = importlib.util.spec_from_file_location("android_readiness", ADAPTER)
READINESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(READINESS)
HOME = "com.example.launcher/com.example.launcher.Home"
WINDOW = """WINDOW MANAGER LAST ANR (dumpsys window lastanr)
  <no ANR has occurred since boot>
WINDOW MANAGER POLICY STATE (dumpsys window policy)
WINDOW MANAGER ANIMATOR STATE (dumpsys window animator)
WINDOW MANAGER SESSIONS (dumpsys window sessions)
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0
    init=1080x2400 420dpi mMinSizeOfResizeableTaskDp=220 cur=1080x2400 app=1080x2400 rng=1080x1080-2400x2400
    deferred=false mLayoutNeeded=false
  ignoreOrientationRequest=false
  mLayoutSeq=10
  mCurrentFocus=Window{a1 u0 com.example.launcher/.Home}
  mFocusedApp=ActivityRecord{aa com.example.launcher/.Home}
  mHoldScreenWindow=null
  mObscuringWindow=Window{a1 u0 com.example.launcher/.Home}
  mLastWakeLockHoldingWindow=null
  mLastWakeLockObscuringWindow=null
  Display areas in top down Z order:
  Task display areas in top down Z order:
WINDOW MANAGER TOKENS (dumpsys window tokens)
"""
OWNERSHIP = """WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #0 Window{a1 u0 com.example.launcher/.Home}:
    mOwnerUid=10101 showForAllUsers=false package=com.example.launcher appop=NONE
  mGlobalConfiguration={1.0 en_US}
  mHasPermanentDpad=false
  mTopFocusedDisplayId=0
  mInTouchMode=true
  mBlurEnabled=false
  mLastDisplayFreezeDuration=0ms
  mLastWakeLockHoldingWindow=null mLastWakeLockObscuringWindow=null
  mSystemBooted=true mDisplayEnabled=true
  mTransactionSequence=42
  mDisplayFrozen=false windows=0 client=false apps=0 mRotation=0 mLastOrientation=-1
  waitingForConfig=false
  Animation settings: disabled=true window=0.0 transition=0.0 animator=0.0
"""
INPUT = """Input Dispatcher State:
  DispatchEnabled: true
  DispatchFrozen: false
  InputFilterEnabled: false
  FocusedDisplayId: 0
  FocusedApplications:
    displayId=0, name='ActivityRecord{aa com.example.launcher/.Home}', dispatchingTimeout=5000ms
  FocusedWindows:
    displayId=0, name='a1 com.example.launcher/.Home'
  mPendingFocusRequests: <none>
  TouchStates: <no displays touched>
  Display: 0
    Windows:
      0: name='a1 com.example.launcher/.Home', id=1, displayId=0, portalToDisplayId=-1, paused=false, focusable=true, hasWallpaper=false, visible=true, alpha=1.00, flags=0x0, type=APPLICATION, frame=[0,0][1080,2400], globalScale=1.000000, applicationInfo=Home, touchableRegion=[0,0][1080,2400], inputFeatures=0x0, ownerPid=1201, ownerUid=10101, dispatchingTimeout=5000ms, trustedOverlay=false, hasToken=true, touchOcclusionMode=BLOCK_UNTRUSTED
  Monitors: <none>
  RecentQueue: <empty>
  PendingEvent: <none>
  InboundQueue: <empty>
  ReplacedKeys: <empty>
  Connections:
    1: channelName='a1 com.example.launcher/.Home', windowName='a1 com.example.launcher/.Home', status=NORMAL, monitor=false, responsive=true
      OutboundQueue: <empty>
      WaitQueue: <empty>
  AppSwitch: not pending
  Configuration:
    KeyRepeatDelay: 50ms
    KeyRepeatTimeout: 500ms
"""
PROCESSES = """ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)
  All known processes:
  *APP* UID 10101 ProcessRecord{aa 1201:com.example.launcher/u0a101}
    user #0 uid=10101 gids={}
    mRequiredAbi=x86_64 instructionSet=x86_64
    dir=/system/Launcher.apk publicDir=/system/Launcher.apk data=/data/user/0/com.example.launcher
    compat={420dpi always-compat}
    thread=android.app.IApplicationThread$Stub$Proxy@12
    pid=1201
    lastActivityTime=-1s startUpTime=-1m startElapsedTime=-1m
    startSeq=1
    mountMode=DEFAULT
  Total persistent processes: 0
  mProcessesReady=true mSystemReady=true mBooted=true mFactoryTest=0
  mBooting=false mCallFinishBooting=false mBootAnimationComplete=true
  mLastPowerCheckUptime=0ms
  mLastIdleTime=0ms mLowRamSinceLastIdle=0ms
  ServiceManager statistics:
  mForceBackgroundCheck=false
"""
EVENTS = "--------- beginning of events\nI/am_proc_start( 1000): [0,1201]\n"
FAKE_ADB = """import json, os, pathlib, sys, time
root = pathlib.Path(os.environ['FAKE_ADB_ROOT'])
arguments = sys.argv[1:]
with (root / 'trace.jsonl').open('a') as trace:
    trace.write(json.dumps(arguments) + '\\n')
rules = json.loads((root / 'rules.json').read_text())
rule = rules.get(json.dumps(arguments))
if rule is None:
    print('Unmodeled fake adb command; no real-device fallback', file=sys.stderr)
    raise SystemExit(97)
if rule.get('stream'):
    print('Synthetic retained boot log', flush=True)
if rule.get('hang') or rule.get('stream'):
    time.sleep(60)
sys.stdout.buffer.write(bytes.fromhex(rule.get('bytes', '')))
sys.stderr.write(rule.get('stderr', ''))
raise SystemExit(rule.get('status', 0))
"""


class Clock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def time(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class FakeDevice:
    def __init__(self, root: Path, api: str = "35"):
        self.root = root
        self.bin = root / "bin"
        self.bin.mkdir()
        executable = self.bin / "adb"
        executable.write_text(f"#!{sys.executable}\n{FAKE_ADB}")
        executable.chmod(0o700)
        self.rules = {}
        self.add(["shell", "getprop", "ro.build.version.sdk"], api)
        self.add(["shell", "getprop", "sys.boot_completed"], "1")
        self.add(["shell", "pm", "path", "android"], "package:/system/framework/framework-res.apk")
        self.add(["shell", "mkdir -p /sdcard/Android && touch /sdcard/Android/.pomodorough-ci-ready "
                  "&& rm /sdcard/Android/.pomodorough-ci-ready"], "")
        self.add(["shell", "cmd", "package", "resolve-activity", "--brief", "--user", "0",
                  "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"], HOME)
        for command, content in ((["shell", "dumpsys", "window"], WINDOW),
                                 (["shell", "dumpsys", "window", "windows"], OWNERSHIP),
                                 (["shell", "dumpsys", "input"], INPUT),
                                 (["shell", "dumpsys", "activity", "processes"], PROCESSES),
                                 (["logcat", "-b", "events", "-d", "-v", "brief"], EVENTS)):
            self.add(command, content)
        for command in (["wait-for-device"],
                        ["shell", "settings", "put", "global", "device_provisioned", "1"],
                        ["shell", "settings", "put", "secure", "user_setup_complete", "1"]):
            self.add(command, "")

    def add(self, command, content="", **options):
        encoded = content if isinstance(content, bytes) else content.encode()
        self.rules[json.dumps(command)] = {"bytes": encoded.hex(), **options}
        (self.root / "rules.json").write_text(json.dumps(self.rules))

    def environment(self):
        return {"PATH": str(self.bin), "FAKE_ADB_ROOT": str(self.root),
                "PYTHONDONTWRITEBYTECODE": "1"}

    def trace(self):
        path = self.root / "trace.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def shell_tools(self):
        for name in ("basename", "cut", "date", "dirname", "grep", "mkdir", "rm", "sleep", "tee", "tr"):
            (self.bin / name).symlink_to(shutil.which(name, path="/usr/bin:/bin"))
        (self.bin / "python3").symlink_to(sys.executable)


def runner_function(name: str) -> str:
    match = re.search(rf"^{name}\(\) \{{\n.*?^\}}", RUNNER.read_text(), re.MULTILINE | re.DOTALL)
    if match is None:
        raise AssertionError(f"Missing runner function: {name}")
    return match[0] + "\n"


def process_metadata_snapshot(package: str) -> str:
    snapshot = PROCESSES.replace("com.example.launcher", package)
    snapshot = snapshot.replace("/system/Launcher.apk", f"/data/app/~~token/{package}-token/base.apk")
    metadata = (f"    class={package}.Application\n"
                f"    manageSpaceActivityName={package}.ManageSpace\n"
                f"    packageList={{{package}}}\n"
                f"    packageDependencies={{{package}, com.example.reader}}\n")
    return snapshot.replace("    compat=", metadata + "    compat=")


class DumpGrammarTests(unittest.TestCase):
    """Mixed-revision source projections, not authenticated Android dump fixtures."""

    def test_source_shaped_focus_and_conditional_process_error_omission(self):
        self.assertEqual(READINESS.window_focus(WINDOW, OWNERSHIP), ("a1", HOME))
        self.assertEqual(READINESS.input_focus(INPUT), ("a1", HOME))
        READINESS.require_process_health(PROCESSES)
        READINESS.require_clean_events(EVENTS)

    def test_window_obstructions_and_unknown_grammar_fail_closed(self):
        focus = "mCurrentFocus=Window{a1 u0 com.example.launcher/.Home}"
        cases = (
            "", "unrecognized header", WINDOW.replace(focus, "mCurrentFocus=Window{"),
            WINDOW + "  " + focus, WINDOW.replace(focus, "mCurrentFocus=Window{a2 u0 unknown}"),
            WINDOW.replace("u0 com.example.launcher/.Home", "u0 Application Not Responding: Pixel Launcher"),
            WINDOW.replace("u0 com.example.launcher/.Home", "u0 Application Not Responding: Messages"),
            WINDOW.replace("u0 ", "u10 "),
        )
        for snapshot in cases:
            with self.subTest(snapshot=snapshot), self.assertRaises(READINESS.HealthFailure):
                READINESS.window_focus(snapshot, OWNERSHIP)
        for ownership in (OWNERSHIP.replace("package=com.example.launcher", "package=android"),
                          OWNERSHIP.replace("mOwnerUid=10101", "mOwnerUid=1000"),
                          OWNERSHIP.replace("appop=NONE", "unknown-owner-field=NONE"),
                          OWNERSHIP.replace("Window #0", "unsupported record")):
            with self.subTest(ownership=ownership), self.assertRaises(READINESS.HealthFailure):
                READINESS.window_focus(WINDOW, ownership)

    def test_hidden_dialog_text_does_not_override_actual_focus(self):
        hidden = ("  Window #1 Window{bb u0 Application Not Responding: Messages}:\n"
                  "    mOwnerUid=1000 showForAllUsers=false package=android appop=NONE\n")
        ownership = OWNERSHIP.replace("  mGlobalConfiguration=", hidden + "  mGlobalConfiguration=")
        self.assertEqual(READINESS.window_focus(WINDOW, ownership), ("a1", HOME))
        self.assertIsNone(READINESS.window_focus(WINDOW.replace(
            "mCurrentFocus=Window{a1 u0 com.example.launcher/.Home}", "mCurrentFocus=null"), ownership))

    def test_input_obstructions_duplicates_and_truncation_fail_closed(self):
        entry = "    displayId=0, name='a1 com.example.launcher/.Home'"
        cases = ("", INPUT.replace("DispatchEnabled: true", "DispatchEnabled: false"),
                 INPUT.replace("DispatchFrozen: false", "DispatchFrozen: true"),
                 INPUT.replace("FocusedDisplayId: 0", "FocusedDisplayId: 1"),
                 INPUT.replace(entry, entry + "\n" + entry),
                 INPUT.replace(entry, entry[:-1]), INPUT.replace(entry, "    unrecognized grammar"),
                 INPUT.replace("com.example.launcher/.Home", "Application Not Responding: Messages"),
                 INPUT.replace("  FocusedWindows:\n", ""), INPUT + INPUT,
                 INPUT.replace("displayId=0", "displayId=1"))
        for snapshot in cases:
            with self.subTest(snapshot=snapshot), self.assertRaises(READINESS.HealthFailure):
                READINESS.input_focus(snapshot)
        self.assertIsNone(READINESS.input_focus(INPUT.replace("  FocusedWindows:\n" + entry,
                                                             "  FocusedWindows: <none>")))
        with self.assertRaises(READINESS.HealthFailure):
            READINESS.input_focus(INPUT.replace(entry, "    <none>"))

    def test_source_shaped_input_lists_and_optional_forms(self):
        monitors = ("  Global monitors in display 0:\n    0: 'global', \n"
                    "  Gesture monitors in display 0:\n    0: 'gesture', \n")
        touch = ("  TouchStatesByDisplay:\n"
                 "    0: down=false, split=false, deviceId=1, source=0x00001002\n"
                 "      Windows:\n        0: name='home', pointerIds=0x1, targetFlags=0x0\n"
                 "      Portal windows:\n        0: name='portal'\n")
        variants = (INPUT.replace("  Monitors: <none>\n", monitors),
                    INPUT.replace("  TouchStates: <no displays touched>\n", touch),
                    INPUT.replace("  Monitors:", "  Display: 1\n    Windows: <none>\n  Monitors:"),
                    INPUT.replace("  RecentQueue: <empty>", "  RecentQueue: length=1\n    KeyEvent, age=10ms"),
                    INPUT.replace("  InboundQueue: <empty>", "  InboundQueue: length=1\n    FocusEvent, age=1ms"),
                    INPUT.replace("  AppSwitch: not pending", "  AppSwitch: pending, due in 5ms"))
        for snapshot in variants:
            with self.subTest(snapshot=snapshot):
                self.assertEqual(READINESS.input_focus(snapshot), ("a1", HOME))
        for snapshot in (variants[0].replace("0: 'global',", "0: 'global'"),
                         variants[0].replace("  Gesture monitors", "  Unknown monitors"),
                         variants[1].replace("      Windows:\n", ""),
                         INPUT.replace(", touchOcclusionMode=BLOCK_UNTRUSTED", ""),
                         INPUT.replace("responsive=true", "responsive=false"),
                         INPUT.replace("status=NORMAL", "status=BROKEN"),
                         INPUT.replace("      WaitQueue: <empty>\n", "")):
            with self.subTest(snapshot=snapshot), self.assertRaises(READINESS.HealthFailure):
                READINESS.input_focus(snapshot)

    def test_emitted_process_error_branches_and_unknown_flags_reject(self):
        branches = ("mCrashing=true null mNotResponding=false null bad=false",
                    "mCrashing=false null mNotResponding=true null bad=false",
                    "mCrashing=false null mNotResponding=false null bad=true",
                    "mCrashing=false [AppErrorDialog@12] mNotResponding=false null bad=false",
                    "mCrashing=false null mNotResponding=false [AppNotRespondingDialog@12] bad=false",
                    "bad=true", "bad=unknown", "mBad=true", "mNotResponding=unknown",
                    "mAnrDialog=dialog", "mCrashDialog=dialog", "mCrashing=false mNotResponding=false",
                    "mCrashing true", "mNotResponding unknown", "bad true")
        for branch in branches:
            snapshot = PROCESSES.replace("    mountMode=DEFAULT\n", "    mountMode=DEFAULT\n    " + branch + "\n")
            with self.subTest(branch=branch), self.assertRaises(READINESS.HealthFailure):
                READINESS.require_process_health(snapshot)

    def test_error_like_package_and_path_names_are_healthy_metadata(self):
        names = ("reader", "badger", "badminton", "mCrashingReporter", "mNotRespondingReader",
                 "mBadReader", "mCrashDialogReader", "mAnrDialogReader", "crashDialogReader", "anrDialogReader")
        for name in names:
            with self.subTest(name=name):
                READINESS.require_process_health(process_metadata_snapshot(f"com.example.{name}"))

    def test_error_field_names_in_metadata_values_are_not_fields(self):
        fields = ("mCrashing", "mNotResponding", "mBad", "bad", "mCrashDialog",
                  "mAnrDialog", "crashDialog", "anrDialog")
        templates = ("class={value}", "manageSpaceActivityName={value}",
                     "dir=/data/app/{value}/base.apk publicDir=/data/app/{value}/base.apk data=/data/user/0/{value}",
                     "packageList={{{value}, com.example.reader}}",
                     "packageDependencies={{com.example.reader, {value}}}",
                     "dir=/data/app/{value}=true/base.apk publicDir=/data/app/{value}=true/base.apk data=/data/user/0/reader")
        for field in fields:
            for template in templates:
                metadata = "    " + template.format(value=field) + "\n"
                snapshot = PROCESSES.replace("    compat=", metadata + "    compat=")
                with self.subTest(field=field, template=template):
                    READINESS.require_process_health(snapshot)

    def test_line_leading_error_fields_and_malformed_neighbors_reject(self):
        fields = ("mCrashing", "mNotResponding", "mBad", "bad", "mCrashDialog",
                  "mAnrDialog", "crashDialog", "anrDialog")
        for field in fields:
            for suffix in ("=true", "=false", "=unknown", "Extra=false", " true", "", ": true"):
                for prefix, reason in (("    ", "process error/dialog branch"),
                                       ("      ", "process error/dialog branch"),
                                       ("    \t", "Unknown or unterminated dump text")):
                    branch = prefix + field + suffix + "\n"
                    snapshot = PROCESSES.replace("    mountMode=DEFAULT\n", "    mountMode=DEFAULT\n" + branch)
                    with self.subTest(field=field, suffix=suffix, prefix=prefix):
                        with self.assertRaisesRegex(READINESS.HealthFailure, reason):
                            READINESS.require_process_health(snapshot)

    def test_whole_record_and_every_section_prefix_truncation_reject(self):
        records, tail = PROCESSES.split("  Total persistent processes:", 1)
        second = records.split("  All known processes:\n", 1)[1].replace("*APP*", "*PERS*")
        second = second.replace("aa 1201", "bb 1202").replace("pid=1201", "pid=1202")
        complete = records + second + "  Total persistent processes:" + tail
        READINESS.require_process_health(complete)
        with self.assertRaises(READINESS.HealthFailure):
            READINESS.require_process_health(records + second + "    bad=true\n  Total persistent processes:" + tail)
        for snapshot, parser in ((complete, READINESS.require_process_health), (INPUT, READINESS.input_focus),
                                 (OWNERSHIP, READINESS.window_owners),
                                 (WINDOW, lambda value: READINESS.window_focus(value, OWNERSHIP))):
            lines = snapshot.splitlines(keepends=True)
            for boundary in range(len(lines)):
                with self.subTest(parser=parser, boundary=boundary), self.assertRaises(READINESS.HealthFailure):
                    parser("".join(lines[:boundary]))
        with self.assertRaises(READINESS.HealthFailure):
            READINESS.require_process_health(records)

    def test_unknown_trailers_and_relevant_boundaries_reject(self):
        for snapshot, parser in ((PROCESSES, READINESS.require_process_health),
                                 (INPUT, READINESS.input_focus), (OWNERSHIP, READINESS.window_owners)):
            for trailer in ("DUMP TIMEOUT\n", "unknown trailer\n", "  UnknownSection:\n", "\x00\n"):
                with self.subTest(parser=parser, trailer=trailer), self.assertRaises(READINESS.HealthFailure):
                    parser(snapshot + trailer)
        for mutation in (PROCESSES.replace("  *APP* UID", "  *APP* truncated\n  *APP* UID"),
                         PROCESSES.replace("    pid=1201", "    pid=unknown"),
                         PROCESSES.replace("  Total persistent processes:", "  UnknownProcessSection:")):
            with self.subTest(mutation=mutation), self.assertRaises(READINESS.HealthFailure):
                READINESS.require_process_health(mutation)

    def test_framing_does_not_validate_unrelated_payloads_or_interior_deletion(self):
        record, tail = PROCESSES.split("  Total persistent processes:", 1)
        second = record.split("  All known processes:\n", 1)[1].replace("aa 1201", "bb 1202")
        READINESS.require_process_health(record + second + "  Total persistent processes:" + tail)
        READINESS.require_process_health(PROCESSES)
        opaque = INPUT.replace("  Monitors:", "        transform delegated payload\n  Monitors:")
        self.assertEqual(READINESS.input_focus(opaque), ("a1", HOME))
        self.assertEqual(READINESS.window_focus(WINDOW + "uninterpreted token payload\n", OWNERSHIP), ("a1", HOME))
        for snapshot, parser in (("unknown\n" + PROCESSES, READINESS.require_process_health),
                                 ("unknown\n" + OWNERSHIP, READINESS.window_owners),
                                 ("unknown\n" + WINDOW, lambda value: READINESS.window_focus(value, OWNERSHIP)),
                                 ("unknown" + INPUT, READINESS.input_focus),
                                 (INPUT.replace("  DispatchEnabled:", "  Unknown\n  DispatchEnabled:"), READINESS.input_focus)):
            with self.subTest(snapshot=snapshot), self.assertRaises(READINESS.HealthFailure):
                parser(snapshot)

    def test_current_input_cannot_borrow_historical_fields(self):
        history = "\nInput Dispatcher State at time of last ANR:\n" + INPUT.split("\n", 1)[1]
        with self.assertRaisesRegex(READINESS.HealthFailure, "Retained input ANR history.*not an active-error"):
            READINESS.input_focus(INPUT + history)
        for missing in ("  DispatchEnabled: true\n", "  FocusedWindows:\n    displayId=0, name='a1 com.example.launcher/.Home'\n",
                        "    KeyRepeatTimeout: 500ms\n"):
            with self.subTest(missing=missing), self.assertRaises(READINESS.HealthFailure) as failure:
                READINESS.input_focus(INPUT.replace(missing, "") + history)
            self.assertNotIn("Retained input ANR", str(failure.exception))

    def test_window_history_is_not_current_focus_or_active_error_proof(self):
        historic = WINDOW.replace("Window{a1 u0 com.example.launcher/.Home}", "Window{bb u0 old/.Old}")
        historic = historic.split("WINDOW MANAGER DISPLAY CONTENTS", 1)[1].split("WINDOW MANAGER TOKENS", 1)[0]
        snapshot = WINDOW.replace("  <no ANR has occurred since boot>",
                                  "Last ANR continued\nWINDOW MANAGER DISPLAY CONTENTS" + historic)
        self.assertEqual(READINESS.current_display_focus(snapshot),
                         ("Window{a1 u0 com.example.launcher/.Home}", True))
        with self.assertRaisesRegex(READINESS.HealthFailure, "Retained window ANR history.*not an active-error"):
            READINESS.window_focus(snapshot, OWNERSHIP)
        missing = snapshot.rsplit("  mCurrentFocus=Window{a1 u0 com.example.launcher/.Home}\n", 1)
        with self.assertRaises(READINESS.HealthFailure):
            READINESS.window_focus("".join(missing), OWNERSHIP)

    def test_inline_no_focus_requires_complete_current_sections(self):
        no_focus = INPUT.replace("  FocusedWindows:\n    displayId=0, name='a1 com.example.launcher/.Home'",
                                 "  FocusedWindows: <none>")
        self.assertIsNone(READINESS.input_focus(no_focus))
        for snapshot in (no_focus.split("  Connections:", 1)[0], no_focus + "unknown trailer\n",
                         no_focus.replace("  DispatchFrozen: false", "  DispatchFrozen: true")):
            with self.subTest(snapshot=snapshot), self.assertRaises(READINESS.HealthFailure):
                READINESS.input_focus(snapshot)

    def test_stable_nonzero_errors_and_unknown_events_fail_closed(self):
        cases = ("", "logcat failed", EVENTS + "I/am_anr( 1000): [0,1201,launcher]\n",
                 EVENTS + "I/am_crash( 1000): [0,1201,app]\n", EVENTS + "I/am_proc_start(")
        for snapshot in cases:
            with self.subTest(snapshot=snapshot), self.assertRaises(READINESS.HealthFailure):
                READINESS.require_clean_events(snapshot)


class BoundedAdapterTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="android-readiness-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.device = FakeDevice(self.root)
        self.environment = mock.patch.dict(os.environ, self.device.environment(), clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def session(self, seconds=30):
        return READINESS.DeviceSession(self.root / "evidence", "test", seconds)

    def test_source_projection_with_api35_and_api36_selectors(self):
        for api in ("35", "36"):
            with self.subTest(api=api), mock.patch.object(READINESS, "time", Clock()):
                self.device.add(["shell", "getprop", "ro.build.version.sdk"], api)
                session = self.session()
                READINESS.wait_for_health(session, "HOME", False)
                self.assertEqual(len(list(session.directory.glob("sample-*.json"))), 2)
        self.assertTrue(all(command in [json.loads(key) for key in self.device.rules]
                            for command in self.device.trace()))

    def test_background_metadata_does_not_hide_real_error_branches(self):
        records, tail = PROCESSES.split("  Total persistent processes:", 1)
        for name in ("reader", "badger", "badminton", "mCrashingReporter"):
            background = process_metadata_snapshot(f"com.example.{name}")
            background = background.split("  All known processes:\n", 1)[1].split("  Total persistent processes:", 1)[0]
            background = background.replace("10101", "10230").replace("u0a101", "u0a230")
            background = background.replace("1201", "2890").replace("ProcessRecord{aa", "ProcessRecord{bb")
            snapshot = records + background + "  Total persistent processes:" + tail
            with self.subTest(name=name):
                self.device.add(["shell", "dumpsys", "activity", "processes"], snapshot)
                self.assertTrue(READINESS.health_sample(self.session(), HOME))
                for branch in ("bad=true", "mCrashing=true null", "mNotResponding=true null",
                               "mCrashing=false [AppErrorDialog@12]", "mNotResponding unknown"):
                    unhealthy = snapshot.replace("    pid=2890\n", "    pid=2890\n    " + branch + "\n")
                    self.device.add(["shell", "dumpsys", "activity", "processes"], unhealthy)
                    with self.subTest(branch=branch), self.assertRaisesRegex(READINESS.HealthFailure, "process error"):
                        READINESS.health_sample(self.session(), HOME)

    def test_startup_requires_eighty_seconds_of_healthy_observations(self):
        clock = Clock()
        with mock.patch.object(READINESS, "time", clock):
            session = self.session(600)
            READINESS.wait_for_health(session, "HOME", True)
            self.assertEqual(clock.now, 80)
            self.assertEqual(len(list(session.directory.glob("sample-*.json"))), 5)

    def test_transition_and_startup_absolute_deadlines_never_accept_last_sample(self):
        for startup, seconds in ((False, 30), (True, 600)):
            with self.subTest(startup=startup), mock.patch.object(READINESS, "time", Clock()) as clock:
                session = self.session(seconds)
                with mock.patch.object(READINESS, "health_sample", return_value=False):
                    with self.assertRaisesRegex(READINESS.HealthFailure, "absolute deadline"):
                        READINESS.wait_for_health(session, HOME, startup)
                self.assertEqual(clock.now, seconds)
                with mock.patch.object(READINESS, "health_sample", return_value=True) as sample:
                    with self.assertRaises(READINESS.HealthFailure):
                        READINESS.wait_for_health(session, HOME, startup)
                sample.assert_not_called()

    def test_transient_samples_reset_startup_stability(self):
        clock = Clock()
        with mock.patch.object(READINESS, "time", clock):
            with mock.patch.object(READINESS, "health_sample", side_effect=[True, False] + [True] * 5):
                READINESS.wait_for_health(self.session(600), HOME, True)
        self.assertEqual(clock.now, 120)

    def test_complete_inline_no_focus_resets_real_sample_stability(self):
        clock = Clock()
        healthy_sample = READINESS.health_sample
        no_focus = INPUT.replace("  FocusedWindows:\n    displayId=0, name='a1 com.example.launcher/.Home'",
                                 "  FocusedWindows: <none>")

        def sample(session, expected):
            self.device.add(["shell", "dumpsys", "input"], no_focus if clock.now == 20 else INPUT)
            return healthy_sample(session, expected)

        with mock.patch.object(READINESS, "time", clock), mock.patch.object(READINESS, "health_sample", side_effect=sample):
            session = self.session(600)
            READINESS.wait_for_health(session, HOME, True)
        self.assertEqual(clock.now, 120)
        observations = [json.loads(path.read_text())["healthy"] for path in sorted(session.directory.glob("sample-*.json"))]
        self.assertEqual(observations, [True, False] + [True] * 5)

    def test_no_focus_does_not_skip_process_or_section_validation(self):
        no_focus = INPUT.replace("  FocusedWindows:\n    displayId=0, name='a1 com.example.launcher/.Home'",
                                 "  FocusedWindows: <none>")
        self.device.add(["shell", "dumpsys", "input"], no_focus)
        self.device.add(["shell", "dumpsys", "activity", "processes"],
                        PROCESSES.replace("    mountMode=DEFAULT", "    mountMode=DEFAULT\n    bad=true"))
        with self.assertRaisesRegex(READINESS.HealthFailure, "process error"):
            READINESS.health_sample(self.session(), HOME)
        self.device.add(["shell", "dumpsys", "activity", "processes"], PROCESSES)
        self.device.add(["shell", "dumpsys", "input"], no_focus.split("  Connections:", 1)[0])
        with self.assertRaisesRegex(READINESS.HealthFailure, "input sections"):
            READINESS.health_sample(self.session(), HOME)

    def test_mismatched_focus_token_component_or_null_is_not_healthy(self):
        for inputs in (INPUT.replace("a1 ", "b2 "), INPUT.replace("/.Home", "/.Other"),
                       INPUT.replace("  FocusedWindows:\n    displayId=0, name='a1 com.example.launcher/.Home'",
                                     "  FocusedWindows: <none>")):
            with self.subTest(inputs=inputs):
                self.device.add(["shell", "dumpsys", "input"], inputs)
                self.assertFalse(READINESS.health_sample(self.session(), HOME))
        self.device.add(["shell", "dumpsys", "input"], INPUT)
        self.assertFalse(READINESS.health_sample(self.session(), "com.example.other/.Home"))

    def test_zero_anr_events_cannot_admit_system_dialog_over_resumed_home(self):
        snapshot = WINDOW.replace("mCurrentFocus=Window{a1 u0 com.example.launcher/.Home}",
                                  "mCurrentFocus=Window{b2 u0 Application Not Responding: Messages}")
        snapshot += "  mFocusedApp=ActivityRecord{aa com.example.launcher/.Home}\n"
        self.device.add(["shell", "dumpsys", "window"], snapshot)
        session = self.session()
        with self.assertRaises(READINESS.HealthFailure):
            READINESS.health_sample(session, HOME)
        self.assertTrue(any("Application Not Responding" in path.read_text()
                            for path in session.directory.glob("*.stdout")))

    def test_failed_empty_hung_and_invalid_utf8_queries_reject_and_retain(self):
        command = ["shell", "dumpsys", "window"]
        for options in ({"status": 7, "stderr": "transport failed"}, {"hang": True}, {}):
            with self.subTest(options=options):
                self.device.add(command, "", **options)
                session = self.session(0.3)
                started = time.monotonic()
                with self.assertRaises(READINESS.HealthFailure):
                    READINESS.window_focus(session.snapshot(*command), OWNERSHIP)
                self.assertLess(time.monotonic() - started, 3)
                metadata = json.loads((session.directory / "001.json").read_text())
                self.assertEqual(metadata["command"], ["adb", *command])
        self.device.add(command, b"\xff")
        with self.assertRaisesRegex(READINESS.HealthFailure, "Invalid device text"):
            self.session().text(*command)

    def test_expired_budget_never_starts_command_and_diagnostics_stay_bounded(self):
        session = self.session(-1)
        session.capture(["shell", "dumpsys", "window"], required=False)
        self.assertEqual(self.device.trace(), [])
        self.assertEqual(json.loads((session.directory / "001.json").read_text())["status"], 124)
        self.device.add(["shell", "dumpsys", "window"], hang=True)
        session = self.session(0.3)
        started = time.monotonic()
        READINESS.collect_diagnostics(session)
        self.assertLess(time.monotonic() - started, 3)
        commands = [json.loads(path.read_text()) for path in session.directory.glob("[0-9]*.json")]
        self.assertGreater(len(commands), 10)
        self.assertTrue(any(command["status"] == 124 for command in commands))
        self.assertIn(["adb", "shell", "dumpsys", "dropbox", "--print", "system_app_anr"],
                      [command["command"] for command in commands])

    def test_command_budget_caps_at_ten_seconds_or_remaining_deadline(self):
        with mock.patch.object(READINESS, "time", Clock()) as clock:
            session = self.session(30)
            with mock.patch.object(READINESS, "run_bounded", return_value=0) as command:
                session.capture(["fake"], required=False)
                self.assertEqual(command.call_args.args[-1], 10)
                clock.now = 29.5
                session.capture(["fake"], required=False)
                self.assertEqual(command.call_args.args[-1], 0.5)

    def test_logcat_failures_and_truncated_output_never_count_as_zero_errors(self):
        for content, options in ((EVENTS, {"status": 7}), ("", {}), (EVENTS + "I/am_anr(", {})):
            with self.subTest(content=content, options=options):
                self.device.add(["logcat", "-b", "events", "-d", "-v", "brief"], content, **options)
                with self.assertRaises(READINESS.HealthFailure):
                    READINESS.health_sample(self.session(), HOME)

    def test_screenshot_failure_is_retained_and_success_keeps_png(self):
        command = ["exec-out", "screencap", "-p"]
        self.device.add(command, b"synthetic-png", status=0)
        session = self.session()
        session.capture(command, required=False)
        self.assertEqual((session.directory / "001.png").read_bytes(), b"synthetic-png")
        self.device.add(command, b"partial", status=2)
        session.capture(command, required=False)
        self.assertFalse((session.directory / "002.png").exists())
        self.assertEqual((session.directory / "002.stdout").read_bytes(), b"partial")

    def test_unknown_api_home_and_failed_storage_are_rejected(self):
        self.device.add(["shell", "getprop", "ro.build.version.sdk"], "37")
        with self.assertRaisesRegex(READINESS.HealthFailure, "Unsupported Android API"):
            READINESS.wait_for_health(self.session(), "HOME", False)
        home_command = next(json.loads(key) for key in self.device.rules if "resolve-activity" in key)
        self.device.add(home_command, HOME + "\ncom.other/.Home")
        with self.assertRaisesRegex(READINESS.HealthFailure, "Unknown component grammar"):
            READINESS.resolve_home(self.session())
        storage_command = next(json.loads(key) for key in self.device.rules if "mkdir -p" in key)
        self.device.add(storage_command, status=1)
        with self.assertRaises(READINESS.HealthFailure):
            READINESS.health_sample(self.session(), HOME)


class RunnerEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="android-runner-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.device = FakeDevice(self.root)
        self.device.shell_tools()

    def shell(self, script):
        return subprocess.run(["/bin/bash", "-euo", "pipefail", "-c", script], cwd=self.root,
                              env=self.device.environment(), capture_output=True, text=True, timeout=20)

    def test_full_runner_rejects_preexisting_dialog_before_install_or_instrumentation(self):
        self.device.add(["logcat", "-b", "all", "-v", "threadtime"], stream=True)
        self.device.add(["shell", "settings", "get", "system", "font_scale"], "1.0")
        self.device.add(["shell", "settings", "put", "system", "font_scale", "1.0"], "")
        self.device.add(["shell", "dumpsys", "window"], WINDOW.replace(
            "mCurrentFocus=Window{a1 u0 com.example.launcher/.Home}",
            "mCurrentFocus=Window{b2 u0 Application Not Responding: Messages}"))
        result = self.shell(f'/bin/bash "{RUNNER}"')
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Android precondition failed", result.stderr)
        trace = self.device.trace()
        self.assertFalse(any("install" in command or "instrument" in command for command in trace))
        self.assertFalse(any(command[0] == "logcat" and "-c" in command for command in trace))
        records = list(self.root.glob("app/build/reports/androidTests/**/result.json"))
        self.assertTrue(any(json.loads(path.read_text())["status"] == "failed" for path in records))
        self.assertTrue(list(self.root.glob("app/build/reports/androidTests/**/logcat-live.txt")))

    def test_home_obstruction_after_force_stop_never_starts_instrumentation(self):
        script = runner_function("prepare_instrumentation") + runner_function("run_instrumentation")
        script += """package_name=me.egigoka.pomodorough
device_command() { printf '%s\\n' "$*" >> trace; }
require_android_health() { printf 'health %s\\n' "$*" >> trace; return 1; }
execute_instrumentation() { echo instrument >> trace; }
verify_instrumentation_result() { echo verify >> trace; }
run_instrumentation shard.txt
"""
        result = self.shell(script)
        self.assertEqual(result.returncode, 1)
        trace = (self.root / "trace").read_text().splitlines()
        self.assertEqual(trace[0], "shard-stop shell am force-stop me.egigoka.pomodorough")
        self.assertIn("pidof", trace[1])
        self.assertEqual(trace[2:], ["health shard-home HOME"])

    def test_failed_output_is_appended_without_changing_failure_or_counts(self):
        output = ("INSTRUMENTATION_STATUS: numtests=36\n" + "INSTRUMENTATION_STATUS_CODE: 0\n" * 35
                  + "INSTRUMENTATION_STATUS: stack=Missing platform input label: Room name optional\n"
                  + "INSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\nINSTRUMENTATION_CODE: -1\n")
        (self.root / "shard.txt").write_text(output)
        script = runner_function("verify_instrumentation_result") + """runner_output=aggregate.txt
announced_tests=0; completed_tests=0; status=0
verify_instrumentation_result shard.txt 0 || status=$?
printf '%s %s %s\\n' "$status" "$completed_tests" "$announced_tests"
"""
        result = self.shell(script)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(result.stdout.endswith("1 35 36\n"))
        self.assertEqual((self.root / "aggregate.txt").read_text(), output)

    def test_failed_diagnostics_never_replace_instrumentation_failure(self):
        script = runner_function("run_instrumentation") + """prepare_instrumentation() { return 0; }
execute_instrumentation() { return 42; }
verify_instrumentation_result() { test "$2" -eq 42; return 1; }
collect_failure_diagnostics() { echo diagnostics; return 79; }
run_instrumentation shard.txt
"""
        result = self.shell(script)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "diagnostics\n")

    def test_cleanup_preserves_failure_and_detects_restore_failure(self):
        for original, restore, expected in ((42, 0, 42), (42, 83, 42), (0, 83, 83), (0, 0, 0)):
            with self.subTest(original=original, restore=restore):
                script = runner_function("cleanup") + f"""logcat_pid=""
collect_failure_diagnostics() {{ echo diagnostics; return 79; }}
restore_font_scale() {{ echo restore; return {restore}; }}
stop_log_capture() {{ echo stop; }}
trap cleanup EXIT
exit {original}
"""
                result = self.shell(script)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                self.assertEqual(result.stdout.splitlines(),
                                 (["diagnostics"] if original else []) + ["restore", "stop"])

    def test_initial_query_failure_still_retains_evidence_without_restore_or_install(self):
        self.device.add(["logcat", "-b", "all", "-v", "threadtime"], stream=True)
        self.device.add(["shell", "settings", "get", "system", "font_scale"], status=7)
        result = self.shell(f'/bin/bash "{RUNNER}"')
        self.assertEqual(result.returncode, 1)
        trace = self.device.trace()
        self.assertIn(["shell", "dumpsys", "window"], trace)
        self.assertFalse(any("install" in command or "instrument" in command for command in trace))
        self.assertFalse(any(command[:4] == ["shell", "settings", "put", "system"] for command in trace))

    def test_successful_run_with_dead_log_capture_is_failure(self):
        script = runner_function("cleanup") + """logcat_pid=99999999
collect_failure_diagnostics() { echo diagnostics; }
restore_font_scale() { echo restore; }
stop_log_capture() { echo stop; }
trap cleanup EXIT
exit 0
"""
        result = self.shell(script)
        self.assertEqual(result.returncode, 1)
        self.assertIn("log capture exited unexpectedly", result.stderr)
        self.assertEqual(result.stdout.splitlines(), ["diagnostics", "restore", "stop"])

    def test_shard_scheduling_preserves_first_failure_and_runs_all_eight_once(self):
        source = RUNNER.read_text()
        scheduling = source.split('if [[ -n "$test_class" ]]; then', 1)[1].split(
            'device_command after-tests-screenshot', 1)[0]
        script = """test_class=""; results_dir=results; runner_status=0
run_instrumentation() { echo "$*"; [[ "$1" != *shard-1.txt ]]; }
if [[ -n "$test_class" ]]; then
""" + scheduling + 'printf "status=%s\\n" "$runner_status"\n'
        result = self.shell(script)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [
            f"results/instrumentation-shard-{index}.txt -e numShards 8 -e shardIndex {index}"
            for index in range(8)] + ["status=1"])


if __name__ == "__main__":
    unittest.main()
