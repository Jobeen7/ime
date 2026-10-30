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
 * - 宽度（裁定 B1，2026-10-29 item-1 D4 修订）：percentWidth 只在
 *   构建期按原始权重算比例（§2.4 公式），原始权重永不回写文件；
 *   分母是布局内最大行权重和（不再按行内和归一化拉伸短行），
 *   短行按缺口补一个空白键。
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
        /**
         * 横屏构建（2026-10-27 并入项）：为 true 时逐键取
         * landscapeWidthWeight（null 时跟竖屏权重），再按行内
         * 比例归一化；竖屏路径参数缺省 false，行为与旧版一致。
         */
        landscape: Boolean = false,
    ): List<List<KeyDef>> {
        // 行宽基准（2026-10-29 item-1 D4）：布局内最大行权重和
        // W_max 为全布局统一分母，逐行 percentWidth = w / W_max；
        // 旧版按行内和归一化会把短行拉伸铺满，新版短行按建成
        // 键宽和的缺口补一个空白键（无行为、无 id、无文本、透明
        // 无边框，占位不交互），行和相等的布局输出与旧版逐键一致。
        // 内置覆写路径（BuiltinLayoutApplier）不在本项内：其行
        // 引用出厂键、出厂网格行为保持不变。
        val rowWeights = layout.rows.map { row ->
            row.map { key ->
                if (landscape) key.landscapeWidthWeight ?: key.widthWeight else key.widthWeight
            }
        }
        val maxWeight = rowWeights.maxOfOrNull { it.sum() } ?: 0.0
        if (maxWeight <= 0.0) {
            // 全空或全零权重布局：校验器外防御——等分铺满，永不崩。
            return layout.rows.map { row ->
                row.mapIndexedNotNull { _, key ->
                    buildKey(
                        layoutId = layout.layoutId,
                        key = key,
                        percentWidth = if (row.isEmpty()) 0f else 1f / row.size,
                        placeholders = placeholders,
                    )
                }
            }
        }
        return layout.rows.mapIndexed { rowIndex, row ->
            val weights = rowWeights[rowIndex]
            val built = row.mapIndexedNotNull { index, key ->
                buildKey(
                    layoutId = layout.layoutId,
                    key = key,
                    percentWidth = (weights[index] / maxWeight).toFloat(),
                    placeholders = placeholders,
                )
            }
            if (built.isEmpty()) return@mapIndexed built
            val shortfall =
                1f - built.sumOf { it.appearance.percentWidth.toDouble() }.toFloat()
            if (shortfall > BLANK_EPSILON) built + blankKey(shortfall) else built
        }
    }

    /** 短行补位阈值：缺口小于它时视为浮点误差，不补。 */
    private const val BLANK_EPSILON = 1e-4f

    /**
     * 空白补位键：Text 外观、空文本、透明（Variant.None）无边框、
     * 无行为、无 id——渲染占位、不触发动作、不进覆写与手势装配。
     */
    private fun blankKey(percentWidth: Float): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = "",
            textSize = TEXT_SIZE,
            percentWidth = percentWidth,
            variant = KeyDef.Appearance.Variant.None,
            border = KeyDef.Appearance.Border.Off,
        ),
        behaviors = emptySet(),
        id = null,
    )

    private fun buildKey(
        layoutId: String,
        key: LayoutKeyModel,
        percentWidth: Float,
        placeholders: Map<String, String>,
    ): KeyDef? {
        // tap 为 null 只可能来自内置布局文件（校验器 allowMissingTap），
        // 自创布局构建不走此路；防御性跳键，永不崩溃。
        val tapAction = key.tap?.let { KeyOverrideApplier.toRuntime(it) } ?: return null
        val label = KeyOverrideApplier.substitute(key.label, placeholders)
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
        ).also {
            // 样式原始引用随基础结构携带（2026-10-27 并入项）：
            // 解析在键盘构建期由覆写装配器按快照完成，本处不解析。
            it.skinStyleRef = key.styleRef
            it.skinStyleOverrides = key.styleOverrides?.mapKeys { (role, _) -> role.jsonName }
        }
    }
}
