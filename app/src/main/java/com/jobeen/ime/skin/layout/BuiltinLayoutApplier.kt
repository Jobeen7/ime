package com.jobeen.ime.skin.layout

import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.skin.runtime.KeyOverrideApplier

/**
 * 内置布局覆盖装配（2026-10-28 修复轮：内置布局 App 侧接应）。
 *
 * 皮肤含内置布局文件（layouts/<内置id>.json，键 stableId = 出厂
 * 语义键 ID）时，出厂布局不再原样用，改按文件重排：
 * - 排版以文件为准：行结构、行内权重归一化、跨行取文件 rowSpan；
 * - 键本体取自出厂 KeyDef（行为/外观/视图 id 全保留），文件只
 *   按槽覆盖：label 非空改显示文本；tap/swipeUp/swipeDown/
 *   longPress 有值改对应槽，缺省槽一律沿用出厂行为；
 * - 文件 style/styleOverrides 随键携带，由后续覆写装配器解析；
 * - 文件中出厂没有的 stableId 静默丢弃（编辑器由出厂生成文件，
 *   正常不出现；逐键盘构建期不记日志，避免刷新日志刷屏）；
 * - 结果为空（行全空）时回退整份出厂布局，永不空白。
 * alignSpanEnd 不覆盖（文件缺省 false 会误清出厂值，布局页亦
 * 不编辑该项）；左右滑（文件格式无此槽）沿用出厂值。
 * 纯函数，可在 JVM 单测（KeyDef 构造不触 Android 运行时）。
 */
object BuiltinLayoutApplier {

    fun apply(
        factory: List<List<KeyDef>>,
        override: LayoutModel,
        landscape: Boolean,
        placeholders: Map<String, String>,
    ): List<List<KeyDef>> {
        val factoryById = factory.flatten().mapNotNull { def ->
            def.id?.let { it to def }
        }.toMap()
        val arranged = override.rows.mapNotNull { row ->
            val weights = row.map { key ->
                if (landscape) key.landscapeWidthWeight ?: key.widthWeight else key.widthWeight
            }
            val total = weights.sum()
            val keys = row.mapIndexedNotNull { index, key ->
                val factoryDef = factoryById[key.stableId] ?: return@mapIndexedNotNull null
                val percentWidth = if (total > 0.0) {
                    (weights[index] / total).toFloat()
                } else {
                    1f / row.size
                }
                arrangeKey(factoryDef, key, percentWidth, placeholders)
            }
            keys.ifEmpty { null }
        }
        return arranged.ifEmpty { factory }
    }

    private fun arrangeKey(
        factoryDef: KeyDef,
        key: LayoutKeyModel,
        percentWidth: Float,
        placeholders: Map<String, String>,
    ): KeyDef {
        val label = KeyOverrideApplier.substitute(key.label, placeholders).ifBlank { null }
        val appearance = rebuildAppearance(factoryDef.appearance, label, percentWidth, key.rowSpan)
        var behaviors = factoryDef.behaviors
        key.tap?.let { tap ->
            behaviors = behaviors.filterNot { it is KeyDef.Behavior.Press }.toSet() +
                KeyDef.Behavior.Press(KeyOverrideApplier.toRuntime(tap))
        }
        if (key.swipeUp != null || key.swipeDown != null) {
            val factorySwipe = behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().firstOrNull()
            behaviors = behaviors.filterNot { it is KeyDef.Behavior.Swipe }.toSet() +
                KeyDef.Behavior.Swipe(
                    upAction = key.swipeUp?.let { KeyOverrideApplier.toRuntime(it) }
                        ?: factorySwipe?.upAction,
                    downAction = key.swipeDown?.let { KeyOverrideApplier.toRuntime(it) }
                        ?: factorySwipe?.downAction,
                    leftAction = factorySwipe?.leftAction,
                    rightAction = factorySwipe?.rightAction,
                )
        }
        var popups = factoryDef.popups
        key.longPress?.let { longPress ->
            behaviors = behaviors.filterNot { it is KeyDef.Behavior.LongPress }.toSet()
            popups = popups?.filterNot { it is KeyDef.Popup.Keyboard }?.toTypedArray()
            when {
                longPress.size == 1 -> behaviors = behaviors +
                    KeyDef.Behavior.LongPress(KeyOverrideApplier.toRuntime(longPress.single()))
                longPress.size >= 2 -> {
                    val displayText = (appearance as? KeyDef.Appearance.Text)?.displayText ?: ""
                    popups = (popups?.toList().orEmpty() + KeyDef.Popup.Keyboard(
                        label = displayText,
                        keys = longPress.map { KeyOverrideApplier.toRuntime(it) },
                    )).toTypedArray()
                }
            }
        }
        return KeyDef(
            appearance = appearance,
            behaviors = behaviors,
            popups = popups,
            id = factoryDef.id,
        ).also {
            it.skinStyleRef = key.styleRef
            it.skinStyleOverrides = key.styleOverrides?.mapKeys { (role, _) -> role.jsonName }
        }
    }

