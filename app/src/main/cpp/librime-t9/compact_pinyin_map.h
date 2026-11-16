// SPDX-License-Identifier: Apache-2.0
//
// 紧凑布局（9/14/18 键）分组→拼音映射：与 T9PinyinMap 同算法、
// 同音节表（t9_pinyin_data.inc，源自 PinYinUtil.allPinyin），
// 只换分组。分组即 CompactLayoutGenerator 的产出（Kotlin 侧）：
// 9 键 {"abc","def","ghi","jkl","mno","pqrs","tuv","wxyz"}，
// 14/18 键为 QWERTY 行内相邻合并的分组。组码取每组首字母大写。
// 与 T9PinyinMap 各自独立（旧 T9 路径不动）；本类是引擎侧认得
// 14/18 键分组的层——键盘点按发首字母、长按发次字母，输入串
// 经此归一化为组码再查首音节候选。数字 2-9 只在 8 组（9 键）
// 分组认（与 T9PinyinMap::KeyCodeFor 同口径）；14/18 键无数字键。

#pragma once

#include <string>
#include <unordered_map>
#include <vector>

namespace jime::t9 {

class CompactPinyinMap {
public:
    // groups: 每组合并字母（小写 a-z，每组非空）；组码 = 每组首字母大写。
    explicit CompactPinyinMap(std::vector<std::string> groups);

    // 首音节候选项：与 T9PinyinMap::FirstSyllableOptions 同口径
    // （输入先归一化为组码、取前 6 码、按长度从长到短查前缀、
    // 滤掉无拼写的单字母 i/u/v、无条数上限）。
    std::vector<std::string> FirstSyllableOptions(const std::string& input) const;

    // 字母/数字→组码：a-z 按所属分组（大小写同值）；数字 2-9 只在
    // 8 组分组认（第 i 组认数字 i+2）；其余字符无映射。
    bool KeyCodeFor(char c, char* out) const;

    const std::vector<std::string>& groups() const { return groups_; }

private:
    std::vector<std::string> groups_;
    std::unordered_map<std::string, std::vector<std::string>> code_to_pinyins_;
};

}  // namespace jime::t9
