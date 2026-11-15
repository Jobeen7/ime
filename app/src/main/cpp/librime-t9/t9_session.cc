// SPDX-License-Identifier: Apache-2.0
//
// T9 会话访问器实现（WO52 1.5）。算法见头文件；展开表见
// t9_pinyin_map（数据与口径均复用 Jime 自身的 PinYinUtil）。

#include "t9_session.h"

#include <algorithm>

#include "t9_pinyin_map.h"

namespace jime::t9 {

namespace {

// 九宫格字母键：2-9。0/1 不是字母键（1 是分词键，不进输入串），
// 不参与未解析段。
bool IsT9Digit(char c) { return c >= '2' && c <= '9'; }

// 分词分隔符（与 Kotlin 侧 UserSegmentSymbol 同值 '\''）。
constexpr char kSegmentSeparator = '\'';

}  // namespace

std::optional<std::pair<size_t, size_t>> FindUnresolvedDigitRun(
        const std::string& input, size_t confirmedPos) {
    const size_t start = std::min(confirmedPos, input.size());
    size_t i = start;
    while (i < input.size() && !IsT9Digit(input[i])) {
        ++i;
    }
    if (i >= input.size()) {
        return std::nullopt;
    }
    size_t j = i;
    while (j < input.size() && IsT9Digit(input[j])) {
        ++j;
    }
    return std::make_pair(i, j - i);
}

std::string T9PinyinList(const std::string& input, size_t confirmedPos) {
    const auto run = FindUnresolvedDigitRun(input, confirmedPos);
    if (!run.has_value()) {
        return std::string();
    }
    const std::vector<std::string> options =
            T9PinyinMap::Instance().FirstSyllableOptions(
                    input.substr(run->first, run->second));
    std::string out;
    for (size_t i = 0; i < options.size(); ++i) {
        if (i != 0) {
            out.push_back(' ');
        }
        out += options[i];
    }
    return out;
}

bool T9SetPinyin(const std::string& input, size_t confirmedPos,
                 const std::string& pinyin, std::string* newInput) {
    if (newInput == nullptr || pinyin.empty()) {
        return false;
    }
    const auto run = FindUnresolvedDigitRun(input, confirmedPos);
    if (!run.has_value()) {
        return false;
    }
    const std::vector<std::string> options =
            T9PinyinMap::Instance().FirstSyllableOptions(
                    input.substr(run->first, run->second));
    if (std::find(options.begin(), options.end(), pinyin) == options.end()) {
        return false;
    }
    // 与 BehaviorHost.build() 同形：钉住的拼音后追加分词分隔符。
    *newInput = input.substr(0, run->first) + pinyin + kSegmentSeparator +
                input.substr(run->first + run->second);
    return true;
}

}  // namespace jime::t9
