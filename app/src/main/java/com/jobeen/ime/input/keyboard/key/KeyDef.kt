package com.jobeen.ime.input.keyboard.key

import android.graphics.Typeface
import android.view.View
import androidx.annotation.DrawableRes

open class KeyDef(
    val appearance: Appearance,
    val behaviors: Set<Behavior>,
    val popups: Array<Popup>? = null,
    /**
     * 稳定键 ID（皮肤 v3 定稿五.1）：只由布局登记表（[KeyIds]）在
     * 构造处标注；null = 未登记键（侧栏/动态/网格键）。只有皮肤
     * 覆写装配器、逐键编辑器与对账测试读它，渲染与手势不读——
     * 无 ID 的键行为与 v1.1.14 逐字节一致。
     */
    val id: String? = null,
) {

    /**
     * 运行时逐键音效解析结果（B2 详设 §7.2）：只由覆写装配器写在
     * 它生成的 KeyDef 副本上，出厂静态 def 永不写（恒为 null/false
     * → 播放路径与 v1.1.14 一致）。值为已接纳资产 id。
     */
    var skinSoundAssetId: String? = null

    /** 逐键显式静音（覆写 sound:""）：为 true 时该键不发任何点按音。 */
    var skinSoundMuted: Boolean = false

    /**
     * 单键样式原始引用（2026-10-27 并入项）：只由自创布局构建
     * 器写在基础 KeyDef 上（布局键节点的 style/styleOverrides），
     * 键盘构建期由覆写装配器按快照解析为下方 5 色槽；出厂 def
     * 恒为 null → 渲染与 v1.1.14 一致。
     */
    var skinStyleRef: String? = null

    /** 单键样式覆盖原始值（角色 json 名 → 取值字符串），与上同。 */
    var skinStyleOverrides: Map<String, String>? = null
    sealed class Appearance(
        val percentWidth: Float,
        val variant: Variant,
        var border: Border,
        val margin: Boolean,
        val viewId: Int,
        var visibility: Int = View.VISIBLE,
        open val rowSpan: Int = 1,
        open val alignSpanEnd: Boolean = false,
    ) {
        /**
         * 单键样式解析结果（2026-10-27 并入项）：只由覆写装配器
         * 写在它生成的外观副本上（出厂外观永不原地修改）；渲染
         * 层取色时优先于方案值（null 角色 = 该角色保持方案默认）。
         */
        var skinFaceColor: Int? = null
        var skinPressedColor: Int? = null
        var skinTextColor: Int? = null
        var skinAltTextColor: Int? = null
        var skinBorderColor: Int? = null
        /** G1 图标染色（keyStyles icon 角色解析结果；无图标的键忽略）。 */
        var skinIconTintColor: Int? = null
        /**
         * G1 单键图片键面：已接纳图片资产逻辑 id（快照接纳口径，
         * 未接纳/解码失败时渲染回退颜色键面）；位图本体不存此处，
         * 由 SkinKeyImageStore 在冷路径按 id 解析。
         */
        var skinImageAssetId: String? = null
        /** G1 分档字号解析结果：主文字（null = 出厂 textSize）。 */
        var skinTextSize: Float? = null
        /** G1 分档字号解析结果：副文字（null = 出厂 altTextSize / 11f）。 */
        var skinAltTextSize: Float? = null
        /**
         * 拟态顶部小字（2026-10-29 item-1）：只由覆写装配器写在它
         * 生成的外观副本上；AltTextKeyView 顶部小字行渲染，null =
         * 无顶部小字（出厂外观恒为 null，行为不变）。
         */
        var skinTopText: String? = null
        /**
         * 按键动效（2026-10-29 item-1）：只由覆写装配器写在它生成
         * 的外观副本上（空格键取空格专属规格、其余键取通用规格；
         * 降级关闭时为 null）；KeyView 按压时缩放、抬起回弹。
         */
        var skinKeyAnimation: com.jobeen.ime.skin.model.KeyAnimationSpec? = null
        /** G1 内容中心偏置（0..1，null = 居中）；只作用于主文字。 */
        var skinContentBiasX: Float? = null
        var skinContentBiasY: Float? = null
        /** G1 可视区域附加内缩（dp，叠加于皮肤级键间距之上）。 */
        var skinHInsetDp: Float? = null
        var skinVInsetDp: Float? = null
        /**
         * G1 可视高度乘子（0.5..1.0）：行高由网格固定，只能以对称
         * 竖向内缩实现可视收缩；渲染层按 (1-factor)*12dp 换算附加竖向内缩。
         */
        var skinHeightFactor: Float? = null
        /** G1 前景多层叠加（装配期已解析，渲染层按序叠加、后层压前层）。 */
        var skinForegroundLayers: List<com.jobeen.ime.skin.model.ResolvedForegroundLayer>? = null
        /**
         * 单键图标（参考项）：Material Symbols 名，只由覆写
         * 装配器写在它生成的外观副本上（出厂外观恒为 null → 渲染与
         * v1.1.14 一致）；文本键以符号字形代主显文本，图片键以符号
         * 字形代工厂图片。未知名装配期已回退，不会到达此处。
         */
        var skinIconSymbol: String? = null
        /**
         * 按压态独立图标（重点候选 8-2）：Material Symbols 名，只由覆写
         * 装配器写在它生成的外观副本上；渲染切换要求常态图标已配。
         */
        var skinPressedIconSymbol: String? = null
        /**
         * 按压态独立图片键面资产 id（重点候选 8-2）：快照接纳口径；
         * 渲染层按压时叠加显示、抬起隐藏（位图本体由 SkinKeyImageStore
         * 在冷路径按 id 解析，与 skinImageAssetId 同机制）。
         */
        var skinPressedImageAssetId: String? = null
        /**
         * G1 动态文字运行时跟随：来源 json 名（candidateFirst/composing），
         * 由覆写装配器写在它生成的外观副本上；键盘层据此在候选/组字
         * 变化时刷新主显文本，无值时回退 [skinDynamicFallback]。
         */
        var skinDynamicText: String? = null
        /** G1 动态文字无值时的静态回退文本（装配期静态 label 或出厂文本）。 */
        var skinDynamicFallback: String? = null
        enum class Variant { Normal, Alternative, Accent, AltForeground, None }

        enum class Border { Default, On, Off, Special }

        open class Text(
            val displayText: String,
            val textSize: Float,
            val textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            visibility: Int = View.VISIBLE,
            /**
             * 跨行参数（B3 §1.3 最小接缝扩展）：默认 1/false 与历史
             * 行为逐字节一致，出厂调用点零改动；只有自创布局的文本
             * 键会传 >1（出厂跨行先例仍是 Image 回车键）。
             */
            override val rowSpan: Int = 1,
            override val alignSpanEnd: Boolean = false,
        ) : Appearance(
            percentWidth, variant, border, margin, viewId, visibility,
            rowSpan, alignSpanEnd,
        )

        class AltText(
            displayText: String,
            val altText: String,
            val altTextTranslationY: Int = 0,
            val mainTextTranslationY: Int = 0,
            val altTextSize:Float  = 11f,
            textSize: Float,
            textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            visibility: Int = View.VISIBLE,
            rowSpan: Int = 1,
            alignSpanEnd: Boolean = false,
        ) : Text(
            displayText,
            textSize,
            textStyle,
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            visibility,
            rowSpan,
            alignSpanEnd
        )

        class SidePannel(
            override val rowSpan: Int = 3,
            val visableRow: Int = 4,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
        ) : Appearance(percentWidth, variant, border, margin, viewId)

        class Image(
            @DrawableRes val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            override val rowSpan: Int = 1,
            override val alignSpanEnd: Boolean = false,
        ) : Appearance(percentWidth, variant, border, margin, viewId)

        class ImageText(
            displayText: String,
            textSize: Float,
            textStyle: Int = Typeface.NORMAL,
            @DrawableRes val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            rowSpan: Int = 1,
            alignSpanEnd: Boolean = false,
        ) : Text(
            displayText, textSize, textStyle, percentWidth, variant, border,
            margin, viewId, View.VISIBLE, rowSpan, alignSpanEnd,
        )
    }

    sealed class Behavior {
        class Press(val action: KeyboardAction) : Behavior()
        class LongPress(val action: KeyboardAction) : Behavior()
        class Repeat(val action: KeyboardAction) : Behavior()
        class Swipe(
            val upAction: KeyboardAction? = null,
            val downAction: KeyboardAction? = null,
            /** G1 左右滑（null = 该方向不出厂/不覆写；缺省 null 与旧行为一致）。 */
            val leftAction: KeyboardAction? = null,
            val rightAction: KeyboardAction? = null,
        ) : Behavior()

        /** 空格键左右滑动移动光标（连续触发，每跨过一个阈值移动一格） */
        data object SwipeMoveCursor : Behavior()
        class DoubleTap(val action: KeyboardAction) : Behavior()
    }

    sealed class Popup {
        open class Preview(val content: String) : Popup()
        class AltPreview(content: String, val alternative: String) : Preview(content)
        class Keyboard(val label: String, val keys: List<KeyboardAction>) : Popup()
        class Menu(val items: Array<Item>) : Popup() {
            class Item(
                val label: String,
                @DrawableRes val icon: Int,
                val action: KeyboardAction,
            )
        }
    }
}
