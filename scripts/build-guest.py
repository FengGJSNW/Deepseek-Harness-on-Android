"""Build the Linux-only process/PTY helper with a pinned, verified official compiler."""

import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.15.2"
NAME = f"zig-x86_64-windows-{VERSION}"
URL = f"https://ziglang.org/download/{VERSION}/{NAME}.zip"
SHA256 = "3a0ed1e8799a2f8ce2a6e6290a9ff22e6906f8227865911fb7ddedc3cc14cb0c"
CACHE = ROOT / ".tmp" / "v080-research"


def main():
    if os.name != "nt":
        raise SystemExit("This pinned bootstrap is for the project's Windows build host.")
    CACHE.mkdir(parents=True, exist_ok=True)
    archive = CACHE / "zig.zip"
    if not archive.exists():
        with urllib.request.urlopen(URL, timeout=60) as response:
            with archive.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
    with archive.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != SHA256:
        raise SystemExit("Official Zig archive checksum mismatch; refusing to execute it.")
    directory = CACHE / "zig"
    compiler = directory / NAME / "zig.exe"
    if not compiler.is_file():
        with zipfile.ZipFile(archive) as source:
            for item in source.infolist():
                target = (directory / item.filename).resolve()
                if not target.is_relative_to(directory.resolve()):
                    raise SystemExit("Unsafe compiler archive path")
            source.extractall(directory)
    environment = os.environ.copy()
    environment["ZIG_GLOBAL_CACHE_DIR"] = str(CACHE / "zig-cache")
    environment["ZIG_LOCAL_CACHE_DIR"] = str(CACHE / "zig-local-cache")
    destination = ROOT / "app/src/main/assets/linux/dsha-guest"
    candidate = CACHE / "dsha-guest"
    subprocess.run(
        [
            str(compiler),
            "cc",
            "-target",
            "x86-linux-musl",
            "-static",
            "-O2",
            "-g0",
            "-s",
            "-Wall",
            "-Wextra",
            f"-ffile-prefix-map={ROOT}=.",
            "runtime/guest/dsha-guest.c",
            "-o",
            str(candidate),
        ],
        env=environment, cwd=ROOT, check=True,
    )
    binary = candidate.read_bytes()
    if binary[:4] != b"\x7fELF":
        raise SystemExit("Compiler did not produce a Linux ELF")
    if any(path.encode().lower() in binary.lower() for path in {str(ROOT), ROOT.as_posix()}):
        raise SystemExit("Guest binary contains a machine-local build path")
    candidate.replace(destination)
    print("Built guest-only x86 Linux broker:", hashlib.sha256(binary).hexdigest())


if __name__ == "__main__":
    main()
