package com.jobeen.ime.skin.model

/**
 * 皮肤几何与类型注册（自 SkinModel.kt 迁出的活声明；
 * 配色／键盘类型等活代码在用）。
 */

/** 几何字段全集（定稿三.4）：只出现在 appearance，单位 dp 浮点。 */
enum class GeometryField(val jsonName: String) {
    CORNER_RADIUS("cornerRadius"),
    KEY_H_MARGIN("keyHMargin"),
    KEY_V_MARGIN("keyVMargin"),
    BOTTOM_INSET("bottomInsetDp"),
    /** 容器内边距（S5；缺省 0，与 bottomInsetDp 同口径 0..48）。 */
    TOP_INSET("topInsetDp"),
    LEFT_INSET("leftInsetDp"),
    RIGHT_INSET("rightInsetDp"),
}

/**
 * 键盘级几何字段（2026-10-27 新增分流）：只出现在顶层 `geometry`
 * 对象，与 appearance 几何是两套字段、同一消费点。
 *
 * - keyRadiusDp / keyHGapDp / keyVGapDp 分别对应圆角、键横/纵间距，
 *   区间与 appearance 几何一致（0..40 / 0..20 / 0..20）；
 * - heightPercent 为键盘高度百分比，区间取 App 现有竖屏滑条
 *   20..50 与横屏滑条 30..70 的并集 20..70，竖横屏同值生效；
 * - heightPercentLandscape 为横屏专属高度百分比（同区间），仅横屏
 *   生效、优先于 heightPercent；未指定时横屏回落 heightPercent。
 * 优先级：geometry 有效值 > appearance 有效值 > App 设置（见
 * [SkinSnapshotBuilder] 与 [KeyboardColors.resolve]，冲突时记
 * INFO issue，不静默覆盖）。
 */
enum class KeyboardGeometryField(val jsonName: String) {
    KEY_RADIUS("keyRadiusDp"),
    KEY_H_GAP("keyHGapDp"),
    KEY_V_GAP("keyVGapDp"),
    HEIGHT_PERCENT("heightPercent"),
    HEIGHT_PERCENT_LANDSCAPE("heightPercentLandscape"),
}

/** 校验问题（逐项进导入报告，无静默项；详设 §3.1）。 */
data class SkinIssue(
    val path: String,
    val kind: Kind,
    val detail: String,
) {
    enum class Kind {
        /** 已知字段取值损坏，已按优先级链回填下一级（定稿九.2）。 */
        FIELD_FALLBACK,

        /** 已知但当前批次未支持的段落：保留原文、不应用（定稿三.2）。 */
        RETAINED_INACTIVE,

        /** 资源引用无效（缺失/类型错），该引用已回退（定稿七.3/九.4）。 */
        RESOURCE_FALLBACK,

        /** 其他需明示事项（不改变生效结果）。 */
        INFO,
    }
}

/**
 * 内置键盘类型名（skin.json `keyboards` 表的保留键；其余任意非空
 * 键均为自定义类型，与方案正交——类型切换不改方案，方案切换
 * 不改类型）。手机竖/横屏两方向共用此表，不分设备形态。
 */
object KeyboardTypes {
    const val ALPHABETIC = "alphabetic"
    const val PINYIN = "pinyin"
    const val EMOJIS = "emojis"
    const val IMAGES = "images"
    const val NUMERIC = "numeric"
    const val NUMBER_PAD = "numberPad"
    const val SYMBOLIC = "symbolic"

    /** 7 个内置类型。 */
    val BUILTIN: Set<String> = setOf(
        ALPHABETIC, PINYIN, EMOJIS, IMAGES, NUMERIC, NUMBER_PAD, SYMBOLIC,
    )

    /** 主键盘类型：中英切换只在这两者之间往返。 */
    val MAIN: Set<String> = setOf(PINYIN, ALPHABETIC)

    /**
     * 临时类型：只活在当前一次附着内，永不写持久化，收起或
     * 切走即复位（不跨收起、不进 last 往返链）。目前仅此一名；
     * 其它 temp_ 前缀名不具此语义（按普通自定义类型处理）。
     */
    const val TEMP_PINYIN = "temp_pinyin"

    /** 工具类型（keyboards 表条目，非键盘类）：布局切换板 / 返回路径板 / 悬浮面板。 */
    const val LAYOUT_SWITCH = "layoutSwitch"
    const val RETURN_PATH = "returnPath"
    const val FLOAT_PANEL = "floatPanel"

    /** 工具类型集（表条目口径；绑定布局时走通用布局键盘，无绑定时走内置工具板）。 */
    val TOOL: Set<String> = setOf(LAYOUT_SWITCH, RETURN_PATH, FLOAT_PANEL)

    fun isTemp(type: String?): Boolean = type == TEMP_PINYIN
}

/**
 * `keyboards` 表单项（skin.json `keyboards.<类型>`，见
 * [SkinJson.parseKeyboards]）：布局绑定与键盘高度均为可选；
 * 全空项不占位（与未声明同等处理）。高度域与布局文件顶层
 * heightPercent 同口径：50–200，域外值解析期丢弃。
 * 本表只描述“类型 → 布局/高度”的映射，不承载键盘实例；
 * 引用解析（布局 id → 已解析布局）在快照工厂层完成。
 */
data class KeyboardTypeEntry(
    /** 竖屏布局绑定（库内布局 id）；null = 无自定义布局。 */
    val layoutId: String? = null,
    /** 横屏布局绑定；null = 横屏沿用竖屏绑定（见快照工厂）。 */
    val layoutIdLandscape: String? = null,
    /** 该类型键盘高度百分比；null = 未指定（走既有高度链）。 */
    val heightPercent: Double? = null,
    /** 横屏专属高度；null = 横屏沿用竖屏值。 */
    val heightPercentLandscape: Double? = null,
    /**
     * 类型样式集引用（G1）：styleSets 中的集名；null = 沿用包级样式。
     * 引用缺失集名时解析层忽略（回落包级样式，不拒包）。
     */
    val styleSet: String? = null,
    /**
     * 深色布局绑定（G4）：深色模式时该类型用的布局 id；
     * null = 深色沿用浅色绑定（与横屏回落竖屏同口径）。
     */
    val layoutIdDark: String? = null,
    /** 深色横屏布局绑定；null = 深色横屏沿用深色竖屏／浅色横屏。 */
    val layoutIdLandscapeDark: String? = null,
) {
    /** 按方向取高度：横屏无专属值时回落竖屏值。 */
    fun heightPercentFor(landscape: Boolean): Double? =
        if (landscape) heightPercentLandscape ?: heightPercent else heightPercent
}
