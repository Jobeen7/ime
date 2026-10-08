# SPDX-License-Identifier: Apache-2.0

find_package(Marisa REQUIRED)

add_library(marisa_jni SHARED marisa_jni.cc)

# Jime build hardening (app-owned bridge target only; vendored deps untouched):
# hidden visibility + per-function/data sections + link-time section GC.
# Safe here: marisa-trie has no static-initializer registry, everything kept
# is reachable from the JNIEXPORT entry points. Unlike rime_jni, whose link
# pulls in librime/plugins with string-keyed static registration and must
# not be section-GC'd.
target_compile_options(marisa_jni PRIVATE
  -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(marisa_jni PRIVATE "LINKER:--gc-sections")

target_link_libraries(marisa_jni ${Marisa_LIBRARY})
target_include_directories(marisa_jni PRIVATE
    "${CMAKE_SOURCE_DIR}/deps/librime/deps/marisa-trie/include"
)
