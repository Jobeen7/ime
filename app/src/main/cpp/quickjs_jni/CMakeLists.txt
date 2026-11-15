# SPDX-License-Identifier: Apache-2.0

# QuickJS native engine (WO52 8.1): libhamster_quickjs.so.
#
# Builds the vendored QuickJS core (../quickjs, MIT, 2026-06-04 release)
# as a static lib and links it into the JNI bridge shared lib. No
# quickjs-libc (no std/os modules: scripts only get the 6 host APIs),
# no qjs/qjsc tools, no module loader — scripts are eval'd from source
# strings (JS_EVAL_TYPE_GLOBAL), exactly the 元书 eval model.
#
# The vendored sources are TRACKED (unlike deps/, which is gitignored):
# the .so must build from a clean checkout with no download step.

set(QUICKJS_CORE_SOURCES
  ../quickjs/quickjs.c
  ../quickjs/dtoa.c
  ../quickjs/libregexp.c
  ../quickjs/libunicode.c
  ../quickjs/cutils.c
)

add_library(quickjs STATIC ${QUICKJS_CORE_SOURCES})

target_compile_definitions(quickjs PRIVATE
  _GNU_SOURCE
  CONFIG_VERSION="2026-06-04"
)

target_include_directories(quickjs PRIVATE ../quickjs)

# Upstream builds with -Wall and friends; the vendored core is not ours
# to silence warning by warning, and warnings must not fail the build.
target_compile_options(quickjs PRIVATE -O2)

add_library(hamster_quickjs SHARED quickjs_jni.c)

# Same hardening as rime_jni (app-owned bridge target only; the vendored
# core is untouched): hidden visibility keeps non-JNI symbols out of the
# dynamic symbol table — JNI entries are JNIEXPORT-annotated and stay
# exported.
target_compile_options(hamster_quickjs PRIVATE
  -fvisibility=hidden -ffunction-sections -fdata-sections)

target_link_libraries(hamster_quickjs quickjs)

target_include_directories(hamster_quickjs PRIVATE ../quickjs)
