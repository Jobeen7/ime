# SPDX-License-Identifier: Apache-2.0

find_package(Opencc REQUIRED)

set(RIME_JNI_SOURCES
  rime_engine.cc
  rime_key.cc
  rime_levers.cc
  rime_config.cc
  rime_opencc.cc
)

add_library(rime_jni SHARED ${RIME_JNI_SOURCES})

# Jime build hardening (app-owned bridge target only; vendored deps untouched):
# hidden visibility keeps non-JNI symbols out of the dynamic symbol table —
# JNI entries are JNIEXPORT-annotated and stay exported. Function/data
# sections are enabled so a future --gc-sections has something to collect.
# Deliberately NO --gc-sections on this target's link: the option applies
# link-wide, and the statically linked librime + plugins (lua/octagram/
# predict) register components via string-keyed static initializers that a
# section GC could mistake for dead code, breaking schema loading at
# runtime in ways a build cannot catch.
target_compile_options(rime_jni PRIVATE
  -fvisibility=hidden -ffunction-sections -fdata-sections)

target_link_libraries(rime_jni rime-static ${Opencc_LIBRARY})
target_include_directories(rime_jni PRIVATE
  "${CMAKE_BINARY_DIR}/deps/librime/src"
  "${CMAKE_SOURCE_DIR}/deps/librime/src"
  "${CMAKE_SOURCE_DIR}/deps/librime/include"
  "${Opencc_INCLUDE_PATH}"
)
