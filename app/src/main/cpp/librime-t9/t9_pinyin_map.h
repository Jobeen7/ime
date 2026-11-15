// SPDX-License-Identifier: Apache-2.0
//
// T9 数字→拼音映射（WO52 1.5，自研插件）。
//
// 数据与推导口径复用 Jime 现有 PinYinUtil（base/util/PinYinUtil.kt）：
// 音节表即 allPinyin（432 条，见 t9_pinyin_data.inc），键位映射即
// t9KeyMap（字母/数字→A/D/G/J/M/P/T/W 八个键位码）；查表算法与
// possibleT9Combinations 逐项一致（输入先归一化为键位码、取前
// 6 码、按长度从长到短查前缀、滤掉无拼写的单字母 i/u/v、无条数
// 上限）。Jime 自研 T9 插件（设计参考开源 t9 插件思路，未复制代码）；
// 本表数据来自 Jime 自身的 PinYinUtil，由生成器机械搬运。

#pragma once

#include <string>
#include <unordered_map>
#include <vector>

namespace jime::t9 {

class T9PinyinMap {
public:
    static const T9PinyinMap& Instance();

    // 首音节候选项：input 为数字串（2-9）或已含字母的混合串；返回
    // 按长度从长到短的拼音列表，与
    // PinYinUtil.possibleT9Combinations 同序同值。
    std::vector<std::string> FirstSyllableOptions(const std::string& input) const;

    // 字母/数字→键位码（PinYinUtil.t9KeyMap 同值）：a/b/c/2→A、
    // d/e/f/3→D、g/h/i/4→G、j/k/l/5→J、m/n/o/6→M、p/q/r/s/7→P、
    // t/u/v/8→T、w/x/y/z/9→W；其余字符无映射。
    static bool KeyCodeFor(char c, char* out);

private:
    T9PinyinMap();

    std::unordered_map<std::string, std::vector<std::string>> code_to_pinyins_;
};

// 无对应方案拼写的单字母条目（PinYinUtil.NO_SPELLING_SINGLE_LETTERS
// 同值）：无以其开头的音节，缩写拼写无从派生。
bool IsNoSpellingSingleLetter(const std::string& pinyin);

}  // namespace jime::t9
