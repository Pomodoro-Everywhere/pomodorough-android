#!/usr/bin/env python3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_localization import (
    catalog,
    validate_catalog_parity,
    validate_kotlin_literals,
)


class LocalizationValidatorTest(unittest.TestCase):
    def test_catalog_parity_rejects_missing_resources_and_placeholder_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = root / "base.xml"
            translated = root / "translated.xml"
            base.write_text(
                '<resources><string name="hello">Hello %1$s</string>'
                '<plurals name="runs"><item quantity="one">%1$d run</item>'
                '<item quantity="other">%1$d runs</item></plurals></resources>',
                encoding="utf-8",
            )
            translated.write_text(
                '<resources><string name="hello">Hello %1$d</string></resources>',
                encoding="utf-8",
            )
            errors = validate_catalog_parity(catalog(base), catalog(translated), "values-en")
            self.assertTrue(any("missing resources" in error for error in errors))
            self.assertTrue(any("placeholder mismatch" in error for error in errors))

    def test_user_visible_compose_literal_is_rejected_but_protocol_token_is_allowed(self):
        source = 'Text("Hardcoded")\nval status = "idle"\nerror("Internal invariant")\n'
        errors = validate_kotlin_literals(Path("ui/Screen.kt"), source)
        self.assertEqual(1, len(errors))
        self.assertIn("Hardcoded", errors[0])

    def test_semantics_and_notification_literals_are_rejected(self):
        source = '''
            Modifier.semantics { contentDescription = "Timer controls" }
            builder.setContentTitle("Timer finished")
        '''
        errors = validate_kotlin_literals(Path("MainActivity.kt"), source)
        self.assertEqual(2, len(errors))

    def test_catalog_parity_rejects_plural_quantity_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = root / "base.xml"
            translated = root / "translated.xml"
            base.write_text(
                '<resources><plurals name="runs"><item quantity="one">%1$d run</item>'
                '<item quantity="other">%1$d runs</item></plurals></resources>', encoding="utf-8",
            )
            translated.write_text(
                '<resources><plurals name="runs"><item quantity="other">%1$d runs</item>'
                '<item quantity="one">%1$d run</item></plurals></resources>', encoding="utf-8",
            )
            errors = validate_catalog_parity(catalog(base), catalog(translated), "values-en")
            self.assertTrue(any("plural quantity mismatch" in error for error in errors))

    def test_catalog_rejects_duplicate_resource_names(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "strings.xml"
            path.write_text(
                '<resources><string name="same">One</string><string name="same">Two</string></resources>',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "duplicate resource"):
                catalog(path)


if __name__ == "__main__":
    unittest.main()
