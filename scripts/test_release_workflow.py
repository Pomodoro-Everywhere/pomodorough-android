from pathlib import Path
import unittest


RELEASE_WORKFLOW = Path(__file__).parents[1] / ".github" / "workflows" / "release.yml"
VERIFY_CALL = "\n          verify_release_assets\n"


def verifies_draft_before_publication(workflow: str) -> bool:
    publication = workflow.split("      - name: Publish GitHub release", 1)[1]
    upload_index = publication.rfind("          gh release upload")
    verify_index = publication.find(VERIFY_CALL, upload_index)
    publish_index = publication.find("\n          gh release edit ", upload_index)

    return 0 <= upload_index < verify_index < publish_index


class ReleaseWorkflowTests(unittest.TestCase):
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
