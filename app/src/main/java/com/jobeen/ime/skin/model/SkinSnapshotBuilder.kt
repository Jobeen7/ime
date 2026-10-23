package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef
import kotlin.math.roundToInt

/**
 * 快照构建（冷路径，定稿一.4 / 三.4.1 / 详设 §2.4）：
 * 校验结果 + 内置默认方案 → 不可变 [SkinSnapshot]。
 *
 * - styles 缺失叶子回落默认方案（部分 styles 合法，见定稿三.3 样例）；
 *   toast 缺省回退合成后的 specialKeyBackground / keyText（定稿十章注）。
 * - 几何：presence 内取文件有效值，否则取默认方案值占位——
 *   resolve() 对非 presence 字段读全局偏好（定稿三.4.2）。
 * - 背景：模糊/采样按内存档位降级（详设 §6.2），文件原值不动。
 *
 * 纯函数：资产 sha 解析与目标尺寸经参数注入，可直接单测。
 */
object SkinSnapshotBuilder {

    /**
     * @param defaultScheme 内置默认方案（样式回落末级）。
     * @param assetSha256 资产逻辑 id → 实体 sha256；查无返回 null。
     * @param targetWidthPx / targetHeightPx 背景解码目标 = 屏幕宽 × 键盘实际高度（详设 §2.6）。
     */
    fun build(
        validated: ValidatedSkin,
        ref: SkinGenRef,
        defaultScheme: KeyboardColors.ColorScheme,
        tier: MemoryTier,
        assetSha256: (String) -> String?,
        targetWidthPx: Int,
        targetHeightPx: Int,
        /** 清单中的资产逻辑 id 顺序（音效接纳的确定性次序，详设 §7.1）。 */
        audioAssetOrder: List<String> = emptyList(),
        /** 资产 id → 音效事实；查无（未入清单/文件缺失）返回 null。 */
        audioFacts: (String) -> AudioAssetFacts? = { null },
        /** 已解析布局（B3 §3.4）：由工厂层引用解析后注入，本类只透传。 */
        resolvedLayout: ResolvedLayout? = null,
        /** 布局解析结论：默认按有无已解析布局推定，工厂层可显式给出失败结论。 */
        layoutResolution: LayoutResolution =
            if (resolvedLayout != null) LayoutResolution.RESOLVED else LayoutResolution.NOT_SET,
    ): SkinSnapshot {
        val policy = DegradationPolicy.forTier(tier)

        var scheme = defaultScheme
        for ((field, value) in validated.styles.colors) {
            scheme = scheme.withStyle(field, value)
        }
        if (validated.styles.surfaceStyle != null) {
            scheme = scheme.copy(surfaceStyle = validated.styles.surfaceStyle)
        }
        // toast 缺省回退（定稿十章注）：以合成后的对应字段为准
        if (StyleField.TOAST_BACKGROUND !in validated.styles.colors) {
            scheme = scheme.copy(toastBackground = scheme.specialKeyBackground)
        }
        if (StyleField.TOAST_TEXT !in validated.styles.colors) {
            scheme = scheme.copy(toastText = scheme.keyText)
        }
        val presence = validated.appearance.presence
        val effective = validated.appearance.effectiveGeometry
        scheme = scheme.copy(
            cornerRadius = if (GeometryField.CORNER_RADIUS in presence) {
                effective.getValue(GeometryField.CORNER_RADIUS)
            } else {
                defaultScheme.cornerRadius
            },
            keyHMargin = if (GeometryField.KEY_H_MARGIN in presence) {
                effective.getValue(GeometryField.KEY_H_MARGIN)
            } else {
                defaultScheme.keyHMargin
            },
            keyVMargin = if (GeometryField.KEY_V_MARGIN in presence) {
                effective.getValue(GeometryField.KEY_V_MARGIN)
            } else {
                defaultScheme.keyVMargin
            },
        )

        val background = validated.appearance.background?.let { spec ->
            val sha = assetSha256(spec.assetId) ?: return@let null
            val effectiveBlur = if (policy.blurDisabled) 0f else spec.blurRadius
            val scaledW = (targetWidthPx * policy.backgroundScale).roundToInt().coerceAtLeast(1)
            val scaledH = (targetHeightPx * policy.backgroundScale).roundToInt().coerceAtLeast(1)
            BackgroundSnapshot(
                assetId = spec.assetId,
                assetSha256 = sha,
                blurRadius = effectiveBlur,
                overlayAlpha = spec.overlayAlpha,
                preparedKey = preparedKey(sha, scaledW, scaledH, effectiveBlur),
            )
        }

        // 音效接纳（详设 §7.1）：被引用集合 = audio.tap + 逐键 sound 字段；
        // 接纳次序 = 清单顺序，未入清单的引用追加在后（facts 恒 null → MISSING）
        val referenced = LinkedHashSet<String>()
        validated.audioRefs["tap"]?.let { referenced += it }
        for (override in validated.keyOverrides.values) {
            override.soundRef?.takeIf { it.isNotEmpty() }?.let { referenced += it }
        }
        val inManifest = audioAssetOrder.filter { it in referenced }
        val notInManifest = referenced.filter { it !in audioAssetOrder }
        val soundBindings = SkinSoundAdmission.admit(
            referencedInOrder = inManifest + notInManifest,
            defaultTapAssetId = validated.audioRefs["tap"],
            facts = audioFacts,
            audioBudgetBytes = policy.audioBudgetBytes,
            skipAll = tier == MemoryTier.EXTREME,
        )

        return SkinSnapshot(
            ref = ref,
            name = validated.metaName,
            colorScheme = scheme,
            geometryPresence = presence,
            effectiveGeometry = effective,
            background = background,
            degradations = policy.degradations(),
            layoutId = validated.layoutId,
            keyOverrides = validated.keyOverrides,
            soundBindings = soundBindings,
            resolvedLayout = resolvedLayout,
            layoutResolution = layoutResolution,
        )
    }

