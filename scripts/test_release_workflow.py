from pathlib import Path
import hashlib
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest


RELEASE_WORKFLOW = Path(__file__).parents[1] / ".github" / "workflows" / "release.yml"
VERIFY_CALL = "\n          verify_release_assets\n"
ASSETS = (
    "pomodorough-v1.2.3-release-unsigned.apk",
    "pomodorough-v1.2.3-release-unsigned.aab",
    "pomodorough-android.spdx.json",
    "SHA256SUMS.txt",
)
FAKE_GH = r'''
import os
from pathlib import Path
import shutil
import sys
import time

root = Path(os.environ["RUNNER_TEMP"])
args = sys.argv[1:]
with (root / "commands").open("a") as output:
    output.write(" ".join(args) + "\n")
if args[:2] == ["release", "view"]:
    if "isDraft" in args:
        if os.environ["RELEASE_STATE"] == "missing":
            sys.exit(1)
        print(os.environ["RELEASE_STATE"])
    elif "assets" in args:
        if os.environ["RELEASE_STATE"] != "missing":
            for asset in sorted((root / "remote").iterdir()):
                if asset.is_file():
                    print(asset.name)
elif args[:2] == ["release", "download"]:
    destination = Path(args[args.index("--dir") + 1])
    destination.mkdir(parents=True, exist_ok=True)
    for asset in (root / "remote").iterdir():
        if asset.is_file():
            shutil.copy(asset, destination / asset.name)
elif args[:2] == ["release", "delete-asset"]:
    tag = args[2]
    asset = args[3]
    assert tag == os.environ.get("GITHUB_REF_NAME", tag)
    assert args[4:] == ["--repo", "test/android", "--yes"]
    (root / "remote" / asset).unlink(missing_ok=True)
elif args[:2] == ["release", "upload"]:
    tag = args[2]
    assert tag == os.environ.get("GITHUB_REF_NAME", tag)
    repo_index = args.index("--repo")
    assert args[repo_index:] == ["--repo", "test/android"]
    for pattern in args[3:repo_index]:
        source = Path(pattern)
        if not source.is_absolute():
            source = Path.cwd() / pattern
        if any(char in pattern for char in "*?[]"):
            candidates = sorted(source.parent.glob(source.name))
        else:
            candidates = [source]
        for candidate in candidates:
            shutil.copy(candidate, root / "remote" / candidate.name)
elif args[:2] == ["attestation", "verify"]:
    asset = args[2]
    assert args[3:] == ["--repo", "test/android"]
    (root / (asset + ".started")).touch()
    deadline = time.monotonic() + 10
    while len(list(root.glob("*.started"))) < 4:
        if time.monotonic() > deadline:
            sys.exit("Attestation checks did not overlap")
        time.sleep(0.01)
    failed = asset in os.environ.get("FAIL_ASSET", "").split(",")
    if not failed:
        time.sleep(0.1)
    (root / (asset + ".finished")).touch()
    if failed:
        sys.exit(int(os.environ.get("FAIL_CODE", "1")))
elif args[:2] == ["release", "edit"]:
    assert len(list(root.glob("*.finished"))) == 4, "Published before checks finished"
    (root / "published").touch()
elif args[:2] == ["api", "--method"]:
    print("Release notes")
elif args[:2] == ["release", "create"]:
    pass
else:
    sys.exit("Unexpected gh command: " + repr(args))
'''
FAKE_GH_QUEUED = r'''
import os
from pathlib import Path
import sys
import time

root = Path(os.environ["RUNNER_TEMP"])
args = sys.argv[1:]
with (root / "commands").open("a") as output:
    output.write(" ".join(args) + "\n")
assert args[:2] == ["attestation", "verify"]
asset = args[2]
assert args[3:] == ["--repo", "test/android"]
(root / (asset + ".started")).touch()
deadline = time.monotonic() + 10
while len(list(root.glob("*.started"))) < 4:
    if time.monotonic() > deadline:
        sys.exit("Queued attestation checks did not overlap")
    time.sleep(0.01)
failed = asset in os.environ.get("FAIL_ASSET", "").split(",")
if not failed:
    time.sleep(0.2)
(root / (asset + ".finished")).touch()
if failed:
    sys.exit(int(os.environ.get("FAIL_CODE", "1")))
'''


