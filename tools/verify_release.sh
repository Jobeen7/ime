#!/usr/bin/env bash
# Jime 发版验包（Q19 固化）：把逐版人工核对的验包清单收编为确定性检查。
# 每项对应一次真实事故或一条发版铁律：
#   1. 版本名/版本号   —— debug 包误当正式版、版本名低于已发布预览版
#   2. 签名指纹        —— 持久签名 key 唯一，覆盖安装靠它
#   3. resource.zip 哈希 —— 内置万象包被误换/漏换时整包功能回归
#   4. sherpa 三库字节  —— 自编关 TTS 库被官方 AAR 覆盖回退（GPL 标记回归）
#   5. dex 名单         —— native 按名查找的方法/类被 R8 改名即闪退
#                         （v1.0.986 教训）；已删符号残留说明删除未落盘
#
# 用法：tools/verify_release.sh <apk> <versionName> <versionCode>
#   versionName/versionCode 必须显式传入（即本次要发布的版本），不许缺省：
#   缺省时脚本会按当前 checkout 的 app/build.gradle.kts 取期望版本，
#    checkout 与待验包版本不一致时，旧包会因“与旧 checkout 一致”
#   而被放行——期望版本只能来自操作员当次的发布意图，不能来自
#   checkout 的现成值。人工使用只多敲两个已知参数，调用方无脚本
#   依赖（仓内无脚本调用本文件），故选显式传参而非保留缺省。
#   期望签名指纹与 resource.zip 哈希为下方常量：换签名 key 或换内置
#   万象包时，必须同步更新这两个常量（更新本身就是一次显式决策）。
# 退出码：0 = 全部通过；1 = 有检查失败（逐行 [FAIL] 标出）。
set -u

APK="${1:?usage: verify_release.sh <apk> <versionName> <versionCode>}"
EXPECTED_VERSION_NAME="${2:?usage: verify_release.sh <apk> <versionName> <versionCode>}"
EXPECTED_VERSION_CODE="${3:?usage: verify_release.sh <apk> <versionName> <versionCode>}"
EXPECTED_SIGNER_SHA256="511831cbb45c4248d15508c0ca1fe563799b31be2b45ac07b40ded6bf9a44744"
EXPECTED_RESOURCE_SHA256="23e801747ea8fb69fa8dee2d491dca2ab54479acdc50bac578fe3f8ddad4e58e"
# 自编 sherpa-onnx 库（关 TTS）在 APK lib/arm64-v8a 下的未压缩字节数
EXPECTED_SHERPA_JNI=3514248
EXPECTED_SHERPA_CAPI=3265488
EXPECTED_SHERPA_CXXAPI=440744

BUILDTOOLS="${BUILDTOOLS:-/home/hatch/android-build/android-sdk/build-tools/37.0.0}"
JAVA_HOME="${JAVA_HOME:-/home/hatch/android-build/jdk21}"
export PATH="$JAVA_HOME/bin:$PATH"
AAPT="$BUILDTOOLS/aapt"
APKSIGNER="$BUILDTOOLS/apksigner"

FAILURES=0
check() { # check <name> <0=pass> <detail>
    if [ "$2" -eq 0 ]; then
        echo "[PASS] $1 ($3)"
    else
        echo "[FAIL] $1 ($3)"
        FAILURES=$((FAILURES + 1))
    fi
}

[ -f "$APK" ] || { echo "[FAIL] apk exists ($APK not found)"; echo "VERDICT: FAILED"; exit 1; }
echo "== verify_release: $APK =="

# 1. 版本（aapt badging）
BADGING="$("$AAPT" dump badging "$APK" 2>/dev/null | head -1)"
GOT_NAME="$(echo "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
GOT_CODE="$(echo "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
[ "$GOT_NAME" = "$EXPECTED_VERSION_NAME" ]
check "versionName" $? "got=$GOT_NAME expected=$EXPECTED_VERSION_NAME"
[ "$GOT_CODE" = "$EXPECTED_VERSION_CODE" ]
check "versionCode" $? "got=$GOT_CODE expected=$EXPECTED_VERSION_CODE"

