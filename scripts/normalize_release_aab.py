#!/usr/bin/env python3
"""Remove R8's elapsed build time from an unsigned release AAB.

R8 writes a nondeterministic buildTimeNs value into bundle metadata even when the
compiled dex payload is reproducible. Release AABs are unsigned at this stage, so
normalizing that diagnostic-only field before comparison and publication keeps
the final bundle byte-reproducible without touching executable content.
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

METADATA_PATH = "BUNDLE-METADATA/com.android.tools/r8.json"


def normalize(path: Path) -> None:
    if not path.is_file():
        raise SystemExit(f"AAB does not exist: {path}")

    with ZipFile(path, "r") as source:
        entries = source.infolist()
        if sum(entry.filename == METADATA_PATH for entry in entries) != 1:
            raise SystemExit(f"expected exactly one {METADATA_PATH} entry")
        metadata = json.loads(source.read(METADATA_PATH))
        compilation = metadata.get("compilation")
        if not isinstance(compilation, dict) or not isinstance(compilation.get("buildTimeNs"), int):
            raise SystemExit("R8 metadata does not contain an integer compilation.buildTimeNs")
        compilation["buildTimeNs"] = 0
        normalized_metadata = json.dumps(metadata, separators=(",", ":")).encode("utf-8")
        archive_comment = source.comment

        fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
        os.close(fd)
        temporary = Path(temporary_name)
        try:
            with ZipFile(temporary, "w", compression=ZIP_DEFLATED, allowZip64=True) as target:
                target.comment = archive_comment
                for entry in entries:
                    payload = normalized_metadata if entry.filename == METADATA_PATH else source.read(entry.filename)
                    target.writestr(entry, payload)
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(f"usage: {Path(sys.argv[0]).name} AAB")
    normalize(Path(sys.argv[1]))
