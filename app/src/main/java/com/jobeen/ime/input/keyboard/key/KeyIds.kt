package com.jobeen.ime.input.keyboard.key

/**
 * 稳定键 ID 登记表（皮肤 v3 定稿五.1 / Batch 2 详设 §2.2）：
 * 出厂布局每个可覆写键的唯一语义 ID，常量名与值同名。
 *
 * 治理（定稿五.1）：只增不改名；废弃保留登记（进 [DEPRECATED]，
 * 旧皮肤命中按未知 ID 逐项忽略）。ID 只存在于本登记表、运行时
 * KeyDef 实例与皮肤文件的 keyMappings 键名，不落其他盘上格式。
 *
 * Symbol/Emoji 只登记功能锚点（返回键 + 分类侧栏项），网格键
 * 不登记、不覆写（定稿五.6）。
 */
object KeyIds {

    object Qwerty {
        const val KEY_Q = "KEY_Q"
        const val KEY_W = "KEY_W"
        const val KEY_E = "KEY_E"
        const val KEY_R = "KEY_R"
        const val KEY_T = "KEY_T"
        const val KEY_Y = "KEY_Y"
        const val KEY_U = "KEY_U"
        const val KEY_I = "KEY_I"
        const val KEY_O = "KEY_O"
        const val KEY_P = "KEY_P"
        const val KEY_A = "KEY_A"
        const val KEY_S = "KEY_S"
        const val KEY_D = "KEY_D"
        const val KEY_F = "KEY_F"
        const val KEY_G = "KEY_G"
        const val KEY_H = "KEY_H"
        const val KEY_J = "KEY_J"
        const val KEY_K = "KEY_K"
        const val KEY_L = "KEY_L"
        const val KEY_CAPS = "KEY_CAPS"
        const val KEY_Z = "KEY_Z"
        const val KEY_X = "KEY_X"
        const val KEY_C = "KEY_C"
        const val KEY_V = "KEY_V"
        const val KEY_B = "KEY_B"
        const val KEY_N = "KEY_N"
        const val KEY_M = "KEY_M"
        const val KEY_DELETE = "KEY_DELETE"
        const val KEY_LAYOUT_NUMBER = "KEY_LAYOUT_NUMBER"
        const val KEY_PERIOD = "KEY_PERIOD"
        const val KEY_SPACE = "KEY_SPACE"
        const val KEY_SCHEMA = "KEY_SCHEMA"
        const val KEY_ENTER = "KEY_ENTER"

        val ALL: Set<String> = setOf(
            KEY_Q, KEY_W, KEY_E, KEY_R, KEY_T, KEY_Y, KEY_U, KEY_I, KEY_O, KEY_P,
            KEY_A, KEY_S, KEY_D, KEY_F, KEY_G, KEY_H, KEY_J, KEY_K, KEY_L,
            KEY_CAPS, KEY_Z, KEY_X, KEY_C, KEY_V, KEY_B, KEY_N, KEY_M, KEY_DELETE,
            KEY_LAYOUT_NUMBER, KEY_PERIOD, KEY_SPACE, KEY_SCHEMA, KEY_ENTER,
        )
    }

    object T9 {
        const val T9_SEGMENT = "T9_SEGMENT"
        const val T9_KEY_2 = "T9_KEY_2"
        const val T9_KEY_3 = "T9_KEY_3"
        const val T9_KEY_4 = "T9_KEY_4"
        const val T9_KEY_5 = "T9_KEY_5"
        const val T9_KEY_6 = "T9_KEY_6"
        const val T9_KEY_7 = "T9_KEY_7"
        const val T9_KEY_8 = "T9_KEY_8"
        const val T9_KEY_9 = "T9_KEY_9"
        const val T9_DELETE = "T9_DELETE"
        const val T9_NEWLINE = "T9_NEWLINE"
        const val T9_ENTER = "T9_ENTER"
        const val T9_LAYOUT_NUMBER = "T9_LAYOUT_NUMBER"
        const val T9_PERIOD = "T9_PERIOD"
        const val T9_SPACE = "T9_SPACE"
        const val T9_SCHEMA = "T9_SCHEMA"

        val ALL: Set<String> = setOf(
            T9_SEGMENT,
            T9_KEY_2, T9_KEY_3, T9_KEY_4, T9_KEY_5, T9_KEY_6, T9_KEY_7, T9_KEY_8, T9_KEY_9,
            T9_DELETE, T9_NEWLINE, T9_ENTER, T9_LAYOUT_NUMBER, T9_PERIOD, T9_SPACE, T9_SCHEMA,
        )
    }

    object T15 {
        const val T15_KEY_1 = "T15_KEY_1"
        const val T15_KEY_2 = "T15_KEY_2"
        const val T15_KEY_3 = "T15_KEY_3"
        const val T15_KEY_4 = "T15_KEY_4"
        const val T15_KEY_5 = "T15_KEY_5"
        const val T15_KEY_6 = "T15_KEY_6"
        const val T15_KEY_7 = "T15_KEY_7"
        const val T15_KEY_8 = "T15_KEY_8"
        const val T15_KEY_9 = "T15_KEY_9"
        const val T15_KEY_10 = "T15_KEY_10"
        const val T15_KEY_11 = "T15_KEY_11"
        const val T15_KEY_12 = "T15_KEY_12"
        const val T15_KEY_13 = "T15_KEY_13"
        const val T15_KEY_14 = "T15_KEY_14"
        const val T15_KEY_15 = "T15_KEY_15"
        const val T15_CLEAR = "T15_CLEAR"
        const val T15_ZERO = "T15_ZERO"
        const val T15_DELETE = "T15_DELETE"
        const val T15_ENTER = "T15_ENTER"
        const val T15_SPACE = "T15_SPACE"
        const val T15_PERIOD = "T15_PERIOD"
        const val T15_LAYOUT_NUMBER = "T15_LAYOUT_NUMBER"
        const val T15_SCHEMA = "T15_SCHEMA"

