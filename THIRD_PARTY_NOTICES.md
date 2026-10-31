# 第三方组件与许可证

Jime 基于开源组件构建。本文件是随应用分发的第三方组件索引：每个组件标注用途、版权归属与许可证；各许可证的全文见同仓库 `licenses/` 目录（见文末索引表）。

本文件以仓库根目录为**唯一维护源**：构建时由 Gradle 任务 `syncLicenses` 同步进 APK 的 `assets/licenses/`，应用内「关于 → 开源许可」读取的即是这一份。不要直接修改 `app/src/main/assets/` 或构建产物里的副本。

## 输入法引擎与原生库

| 组件 | 用途 | 版权 | 许可证 |
|---|---|---|---|
| librime（含 danjian 的修改版） | Rime 输入法引擎 | Copyright (c) 2014, RIME Developers | BSD 3-Clause |
| librime-lua | Lua 插件支持 | Copyright (c) 2021, librime-lua Developers | BSD 3-Clause |
| librime-octagram | 语法模型（语言模型）支持 | Copyright (c) 2014, RIME Developers | BSD 3-Clause |
| librime-predict | 预测插件 | librime-predict Developers | BSD 3-Clause |
| Lua 5.4 | 脚本运行时（随 librime-lua 引入） | Copyright (c) 1994-2024, Lua.org, PUC-Rio | MIT |
| OpenCC | 简繁转换 | OpenCC 作者 | Apache-2.0 |
| snappy | 压缩 | Copyright 2011, Google Inc. | BSD 3-Clause |
| glog | 日志 | Copyright (c) Google Inc. | BSD 3-Clause |
| yaml-cpp | YAML 解析 | Copyright (c) 2008-2015, Jesse Beder | MIT |
| leveldb | 词库存储 | Copyright (c) 2011, The LevelDB Authors | BSD 3-Clause |
| marisa-trie | 双数组字典 | Copyright (c) 2010-2025, Susumu Yata | BSD 2-Clause / LGPL-2.1-or-later 双许可（Jime 按 BSD 2-Clause 条款使用） |
| Boost | C++ 基础库 | 各头文件作者 | Boost Software License 1.0 |

## 语音识别

| 组件 | 用途 | 版权 | 许可证 |
|---|---|---|---|
| sherpa-onnx（k2-fsa） | 离线语音识别运行时与模型 | k2-fsa / sherpa-onnx 作者 | Apache-2.0 |
| ONNX Runtime（Microsoft，随 sherpa-onnx AAR 分发） | 推理运行时 | Copyright (c) Microsoft Corporation | MIT |

注：仓库内的 sherpa-onnx AAR 还包含 Qualcomm QNN 运行时库，APK 打包时已全部剔除，不随应用分发。

### sherpa-onnx 库内嵌的组件（已自编关闭 TTS）

仓库内 AAR 中的三个 sherpa-onnx 原生库（`libsherpa-onnx-jni.so` / `libsherpa-onnx-c-api.so` / `libsherpa-onnx-cxx-api.so`）已由 Jime 从 sherpa-onnx 官方 v1.13.5 源码自行构建，替换上游开启语音合成（TTS）的预编译版本：使用官方 `build-android-arm64-v8a.sh`，参数 `SHERPA_ONNX_ENABLE_TTS=OFF`、`SHERPA_ONNX_ENABLE_C_API=ON`、`SHERPA_ONNX_ENABLE_QNN=OFF`，NDK 28.2.13676358；`libonnxruntime.so`（1.27.1）与 AAR 其余内容（classes.jar 等）保持上游原样、未改动。Jime 只使用其中的离线语音识别（ASR）路径，**不调用 TTS**。

自编库仍静态内含下列组件：

| 组件 | 说明 | 版权 | 许可证 |
|---|---|---|---|
| OpenFst | 有限状态转换器库（经 kaldifst 引入） | Copyright (c) Google Inc. | Apache-2.0 |
| kaldifst | Kaldi 风格 FST 封装 | kaldifst 作者（k2-fsa） | Apache-2.0 |
| kaldi-decoder | 解码器 | kaldi-decoder 作者（k2-fsa） | Apache-2.0 |
| simple-sentencepiece | 分词 | simple-sentencepiece 作者（k2-fsa） | Apache-2.0 |
| kaldi-native-fbank | 特征提取（待核：随附 .so 内未检出其源码路径字符串，上游 sherpa-onnx 的构建依赖包含此库） | kaldi-native-fbank 作者（k2-fsa） | Apache-2.0（待核实） |

关于 espeak-ng：上游预编译版本曾静态内含 espeak-ng（经 piper-phonemize 引入，GPL-3.0-or-later）与 piper-phonemize。自编关闭 TTS 后，两者均不再链接进上述原生库——已以字符串全量扫描核验（espeak-ng / piper / phonemize 相关字符串与符号为 0；JNI 导出符号与上游版本相比仅少 9 个 TTS 专用符号，Jime 从不调用），故 espeak-ng 不再随应用分发，此前的 GPL-3.0 对应源码提供义务随之消解。`licenses/GPL-3.0.txt` 保留备查。

## 安卓与 JVM 依赖（经 Maven Central / Google Maven 引入）

