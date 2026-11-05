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
        /**
         * L1 跨行列区间（可选）：span 在起始行键链中的列位置，
         * [spanCol, spanColEnd)；null = 沿用 alignSpanEnd 旧口径
         * （左 → [0,1)，右 → [n,n+1)）。仅 rowSpan > 1 的键有意义。
         */
        open val spanCol: Int? = null,
        open val spanColEnd: Int? = null,
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
        /**
         * 审计 A 项几何面（只由覆写装配器写在它生成的外观副本上）：
         * 渐变（有值时渲染优先于纯色面）、逐样式阴影、描边宽、
         * 按压描边、下边缘色、圆角、可视区域；null = 该项无样式
         * 指定（渲染沿用既有口径）。出厂外观恒为 null。
         */
        var skinGradient: com.jobeen.ime.skin.model.KeyGradient? = null
        var skinPressedGradient: com.jobeen.ime.skin.model.KeyGradient? = null
        var skinShadow: com.jobeen.ime.skin.model.KeyPerStyleShadow? = null
        var skinBorderWidthDp: Float? = null
        var skinPressedBorderColor: Int? = null
        var skinLowerEdgeColor: Int? = null
        var skinPressedLowerEdgeColor: Int? = null
        var skinCornerRadiusDp: Float? = null
        var skinCornerCurve: com.jobeen.ime.skin.model.KeyCornerCurve? = null
        var skinVisibleBounds: com.jobeen.ime.skin.model.KeyVisibleBounds? = null
        /**
         * 审计 H 项动效（只由覆写装配器写在它生成的外观副本上）：
         * 本键样式动效名经动效表解析后的定义列表（图片帧已过滤
         * 为快照接纳资产）；null/空 = 无样式动效（走全局动效）。
         */
        var skinAnimations: List<com.jobeen.ime.skin.model.KeyAnimationDef>? = null
        /** 大写激活时主文字色（审计 E：uppercased 前景样式，Qwerty 消费）。 */
        var skinUppercasedTextColor: Int? = null
        /** 大写锁定后主文字色（审计 E：caps-locked 前景样式，Qwerty 消费）。 */
        var skinCapsLockedTextColor: Int? = null
        /** 上滑中主文字暂显色（审计 E：滑动方向前景样式）。 */
        var skinSwipeUpTextColor: Int? = null
        /** 滑动方向指示（多主类型补充）：true = 显示上下滑方向标记；视图渲染后续。 */
        var skinShowSwipeIndicators: Boolean = false
        /** 下滑中主文字暂显色（审计 E：滑动方向前景样式）。 */
        var skinSwipeDownTextColor: Int? = null
        /** 状态前景字号（S6，dp）：与上四色平行，null = 跟主字号。 */
        var skinUppercasedTextSize: Float? = null
        var skinCapsLockedTextSize: Float? = null
        var skinSwipeUpTextSize: Float? = null
        var skinSwipeDownTextSize: Float? = null
        /** G1 图标染色（keyStyles icon 角色解析结果；无图标的键忽略）。 */
        var skinIconTintColor: Int? = null
        /**
         * G1 单键图片键面：已接纳图片资产逻辑 id（快照接纳口径，
         * 未接纳/解码失败时渲染回退颜色键面）；位图本体不存此处，
         * 由 SkinKeyImageStore 在冷路径按 id 解析。
         */
        var skinImageAssetId: String? = null
        /**
         * 键面图片内容模式（审计 D 项）：仅覆写装配器写（image
         * 已接纳时取覆写值）；null = 旧口径 CENTER_CROP。
         */
        var skinFaceContentMode: com.jobeen.ime.skin.model.ImageContentMode? = null
        /**
         * 键面精灵图引用（工具项 8）：仅覆写装配器写（sheet 已接纳
         * 时取覆写值）；null = 未用精灵图（走整图键面）。渲染期按
         * sheet+子图裁剪（帧矩形＋cap 边框），见 KeyView 面层。
         */
        var skinFaceSpriteRef: com.jobeen.ime.skin.model.SkinImageRef? = null
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
        /** L4 四边内缩（dp）：单边优先于对称值（缺边回落 h/v，见 KeyView）。 */
        var skinLeftInsetDp: Float? = null
        var skinRightInsetDp: Float? = null
        var skinTopInsetDp: Float? = null
        var skinBottomInsetDp: Float? = null
        /**
         * G1 可视高度乘子（0.5..1.0）：行高由网格固定，只能以对称
         * 竖向内缩实现可视收缩；渲染层按 (1-factor)*12dp 换算附加竖向内缩。
         */
        var skinHeightFactor: Float? = null
        /** G1 前景多层叠加（装配期已解析，渲染层按序叠加、后层压前层）。 */
        var skinForegroundLayers: List<com.jobeen.ime.skin.model.ResolvedForegroundLayer>? = null
        /**
         * 长按符号样式名（现行 form）：只由覆写装配器写在它生成
         * 的外观副本上；快照 hintSymbolsStyles 表中的样式名，
         * 长按面板挂载时按名取样式（面板本身为后续项）。
         */
        var skinHintSymbolsStyle: String? = null
        /**
         * 长按符号网格样式名（网格版）：只由覆写装配器写在它生成
         * 的外观副本上；快照 hintSymbolsGridStyles 表中的样式名，
         * 非空时优先于 skinHintSymbolsStyle（面板手势挂载为后续项）。
         */
        var skinHintSymbolsGridStyle: String? = null
        /**
         * 短按气泡样式（hintStyle 节点）：只由覆写装配器写在它
         * 生成的外观副本上；现有按键预览弹窗按此取气泡尺寸与
         * 溢出检测，未配时走默认尺寸与顶层 bubbleOverflowCheck。
         */
        var skinHintBubble: com.jobeen.ime.skin.model.HintBubbleStyle? = null
        /**
         * 单键图标（参考项）：Material Symbols 名，只由覆写
         * 装配器写在它生成的外观副本上（出厂外观恒为 null → 渲染与
         * v1.1.14 一致）；文本键以符号字形代主显文本，图片键以符号
         * 字形代工厂图片。未知名装配期已回退，不会到达此处。
         */
        var skinIconSymbol: String? = null
        /**
         * 图标字形字号（S1，dp）：样式 geometry.iconFontSize；
         * null = 渲染层回落 24。
         */
        var skinIconSizeDp: Float? = null
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
         * 按压态图片内容模式（审计 D 项）：仅覆写装配器写
         * （pressedImage 已接纳时取覆写值）；null = 旧口径 CENTER_CROP。
         */
        var skinPressedFaceContentMode: com.jobeen.ime.skin.model.ImageContentMode? = null
        /**
         * 按压态精灵图引用（工具项 8）：仅覆写装配器写；null = 未用
         * 精灵图（走整图按压面）。渲染口径与常态精灵图面相同。
         */
        var skinPressedFaceSpriteRef: com.jobeen.ime.skin.model.SkinImageRef? = null
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
            /** L1 跨行列区间（可选，见基类）；null = 沿用旧口径。 */
            override val spanCol: Int? = null,
            override val spanColEnd: Int? = null,
        ) : Appearance(
            percentWidth, variant, border, margin, viewId, visibility,
            rowSpan, alignSpanEnd, spanCol, spanColEnd,
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
            spanCol: Int? = null,
            spanColEnd: Int? = null,
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
            alignSpanEnd,
            spanCol,
            spanColEnd,
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
            spanCol: Int? = null,
            spanColEnd: Int? = null,
        ) : Text(
            displayText, textSize, textStyle, percentWidth, variant, border,
            margin, viewId, View.VISIBLE, rowSpan, alignSpanEnd, spanCol, spanColEnd,
        )
    }

    sealed class Behavior {
        class Press(
            val action: KeyboardAction,
            /** 组合（preedit）存在时的点击动作（审计 E：preeditStateAction）。 */
            val preeditAction: KeyboardAction? = null,
            /** 大写激活时的点击动作（审计 E：uppercasedStateAction）。 */
            val uppercasedAction: KeyboardAction? = null,
        ) : Behavior()
        class LongPress(val action: KeyboardAction) : Behavior()
        class Repeat(val action: KeyboardAction) : Behavior()
        class Swipe(
            val upAction: KeyboardAction? = null,
            val downAction: KeyboardAction? = null,
            /** G1 左右滑（null = 该方向不出厂/不覆写；缺省 null 与旧行为一致）。 */
            val leftAction: KeyboardAction? = null,
            val rightAction: KeyboardAction? = null,
            /** 组合中各方向滑动动作（审计 E：preeditStateAction 方向版）。 */
            val preeditUpAction: KeyboardAction? = null,
            val preeditDownAction: KeyboardAction? = null,
            val preeditLeftAction: KeyboardAction? = null,
            val preeditRightAction: KeyboardAction? = null,
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
