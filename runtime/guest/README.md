# Guest broker build

`dsha-guest.c` is DSHA's Linux-only forkpty/process broker. It runs inside v86; it does not link Android's su or Shizuku APIs.

Built with unmodified official Zig 0.15.2, `zig cc -target x86-linux-musl -static -O2 -Wall -Wextra`. Compiler download and SHA-256 are read from the official https://ziglang.org/download/index.json release index; the archive is stored only under the E-drive project .tmp cache. musl copyright and full notices are retained in app/src/main/assets/linux/musl-LICENSE.txt.

The binary is an x86 Linux ELF, not an Android executable. Its UART protocol never contains Android file APIs. Kernel/BIOS/Alpine are downloaded separately from pinned upstream HTTPS artifacts; see RuntimeArtifacts.kt and qa/v080/architecture.md.

Run `python scripts/build-guest.py` from the repository root on Windows. The pinned compiler archive SHA-256 is `3a0ed1e8799a2f8ce2a6e6290a9ff22e6906f8227865911fb7ddedc3cc14cb0c`; download, extraction and compiler caches remain under the project `.tmp` directory. The checked-in helper is rebuilt from this source; final Android tests boot a fresh VM with the rebuilt ELF.