# 2. 签名指纹（apksigner 证书 SHA-256）
GOT_SIGNER="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null \
    | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr -d ' ')"
[ "$GOT_SIGNER" = "$EXPECTED_SIGNER_SHA256" ]
check "signer SHA-256" $? "got=$GOT_SIGNER"

# 3. resource.zip 哈希（APK 内 assets/resource.zip 与期望值比对）
TMPDIR_V="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_V"' EXIT
unzip -o -q "$APK" "assets/resource.zip" -d "$TMPDIR_V"
GOT_RESOURCE="$(sha256sum "$TMPDIR_V/assets/resource.zip" | cut -d' ' -f1)"
[ "$GOT_RESOURCE" = "$EXPECTED_RESOURCE_SHA256" ]
check "resource.zip SHA-256" $? "got=$GOT_RESOURCE"

# 4. sherpa 三库字节（APK 内 lib/arm64-v8a 未压缩大小）
lib_size() { unzip -l "$APK" "lib/arm64-v8a/$1" | awk -v n="$1" '$4 == "lib/arm64-v8a/" n {print $1}'; }
GOT_JNI="$(lib_size libsherpa-onnx-jni.so)"
GOT_CAPI="$(lib_size libsherpa-onnx-c-api.so)"
GOT_CXXAPI="$(lib_size libsherpa-onnx-cxx-api.so)"
[ "$GOT_JNI" = "$EXPECTED_SHERPA_JNI" ]
check "libsherpa-onnx-jni.so bytes" $? "got=$GOT_JNI expected=$EXPECTED_SHERPA_JNI"
[ "$GOT_CAPI" = "$EXPECTED_SHERPA_CAPI" ]
check "libsherpa-onnx-c-api.so bytes" $? "got=$GOT_CAPI expected=$EXPECTED_SHERPA_CAPI"
[ "$GOT_CXXAPI" = "$EXPECTED_SHERPA_CXXAPI" ]
check "libsherpa-onnx-cxx-api.so bytes" $? "got=$GOT_CXXAPI expected=$EXPECTED_SHERPA_CXXAPI"

# 5. dex 名单：native 按名查找的类/方法必须在（librime_jni/jni_env.h 的
#    FindClass/GetMethodID 清单 + proguard keep 的回调类）；已删符号必须不在。
#    proguard-rules.pro 里具名的 native 回调方法只有两个（Rime keep 段），
#    两者都在 PRESENT_REQUIRED 内：handleMessage 与 handleNativeNotification
#    （此前只列了后者，前者被 R8 改名/删除时脚本会漏报）。其余 keep 段
#    均为整类保留或构造器，类名本身已在名单内。
unzip -o -q "$APK" "classes*.dex" -d "$TMPDIR_V"
DEX_STRINGS="$(strings "$TMPDIR_V"/classes*.dex)"
PRESENT_REQUIRED="
Lcom/jobeen/ime/engine/rime/core/Rime;
Lcom/jobeen/ime/engine/rime/core/RimeConfig;
Lcom/jobeen/ime/engine/rime/core/RimeKeyEvent;
Lcom/jobeen/ime/engine/rime/core/CandidateProto;
Lcom/jobeen/ime/engine/rime/core/CommitProto;
Lcom/jobeen/ime/engine/rime/core/ContextProto;
Lcom/jobeen/ime/engine/rime/core/SyllableProto;
Lcom/jobeen/ime/engine/rime/core/CompositionProto;
Lcom/jobeen/ime/engine/rime/core/MenuProto;
Lcom/jobeen/ime/engine/rime/core/StatusProto;
Lcom/jobeen/ime/engine/rime/core/SchemaItem;
Lcom/jobeen/ime/engine/rime/data/opencc/OpenCCDictManager;
Lcom/jobeen/ime/engine/rime/data/userdict/UserDictManager;
Lcom/jobeen/ime/base/marisa/MarisaJNI;
handleMessage
handleNativeNotification
"
for sym in $PRESENT_REQUIRED; do
    echo "$DEX_STRINGS" | grep -qF "$sym"
    check "dex has $sym" $? "native-by-name lookup target"
