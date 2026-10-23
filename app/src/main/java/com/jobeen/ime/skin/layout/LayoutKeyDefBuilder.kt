package com.jobeen.ime.skin.layout

import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.runtime.KeyOverrideApplier

/**
 * 布局构建（B3 详设 §3.1/§3.2，冷路径）：校验通过的 [LayoutModel]
 * → `List<List<KeyDef>>`，与出厂布局走同一条 BaseKeyboard 渲染/
 * 手势装配路径。热路径只读构建结果，不解析、不校验、不读盘。
 *
 * - 宽度（裁定 B1）：percentWidth 只在构建期按行内原始权重算
 *   比例（§2.4 公式），原始权重永不回写文件。
 * - 外观：统一 [KeyDef.Appearance.Text]；variant/字号走统一口径，
 *   皮肤 styles 如何作用于出厂文本键，就如何作用于自创键。
 * - 动作装配与 B2 覆写层同一转换面（[KeyOverrideApplier.toRuntime]，
 *   长按多项同规：单项 LongPress、≥2 项 Popup.Keyboard）。
 * - 占位符只替换键标签显示文本（B2 施工裁量已定：动作 value 是
 *   字面内容，永不替换），时机 = 本次构建（布局初始化/方案切换
 *   重建），替换结果不写回文件与快照。
 */
object LayoutKeyDefBuilder {

    /** 自创键统一字号（布局格式无逐键字号面，§2.1 外观边界）。 */
    const val TEXT_SIZE = 18f

    fun build(
        layout: LayoutModel,
        placeholders: Map<String, String>,
    ): List<List<KeyDef>> = layout.rows.map { row ->
        // §2.4：行内权重和 W_r；percentWidth_i = w_i / W_r（校验器
        // 已保证 W_r 有限且 ∈ [0.05, 200]，此处不重复防御）
        val totalWeight = row.sumOf { it.widthWeight }
        row.map { key ->
            buildKey(
                layoutId = layout.layoutId,
                key = key,
                percentWidth = (key.widthWeight / totalWeight).toFloat(),
                placeholders = placeholders,
            )
        }
    }

    private fun buildKey(
        layoutId: String,
        key: LayoutKeyModel,
        percentWidth: Float,
        placeholders: Map<String, String>,
    ): KeyDef {
        val label = KeyOverrideApplier.substitute(key.label, placeholders)
        val tapAction = KeyOverrideApplier.toRuntime(key.tap)
        val behaviors = mutableSetOf<KeyDef.Behavior>(KeyDef.Behavior.Press(tapAction))
        if (key.swipeUp != null || key.swipeDown != null) {
            behaviors += KeyDef.Behavior.Swipe(
                upAction = key.swipeUp?.let { KeyOverrideApplier.toRuntime(it) },
                downAction = key.swipeDown?.let { KeyOverrideApplier.toRuntime(it) },
            )
        }
        var popups: Array<KeyDef.Popup>? = null
        key.longPress?.let { longPress ->
            when {
                longPress.size == 1 -> behaviors += KeyDef.Behavior.LongPress(
                    KeyOverrideApplier.toRuntime(longPress.single()),
                )
                longPress.size >= 2 -> popups = arrayOf(
                    KeyDef.Popup.Keyboard(
                        label = label,
                        keys = longPress.map { KeyOverrideApplier.toRuntime(it) },
                    ),
                )
            }
        }
        // 空格键与出厂同待遇：viewId 供 BaseKeyboard 的空格文案
        // 下发（updateSpaceKeyText 写方案名）命中，边框走 Special
        val isSpace = tapAction is KeyboardAction.SpaceAction
        return KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = label,
                textSize = TEXT_SIZE,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Normal,
                border = if (isSpace) {
                    KeyDef.Appearance.Border.Special
                } else {
                    KeyDef.Appearance.Border.Default
                },
                viewId = if (isSpace) KeyView.button_space else -1,
                rowSpan = key.rowSpan,
                alignSpanEnd = key.alignSpanEnd,
            ),
            behaviors = behaviors,
            popups = popups,
            id = LayoutModel.logicalId(layoutId, key.stableId),
        )
    }
}