        val ALL: Set<String> = setOf(
            T15_KEY_1, T15_KEY_2, T15_KEY_3, T15_KEY_4, T15_KEY_5,
            T15_KEY_6, T15_KEY_7, T15_KEY_8, T15_KEY_9, T15_KEY_10,
            T15_KEY_11, T15_KEY_12, T15_KEY_13, T15_KEY_14, T15_KEY_15,
            T15_CLEAR, T15_ZERO, T15_DELETE, T15_ENTER, T15_SPACE,
            T15_PERIOD, T15_LAYOUT_NUMBER, T15_SCHEMA,
        )
    }

    object Number {
        const val NUM_KEY_1 = "NUM_KEY_1"
        const val NUM_KEY_2 = "NUM_KEY_2"
        const val NUM_KEY_3 = "NUM_KEY_3"
        const val NUM_KEY_4 = "NUM_KEY_4"
        const val NUM_KEY_5 = "NUM_KEY_5"
        const val NUM_KEY_6 = "NUM_KEY_6"
        const val NUM_KEY_7 = "NUM_KEY_7"
        const val NUM_KEY_8 = "NUM_KEY_8"
        const val NUM_KEY_9 = "NUM_KEY_9"
        const val NUM_KEY_0 = "NUM_KEY_0"
        const val NUM_DELETE = "NUM_DELETE"
        const val NUM_CLEAR = "NUM_CLEAR"
        const val NUM_SPACE = "NUM_SPACE"
        const val NUM_RESUME = "NUM_RESUME"
        const val NUM_SYMBOL = "NUM_SYMBOL"
        const val NUM_COMMA = "NUM_COMMA"
        const val NUM_DOT = "NUM_DOT"
        const val NUM_AT = "NUM_AT"
        const val NUM_ENTER = "NUM_ENTER"

        val ALL: Set<String> = setOf(
            NUM_KEY_1, NUM_KEY_2, NUM_KEY_3, NUM_KEY_4, NUM_KEY_5,
            NUM_KEY_6, NUM_KEY_7, NUM_KEY_8, NUM_KEY_9, NUM_KEY_0,
            NUM_DELETE, NUM_CLEAR, NUM_SPACE, NUM_RESUME, NUM_SYMBOL,
            NUM_COMMA, NUM_DOT, NUM_AT, NUM_ENTER,
        )
    }

    /** Symbol 面板锚点（定稿五.6）：分类序号 = data/Symbol.kt 分类表顺序，只增不重排。 */
    object Symbol {
        const val SYM_RETURN = "SYM_RETURN"
        const val SYM_CAT_1 = "SYM_CAT_1"
        const val SYM_CAT_2 = "SYM_CAT_2"
        const val SYM_CAT_3 = "SYM_CAT_3"
        const val SYM_CAT_4 = "SYM_CAT_4"
        const val SYM_CAT_5 = "SYM_CAT_5"
        const val SYM_CAT_6 = "SYM_CAT_6"
        const val SYM_CAT_7 = "SYM_CAT_7"

        /** 分类锚点按序号排列：下标 0 = 第 1 分类。 */
        val CATS: List<String> = listOf(
            SYM_CAT_1, SYM_CAT_2, SYM_CAT_3, SYM_CAT_4, SYM_CAT_5, SYM_CAT_6, SYM_CAT_7,
        )

        val ALL: Set<String> = setOf(SYM_RETURN) + CATS
    }

    /** Emoji 面板锚点（定稿五.6）：分类序号 = data/Symbol.kt Emoji 分类表顺序，只增不重排。 */
    object Emoji {
        const val EMOJI_RETURN = "EMOJI_RETURN"
        const val EMOJI_CAT_1 = "EMOJI_CAT_1"
        const val EMOJI_CAT_2 = "EMOJI_CAT_2"
        const val EMOJI_CAT_3 = "EMOJI_CAT_3"
        const val EMOJI_CAT_4 = "EMOJI_CAT_4"
        const val EMOJI_CAT_5 = "EMOJI_CAT_5"
        const val EMOJI_CAT_6 = "EMOJI_CAT_6"
        const val EMOJI_CAT_7 = "EMOJI_CAT_7"
        const val EMOJI_CAT_8 = "EMOJI_CAT_8"
        const val EMOJI_CAT_9 = "EMOJI_CAT_9"

        /** 分类锚点按序号排列：下标 0 = 第 1 分类。 */
        val CATS: List<String> = listOf(
            EMOJI_CAT_1, EMOJI_CAT_2, EMOJI_CAT_3, EMOJI_CAT_4, EMOJI_CAT_5,
            EMOJI_CAT_6, EMOJI_CAT_7, EMOJI_CAT_8, EMOJI_CAT_9,
        )

        val ALL: Set<String> = setOf(EMOJI_RETURN) + CATS
    }

    /** 全部可覆写键 ID（四布局）。 */
    val OVERRIDABLE: Set<String> = Qwerty.ALL + T9.ALL + T15.ALL + Number.ALL

    /** 全部功能锚点 ID（只登记不覆写）。 */
    val ANCHORS: Set<String> = Symbol.ALL + Emoji.ALL

    /** 已废弃但保留登记的 ID（定稿五.1 治理）：命中按未知 ID 处理。 */
    val DEPRECATED: Set<String> = emptySet()

    fun isOverridable(id: String): Boolean = id in OVERRIDABLE

    fun isAnchor(id: String): Boolean = id in ANCHORS

    fun isRegistered(id: String): Boolean = isOverridable(id) || isAnchor(id)
}
