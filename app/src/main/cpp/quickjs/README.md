# Vendored QuickJS core (WO52 8.1)

- Upstream: bellard/quickjs (MIT, see LICENSE in this directory)
- Version: **2026-06-04** (latest release listed at
  https://bellard.org/quickjs/ at build time; the 2024-02-14 tarball
  URL now 404s and the index lists 2026-06-04 as current)
- Source tarball:
  https://bellard.org/quickjs/quickjs-2026-06-04.tar.xz
- sha256 of the tarball:
  `b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a`
  (verified with `xz -t` before unpacking)
- Also cross-checked against the GitHub master tarball
  (codeload.github.com/bellard/quickjs/tar.gz/refs/heads/master,
  VERSION file `2026-06-04`): same release.

Only the files the engine core needs are vendored (no qjs/qjsc
interpreter, no quickjs-libc std/os modules, no tests/examples/docs):

- quickjs.c / quickjs.h / quickjs-atom.h / quickjs-opcode.h
- dtoa.c / dtoa.h
- libregexp.c / libregexp.h / libregexp-opcode.h
- libunicode.c / libunicode.h / libunicode-table.h
- cutils.c / cutils.h
- list.h

The files are byte-identical to the release tarball (no patches).
The JNI bridge that links this core lives in ../quickjs_jni/.

Build: `../quickjs_jni/CMakeLists.txt` compiles the core as a static
lib with `-DCONFIG_VERSION="2026-06-04" -D_GNU_SOURCE` (the same
defines upstream's Makefile passes) and links it into
`libjime_quickjs.so` for arm64-v8a only (the app's only ABI).
