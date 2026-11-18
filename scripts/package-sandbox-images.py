"""Generate the shared base image and the optional signed tool-package ZIP."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
source = root / ".tmp/sandbox-bundle/runtime-bundle"
built = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
version = json.loads((root / "package.json").read_text(encoding="utf-8"))["version"]
args.output.mkdir(parents=True, exist_ok=True)
base = ("kernel.bin", "seabios.bin", "vgabios.bin", "alpine-3.24.3.tar.gz")
tools = sorted(name for name in built["files"] if name.startswith("tools/"))
assert tools and "tools/main/x86/APKINDEX.tar.gz" in tools
packages = {}

for kind, title, image, entries in (
    ("alpine", "DSHA-Linux-Lite", base[-1], {name: name for name in base}),
    (
        "tools",
        "DSHA-Tool",
        "tools-main-APKINDEX.tar.gz",
        {name.replace("tools/", "tools-", 1).replace("/x86/", "-"): name for name in tools},
    ),
):
    files = {}
    for archive_name, name in entries.items():
        path = source / name
        spec = built["files"][name]
        with path.open("rb") as data:
            actual = hashlib.file_digest(data, "sha256").hexdigest()
        assert path.stat().st_size == spec["bytes"] and actual == spec["sha256"], name
        files[archive_name] = {key: spec[key] for key in ("bytes", "sha256")}
    manifest = {
        "format": "dsha-sandbox-image",
        "schema": 1,
        "kind": kind,
        "release": "3.24.3",
        "image": image,
        "files": files,
    }
    packages[kind] = manifest
    destination = args.output / f"{title}-{version}.zip"
    temporary = destination.with_suffix(".zip.part")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
        for archive_name, name in entries.items():
            compressed = name.endswith((".tar.gz", ".apk"))
            archive.write(source / name, archive_name,
                          compress_type=zipfile.ZIP_STORED if compressed else zipfile.ZIP_DEFLATED)
    os.replace(temporary, destination)
    print(f"{destination.name}: {destination.stat().st_size / 1048576:.2f} MiB")

catalog = root / "app/src/main/assets/runtime-images.json"
before = catalog.read_bytes()
content = (json.dumps({"schema": 1, "packages": packages}, indent=2) + "\n").encode()
assert catalog.read_bytes() == before
staged = catalog.with_suffix(".json.part")
staged.write_bytes(content)
os.replace(staged, catalog)
