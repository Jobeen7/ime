// SPDX-License-Identifier: Apache-2.0
//
// T9 会话访问器（WO52 1.5，自研插件）：纯字符串函数，不依赖
// librime 会话对象与 JNI——JNI 层从会话读出 input/confirmedPos/
// preedit 后调这里，写回时由 JNI 层调 set_input。这样本层可脱离
// 引擎在主机上单测（见 wo52-g8-t9-host-test）。
//
// 口径与 Kotlin 侧 BehaviorHost.possiblePinYin / build() 一致：
// 输入串是会话原始输入（数字＋已钉住的拼音段，以 ' 分隔）；未解
// 析段＝confirmedPos 之后第一个仅由 2-9 组成的极大连续段；钉住
// 一段＝把该段替换为“拼音＋'”。

#pragma once

#include <cstddef>
#include <optional>
#include <string>
#include <utility>

namespace jime::t9 {

// 首个未解析数字段在 input 中的 [start, length)（字节下标；T9
// 会话输入全 ASCII，字节下标即字符下标）；无未解析段时返回 nullopt。
std::optional<std::pair<size_t, size_t>> FindUnresolvedDigitRun(
        const std::string& input, size_t confirmedPos);

// t9PinyinList：未解析段的首音节候选项，以单个空格连接；无段或
// 无候选时返回空串（与对端“按空格切分”同口径）。
std::string T9PinyinList(const std::string& input, size_t confirmedPos);

// t9SetPinyin：pinyin 须在当前未解析段的候选项内；命中时把该段
// 替换为 "pinyin'" 并写入 newInput，返回 true；否则不写回、返回
// false（调用方回落到既有 Kotlin 重建输入）。
bool T9SetPinyin(const std::string& input, size_t confirmedPos,
                 const std::string& pinyin, std::string* newInput);

}  // namespace jime::t9
