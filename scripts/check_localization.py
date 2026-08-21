#!/usr/bin/env python3
"""Fail CI when Android UI copy bypasses resources or catalogs drift."""
from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORMAT = re.compile(r"%(?:\d+\$)?[a-zA-Z]")
STRING = re.compile(r'"(?:\\.|[^"\\])*"')
TECHNICAL = {
    "", "idle", "DELETE", "android", "text/plain", "%02d", ":", ". ", " · ",
    "●", "○", "−", "+", "F", "SB", "LB", "UNCHECKED_CAST",
}


@dataclass(frozen=True)
class Entry:
    kind: str
    placeholders: tuple[str, ...]
    quantities: tuple[str, ...] = ()


def catalog(path: Path) -> dict[str, Entry]:
    root = ET.parse(path).getroot()
    result: dict[str, Entry] = {}
    for node in root:
        name = node.attrib.get("name")
        if not name or node.tag not in {"string", "plurals"}:
            continue
        if name in result:
            raise ValueError(f"{path}: duplicate resource {name}")
        if node.tag == "string":
            text = "".join(node.itertext())
            result[name] = Entry("string", tuple(FORMAT.findall(text)))
        else:
            quantities = tuple(item.attrib.get("quantity", "") for item in node.findall("item"))
            placeholders = tuple(
                placeholder
                for item in node.findall("item")
                for placeholder in FORMAT.findall("".join(item.itertext()))
            )
            result[name] = Entry("plurals", placeholders, quantities)
    return result


def validate_catalog_parity(base: dict[str, Entry], other: dict[str, Entry], label: str) -> list[str]:
    errors: list[str] = []
    missing = sorted(base.keys() - other.keys())
    extra = sorted(other.keys() - base.keys())
    if missing:
        errors.append(f"{label}: missing resources: {', '.join(missing)}")
    if extra:
        errors.append(f"{label}: uncatalogued extra resources: {', '.join(extra)}")
    for name in sorted(base.keys() & other.keys()):
        if base[name].kind != other[name].kind:
            errors.append(f"{label}:{name}: resource kind mismatch")
        if base[name].placeholders != other[name].placeholders:
            errors.append(f"{label}:{name}: placeholder mismatch")
        if base[name].quantities != other[name].quantities:
            errors.append(f"{label}:{name}: plural quantity mismatch")
    return errors


def _is_technical(value: str, prefix: str) -> bool:
    if value in TECHNICAL or not re.search(r"[A-Za-z]", value):
        return True
    if re.fullmatch(r"\$[A-Za-z_][A-Za-z0-9_]*|\$\{[^}]+\}", value):
        return True
    if "://" in value or value.startswith(("package:", "me.egigoka.", "PomodoroughAlarm")):
        return True
    if "DateTimeFormatter" in prefix[-100:]:
        return True
    if "label =" in prefix[-40:] and re.search(r"animate\w*|rememberInfiniteTransition", prefix[-120:]):
        return True
    return value.startswith("%")  # printf formatting, not copy


def validate_kotlin_literals(path: Path, source: str) -> list[str]:
    normalized = str(path).replace("\\", "/")
    if not any(part in normalized for part in ("/ui/", "ui/", "MainActivity.kt")):
        return []
    errors: list[str] = []
    for match in STRING.finditer(source):
        value = json.loads(match.group())
        prefix = source[max(0, match.start() - 120):match.start()]
        if _is_technical(value, prefix):
            continue
        if not re.search(
            r'(?:Text|setContentTitle|setContentText|setTicker|setSubText|NotificationChannel)\s*\(\s*'
            r'(?:text\s*=\s*)?$|(?:contentDescription|stateDescription)\s*=\s*$'
            r'|onClick\s*\([^)]*label\s*=\s*$',
            prefix,
            re.DOTALL,
        ):
            continue
        line = source.count("\n", 0, match.start()) + 1
        errors.append(f"{path}:{line}: uncatalogued user-visible literal {match.group()}")
    return errors


def main() -> int:
    errors: list[str] = []
    base_path = ROOT / "app/src/main/res/values/strings.xml"
    english_path = ROOT / "app/src/main/res/values-en/strings.xml"
    if not english_path.exists():
        errors.append("values-en/strings.xml is required for explicit English catalog parity")
    else:
        errors.extend(validate_catalog_parity(catalog(base_path), catalog(english_path), "values-en"))

    build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    locales = (ROOT / "app/src/main/res/xml/locales_config.xml").read_text(encoding="utf-8")
    if "isPseudoLocalesEnabled = true" not in build:
        errors.append("debug pseudo-locales must be enabled")
    if 'android:supportsRtl="true"' not in manifest:
        errors.append("manifest must enable RTL layout mirroring")
    if '<locale android:name="en"' not in locales:
        errors.append("English fallback must be declared in locales_config.xml")

    source_root = ROOT / "app/src/main/java"
    for path in source_root.rglob("*.kt"):
        errors.extend(validate_kotlin_literals(path, path.read_text(encoding="utf-8")))

    if errors:
        print("Android localization validation failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Android localization ok ({len(catalog(base_path))} catalog entries; pseudo-locale + RTL enabled)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
