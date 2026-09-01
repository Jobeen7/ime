#!/bin/bash

# 严格模式：遇到错误立即停止
set -e

# --- 配置区域 ---
DEPS_DIR="app/src/main/cpp/deps"
BOOST_VERSION="1.89.0"
BOOST_DIR="$DEPS_DIR/boost" # 解压到根目录下的 boost 文件夹
BOOST_URL="https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
BOOST_HASH="67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74"

# Git 依赖定义
DEPS=(
    "$DEPS_DIR/OpenCC|https://github.com/BYVoid/OpenCC.git|master"
    "$DEPS_DIR/snappy|https://github.com/google/snappy.git|main"
    "$DEPS_DIR/librime|https://github.com/danjian/librime.git|main"
    "$DEPS_DIR/librime-lua|https://github.com/hchunhui/librime-lua.git|master"
    "$DEPS_DIR/librime-lua-deps|https://github.com/hchunhui/librime-lua.git|thirdparty"
    "$DEPS_DIR/librime-octagram|https://github.com/lotem/librime-octagram.git|master"
    "$DEPS_DIR/librime-predict|https://github.com/rime/librime-predict.git|master"
    "$DEPS_DIR/llama.cpp|https://github.com/ggml-org/llama.cpp.git|master"
)

echo ">>> 开始同步 Git 依赖..."
for item in "${DEPS[@]}"; do
    IFS="|" read -r path url branch <<< "$item"

    if [ ! -d "$path/.git" ]; then
        echo ">>> 克隆: $path"
        mkdir -p "$(dirname "$path")"
        git clone --depth 1 -b "$branch" "$url" "$path"
    else
        echo ">>> 更新: $path (分支: $branch)"
        cd "$path"
        git fetch origin "$branch"
        git reset --hard "origin/$branch"
        cd - > /dev/null
    fi
done

echo ">>> 开始同步 Boost 依赖..."
if [ ! -d "$BOOST_DIR" ]; then
    echo ">>> 下载 Boost ${BOOST_VERSION}..."
    # 使用 curl 下载
    curl -L "$BOOST_URL" -o "boost.tar.xz"

    # 校验哈希
    if command -v sha256sum &> /dev/null; then
      echo "$BOOST_HASH  boost.tar.xz" | sha256sum -c -
    elif command -v shasum &> /dev/null; then
      echo "$BOOST_HASH  boost.tar.xz" | shasum -a 256 -c -
    else
      echo "Warning: no sha256sum or shasum found, skipping hash check"
    fi

    echo ">>> 解压 Boost..."
    mkdir -p "$BOOST_DIR"
    tar -xf "boost.tar.xz" -C "$BOOST_DIR" --strip-components=1
    rm "boost.tar.xz"
else
    echo ">>> Boost 已存在，跳过下载。"
fi

# Git 依赖定义
RIME_DEPS=(
    "$DEPS_DIR/librime/deps/glog|https://github.com/google/glog.git|master"
    "$DEPS_DIR/librime/deps/yaml-cpp|https://github.com/jbeder/yaml-cpp.git|master"
    "$DEPS_DIR/librime/deps/leveldb|https://github.com/google/leveldb.git|main"
    "$DEPS_DIR/librime/deps/marisa-trie|https://github.com/s-yata/marisa-trie.git|master"
)

echo ">>> 开始同步librime Git 依赖..."

for item in "${RIME_DEPS[@]}"; do
    IFS="|" read -r path url branch <<< "$item"

    if [ ! -d "$path/.git" ]; then
        echo ">>> 克隆: $path"
        mkdir -p "$(dirname "$path")"
        git clone --depth 1 -b "$branch" "$url" "$path"
    else
        echo ">>> 更新: $path (分支: $branch)"
        cd "$path"
        git fetch origin "$branch"
        git reset --hard "origin/$branch"
        cd - > /dev/null
    fi
done

echo ">>> 所有依赖已同步完成。"