    /** 预处理产物键（详设 §2.6）：bg_<assetHash>_<w>x<h>_b<blur>，blur 取整入键。 */
    fun preparedKey(assetSha256: String, widthPx: Int, heightPx: Int, blurRadius: Float): String =
        "bg_${assetSha256}_${widthPx}x${heightPx}_b${blurRadius.roundToInt()}"

    /** 把一个配色叶子写进方案（嵌套字段走 copy 链）。 */
    fun KeyboardColors.ColorScheme.withStyle(
        field: StyleField,
        value: Int,
    ): KeyboardColors.ColorScheme = when (field) {
        StyleField.KEY_BACKGROUND -> copy(keyBackground = value)
        StyleField.KEY_PRESSED -> copy(keyPressed = value)
        StyleField.KEY_BORDER_STROKE -> copy(keyBorderStroke = value)
        StyleField.SPECIAL_KEY_BACKGROUND -> copy(specialKeyBackground = value)
        StyleField.SPECIAL_KEY_PRESSED -> copy(specialKeyPressed = value)
        StyleField.SPECIAL_KEY_BORDER_STROKE -> copy(specialKeyBorderStroke = value)
        StyleField.ACCENT_KEY_BACKGROUND -> copy(accentKeyBackground = value)
        StyleField.ACCENT_KEY_PRESSED -> copy(accentKeyPressed = value)
        StyleField.ACCENT_KEY_BORDER_STROKE -> copy(accentKeyBorderStroke = value)
        StyleField.KEY_TEXT -> copy(keyText = value)
        StyleField.SPECIAL_KEY_TEXT -> copy(specialKeyText = value)
        StyleField.ACCENT_KEY_TEXT -> copy(accentKeyText = value)
        StyleField.ALT_TEXT -> copy(altText = value)
        StyleField.BACKGROUND -> copy(background = value)
        StyleField.TOAST_BACKGROUND -> copy(toastBackground = value)
        StyleField.TOAST_TEXT -> copy(toastText = value)
        StyleField.PANEL_BACKGROUND -> copy(panel = panel.copy(background = value))
        StyleField.PANEL_TOOLBAR_TEXT -> copy(panel = panel.copy(toolbarText = value))
        StyleField.PANEL_TOOLBAR_ACTIVED -> copy(panel = panel.copy(toolbarActived = value))
        StyleField.PANEL_TOOLBAR_ICON -> copy(panel = panel.copy(toolbarIcon = value))
        StyleField.PANEL_TOOLBAR_PRESSED -> copy(panel = panel.copy(toolbarPressed = value))
        StyleField.PANEL_CANDIDATE_BACKGROUND -> copy(panel = panel.copy(candidateBackground = value))
        StyleField.PANEL_CANDIDATE_TEXT -> copy(panel = panel.copy(candidateText = value))
        StyleField.PANEL_CANDIDATE_INDEX -> copy(panel = panel.copy(candidateIndex = value))
        StyleField.PANEL_CANDIDATE_DIVIDER -> copy(panel = panel.copy(candidateDivider = value))
        StyleField.PINNER_BACKGROUND -> copy(pinner = pinner.copy(background = value))
        StyleField.PINNER_TEXT_COLOR -> copy(pinner = pinner.copy(textColor = value))
        StyleField.PINNER_SECONDARY_TEXT_COLOR -> copy(
            pinner = pinner.copy(secondaryTextColor = value),
        )
    }
}