class ReleaseExecutionTests(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.remote = self.root / "remote"
        self.remote.mkdir()
        self.dist = self.root / "dist"
        self.dist.mkdir()
        for asset in ASSETS[:-1]:
            (self.remote / asset).write_text(asset, encoding="utf-8")
        self.write_manifest()
        for asset in ASSETS:
            (self.dist / asset).write_bytes((self.remote / asset).read_bytes())
        gh = self.root / "gh"
        gh.write_text(f"#!{sys.executable}\n" + FAKE_GH, encoding="utf-8")
        gh.chmod(0o755)
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        publication = workflow.split("      - name: Publish GitHub release", 1)[1]
        self.script = textwrap.dedent(publication.split("        run: |\n", 1)[1])
        self.env = dict(os.environ, PATH=f"{self.root}:{os.environ['PATH']}",
                        RUNNER_TEMP=str(self.root), GITHUB_REF_NAME="v1.2.3",
                        GITHUB_REPOSITORY="test/android", RELEASE_STATE="draft")

    def write_manifest(self, assets: tuple[str, ...] = ASSETS[:-1]) -> None:
        manifest = "".join(
            f"{hashlib.sha256((self.remote / asset).read_bytes()).hexdigest()}  {asset}\n"
            for asset in assets
        )
        (self.remote / "SHA256SUMS.txt").write_text(manifest, encoding="utf-8")

    def write_dist_manifest(self, assets: tuple[str, ...] = ASSETS[:-1]) -> None:
        manifest = "".join(
            f"{hashlib.sha256((self.dist / asset).read_bytes()).hexdigest()}  {asset}\n"
            for asset in assets
        )
        (self.dist / "SHA256SUMS.txt").write_text(manifest, encoding="utf-8")

    def run_publication(self, **environment: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", "-c", self.script], cwd=self.root,
            env=self.env | environment, capture_output=True, text=True, timeout=20,
        )

    def test_all_checks_overlap_and_finish_before_publication(self) -> None:
        result = self.run_publication()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "published").exists())
        self.assertEqual(len(list(self.root.glob("*.finished"))), 4)
        commands = (self.root / "commands").read_text(encoding="utf-8").splitlines()
        self.assertCountEqual(
            [command for command in commands if command.startswith("attestation verify")],
            [f"attestation verify {asset} --repo test/android" for asset in ASSETS],
        )

    def test_multiple_failures_still_wait_for_successful_peers(self) -> None:
        result = self.run_publication(FAIL_ASSET=",".join(ASSETS[:2]), FAIL_CODE="255")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "published").exists())
        self.assertEqual(len(list(self.root.glob("*.finished"))), 4)

    def test_each_failed_check_blocks_publication_and_waits_for_peers(self) -> None:
        for asset in ASSETS:
            for code in ("1", "255"):
                with self.subTest(asset=asset, code=code):
                    for marker in self.root.glob("*.started"):
                        marker.unlink()
                    for marker in self.root.glob("*.finished"):
                        marker.unlink()
                    result = self.run_publication(FAIL_ASSET=asset, FAIL_CODE=code)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse((self.root / "published").exists())
                    self.assertEqual(len(list(self.root.glob("*.finished"))), 4)

    def test_255_normalization_waits_for_queued_input(self) -> None:
        # A36: 4 assets with -P 4 never queue, so a 255 abort of queued
        # input is unproven. Run 5 assets through -P 4 with one 255
        # failure; normalization (|| exit 1) must let the queued check run.
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("|| exit 1", workflow)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            gh = root / "gh"
            gh.write_text(f"#!{sys.executable}\n" + FAKE_GH_QUEUED, encoding="utf-8")
            gh.chmod(0o755)
            assets = ("queued-a.apk", "queued-b.aab", "queued-c.json",
                      "queued-d.txt", "queued-e.txt")
            script = (
                "printf '%s\\0' " + " ".join(assets) +
                " | xargs -0 -n 1 -P 4 bash -c "
                "'gh attestation verify \"$1\" --repo \"$GITHUB_REPOSITORY\" || exit 1' _"
            )
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}",
                       RUNNER_TEMP=str(root), GITHUB_REPOSITORY="test/android",
                       FAIL_ASSET=assets[0], FAIL_CODE="255")
            result = subprocess.run(
                ["bash", "-c", script], cwd=root,
                env=env, capture_output=True, text=True, timeout=20,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(len(list(root.glob("*.started"))), 5)
            self.assertEqual(len(list(root.glob("*.finished"))), 5)

    def test_draft_with_stale_assets_is_replaced_by_dist_payload(self) -> None:
        # A38: seed a fake draft with stale assets plus a real dist/
        # payload; assert per-asset delete plus upload args, and that the
        # stale inventory is gone after publication.
        for asset in list(self.remote.iterdir()):
            asset.unlink()
        for asset in list(self.dist.iterdir()):
            asset.unlink()
        stale = ("stale-old.apk", "legacy-notes.txt")
        for asset in stale:
            (self.remote / asset).write_text("stale", encoding="utf-8")
        for asset in ASSETS[:-1]:
            (self.dist / asset).write_text(asset, encoding="utf-8")
        self.write_dist_manifest()
        result = self.run_publication()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "published").exists())
        commands = (self.root / "commands").read_text(encoding="utf-8").splitlines()
        for asset in stale:
            self.assertIn(
                f"release delete-asset v1.2.3 {asset} --repo test/android --yes",
                commands,
            )
        upload = next(line for line in commands if line.startswith("release upload "))
        self.assertTrue(upload.startswith("release upload v1.2.3 "), upload)
        self.assertTrue(upload.endswith(" --repo test/android"), upload)
        for asset in ASSETS:
            self.assertIn(f"dist/{asset}", upload)
        self.assertCountEqual(
            sorted(asset.name for asset in self.remote.iterdir()),
            sorted(ASSETS),
        )
        for asset in ASSETS:
            self.assertEqual(
                (self.remote / asset).read_bytes(),
                (self.dist / asset).read_bytes(),
            )

    def test_already_published_release_only_verifies(self) -> None:
        result = self.run_publication(RELEASE_STATE="published")
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = (self.root / "commands").read_text(encoding="utf-8")
        for mutation in ("release create", "release upload", "release edit"):
            self.assertNotIn(mutation, commands)
        self.assertEqual(len(list(self.root.glob("*.finished"))), 4)

    def test_missing_release_is_created_as_draft_then_verified(self) -> None:
        result = self.run_publication(RELEASE_STATE="missing")
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = (self.root / "commands").read_text(encoding="utf-8")
        creation = next(line for line in commands.splitlines() if "release create" in line)
        self.assertIn("--draft", creation)
        self.assertTrue((self.root / "published").exists())

    def test_already_published_attestation_failure_is_not_success(self) -> None:
        result = self.run_publication(RELEASE_STATE="published", FAIL_ASSET=ASSETS[-1])
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(list(self.root.glob("*.finished"))), 4)

    def assert_rejected_before_attestation(self) -> None:
        result = self.run_publication()
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(list(self.root.glob("*.started")))
        self.assertFalse((self.root / "published").exists())

    def test_checksum_corruption_blocks_publication(self) -> None:
        (self.dist / ASSETS[0]).write_text("corrupt", encoding="utf-8")
        self.assert_rejected_before_attestation()

    def test_unexpected_inventory_blocks_publication(self) -> None:
        (self.dist / "unexpected.txt").touch()
        self.assert_rejected_before_attestation()

    def test_missing_inventory_blocks_publication(self) -> None:
        (self.dist / ASSETS[0]).unlink()
        self.assert_rejected_before_attestation()

    def test_incomplete_manifest_blocks_publication(self) -> None:
        self.write_dist_manifest(ASSETS[:1])
        self.assert_rejected_before_attestation()

    def test_duplicate_manifest_entry_blocks_publication(self) -> None:
        self.write_dist_manifest(ASSETS[:-1] + ASSETS[:1])
        self.assert_rejected_before_attestation()


