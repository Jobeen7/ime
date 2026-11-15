# SPDX-License-Identifier: Apache-2.0
#
# T9 会话访问器插件（WO52 1.5，自研 C++ 插件）。
#
# 追踪源码（与 deps/ 下 gitignored 的厂商源码不同）：.so 须能从
# 干净检出构建，无下载步骤。只依赖 librime 公共头（rime_api.h）
# 与 Jime 自身的 PinYinUtil 数据（t9_pinyin_data.inc 由其机械
# 生成）；不依赖任何插件源码（Xime librime-t9 为 GPL-3.0，仅学
# 了“纯函数映射表＋首音节选项”的组件划分思路，未抄代码）。

set(T9_PLUGIN_SOURCES
  t9_pinyin_map.cc
  t9_session.cc
  t9_module.cc
)

add_library(rime-t9 STATIC ${T9_PLUGIN_SOURCES})

target_include_directories(rime-t9 PRIVATE
  "${CMAKE_SOURCE_DIR}/deps/librime/src"
  "${CMAKE_SOURCE_DIR}/deps/librime/include"
)

# 与 rime_jni 桥目标同口径的加固（app 自有目标；vendored 源码不动）：
# 隐藏可见度（JNI 导出不在此目标内）＋可复现构建的路径映射。
target_compile_options(rime-t9 PRIVATE
  -fvisibility=hidden -ffunction-sections -fdata-sections
  "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")