    /**
     * 外观重建：类型与出厂一致，字段全保留，只换宽度/跨行；
     * 文本类外观在文件 label 非空时换显示文本。alignSpanEnd
     * 恒取 出厂值（见类注）。AltText/ImageText 须先于 Text 匹配。
     */
    private fun rebuildAppearance(
        appearance: KeyDef.Appearance,
        label: String?,
        percentWidth: Float,
        rowSpan: Int,
    ): KeyDef.Appearance = when (appearance) {
        is KeyDef.Appearance.AltText -> KeyDef.Appearance.AltText(
            displayText = label ?: appearance.displayText,
            altText = appearance.altText,
            altTextTranslationY = appearance.altTextTranslationY,
            mainTextTranslationY = appearance.mainTextTranslationY,
            altTextSize = appearance.altTextSize,
            textSize = appearance.textSize,
            textStyle = appearance.textStyle,
            percentWidth = percentWidth,
            variant = appearance.variant,
            border = appearance.border,
            margin = appearance.margin,
            viewId = appearance.viewId,
            visibility = appearance.visibility,
            rowSpan = rowSpan,
            alignSpanEnd = appearance.alignSpanEnd,
        )
        is KeyDef.Appearance.ImageText -> KeyDef.Appearance.ImageText(
            displayText = label ?: appearance.displayText,
            textSize = appearance.textSize,
            textStyle = appearance.textStyle,
            src = appearance.src,
            percentWidth = percentWidth,
            variant = appearance.variant,
            border = appearance.border,
            margin = appearance.margin,
            viewId = appearance.viewId,
            rowSpan = rowSpan,
            alignSpanEnd = appearance.alignSpanEnd,
        )
        is KeyDef.Appearance.Text -> KeyDef.Appearance.Text(
            displayText = label ?: appearance.displayText,
            textSize = appearance.textSize,
            textStyle = appearance.textStyle,
            percentWidth = percentWidth,
            variant = appearance.variant,
            border = appearance.border,
            margin = appearance.margin,
            viewId = appearance.viewId,
            visibility = appearance.visibility,
            rowSpan = rowSpan,
            alignSpanEnd = appearance.alignSpanEnd,
        )
        is KeyDef.Appearance.Image -> KeyDef.Appearance.Image(
            src = appearance.src,
            percentWidth = percentWidth,
            variant = appearance.variant,
            border = appearance.border,
            margin = appearance.margin,
            viewId = appearance.viewId,
            rowSpan = rowSpan,
            alignSpanEnd = appearance.alignSpanEnd,
        )
        is KeyDef.Appearance.SidePannel -> KeyDef.Appearance.SidePannel(
            rowSpan = rowSpan,
            visableRow = appearance.visableRow,
            percentWidth = percentWidth,
            variant = appearance.variant,
            border = appearance.border,
            margin = appearance.margin,
            viewId = appearance.viewId,
        )
    }
}

/**
 * 从皮肤代次目录解析内置布局覆盖（快照构建期调用，JVM 可测）。
 *
 * 只读 `<genDir>/layouts/<内置id>.json`（导入期内置分流落盘处）；
 * 文件缺失/解析失败/校验无效 → 该内置 id 无覆盖（回退出厂布局），
 * 无效者记一条 [Invalid]（调用方记日志）；有效者即使带字段回退
 * 也照常采用（回退语义与自创布局一致）。
 */
fun resolveBuiltinLayouts(genDir: java.io.File): BuiltinLayoutResolution {
    val resolved = mutableMapOf<String, LayoutModel>()
    val invalid = mutableMapOf<String, List<LayoutIssue>>()
    for (id in BuiltinLayouts.IDS) {
        val file = java.io.File(java.io.File(genDir, "layouts"), "$id.json")
        if (!file.isFile) continue
        val tree = runCatching {
            com.jobeen.ime.skin.model.SkinJson.parseObject(file.readText())
        }.getOrNull()
        when (val validation = LayoutValidator.validate(tree, id, allowMissingTap = true)) {
            is LayoutValidation.Valid -> resolved[id] = validation.layout
            is LayoutValidation.Invalid -> invalid[id] = validation.issues
        }
    }
    return BuiltinLayoutResolution(resolved, invalid)
}

data class BuiltinLayoutResolution(
    val layouts: Map<String, LayoutModel>,
    val invalid: Map<String, List<LayoutIssue>>,
)
