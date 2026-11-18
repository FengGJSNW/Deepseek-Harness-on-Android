"""Bundle the host and corresponding embedded sources without local build state."""

import hashlib
import json
import pathlib
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))["version"]
DESTINATION = ROOT / "dist" / f"DSHA-v{VERSION}-flclash-source.zip"


def source_files(checkout):
    names = subprocess.check_output(
        ["git", "-C", str(checkout), "ls-files", "-c", "-o", "--exclude-standard", "-z"]
    ).decode("utf-8").split("\0")
    for name in sorted(set(filter(None, names))):
        path = checkout / name
        if path.is_file():
            yield path, name


DESTINATION.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(DESTINATION, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for path, name in source_files(ROOT):
        parts = pathlib.PurePosixPath(name).parts
        if parts[0] in {"qa", "private", "resourse", "dist", ".android", ".tmp"}:
            continue
        if path.suffix.lower() in {".keystore", ".jks", ".key", ".p12", ".apk", ".zip"}:
            continue
        if path.name in {"local.properties", "signing.properties", "secrets.json"}:
            continue
        archive.write(path, "DeepSeekMobile/" + name)

    # Keep source snapshots outside the Git checkout paths. The setup scripts
    # recreate those checkouts at the pinned revisions and apply flclash.patch.
    for directory, prefix in [
        ("integrations/flclash", "FlClash"),
        ("integrations/flclash/core/Clash.Meta", "FlClash/core/Clash.Meta"),
        ("integrations/flclash/plugins/prism_ui", "FlClash/plugins/prism_ui"),
        ("upstream", "DeepSeekHarness"),
    ]:
        for path, name in source_files(ROOT / directory):
            archive.write(path, "DeepSeekMobile/third-party-source/" + prefix + "/" + name)
    archive.writestr(
        "DeepSeekMobile/third-party-source/README.txt",
        "These are the complete source snapshots used by this build, including "
        "FlClash's DSHA adaptations and original license files.\n"
        "Build from the host root using integrations/README.md. The setup scripts "
        "recreate Git checkouts at the lock-file revisions and apply flclash.patch; "
        "these review snapshots have no Git metadata.\n"
        "Signing keys, account data, machine paths, caches and device logs are excluded.\n",
    )
print(f"{DESTINATION}: {DESTINATION.stat().st_size} bytes")
print("SHA256: " + hashlib.file_digest(DESTINATION.open("rb"), "sha256").hexdigest())
