// SPDX-License-Identifier: Apache-2.0
//
// 紧凑布局分组→拼音映射实现。数据见 t9_pinyin_data.inc
// （PinYinUtil.allPinyin 机械生成，与 T9PinyinMap 同表）；
// 推导口径与 PinYinUtil.possibleCompactCombinations 逐项一致，
// 见头文件注释。

#include "compact_pinyin_map.h"

#include <utility>

#include "t9_pinyin_map.h"

namespace jime::t9 {

namespace {

// 音节表：PinYinUtil.allPinyin 的 432 条，同声明顺序。
#include "t9_pinyin_data.inc"

constexpr int kMaxCodes = 6;

}  // namespace

CompactPinyinMap::CompactPinyinMap(std::vector<std::string> groups)
    : groups_(std::move(groups)) {
    // 与 T9PinyinMap 构造同构：按音节声明顺序分组，组内顺序
    // 即 allPinyin 的声明顺序。
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

bool CompactPinyinMap::KeyCodeFor(char c, char* out) const {
    if (c >= 'a' && c <= 'z') {
        for (const std::string& group : groups_) {
            if (group.find(c) != std::string::npos) {
                // 组码 = 首字母大写（Kotlin CompactKeyGroup.code 同值）。
                *out = static_cast<char>(group[0] - 'a' + 'A');
                return true;
            }
        }
        return false;
    }
    if (c >= 'A' && c <= 'Z') {
        return KeyCodeFor(static_cast<char>(c - 'A' + 'a'), out);
    }
    // 数字只认 8 组分组（9 键）：第 i 组认数字 i+2。
    if (groups_.size() == 8 && c >= '2' && c <= '9') {
        const std::string& group = groups_[c - '2'];
        *out = static_cast<char>(group[0] - 'a' + 'A');
        return true;
    }
    return false;
}

std::vector<std::string> CompactPinyinMap::FirstSyllableOptions(
        const std::string& input) const {
    // T9PinyinMap::FirstSyllableOptions 同口径：逐字归一化为组码
    // （无映射的字符原样保留，查表时自然落空）、取前 6 码、按长度
    // 从长到短查前缀；单字母 i/u/v 不产出。
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
            if (len == 1) {
                break;
            }
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
