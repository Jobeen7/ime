#!/usr/bin/env python3
"""Generate app/src/main/assets/resource-manifest.json from resource.zip.

The manifest lets startup replace the full re-unzip with a checksum
differential (see ResourceDifferential.kt): unchanged builtin files are
skipped, changed/missing files are written, surplus files under shared/
and model/ are pruned.

Pairing rule: the manifest and the zip are ONE pair — regenerate this
file EVERY time resource.zip changes (upstream swap, adaptation edit),
and commit both together. The runtime does not enforce pairing by hash
(ZipInputStream buffering makes a streaming hash unreproducible); safety
under a stale manifest comes from the differential itself (off-manifest
stream entries are still extracted, and pruning spares anything seen in
the stream). A stale manifest therefore degrades to leftover removed
files, never to missing new files.

Manifest shape:
  {
    "version": 1,
    "zipSha256": "<sha256 of the zip file, for humans/tools only>",
    "preserve": ["<path prefixes never overwritten when present>"],
    "entries": [{"path": ..., "size": <uncompressed bytes>,
                 "crc": "<crc32, 8 lowercase hex>"}, ...]
  }
Entries come from the zip central directory (no decompression): file
entries only, directory entries skipped, extractor skip-pattern entries
(__MACOSX, .DS_Store, PaxHeader, ._* files) excluded to match the
runtime extractor.

Preserve prefixes: opencc user dictionaries (Custom_*). The opencc
configs reference them as plain-text dicts and users may edit them;
the differential fills them when missing but never overwrites or
prunes them.

Usage (from the repo root):
  python3 tools/gen_resource_manifest.py [resource.zip] [resource-manifest.json]
Defaults are app/src/main/assets/resource.zip and
app/src/main/assets/resource-manifest.json.
"""

import hashlib
import json
import sys
import zipfile
from pathlib import Path

SKIP_SEGMENTS = {"__MACOSX", ".DS_Store", "PaxHeader"}

# Opencc user dictionaries: fill-if-missing, never overwrite, never prune.
PRESERVE_PREFIXES = ["shared/opencc/wanxiang/Custom_"]


def is_skipped(name: str) -> bool:
    simple = name.rsplit("/", 1)[-1]
    if not simple or simple.startswith("._"):
        return True
    return any(seg in SKIP_SEGMENTS for seg in name.split("/"))


def main(argv: list[str]) -> int:
    repo_root = Path(__file__).resolve().parent.parent
    zip_path = Path(argv[1]) if len(argv) > 1 else \
        repo_root / "app/src/main/assets/resource.zip"
    out_path = Path(argv[2]) if len(argv) > 2 else \
        repo_root / "app/src/main/assets/resource-manifest.json"

    zip_bytes = zip_path.read_bytes()
    zip_sha256 = hashlib.sha256(zip_bytes).hexdigest()
    entries = []
    with zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            if info.is_dir() or is_skipped(info.filename):
                continue
            entries.append({
                "path": info.filename,
                "size": info.file_size,
                "crc": "%08x" % (info.CRC & 0xFFFFFFFF),
            })
    entries.sort(key=lambda e: e["path"])
    manifest = {
        "version": 1,
        "zipSha256": zip_sha256,
        "preserve": PRESERVE_PREFIXES,
        "entries": entries,
    }
    out_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
                         encoding="utf-8")
    print(f"wrote {out_path} ({len(entries)} entries, zip sha256 {zip_sha256[:12]}...)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
