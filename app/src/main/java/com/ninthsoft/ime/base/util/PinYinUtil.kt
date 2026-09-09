package com.ninthsoft.ime.base.util

object PinYinUtil {
    const val CandidateKindT9 = "T9PinYin"
    const val CandidateKindFull = "PinYin"

    val supportTypes = arrayOf(
        CandidateKindT9, CandidateKindFull
    )


    private val t9KeyMap = mapOf(
        'a' to 'A',
        'b' to 'A',
        'c' to 'A',
        'd' to 'D',
        'e' to 'D',
        'f' to 'D',
        'g' to 'G',
        'h' to 'G',
        'i' to 'G',
        'j' to 'J',
        'k' to 'J',
        'l' to 'J',
        'm' to 'M',
        'n' to 'M',
        'o' to 'M',
        'p' to 'P',
        'q' to 'P',
        'r' to 'P',
        's' to 'P',
        't' to 'T',
        'u' to 'T',
        'v' to 'T',
        'w' to 'W',
        'x' to 'W',
        'y' to 'W',
        'z' to 'W',

        '2' to 'A',
        '3' to 'D',
        '4' to 'G',
        '5' to 'J',
        '6' to 'M',
        '7' to 'P',
        '8' to 'T',
        '9' to 'W'
    )

    private val allPinyin = setOf(
        "a",
        "ai",
        "an",
        "ang",
        "ao",

        "b",
        "ba",
        "bai",
        "ban",
        "bang",
        "bao",
        "bei",
        "ben",
        "beng",
        "bi",
        "bian",
        "biang",
        "biao",
        "bie",
        "bin",
        "bing",
        "bo",
        "bu",

        "c",
        "ca",
        "cai",
        "can",
        "cang",
        "cao",
        "ce",
        "cen",
        "ceng",
        "cha",
        "chai",
        "chan",
        "chang",
        "chao",
        "che",
        "chen",
        "cheng",
        "chi",
        "chong",
        "chou",
        "chu",
        "chua",
        "chuai",
        "chuan",
        "chuang",
        "chui",
        "chun",
        "chuo",
        "ci",
        "cong",
        "cou",
        "cu",
        "cuan",
        "cui",
        "cun",
        "cuo",

        "d",
        "da",
        "dai",
        "dan",
        "dang",
        "dao",
        "de",
        "dei",
        "den",
        "deng",
        "di",
        "dia",
        "dian",
        "diao",
        "die",
        "ding",
        "diu",
        "dong",
        "dou",
        "du",
        "duan",
        "dui",
        "dun",
        "duo",

        "e",
        "ei",
        "en",
        "eng",
        "er",

        "f",
        "fa",
        "fan",
        "fang",
        "fei",
        "fen",
        "feng",
        "fo",
        "fou",
        "fu",

        "g",
        "ga",
        "gai",
        "gan",
        "gang",
        "gao",
        "ge",
        "gei",
        "gen",
        "geng",
        "gong",
        "gou",
        "gu",
        "gua",
        "guai",
        "guan",
        "guang",
        "gui",
        "gun",
        "guo",

        "h",
        "ha",
        "hai",
        "han",
        "hang",
        "hao",
        "he",
        "hei",
        "hen",
        "heng",
        "hong",
        "hou",
        "hu",
        "hua",
        "huai",
        "huan",
        "huang",
        "hui",
        "hun",
        "huo",

        "i",

        "j",
        "ji",
        "jia",
        "jian",
        "jiang",
        "jiao",
        "jie",
        "jin",
        "jing",
        "jiong",
        "jiu",
        "ju",
        "juan",
        "jue",
        "jun",

        "k",
        "ka",
        "kai",
        "kan",
        "kang",
        "kao",
        "ke",
        "ken",
        "keng",
        "kong",
        "kou",
        "ku",
        "kua",
        "kuai",
        "kuan",
        "kuang",
        "kui",
        "kun",
        "kuo",

        "l",
        "la",
        "lai",
        "lan",
        "lang",
        "lao",
        "le",
        "lei",
        "leng",
        "li",
        "lia",
        "lian",
        "liang",
        "liao",
        "lie",
        "lin",
        "ling",
        "liu",
        "lo",
        "long",
        "lou",
        "lu",
        "luan",
        "lun",
        "luo",
        "lv",
        "lve",

        "m",
        "ma",
        "mai",
        "man",
        "mang",
        "mao",
        "me",
        "mei",
        "men",
        "meng",
        "mi",
        "mian",
        "miao",
        "mie",
        "min",
        "ming",
        "miu",
        "mo",
        "mou",
        "mu",

        "n",
        "na",
        "nai",
        "nan",
        "nang",
        "nao",
        "ne",
        "nei",
        "nen",
        "neng",
        "ni",
        "nian",
        "niang",
        "niao",
        "nie",
        "nin",
        "ning",
        "niu",
        "nong",
        "nou",
        "nu",
        "nuan",
        "nuo",
        "nv",
        "nve",

        "o",
        "ou",

        "p",
        "pa",
        "pai",
        "pan",
        "pang",
        "pao",
        "pei",
        "pen",
        "peng",
        "pi",
        "pian",
        "piao",
        "pie",
        "pin",
        "ping",
        "po",
        "pou",
        "pu",

        "q",
        "qi",
        "qia",
        "qian",
        "qiang",
        "qiao",
        "qie",
        "qin",
        "qing",
        "qiong",
        "qiu",
        "qu",
        "quan",
        "que",
        "qun",

        "r",
        "ran",
        "rang",
        "rao",
        "re",
        "ren",
        "reng",
        "ri",
        "rong",
        "rou",
        "ru",
        "rua",
        "ruan",
        "rui",
        "run",
        "ruo",

        "s",
        "sa",
        "sai",
        "san",
        "sang",
        "sao",
        "se",
        "sen",
        "seng",
        "sha",
        "shai",
        "shan",
        "shang",
        "shao",
        "she",
        "shei",
        "shen",
        "sheng",
        "shi",
        "shou",
        "shu",
        "shua",
        "shuai",
        "shuan",
        "shuang",
        "shui",
        "shun",
        "shuo",
        "si",
        "song",
        "sou",
        "su",
        "suan",
        "sui",
        "sun",
        "suo",

        "t",
        "ta",
        "tai",
        "tan",
        "tang",
        "tao",
        "te",
        "teng",
        "ti",
        "tian",
        "tiao",
        "tie",
        "ting",
        "tong",
        "tou",
        "tu",
        "tuan",
        "tui",
        "tun",
        "tuo",

        "u",
        "v",

        "w",
        "wa",
        "wai",
        "wan",
        "wang",
        "wei",
        "wen",
        "weng",
        "wo",
        "wu",

        "x",
        "xi",
        "xia",
        "xian",
        "xiang",
        "xiao",
        "xie",
        "xin",
        "xing",
        "xiong",
        "xiu",
        "xu",
        "xuan",
        "xue",
        "xun",

        "y",
        "ya",
        "yan",
        "yang",
        "yao",
        "ye",
        "yi",
        "yin",
        "ying",
        "yo",
        "yong",
        "you",
        "yu",
        "yuan",
        "yue",
        "yun",

        "z",
        "za",
        "zai",
        "zan",
        "zang",
        "zao",
        "ze",
        "zei",
        "zen",
        "zeng",
        "zha",
        "zhai",
        "zhan",
        "zhang",
        "zhao",
        "zhe",
        "zhen",
        "zheng",
        "zhi",
        "zhong",
        "zhou",
        "zhu",
        "zhua",
        "zhuai",
        "zhuan",
        "zhuang",
        "zhui",
        "zhun",
        "zhuo",
        "zi",
        "zong",
        "zou",
        "zu",
        "zuan",
        "zui",
        "zun",
        "zuo"
    )

