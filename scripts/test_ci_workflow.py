from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
BUILD_FILE = ROOT / "app" / "build.gradle.kts"
PROGUARD_FILE = ROOT / "app" / "proguard-rules.pro"
SMOKE_SCRIPT = ROOT / ".github" / "scripts" / "smoke-packaged-release.sh"
INSTRUMENTED_SCRIPT = ROOT / ".github" / "scripts" / "run-instrumented-tests.sh"
RELEASE_IROH_SOURCE = ROOT / "app" / "src" / "androidTest" / "java" / "me" / "egigoka" / "pomodorough" / "releaseiroh" / "ReleaseIrohSmokeInstrumentation.java"
PROVENANCE_SCRIPT = ROOT / "scripts" / "verify_shared_core_provenance.py"
VALID_WASM = b"\0asm\x01\0\0\0"
DIFFERENT_VALID_WASM = VALID_WASM + b"\0\x01\0"


DEPENDENCY_LOCK = ROOT / "app" / "gradle.lockfile"


class CIWorkflowTests(unittest.TestCase):
    def test_chicory_dependencies_are_locked(self) -> None:
        lock = DEPENDENCY_LOCK.read_text(encoding="utf-8")
        self.assertIn("com.dylibso.chicory:runtime:1.7.5", lock)
        self.assertIn("com.dylibso.chicory:wasm:1.7.5", lock)

    def test_pinned_shared_core_is_rebuilt_and_contract_verified(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn(
            'CORE_COMMIT: "44ff36e125cf653c1761dfb5951f6e77e41a2c82"',
            workflow,
        )
        self.assertIn(
            'CORE_SHA256: "f34fe57b5e080dd69afa5c7f28b60bc77851c7f874db99744eab72b4e1858877"',
            workflow,
        )
        self.assertIn("repository: Pomodoro-Everywhere/pomodorough-core", workflow)
        self.assertIn("ref: ${{ env.CORE_COMMIT }}", workflow)
        self.assertIn(
            "cargo +1.97.1 build --release --target wasm32-unknown-unknown --locked",
            workflow,
        )
        self.assertIn("canonicalize_wasm_artifact.py", workflow)
        self.assertIn("verify_wasm_artifact.py", workflow)
        self.assertIn('--sha256 "$CORE_SHA256"', workflow)
        self.assertIn("scripts/verify_shared_core_provenance.py", workflow)
        self.assertIn('"$rebuilt"', workflow)
        self.assertIn(
            'grep -Fx "CORE_COMMIT=$CORE_COMMIT" app/src/main/assets/shared_core.properties',
            workflow,
        )
        self.assertIn(
            'grep -Fx "CORE_SHA256=$CORE_SHA256" app/src/main/assets/shared_core.properties',
            workflow,
        )

    def test_provenance_rejects_different_valid_wasm(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rebuilt = root / "rebuilt.wasm"
            embedded = root / "embedded.wasm"
            rebuilt.write_bytes(VALID_WASM)
            embedded.write_bytes(DIFFERENT_VALID_WASM)
            result = subprocess.run(
                [
                    sys.executable,
                    str(PROVENANCE_SCRIPT),
                    "--sha256",
                    hashlib.sha256(VALID_WASM).hexdigest(),
                    str(rebuilt),
                    str(embedded),
                ],
                capture_output=True,
                check=False,
                text=True,
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("differs from rebuild", result.stderr)


    def test_release_packages_contain_exact_shared_core(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("Verify shared core in unsigned release APK", workflow)
        self.assertIn("unzip -p \"$release_apk\" assets/pomodorough_core.wasm", workflow)
        self.assertIn("unzip -p \"$release_aab\" base/assets/pomodorough_core.wasm", workflow)
        self.assertIn('test "$packaged_core_sha" = "$CORE_SHA256"', workflow)

    def test_connected_matrix_covers_english_and_rtl_at_both_font_scales(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        connected_job = workflow.split("  connected:", 1)[1].split(
            "\n  release-smoke:", 1
        )[0]

        self.assertEqual(connected_job.count('locale: "en-US"'), 4)
        self.assertEqual(connected_job.count('locale: "ar-XB"'), 4)
        self.assertIn('test-class: ""', connected_job)
        self.assertEqual(
            connected_job.count(
                "test-class: me.egigoka.pomodorough.ui.PomodoroughRtlAccessibilityTest"
            ),
            4,
        )
        self.assertIn("${{ matrix.locale }}", connected_job)
        self.assertIn("TEST_LOCALE=${{ matrix.locale }}", connected_job)
        self.assertIn("TEST_CLASS=${{ matrix.test-class }}", connected_job)
        self.assertIn(
            "TEST_LOCALE=${{ matrix.locale }} TEST_CLASS=${{ matrix.test-class }} "
            ".github/scripts/run-instrumented-tests.sh",
            connected_job,
        )
        self.assertIn("locale-${{ matrix.locale }}", connected_job)

    def test_instrumented_runner_applies_and_verifies_requested_app_locale(self) -> None:
        script = INSTRUMENTED_SCRIPT.read_text(encoding="utf-8")

        self.assertIn('test_locale="${TEST_LOCALE:-en-US}"', script)
        self.assertIn('test_class="${TEST_CLASS:-}"', script)
        self.assertIn(
            'instrumentation_timeout_seconds="${INSTRUMENTATION_TIMEOUT_SECONDS:-600}"',
            script,
        )
        self.assertIn(
            'cmd locale set-app-locales "$package_name" --user 0 --locales "$test_locale"',
            script,
        )
        self.assertIn("cmd locale get-app-locales", script)
        self.assertIn(
            'expected_locale_line="Locales for $package_name for user 0 are [$test_locale]"',
            script,
        )
        self.assertIn('test "$locale_output" = "$expected_locale_line"', script)
        self.assertIn('if [[ -n "$test_class" ]]; then', script)
        self.assertIn('-e expectedLocale "$test_locale"', script)
        self.assertIn('-e class "$test_class"', script)
        self.assertIn('shard_count="${TEST_SHARD_COUNT:-8}"', script)
        self.assertIn('-e numShards "$shard_count" -e shardIndex "$shard_index"', script)
        self.assertIn('instrumentation-shard-$shard_index.txt', script)
        self.assertIn('completed_tests=$((completed_tests + completed))', script)
        self.assertIn('announced_tests=$((announced_tests + announced))', script)
        self.assertIn('shard_status=0', script)
        self.assertIn('if [[ $runner_status -eq 0 && $shard_status -ne 0 ]]', script)
        self.assertNotIn('|| { runner_status=$?; break; }', script)
        self.assertIn('if ! tee -a "$runner_output" < "$output_file"; then', script)
        self.assertIn('Instrumentation completed $completed_tests/$announced_tests tests across', script)
        self.assertIn("'^INSTRUMENTATION_STATUS_CODE: -[12]$|^FAILURES!!!$'", script)
        self.assertNotIn('run_instrumentation_with_startup_retry', script)
        self.assertNotIn('final_pid=', script)
        self.assertIn('bounded_force_stop() {', script)
        self.assertIn('kill -KILL "$instrumentation_pid"', script)
        self.assertLess(
            script.index('kill "$instrumentation_pid"'),
            script.index('bounded_force_stop "$package_name"'),
        )
        self.assertNotIn('"${instrumentation_args[@]}"', script)
        self.assertIn("PomodoroughRtlAccessibilityTest", workflow := CI_WORKFLOW.read_text(encoding="utf-8"))
        self.assertIn("TEST_CLASS=${{ matrix.test-class }}", workflow)

    def test_connected_assembly_precedes_emulator_with_identical_targets(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        connected = workflow.split("  connected:", 1)[1].split("\n  release-smoke:", 1)[0]
        command = "./gradlew --no-daemon --stacktrace :app:assembleDebug :app:assembleDebugAndroidTest"
        self.assertEqual(connected.count(command), 1)
        self.assertLess(connected.index(command), connected.index("- name: Run connected tests"))
        self.assertIn("python3 -m unittest scripts/test_android_readiness.py -v", connected)
        self.assertIn("python3 .github/scripts/run-android-emulator.py", connected)
        self.assertIn("--api-level ${{ matrix.api-level }} --architecture x86_64", connected)
        self.assertIn("scripts/test_android_emulator_lifecycle.py -v", connected)
        self.assertNotIn("ReactiveCircus/android-emulator-runner@", connected)
        self.assertIn("fail-fast: false", connected)

    def test_verify_assembly_uses_fresh_gradle_process(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        verify = workflow.split("  verify:", 1)[1].split("\n  connected:", 1)[0]
        tests = verify.split("- name: Run unit tests and lint", 1)[1].split(
            "- name: Assemble debug and test APKs", 1
        )[0]
        assembly = verify.split("- name: Assemble debug and test APKs", 1)[1].split(
            "- name: Assemble unsigned release APK", 1
        )[0]

        self.assertEqual(tests.count("./gradlew --no-daemon --stacktrace"), 1)
        self.assertIn(":app:testDebugUnitTest", tests)
        self.assertIn(":app:testReleaseUnitTest", tests)
        self.assertIn(":app:lintDebug", tests)
        self.assertIn(":app:lintRelease", tests)
        self.assertNotIn(":app:assembleDebug", tests)
        self.assertEqual(assembly.count("./gradlew --no-daemon --stacktrace"), 1)
        self.assertIn(":app:assembleDebug :app:assembleDebugAndroidTest", assembly)
        self.assertNotIn(":app:lint", assembly)
        self.assertNotIn("UnitTest", assembly)

    def test_runner_gates_active_windows_without_dismissing_or_clearing_evidence(self) -> None:
        script = INSTRUMENTED_SCRIPT.read_text(encoding="utf-8")
        self.assertLess(script.index("trap cleanup EXIT"), script.index('original_font_scale="$(device_command'))
        self.assertLess(script.index("adb logcat -b all -v threadtime"), script.index("require_android_health boot"))
        self.assertIn("require_android_health boot HOME --startup", script)
        self.assertIn("require_android_health configured HOME --startup", script)
        self.assertIn("require_android_health launched me.egigoka.pomodorough/.MainActivity", script)
        self.assertIn('require_android_health "$phase-home" HOME', script)
        self.assertLess(script.index('prepare_instrumentation "$output_file"'),
                        script.index('execute_instrumentation "$output_file"'))
        self.assertLess(script.index("    collect_failure_diagnostics\n"),
                        script.index("  restore_font_scale || restore_status=$?"))
        self.assertLess(script.index('tee -a "$runner_output"'),
                        script.index('echo "Instrumentation runner failed'))
        for prohibited in ("logcat -c", "logcat -b events -c", "input keyevent", "input tap",
                           "pm disable", "wait_for_android_idle", "previous_anr_count"):
            self.assertNotIn(prohibited, script)

    def test_instrumentation_count_matches_source_inventory(self) -> None:
        script = INSTRUMENTED_SCRIPT.read_text(encoding="utf-8")
        default = re.search(r'expected_test_count="\$\{EXPECTED_TEST_COUNT:-(\d+)\}"', script)
        self.assertIsNotNone(default)
        inventory = []
        for source_set in ("androidTest", "sharedTest"):
            for path in sorted((ROOT / "app/src" / source_set / "java").rglob("*")):
                if path.suffix not in (".kt", ".java"):
                    continue
                source = path.read_text(encoding="utf-8")
                annotations = re.findall(r"^\s*@Test\b", source, re.MULTILINE)
                # Current suite uses JUnit4 Kotlin methods. Fail if a new annotation
                # shape needs inventory support instead of silently undercounting it.
                methods = re.findall(
                    r"^\s*@Test(?:\([^\n]*\))?\s*\n\s*fun\s+(\w+)\(",
                    source, re.MULTILINE,
                )
                self.assertEqual(len(annotations), len(methods), str(path))
                inventory.extend((path.relative_to(ROOT), method) for method in methods)
        self.assertTrue(inventory)
        self.assertEqual(len(inventory), len(set(inventory)))
        self.assertEqual(int(default[1]), len(inventory), "Update runner's explicit inventory guard")

    def test_instrumentation_count_guard_fails_closed(self) -> None:
        script = INSTRUMENTED_SCRIPT.read_text(encoding="utf-8")
        default = re.search(r'expected_test_count="\$\{EXPECTED_TEST_COUNT:-(\d+)\}"', script)
        expected = int(default[1])
        guard = script[script.index('echo "Instrumentation completed '):]
        cases = (
            (expected, expected, 0, "", True),
            (expected - 1, expected - 1, 0, "", False),
            (expected + 1, expected + 1, 0, "", False),
            (0, 0, 0, "", False),
            (expected - 1, expected, 0, "", False),
            (expected, expected, 1, "", False),
            (5, 5, 0, "FocusedTest", True),
            (4, 5, 0, "FocusedTest", False),
            (5, 5, 1, "FocusedTest", False),
        )
        for completed, announced, status, test_class, passes in cases:
            with self.subTest(completed=completed, announced=announced, status=status, test_class=test_class):
                setup = (
                    f"completed_tests={completed}; announced_tests={announced}; "
                    f"runner_status={status}; expected_test_count={expected}; test_class='{test_class}'\n"
                )
                result = subprocess.run(
                    ["bash", "-euo", "pipefail", "-c", setup + guard],
                    capture_output=True, text=True, check=False,
                )
                self.assertEqual(result.returncode == 0, passes, result.stdout + result.stderr)

    def test_release_smoke_depends_on_complete_connected_matrix(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        release_job = workflow.split("  release-smoke:", 1)[1].split(
            "\n  dependency-review:", 1
        )[0]

        self.assertIn("needs: [verify, connected]", release_job)

    def test_release_runtime_matrix_executes_every_packaged_abi(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        release_job = workflow.split("  release-smoke:", 1)[1].split(
            "\n  dependency-review:", 1
        )[0]
        cells = re.findall(
            r"- api-level: (\d+)\s+architecture: (\S+)\s+runtime-abi: (\S+)",
            release_job,
        )
        self.assertCountEqual(cells, [
            ("30", "x86", "x86"), ("36", "x86_64", "x86_64"),
            ("30", "x86", "armeabi-v7a"), ("30", "x86_64", "arm64-v8a"),
        ])
        self.assertIn("python3 .github/scripts/run-android-emulator.py", release_job)
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("without publishing", workflow)
        self.assertIn("if: inputs.upload-release-bundle", release_job)

    def test_release_bundle_contains_release_targeted_instrumentation(self) -> None:
        build = BUILD_FILE.read_text(encoding="utf-8")
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn('testBuildType = providers.gradleProperty("pomodorough.testBuildType")', build)
        self.assertIn("ReleaseIrohSmokeInstrumentation", build)
        self.assertIn('JAVA_VERSION: "21"', workflow)
        release_source = RELEASE_IROH_SOURCE.read_text(encoding="utf-8")
        self.assertIn("BuildersKt.runBlocking", release_source)
        self.assertNotIn("class BlockingContinuation", release_source)
        self.assertIn("builder.applyMinimal();", release_source)
        self.assertNotIn("builder.applyN0();", release_source)
        self.assertNotIn("androidx.test", release_source)
        proguard = PROGUARD_FILE.read_text(encoding="utf-8")
        self.assertIn("-keepattributes Signature,*Annotation*", proguard)
        self.assertIn("-keep class com.sun.jna.** { *; }", proguard)
        self.assertIn("-keepclassmembers class computer.iroh.EndpointBuilder", proguard)
        self.assertIn("public <init>();", proguard)
        self.assertIn("-keepclassmembers,allowobfuscation class computer.iroh.Endpoint", proguard)
        self.assertIn("public java.lang.Object bind(kotlin.coroutines.Continuation);", proguard)
        self.assertIn("public java.lang.Object connect(computer.iroh.EndpointAddr, byte[], kotlin.coroutines.Continuation);", proguard)
        self.assertNotIn("-keep class computer.iroh.EndpointBuilder", proguard)
        self.assertIn("-keep @com.sun.jna.Structure$FieldOrder class * { *; }", proguard)
        self.assertIn("-keepclassmembers class * extends com.sun.jna.Structure", proguard)
        self.assertIn("<fields>;", proguard)
        self.assertIn("public <init>();", proguard)
        self.assertIn("-keep interface * extends com.sun.jna.Library { *; }", proguard)
        self.assertIn("-keep interface * extends com.sun.jna.Callback { *; }", proguard)
        self.assertIn("-keep class * implements com.sun.jna.Callback { *; }", proguard)
        self.assertIn("-dontwarn java.awt.**", proguard)
        self.assertIn("-keep,allowoptimization class kotlinx.coroutines.BuildersKt", proguard)
        self.assertIn("public static java.lang.Object runBlocking(", proguard)
        self.assertNotIn("kotlinx.coroutines.**", proguard)
        self.assertNotIn("-keep class computer.iroh.**", proguard)
        self.assertIn('if (requestedTestBuildType == "release")', build)
        self.assertIn('else "androidx.test.runner.AndroidJUnitRunner"', build)
        self.assertIn('testProguardFiles("android-test-proguard-rules.pro")', build)
        self.assertIn("-Ppomodorough.testBuildType=release", workflow)
        self.assertIn(":app:assembleReleaseAndroidTest", workflow)
        self.assertIn("app-release-androidTest.apk", workflow)
        self.assertIn("me.egigoka.pomodorough.data.iroh.IrohReplicationService", workflow)
        self.assertIn("me.egigoka.pomodorough.data.iroh.IrohEndpointLifecycle", workflow)
        for binding_class in (
            "computer.iroh.EndpointBuilder",
            "computer.iroh.Endpoint",
            "computer.iroh.EndpointAddr",
            "computer.iroh.Incoming",
            "computer.iroh.Accepting",
            "computer.iroh.Connection",
        ):
            self.assertIn(binding_class, workflow)
        self.assertIn('grep -F "$source_class -> "', workflow)
        self.assertIn("apkanalyzer dex packages --defined-only", workflow)
        self.assertIn('test "$obfuscated_class" != "$source_class"', workflow)
        self.assertIn('grep -E "^C[[:space:]].*[[:space:]]${obfuscated_class}$"', workflow)
        self.assertIn("Release test APK defines production class", workflow)
        self.assertIn("mkdir -p dist release-test", workflow)
        self.assertIn('cp "$release_test_apk" "release-test/', workflow)
        self.assertNotIn('cp "$release_test_apk" "dist/', workflow)
        self.assertIn("name: pomodorough-android-release-test-${{ github.ref_name }}", workflow)
        self.assertIn("path: release-test/", workflow)

    def test_release_smoke_runs_real_iroh_handshake_from_minified_target(self) -> None:
        script = SMOKE_SCRIPT.read_text(encoding="utf-8")

        self.assertIn("release_test_apks=(release-test/*-release-androidTest.apk)", script)
        self.assertIn("sha256sum -c SHA256SUMS.txt", script)
        self.assertIn('runtime_abi="$(select_release_runtime_abi)"', script)
        self.assertIn('adb install --no-incremental --abi "$runtime_abi" "$smoke_apk"', script)
        self.assertIn('adb install --no-incremental --abi "$runtime_abi" "$smoke_test_apk"', script)
        self.assertIn('verify_installed_release_abi "$runtime_abi"', script)
        self.assertIn('--abi "$runtime_abi" -e expectedAbi "$runtime_abi"', script)
        self.assertIn('INSTRUMENTATION_STATUS: runtimeAbi=$runtime_abi', script)
        release_source = RELEASE_IROH_SOURCE.read_text(encoding="utf-8")
        self.assertLess(release_source.index("verifyRuntimeAbi();"),
                        release_source.index("completeEndpointHandshake();"))
        self.assertIn("Process.is64Bit() == expected64Bit", release_source)
        self.assertIn('nativeDirectory.endsWith("/" + libraryArchitecture)', release_source)
        self.assertIn('grep -Fx "lib/$runtime_abi/libiroh_ffi.so"', script)
        self.assertIn('unzip -Z1 "$unsigned_apk" > "$native_entries"', script)
        self.assertNotIn('unzip -Z1 "$unsigned_apk" | grep -Fxq', script)
        self.assertIn("for abi in arm64-v8a armeabi-v7a x86 x86_64", script)
        self.assertIn("lib/$abi/libiroh_ffi.so", script)
        self.assertIn("test \"$app_signer\" = \"$test_signer\"", script)
        self.assertIn('adb pull "$installed_path" "$installed_apk"', script)
        self.assertIn('cmp "$smoke_apk" "$installed_apk"', script)
        self.assertIn(
            "me.egigoka.pomodorough.test/"
            "me.egigoka.pomodorough.releaseiroh.ReleaseIrohSmokeInstrumentation",
            script,
        )
        self.assertNotIn("AndroidJUnitRunner", script)
        self.assertIn("ReleaseIrohSmokeInstrumentation", script)
        self.assertIn("INSTRUMENTATION_STATUS: numtests=1", script)
        self.assertIn("INSTRUMENTATION_CODE: -1", script)
        self.assertIn("trap retain_release_smoke_diagnostics EXIT", script)
        self.assertNotIn('exit "$status"', script)
        self.assertIn('runner_temp="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"', script)
        self.assertIn('results_dir="release-smoke-results"', script)
        self.assertIn("adb logcat -d", script)

    def test_emulator_runner_invokes_checked_in_release_smoke_script_once(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        release_job = workflow.split("  release-smoke:", 1)[1].split(
            "\n  dependency-review:", 1
        )[0]
        smoke = release_job.split(
            "      - name: Ephemerally sign, clean-install, and launch packaged APK", 1
        )[1]

        self.assertIn("- name: Check out repository", release_job)
        self.assertIn("strategy:", release_job)
        self.assertIn("architecture: x86", release_job)
        self.assertIn("architecture: x86_64", release_job)
        self.assertIn("--api-level ${{ matrix.api-level }}", release_job)
        self.assertIn("--architecture ${{ matrix.architecture }}", release_job)
        self.assertIn("-- env RUNTIME_ABI=${{ matrix.runtime-abi }} bash .github/scripts/smoke-packaged-release.sh", smoke)
        self.assertNotIn("script: |", smoke)
        self.assertIn("name: release-smoke-api-${{ matrix.api-level }}-${{ matrix.runtime-abi }}", release_job)
        self.assertIn("path: |\n            release-smoke-results/\n            release-emulator-results/", release_job)
        self.assertIn("if: always()", release_job)

        emulator_runner_count = workflow.count("python3 .github/scripts/run-android-emulator.py")
        self.assertEqual(2, emulator_runner_count)
        self.assertNotIn("ReactiveCircus/android-emulator-runner@", workflow)
        self.assertEqual(emulator_runner_count, workflow.count("          cmdline-tools-version: '14742923'"))
        self.assertEqual(emulator_runner_count, workflow.count("          packages: platform-tools"))

        script = SMOKE_SCRIPT.read_text(encoding="utf-8")
        self.assertTrue(script.startswith("#!/usr/bin/env bash\nset -euo pipefail\n"))
        self.assertIn("release_apks=(dist/*-release-unsigned.apk)", script)
        self.assertIn('adb install --no-incremental --abi "$runtime_abi" "$smoke_apk"', script)
        self.assertEqual(2, script.count("adb install --no-incremental "))
        self.assertIn("grep -Fq 'Status: ok'", script)


if __name__ == "__main__":
    unittest.main()