done
ABSENT_REQUIRED="
volatileDropEq
isUnprotectedOverwrite
unprotectedOverwriteMessage
fetchRemoteState
tryReconcileLateFinal
mergedStateMatchesCurrent
amendLastSegment
"
for sym in $ABSENT_REQUIRED; do
    echo "$DEX_STRINGS" | grep -qF "$sym"
    [ $? -ne 0 ]
    check "dex lacks $sym" $? "deleted symbol must not ship"
done

# 6. resource 清单一致性（resource-manifest.json 已入库、
#    resource.zip 被 gitignore）：清单与 zip 是一对，zip 一换
#    清单必须同步重算（见 tools/gen_resource_manifest.py）。
#    本项只校验报警/失败：不一致即 [FAIL] 拦住本次验包；绝不
#    删除已入库清单、不改构建产物逻辑。任一文件缺失时无法
#    比对，只告警跳过（zip 被 gitignore，缺失属合法检出态），
#    不因此判失败；校验本身不写任何文件。
MANIFEST="app/src/main/assets/resource-manifest.json"
SRC_ZIP="app/src/main/assets/resource.zip"
if [ -f "$MANIFEST" ] && [ -f "$SRC_ZIP" ]; then
    MANIFEST_CHECK="$(python3 - "$MANIFEST" "$SRC_ZIP" <<'PY' 2>&1
import hashlib, json, sys, zipfile
manifest_path, zip_path = sys.argv[1], sys.argv[2]
try:
    manifest = json.load(open(manifest_path, encoding="utf-8"))
    zip_bytes = open(zip_path, "rb").read()
    zip_sha = hashlib.sha256(zip_bytes).hexdigest()
    if manifest.get("zipSha256") != zip_sha:
        print("zipSha256 mismatch")
        sys.exit(0)
    want = {(e["path"], e["size"], e["crc"]) for e in manifest.get("entries", [])}
    with zipfile.ZipFile(zip_path) as zf:
        got = {(i.filename, i.file_size, "%08x" % (i.CRC & 0xFFFFFFFF))
               for i in zf.infolist() if not i.is_dir()}
    # 清单生成时排除的提取器跳过项（见 gen_resource_manifest.py）
    # 不在 want 里：只报 want/got 的双向差集，跳过项不在差集内
    # 才算一致——此处直接比对会把跳过项算成 got 多余，故先减去
    # 生成器同款跳过规则的条目。
    skip_segs = {"__MACOSX", ".DS_Store", "PaxHeader"}
    def skipped(name):
        simple = name.rsplit("/", 1)[-1]
        return (not simple) or simple.startswith("._") or \
            any(seg in skip_segs for seg in name.split("/"))
    got = {e for e in got if not skipped(e[0])}
    if want != got:
        print("entries differ: manifest-only=%d zip-only=%d" % (
            len(want - got), len(got - want)))
    else:
        print("OK")
except Exception as exc:
    print("manifest check error: %s" % exc.__class__.__name__)
PY
)"
    if [ "$MANIFEST_CHECK" = "OK" ]; then
        check "resource-manifest matches resource.zip" 0 "$MANIFEST_CHECK"
    else
        check "resource-manifest matches resource.zip" 1 "$MANIFEST_CHECK"
    fi
else
    echo "[WARN] resource-manifest check skipped (manifest or resource.zip absent)"
fi

if [ "$FAILURES" -eq 0 ]; then
    echo "VERDICT: ALL PASS"
    exit 0
else
    echo "VERDICT: FAILED ($FAILURES check(s))"
    exit 1
fi