    private val pinyinMap: Map<String, List<String>> = buildMap {
        allPinyin.groupBy { pinyin ->
            buildString(pinyin.length) {
                pinyin.forEach { c ->
                    append(t9KeyMap[c] ?: c)
                }
            }
        }.forEach { (key, value) ->
            put(key, value)
        }
    }

    /**
     * 根据输入类型获取可能的拼音组合。
     *
     * T9:
     *   按 T9 键位进行前缀模糊匹配。
     *
     * Full:
     *   按完整拼音音节进行逐级截断。
     */
    fun possibleCombinations(
        type: String,
        sequence: String?
    ): Array<String> {
        if (sequence.isNullOrBlank()) {
            return emptyArray()
        }

        return when (type) {
            CandidateKindT9 -> possibleT9Combinations(sequence)
            CandidateKindFull -> possibleFullCombinations(sequence)
            else -> emptyArray()
        }
    }

    private fun possibleT9Combinations(sequence: String): Array<String> {
        val mappedSequence = buildString(sequence.length) {
            sequence.forEach { c ->
                append(t9KeyMap[c.lowercaseChar()] ?: c)
            }
        }

        val numString = mappedSequence.take(6)
        if (numString.isEmpty()) {
            return emptyArray()
        }

        val result = ArrayList<String>()

        for (length in numString.length downTo 1) {
            pinyinMap[numString.substring(0, length)]?.let {
                result.addAll(it)
            }
        }

        return result.toTypedArray()
    }