def verifies_draft_before_publication(workflow: str) -> bool:
    publication = workflow.split("      - name: Publish GitHub release", 1)[1]
    upload_index = publication.rfind("          gh release upload")
    verify_index = publication.find(VERIFY_CALL, upload_index)
    publish_index = publication.find("\n          gh release edit ", upload_index)

    return 0 <= upload_index < verify_index < publish_index


class ReleaseWorkflowTests(unittest.TestCase):
    def test_release_validation_executes_publication_tests(self) -> None:
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        validation = workflow.split("  validate-release:", 1)[1].split("\n  ci:", 1)[0]
        self.assertIn("python3 -m unittest scripts/test_release_workflow.py -v", validation)
        self.assertIn("\n    needs: validate-release\n", workflow)
        self.assertIn("\n    needs: ci\n", workflow)

    def test_attestation_concurrency_is_bounded(self) -> None:
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn('printf \'%s\\0\' "${expected_release_assets[@]}"', workflow)
        self.assertIn("xargs -0 -n 1 -P 4 bash -c", workflow)
        # A36: normalize exit 255 so xargs does not stop queued checks.
        self.assertIn("|| exit 1", workflow)

    def test_release_is_verified_before_publication(self) -> None:
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertTrue(verifies_draft_before_publication(workflow))

    def test_already_published_verification_cannot_mask_missing_draft_verification(self) -> None:
        workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        prefix, separator, suffix = workflow.rpartition(VERIFY_CALL)
        self.assertEqual(separator, VERIFY_CALL)

        mutated = prefix + "\n" + suffix
        self.assertFalse(verifies_draft_before_publication(mutated))


if __name__ == "__main__":
    unittest.main()
