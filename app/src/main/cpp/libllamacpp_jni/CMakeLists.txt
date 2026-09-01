# SPDX-License-Identifier: Apache-2.0
#
# JNI bridge that links llama.cpp (built as static libs) into libllamacpp.so.

cmake_minimum_required(VERSION 3.18.0)

# --------------------------------------------------------------------------
# llama.cpp build configuration (static, no dynamic backends, cross-compile
# friendly for Android arm64-v8a). These cache entries are set *before*
# add_subdirectory() so the upstream option() calls don't override them.
# --------------------------------------------------------------------------
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "llama.cpp: build static libs" FORCE)

set(LLAMA_BUILD_APP        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     ON  CACHE BOOL "llama: build common utils library" FORCE)
set(LLAMA_OPENSSL          OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE            OFF CACHE BOOL "ggml: do not autodetect host arch" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "ggml: static backends" FORCE)
set(GGML_CPU_ALL_VARIANTS  OFF CACHE BOOL "ggml: single CPU variant" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

set(LLAMA_CPP_SOURCE_DIR "${CMAKE_SOURCE_DIR}/deps/llama.cpp")
add_subdirectory(${LLAMA_CPP_SOURCE_DIR} ${CMAKE_BINARY_DIR}/deps/llama.cpp)

# --------------------------------------------------------------------------
# libllamacpp JNI shared library
# --------------------------------------------------------------------------
add_library(llamacpp SHARED
        ai_chat.cpp)

target_include_directories(llamacpp PRIVATE
        ${LLAMA_CPP_SOURCE_DIR}
        ${LLAMA_CPP_SOURCE_DIR}/common
        ${LLAMA_CPP_SOURCE_DIR}/vendor
        ${LLAMA_CPP_SOURCE_DIR}/include
        ${LLAMA_CPP_SOURCE_DIR}/ggml/include)

target_link_libraries(llamacpp
        llama
        llama-common
        android
        log)