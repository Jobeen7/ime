#!/usr/bin/env bash
# 可复现打包 app/src/main/assets/resource.zip（内置万象方案）。
#
# 流程：下载官方 lite zip → 校验大小与 sha256 → 解压（官方包为根目录
# 平铺）→ 整体放入 shared/ → 用本目录四个适配文件覆盖 shared/ 同名
# 文件 → 加入 model/predict.marisa（沿用自现有 resource.zip，官方
# lite 包不含此文件，取出后按 MARISA_SHA256 校验，不符即停）→ 以
# 固定排序、固定时间戳打包 → 输出成品路径与 sha256。
#
# 依赖：bash、curl、python3（大小与 sha256 均走 python3，不依赖 GNU
# coreutils，macOS 可直接运行）。
#
# 用法：bash tools/wanxiang-builtin/build-resource.sh
# 换官方版本时只改下面 VERSION / EXPECTED_SIZE / EXPECTED_SHA256
# 三处（新版资产的 size 与 digest 可从 GitHub release API 的资产
# 列表读取），并同步核对五处适配是否仍成立（见 README.md）。
set -euo pipefail

VERSION="v18.1.8"
ASSET="rime-wanxiang-lite.zip"
URL="https://github.com/amzxyz/rime-wanxiang/releases/download/${VERSION}/${ASSET}"
EXPECTED_SIZE=23403343
EXPECTED_SHA256="e94a110fd65456aad7b62b8e240b25a01718b10b7d52453a9b382493ae9e9323"
# model/predict.marisa 的登记 sha256（与 README.md「model/ 的来源」一致）
MARISA_SHA256="bcd55c3e7b149a5611cde4d027781710f50533c90e67ab9fc7824e96ff62701d"

sha256_of() {
    python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"
}
file_size() {
    python3 -c 'import os, sys; print(os.path.getsize(sys.argv[1]))' "$1"
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUT="${REPO_ROOT}/app/src/main/assets/resource.zip"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

echo "下载 ${URL}"
curl -fL --retry 3 -o "${WORK}/${ASSET}" "${URL}"

ACTUAL_SIZE="$(file_size "${WORK}/${ASSET}")"
if [ "${ACTUAL_SIZE}" != "${EXPECTED_SIZE}" ]; then
    echo "大小不符：期望 ${EXPECTED_SIZE}，实际 ${ACTUAL_SIZE}" >&2
    exit 1
fi
ACTUAL_SHA256="$(sha256_of "${WORK}/${ASSET}")"
if [ "${ACTUAL_SHA256}" != "${EXPECTED_SHA256}" ]; then
    echo "sha256 不符：期望 ${EXPECTED_SHA256}，实际 ${ACTUAL_SHA256}" >&2
    exit 1
fi
echo "官方包校验通过：${ACTUAL_SHA256}"

mkdir -p "${WORK}/tree/shared"
python3 - "${WORK}/${ASSET}" "${WORK}/tree/shared" <<'PY'
import sys, zipfile
src, dest = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as z:
    z.extractall(dest)
PY

# 五处适配的载体：本目录四文件覆盖 shared/ 同名文件
for f in default.yaml wanxiang_t9.schema.yaml wanxiang_lite.schema.yaml wanxiang_english.schema.yaml; do
    cp "${SCRIPT_DIR}/${f}" "${WORK}/tree/shared/${f}"
done

# model/predict.marisa 沿用自现有 resource.zip（官方 lite 包没有
# model/）；现有包缺失或不含该文件时直接失败，不静默产出残包
mkdir -p "${WORK}/tree/model"
python3 - "${OUT}" "${WORK}/tree/model/predict.marisa" <<'PY'
import sys, zipfile
src, dest = sys.argv[1], sys.argv[2]
try:
    with zipfile.ZipFile(src) as z:
        data = z.read("model/predict.marisa")
except Exception as e:
    sys.exit(f"无法从现有 resource.zip 取 model/predict.marisa：{e}")
with open(dest, "wb") as fh:
    fh.write(data)
PY

# 取出的 predict.marisa 必须与登记值一致，任何内容不符都不进新包
MARISA_ACTUAL_SHA256="$(sha256_of "${WORK}/tree/model/predict.marisa")"
if [ "${MARISA_ACTUAL_SHA256}" != "${MARISA_SHA256}" ]; then
    echo "predict.marisa sha256 不符：登记 ${MARISA_SHA256}，实际 ${MARISA_ACTUAL_SHA256}" >&2
    exit 1
fi
echo "predict.marisa 校验通过：${MARISA_ACTUAL_SHA256}"

# 固定排序 + 固定时间戳（1980-01-01，zip 纪元）+ 不写额外字段，
# 同输入必得同 sha256 的成品
python3 - "${WORK}/tree" "${WORK}/resource.zip" <<'PY'
import os, sys, zipfile
tree, out = sys.argv[1], sys.argv[2]
entries = []
for root, dirs, files in os.walk(tree):
    for name in dirs + files:
        full = os.path.join(root, name)
        arc = os.path.relpath(full, tree)
        entries.append((arc, full, os.path.isdir(full)))
entries.sort(key=lambda e: e[0])
FIXED = (1980, 1, 1, 0, 0, 0)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for arc, full, is_dir in entries:
        info = zipfile.ZipInfo(arc + "/" if is_dir else arc, FIXED)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o40755 if is_dir else 0o100644) << 16
        if is_dir:
            z.writestr(info, b"")
        else:
            with open(full, "rb") as fh:
                z.writestr(info, fh.read())
PY

cp "${WORK}/resource.zip" "${OUT}"
echo "成品：${OUT}"
echo "成品 sha256：$(sha256_of "${OUT}")（换包后同步登记到 README.md「当前成品登记」）"
