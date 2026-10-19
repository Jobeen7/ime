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

## 安卓与 JVM 依赖（经 Maven Central / Google Maven 引入）

AndroidX、Kotlin 标准库与协程、OkHttp/Okio、kotlinx.serialization、Apache Commons Compress 等，均为 Apache-2.0 或同等宽松许可证，完整清单以构建依赖为准。

## 数据与方案

| 组件 | 用途 | 说明 |
|---|---|---|
| 万象拼音方案与词库（amzxyz/rime-wanxiang） | 内置输入方案与词库、方案更新 | 见其仓库说明 |
| RIME-LMDG 语法模型（amzxyz） | 语法模型下载源 | 见其仓库说明 |

## 本项目

Jime 自身代码以根目录 LICENSE（BSD 3-Clause）发布。
