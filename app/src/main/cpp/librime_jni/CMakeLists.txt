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
target_link_libraries(rime_jni rime-static ${Opencc_LIBRARY})
target_include_directories(rime_jni PRIVATE
  "${CMAKE_BINARY_DIR}/deps/librime/src"
  "${CMAKE_SOURCE_DIR}/deps/librime/src"
  "${CMAKE_SOURCE_DIR}/deps/librime/include"
  "${Opencc_INCLUDE_PATH}"
)
