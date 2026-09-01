# SPDX-License-Identifier: Apache-2.0

find_package(Marisa REQUIRED)

add_library(marisa_jni SHARED marisa_jni.cc)
target_link_libraries(marisa_jni ${Marisa_LIBRARY})
target_include_directories(marisa_jni PRIVATE
    "${CMAKE_SOURCE_DIR}/deps/librime/deps/marisa-trie/include"
)