| 组件 | 用途 | 版权 | 许可证 |
|---|---|---|---|
| AndroidX（core-ktx、lifecycle、activity-compose、Compose UI/Material3、Room 等） | 安卓基础与界面 | Copyright (C) The Android Open Source Project | Apache-2.0 |
| Material Components for Android | 界面组件 | Copyright (C) The Android Open Source Project | Apache-2.0 |
| Kotlin 标准库 | 语言运行时 | Copyright 2010-2024 JetBrains s.r.o. 与 Kotlin 贡献者 | Apache-2.0 |
| kotlinx.coroutines | 协程 | Copyright 2016-2024 JetBrains s.r.o. | Apache-2.0 |
| kotlinx.serialization | 序列化 | Copyright 2017-2024 JetBrains s.r.o. | Apache-2.0 |
| OkHttp | HTTP 客户端 | Copyright (C) 2013 Square, Inc. | Apache-2.0 |
| Okio（随 OkHttp 引入） | IO 缓冲 | Copyright (C) 2013 Square, Inc. | Apache-2.0 |
| Apache Commons Compress | 压缩包处理 | Copyright (C) The Apache Software Foundation | Apache-2.0 |
| Timber | 日志 | Copyright (C) 2013 Jake Wharton | Apache-2.0 |
| Splitties | 安卓 DSL 工具集 | Copyright (C) 2015 Louis CAD | Apache-2.0 |
| zxing-android-embedded（基于 ZXing） | 二维码扫描 | JourneyApps 与 ZXing 作者 | Apache-2.0 |

## 界面效果

| 组件 | 用途 | 版权 | 许可证 |
|---|---|---|---|
| AndroidLiquidGlass Backdrop（Kyant0） | FAB 液态玻璃背景 | Kyant0 / AndroidLiquidGlass 作者 | Apache-2.0 |

Backdrop 以源码移植方式引入（`app/src/main/java/com/kyant/backdrop/`，非 Maven 依赖），用于主页三钮（主钮＋部署／同步动作钮）的玻璃底（模糊＋折射）。移植改动：Android 单模块无 expect/actual 机制，actual 关键字去掉，实现即 Android 版原文，common 版 RuntimeShader 接口原样并入；Lens.kt 去掉 RoundedRectangularShape 分支（需 kyant shapes 依赖，未引入；本工程只用 androidx CornerBasedShape，行为不变）；@Language 注解去掉（IDE 提示用，无运行时作用）。旧设备降级为库内守卫：API 31 以下 RenderEffect、API 33 以下 RuntimeShader 自动不生效（仍绘制背景层）。许可证全文见 `licenses/Apache-2.0.txt`。

## 数据与方案

| 组件 | 用途 | 说明 |
|---|---|---|
| 万象拼音方案与词库（amzxyz/rime-wanxiang） | 内置输入方案与词库、方案更新 | CC BY 4.0（见下） |
| RIME-LMDG 语法模型（amzxyz） | 语法模型下载源 | 见其仓库说明 |
| predict.marisa 预测模型 | 内置候选预测模型数据（marisa-trie 格式） | 内置万象方案包随包分发物（resource.zip 内 `model/predict.marisa`），不在官方 lite zip 内，历次换方案包时原样保留；大小 37,366,176 字节与 sha256 登记见 tools/wanxiang-builtin/README.md，build-resource.sh 打包时校验一致后才进新包 |

万象拼音方案与词库由 amzxyz 维护（https://github.com/amzxyz/rime-wanxiang），以 Creative Commons Attribution 4.0 International（CC BY 4.0）许可发布，许可证全文见 https://creativecommons.org/licenses/by/4.0/ 。Jime 内置的方案文件包含本地修改（方案清单启用、布局字段注入、功能选项段与 T9 拼写规则调整，明细见 tools/wanxiang-builtin/README.md）。

## 字体

| 组件 | 用途 | 许可证 |
|---|---|---|
| Xiaolai Mono 子集（随应用分发的文件名为 `fonts/jime-mono-regular.ttf`） | 键盘等宽字体显示 | SIL OFL 1.1 |

Xiaolai Mono：Copyright 2020-2024 LXGW (https://github.com/lxgw/kose-font)，Copyright 2014 Nozomi Seto (https://ja.osdn.net/projects/setofont/)。以 SIL Open Font License 1.1 发布，许可证全文见 `licenses/OFL-1.1.txt`（官方文本主页 https://openfontlicense.org ）。随应用分发的为该字体的子集：仅含 5 个码位（6 字形，含 .notdef），文件已改名 `jime-mono-regular.ttf`，不以原保留字体名分发；字体内部 nameID 未改动。

## 许可证全文索引

| 文件 | 许可证 |
|---|---|
| `licenses/Apache-2.0.txt` | Apache License 2.0 |
| `licenses/GPL-3.0.txt` | GNU General Public License 3.0 |
| `licenses/BSD-3-Clause.txt` | BSD 3-Clause |
| `licenses/BSD-2-Clause.txt` | BSD 2-Clause |
| `licenses/MIT.txt` | MIT License |
| `licenses/BSL-1.0.txt` | Boost Software License 1.0 |
| `licenses/OFL-1.1.txt` | SIL Open Font License 1.1 |

CC BY 4.0 全文不在上述文件内，随万象方案一并分发，见 https://creativecommons.org/licenses/by/4.0/ 。

## 本项目

Jime 自身代码以根目录 LICENSE（BSD 3-Clause）发布。
