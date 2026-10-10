#!/bin/bash

# 严格模式：遇到错误立即停止
set -e

# --- 配置区域 ---
DEPS_DIR="app/src/main/cpp/deps"
BOOST_VERSION="1.89.0"
BOOST_DIR="$DEPS_DIR/boost" # 解压到根目录下的 boost 文件夹
BOOST_URL="https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
BOOST_HASH="67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74"

# Git 依赖定义：路径|仓库|分支|锁定 commit
# 全部依赖锁定到具体 commit，不再跟随分支尖端：commit 哈希即内容寻址的
# 完整性校验（等同 Boost tarball 的 sha256）。锁定值来源：灾备清单
# MANIFEST（2026-10-08）核对 + 本地克隆 HEAD，二者一致；其中 glog /
# yaml-cpp / leveldb / marisa-trie 四项清单未收录，取锁定当日的本地
# 克隆 HEAD（即现行构建实际所用版本）。升级某依赖时改这里的 commit。
DEPS=(
    "$DEPS_DIR/OpenCC|https://github.com/BYVoid/OpenCC.git|master|2939943bd6f4d459b46d7fdcf07a885ab3f01761"
    "$DEPS_DIR/snappy|https://github.com/google/snappy.git|main|9c28114a38866f6deeaa826db918293bc28ae410"
    "$DEPS_DIR/librime|https://github.com/danjian/librime.git|main|95d3e11b335d484baa2e80346dea517565b463bb"
    "$DEPS_DIR/librime-lua|https://github.com/hchunhui/librime-lua.git|master|ad1e4a6c98abf634dd34242a747f9b1d5d069fbe"
    "$DEPS_DIR/librime-lua-deps|https://github.com/hchunhui/librime-lua.git|thirdparty|9e5bb71db1544913f8005dadc3df8439c00d08b7"
    "$DEPS_DIR/librime-octagram|https://github.com/lotem/librime-octagram.git|master|57d18b9f58e5284bd891d559f6bdd16cf60341e9"
    "$DEPS_DIR/librime-predict|https://github.com/rime/librime-predict.git|master|920bd41ebf6f9bf6855d14fbe80212e54e749791"
)

# Git 依赖定义（librime 的 deps 子目录）
RIME_DEPS=(
    "$DEPS_DIR/librime/deps/glog|https://github.com/google/glog.git|master|53d58e4531c7c90f71ddab503d915e027432447a"
    "$DEPS_DIR/librime/deps/yaml-cpp|https://github.com/jbeder/yaml-cpp.git|master|1e0876c671268661deb2628040e3959e1e9d6e69"
    "$DEPS_DIR/librime/deps/leveldb|https://github.com/google/leveldb.git|main|7ee830d02b623e8ffe0b95d59a74db1e58da04c5"
    "$DEPS_DIR/librime/deps/marisa-trie|https://github.com/s-yata/marisa-trie.git|master|38180f6d3653cd2c88dd7047711e75f48f84527c"
)

# 同步单个 Git 依赖到锁定 commit：已在锁定版本则跳过（不触碰工作树，
# 构建期自动应用的 vendored 补丁得以保留）；否则拉取并硬重置到锁定点
#（与旧版「reset --hard 到分支尖端」同样的同步语义，只是目标改为锁定值，
# 构建时 CMake 会重新应用补丁）。
sync_dep() {
    local path="$1" url="$2" branch="$3" commit="$4"

    if [ ! -d "$path/.git" ]; then
        echo ">>> 克隆: $path"
        mkdir -p "$(dirname "$path")"
        git clone --depth 1 -b "$branch" "$url" "$path"
    fi

    local head
    head="$(git -C "$path" rev-parse HEAD)"
    if [ "$head" = "$commit" ]; then
        echo ">>> 已锁定，跳过: $path ($commit)"
        return
    fi

    echo ">>> 锁定: $path -> $commit（当前 $head）"
    if ! git -C "$path" cat-file -e "${commit}^{commit}" 2>/dev/null; then
        # 先按 commit 浅拉取（GitHub 支持），失败再退回整分支拉取
        git -C "$path" fetch --depth 1 origin "$commit" \
            || git -C "$path" fetch origin "$branch"
    fi
    git -C "$path" reset --hard "$commit" > /dev/null

    head="$(git -C "$path" rev-parse HEAD)"
    if [ "$head" != "$commit" ]; then
        echo "错误: $path 未能锁定到 $commit（实际 $head）" >&2
        exit 1
    fi
}

echo ">>> 开始同步 Git 依赖..."
for item in "${DEPS[@]}"; do
    IFS="|" read -r path url branch commit <<< "$item"
    sync_dep "$path" "$url" "$branch" "$commit"
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
      echo "Error: sha256sum or shasum is required to verify Boost" >&2
      exit 1
    fi

    echo ">>> 解压 Boost..."
    mkdir -p "$BOOST_DIR"
    tar -xf "boost.tar.xz" -C "$BOOST_DIR" --strip-components=1
    rm "boost.tar.xz"
else
    echo ">>> Boost 已存在，跳过下载。"
fi

echo ">>> 开始同步librime Git 依赖..."
for item in "${RIME_DEPS[@]}"; do
    IFS="|" read -r path url branch commit <<< "$item"
    sync_dep "$path" "$url" "$branch" "$commit"
done

echo ">>> 所有依赖已同步完成。"