    private fun possibleFullCombinations(sequence: String): Array<String> {
        val pinyin = sequence.lowercase()

        if (pinyin.isEmpty()) {
            return emptyArray()
        }

        val result = ArrayList<String>()

        // 完整拼音
        if (pinyin in allPinyin) {
            result.add(pinyin)
        }

        // 只按照拼音结构进行截断。
        //
        // 例如：
        // zhuang -> zhuan -> z
        // zhang  -> zhan -> zha -> z
        // chang  -> chan -> cha -> c
        //
        // 不直接按照每个字符进行 substring，
        // 而是使用合法拼音集合判断截断结果。
        for (length in pinyin.length - 1 downTo 1) {
            val prefix = pinyin.substring(0, length)

            if (prefix !in allPinyin) {
                continue
            }

            if (isValidFullPinyinPrefix(pinyin, prefix)) {
                result.add(prefix)
            }
        }

        return result.toTypedArray()
    }

    fun isValidType(type: String): Boolean {
        return supportTypes.contains(type)
    }

    /**
     * 判断 prefix 是否是 sequence 的有效拼音截断。
     *
     * 这里不是简单的 contains，而是限制：
     *
     * 1. prefix 必须是合法拼音
     * 2. prefix 必须是 sequence 的前缀
     * 3. prefix 必须位于合理的拼音截断位置
     */
    private fun isValidFullPinyinPrefix(
        sequence: String,
        prefix: String
    ): Boolean {
        if (!sequence.startsWith(prefix)) {
            return false
        }

        // 单字母声母始终允许作为最终截断。
        if (prefix.length == 1) {
            return prefix[0] in INITIALS
        }

        // 双字母声母只允许在完整声母位置作为最终截断。
        if (prefix.length == 2 && prefix in DOUBLE_INITIALS) {
            return true
        }

        /*
         * 对于 zhuang / zhuan 这类情况：
         *
         * zhuang
         *   ↓
         * zhuan
         *
         * 这是从复韵母 uang 截断到 uan。
         *
         * 因此只有当 prefix 本身仍然是完整的、
         * 合法的拼音音节时才允许。
         */
        return prefix in allPinyin
    }


    private val INITIALS = setOf(
        'b',
        'c',
        'd',
        'f',
        'g',
        'h',
        'j',
        'k',
        'l',
        'm',
        'n',
        'p',
        'q',
        'r',
        's',
        't',
        'w',
        'x',
        'y',
        'z'
    )

    private val DOUBLE_INITIALS = setOf(
        "ch",
        "sh",
        "zh"
    )
}