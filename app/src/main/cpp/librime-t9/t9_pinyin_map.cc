// SPDX-License-Identifier: Apache-2.0
//
// T9 数字→拼音映射实现（WO52 1.5）。数据见 t9_pinyin_data.inc
// （由 PinYinUtil.allPinyin 机械生成）；推导口径与
// PinYinUtil.possibleT9Combinations 逐项一致，见头文件注释。

#include "t9_pinyin_map.h"

namespace jime::t9 {

namespace {

// 音节表：PinYinUtil.allPinyin 的 432 条，同声明顺序。
#include "t9_pinyin_data.inc"

constexpr int kMaxCodes = 6;

}  // namespace

bool IsNoSpellingSingleLetter(const std::string& pinyin) {
    return pinyin.size() == 1 &&
           (pinyin[0] == 'i' || pinyin[0] == 'u' || pinyin[0] == 'v');
}

bool T9PinyinMap::KeyCodeFor(char c, char* out) {
    // PinYinUtil.t9KeyMap 同值。数字只认 2-9（0/1 不是九宫格字母键）。
    static constexpr char kTable[26] = {
            // a b c d e f g h i j k l m
            'A', 'A', 'A', 'D', 'D', 'D', 'G', 'G', 'G', 'J', 'J', 'J', 'M',
            // n o p q r s t u v w x y z
            'M', 'M', 'P', 'P', 'P', 'P', 'T', 'T', 'T', 'W', 'W', 'W', 'W'};
    if (c >= 'a' && c <= 'z') {
        *out = kTable[c - 'a'];
        return true;
    }
    if (c >= 'A' && c <= 'Z') {
        *out = kTable[c - 'A'];
        return true;
    }
    switch (c) {
        case '2': *out = 'A'; return true;
        case '3': *out = 'D'; return true;
        case '4': *out = 'G'; return true;
        case '5': *out = 'J'; return true;
        case '6': *out = 'M'; return true;
        case '7': *out = 'P'; return true;
        case '8': *out = 'T'; return true;
        case '9': *out = 'W'; return true;
        default: return false;
    }
}

const T9PinyinMap& T9PinyinMap::Instance() {
    static const T9PinyinMap instance;
    return instance;
}

T9PinyinMap::T9PinyinMap() {
    // 与 PinYinUtil.pinyinMap 同构：按音节声明顺序分组，组内顺序
    // 即 allPinyin 的声明顺序（groupBy 的遭遇顺序）。
    for (const char* pinyin : kPinyinList) {
        std::string code;
        code.reserve(6);
        bool ok = true;
        for (const char* p = pinyin; *p != '\0'; ++p) {
            char key = 0;
            if (!KeyCodeFor(*p, &key)) {
                ok = false;
                break;
            }
            code.push_back(key);
        }
        if (ok) {
            code_to_pinyins_[code].emplace_back(pinyin);
        }
    }
}

std::vector<std::string> T9PinyinMap::FirstSyllableOptions(
        const std::string& input) const {
    // possibleT9Combinations 同口径：逐字归一化为键位码（无映射的
    // 字符原样保留，查表时自然落空）、取前 6 码、按长度从长到短查
    // 前缀；单字母 i/u/v 不产出。
    std::string mapped;
    mapped.reserve(input.size());
    for (char c : input) {
        char key = 0;
        if (KeyCodeFor(c, &key)) {
            mapped.push_back(key);
        } else {
            mapped.push_back(c);
        }
    }
    if (mapped.empty()) {
        return {};
    }
    const std::string num_string = mapped.substr(0, kMaxCodes);
    std::vector<std::string> result;
    for (size_t len = num_string.size(); len >= 1; --len) {
        auto it = code_to_pinyins_.find(num_string.substr(0, len));
        if (it == code_to_pinyins_.end()) {
            continue;
        }
        for (const std::string& pinyin : it->second) {
            if (!IsNoSpellingSingleLetter(pinyin)) {
                result.push_back(pinyin);
            }
        }
        if (len == 1) {
            break;  // size_t 下溢防护：len-- 到 0 即停
        }
    }
    return result;
}

}  // namespace jime::t9
