# 第三方组件与许可证

Jime 基于开源组件构建。本文件列出随应用分发的主要组件及其许可证；各组件的完整许可证文本见其各自仓库/源码目录。

## 输入法引擎与原生库

| 组件 | 用途 | 许可证 |
|---|---|---|
| librime（含 danjian 的修改版） | Rime 输入法引擎 | BSD 3-Clause |
| librime-lua | Lua 插件支持 | BSD 3-Clause |
| librime-octagram | 语法模型（语言模型）支持 | BSD 3-Clause |
| librime-predict | 预测插件 | BSD 3-Clause |
| Lua | 脚本运行时（随 librime-lua 引入） | MIT |
| OpenCC | 简繁转换 | Apache-2.0 |
| snappy | 压缩 | BSD 3-Clause |
| glog | 日志 | BSD 3-Clause |
| yaml-cpp | YAML 解析 | MIT |
| leveldb | 词库存储 | BSD 3-Clause |
| marisa-trie | 双数组字典 | BSD 2-Clause / LGPL-2.1 双许可 |
| Boost | C++ 基础库 | Boost Software License 1.0 |

## 语音识别

| 组件 | 用途 | 许可证 |
|---|---|---|
| sherpa-onnx（k2-fsa） | 离线语音识别运行时与模型 | Apache-2.0 |
| ONNX Runtime（Microsoft，随 sherpa-onnx AAR 分发） | 推理运行时 | MIT |

注：仓库内的 sherpa-onnx AAR 还包含 Qualcomm QNN 运行时库，APK 打包时已全部剔除，不随应用分发。

## 安卓与 JVM 依赖（经 Maven Central / Google Maven 引入）

AndroidX、Kotlin 标准库与协程、OkHttp/Okio、kotlinx.serialization、Apache Commons Compress 等，均为 Apache-2.0 或同等宽松许可证，完整清单以构建依赖为准。

## 数据与方案

| 组件 | 用途 | 说明 |
|---|---|---|
| 万象拼音方案与词库（amzxyz/rime-wanxiang） | 内置输入方案与词库、方案更新 | CC BY 4.0（见下） |
| RIME-LMDG 语法模型（amzxyz） | 语法模型下载源 | 见其仓库说明 |

万象拼音方案与词库由 amzxyz 维护（https://github.com/amzxyz/rime-wanxiang），以 Creative Commons Attribution 4.0 International（CC BY 4.0）许可发布，许可证全文见 https://creativecommons.org/licenses/by/4.0/ 。Jime 内置的方案文件包含本地修改（方案清单启用、布局字段注入、功能选项段与 T9 拼写规则调整，明细见 tools/wanxiang-builtin/README.md）。

## 字体

| 组件 | 用途 | 许可证 |
|---|---|---|
| Xiaolai Mono（小赖字体等宽版） | 键盘等宽字体显示 | SIL OFL 1.1 |

Xiaolai Mono：Copyright 2020-2024 LXGW (https://github.com/lxgw/kose-font)，Copyright 2014 Nozomi Seto (https://ja.osdn.net/projects/setofont/)。以 SIL Open Font License 1.1 发布，许可证全文见 https://openfontlicense.org 。

## 本项目

Jime 自身代码以根目录 LICENSE（BSD 3-Clause）发布。
