package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.BackgroundBlurOverride
import com.jobeen.ime.data.keyboard.theme.GlobalStyleResolver
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.layout.LayoutModel
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
        /**
         * 全局背景模糊覆盖（2026-10-29 外观轮）：工厂层从偏好读入；
         * 默认跟随＝现行口径（降级关模糊时仍为 0，见解析函数）。
         */
        globalBlur: BackgroundBlurOverride = BackgroundBlurOverride(),
        /** 布局解析结论：默认按有无已解析布局推定，工厂层可显式给出失败结论。 */
        layoutResolution: LayoutResolution =
            if (resolvedLayout != null) LayoutResolution.RESOLVED else LayoutResolution.NOT_SET,
        /** 横屏专属布局（2026-10-27 第一组）：工厂层解析后注入，只透传。 */
        resolvedLayoutLandscape: ResolvedLayout? = null,
        /** 横屏绑定解析结论：默认按有无已解析横屏布局推定。 */
        landscapeLayoutResolution: LayoutResolution =
            if (resolvedLayoutLandscape != null) {
                LayoutResolution.RESOLVED
            } else {
                LayoutResolution.NOT_SET
            },
        /** 内置布局覆盖（2026-10-28）：工厂层从皮肤代次目录解析后注入，只透传。 */
        builtinLayouts: Map<String, LayoutModel> = emptyMap(),
        /** 资产逻辑 id → 清单 path 扩展名（小写，无点；工具栏图标寻址用）。 */
        assetExt: (String) -> String? = { null },
    ): SkinSnapshot {
        val policy = DegradationPolicy.forTier(tier)

        val presence = validated.appearance.presence
        val effective = validated.appearance.effectiveGeometry
// 浅色板与深色板同一合成口径（几何同板，不分模式）；
        // 深色板缺失叶子在校验期已按浅色板回填，此处不再回落默认方案。
        // 键盘级 geometry 优先级（几何分流）：keyboardGeometry >
        // appearance > 默认方案占位，浅/深两板同值覆盖（几何不分模式）。
        val keyboardGeometry = validated.keyboardGeometry
        val scheme = applyKeyboardGeometry(
            buildScheme(validated.styles, defaultScheme, presence, effective),
            keyboardGeometry, presence, effective, defaultScheme,
        )
        val darkScheme = validated.darkStyles?.let { dark ->
            applyKeyboardGeometry(
                buildScheme(dark, defaultScheme, presence, effective),
                keyboardGeometry, presence, effective, defaultScheme,
            )
        }

        val background = validated.appearance.background?.let { spec ->
            val sha = assetSha256(spec.assetId) ?: return@let null
            // 模糊生效口径统一走全局样式解析函数（跟随＝现行口径：
            // imageAdjust.blurRadiusDp 优先于 blurRadius，不叠加；
            // 降级关模糊时一律为 0 且调节去模糊，产物键与产物本体一致）。
            val (effectiveBlur, effectiveAdjust) = GlobalStyleResolver.effectiveBlur(
                spec.blurRadius, spec.imageAdjust, policy.blurDisabled, globalBlur,
            )
            val scaledW = (targetWidthPx * policy.backgroundScale).roundToInt().coerceAtLeast(1)
            val scaledH = (targetHeightPx * policy.backgroundScale).roundToInt().coerceAtLeast(1)
            BackgroundSnapshot(
                assetId = spec.assetId,
                assetSha256 = sha,
                blurRadius = effectiveBlur,
                overlayAlpha = spec.overlayAlpha,
                preparedKey = preparedKey(sha, scaledW, scaledH, effectiveBlur, effectiveAdjust),
                imageAdjust = effectiveAdjust,
                targetWidthPx = scaledW,
                targetHeightPx = scaledH,
                specBlurRadius = spec.blurRadius,
                specImageAdjust = spec.imageAdjust,
                blurDisabled = policy.blurDisabled,
                // 视频与静态共段：视频 sha 查无＝无视频（绝不坏引用占位）；
                // 静态资产扩展名供运行期 GIF 动图判定（video 优先于 GIF）。
                backgroundAssetExt = assetExt(spec.assetId) ?: "",
                videoAssetId = spec.videoAssetId,
                videoSha256 = spec.videoAssetId?.let { assetSha256(it) },
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

        // 工具栏构成（2026-10-27）：只做引用转换（id + sha + 扩展名），
        // 不解码；清单查无 sha 的图标引用在此置空（渲染期回退内置图标，
        // 工厂日志层按 spec 与快照差值记 toolbar_icon_unresolved）。
        fun iconRef(assetId: String?): SkinToolbarIconRef? {
            if (assetId == null) return null
            val sha = assetSha256(assetId) ?: return null
            return SkinToolbarIconRef(assetId, sha, assetExt(assetId) ?: "")
        }
        val toolbarSnapshot = SkinToolbarSnapshot(
            present = validated.toolbar.present,
            buttons = validated.toolbar.buttons?.map { button ->
                SkinToolbarButtonSnapshot(
                    button.id, iconRef(button.iconAssetId), button.iconSymbol,
                )
            },
            expandIcon = iconRef(validated.toolbar.expandIconAssetId),
            collapseIcon = iconRef(validated.toolbar.collapseIconAssetId),
            // 符号名原样透传（资产缺失时 iconRef 为 null 亦然）：
            // 渲染期无注册表时回退内置图标，注册表分流接线后解析。
            expandSymbol = validated.toolbar.expandIconSymbol,
            collapseSymbol = validated.toolbar.collapseIconSymbol,
        )

        // G1 单键图片接纳（详设 §7.1 同款口径的轻量版）：被引用集合 =
        // 逐键 image + 前景 image 层；清单在册（assetSha256 查到）即接纳，
        // 查无（未入清单/文件缺失）按字段级回退——装配器不写 image 槽、
        // 渲染走颜色键面并记日志。类别/尺寸已由校验器守过，此处不再复判。
        val imageReferenced = LinkedHashSet<String>()
        for (override in validated.keyOverrides.values) {
            override.image?.let { imageReferenced += it }
            override.pressedImage?.let { imageReferenced += it }
            override.foregroundLayers?.forEach { layer ->
                layer.image?.let { imageReferenced += it }
            }
        }
        val imageAssets = LinkedHashMap<String, String>()
        for (id in imageReferenced) {
            assetSha256(id)?.let { imageAssets[id] = it }
        }

        return SkinSnapshot(
            ref = ref,
            name = validated.metaName,
            colorScheme = scheme,
            darkColorScheme = darkScheme,
            geometryPresence = presence,
            stylePresence = validated.styles.colors.keys,
            darkStylePresence = validated.darkStyles?.colors?.keys,
            effectiveGeometry = effective,
            background = background,
            degradations = policy.degradations(),
            layoutId = validated.layoutId,
            keyboardGeometry = keyboardGeometry,
            fontFamily = validated.fontFamily,
            roleFonts = validated.roleFonts,
            keyShadow = validated.keyShadow,
            keyAnimation = validated.keyAnimation,
            spaceAnimation = validated.spaceAnimation,
            bubbleOverflowCheck = validated.bubbleOverflowCheck,
            swipeThresholdDp = validated.swipeThresholdDp,
            candidateRadiusDp = validated.candidateRadiusDp,
            predictionCloseBg = validated.predictionCloseBg,
            predictionCloseRadiusDp = validated.predictionCloseRadiusDp,
            keyOverrides = validated.keyOverrides,
            keyStyles = validated.keyStyles,
            textSizes = validated.textSizes,
            imageAssets = imageAssets,
            soundBindings = soundBindings,
            resolvedLayout = resolvedLayout,
            layoutResolution = layoutResolution,
            resolvedLayoutLandscape = resolvedLayoutLandscape,
            landscapeLayoutResolution = landscapeLayoutResolution,
            builtinLayouts = builtinLayouts,
            toolbar = toolbarSnapshot,
            notifications = validated.notifications,
            conditionStyles = validated.conditionStyles,
            symbolBar = validated.symbolBar,
        )
    }

    /**
     * 单板合成（浅色板与深色板共用）：令牌表合成到默认方案之上；
     * toast 缺省回退合成后的 specialKeyBackground / keyText
     * （定稿十章注）；几何 presence 内取文件有效值，否则取
     * 默认方案值占位（resolve() 对非 presence 字段读全局偏好）。
     */
    private fun buildScheme(
        styles: StylesSpec,
        defaultScheme: KeyboardColors.ColorScheme,
        presence: Set<GeometryField>,
        effective: Map<GeometryField, Float>,
    ): KeyboardColors.ColorScheme {
        var scheme = defaultScheme
        for ((field, value) in styles.colors) {
            scheme = scheme.withStyle(field, value)
        }
        if (styles.surfaceStyle != null) {
            scheme = scheme.copy(surfaceStyle = styles.surfaceStyle)
        }
        if (StyleField.TOAST_BACKGROUND !in styles.colors) {
            scheme = scheme.copy(toastBackground = scheme.specialKeyBackground)
        }
        if (StyleField.TOAST_TEXT !in styles.colors) {
            scheme = scheme.copy(toastText = scheme.keyText)
        }
        return scheme.copy(
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
    }

    private fun applyKeyboardGeometry(
        scheme: KeyboardColors.ColorScheme,
        keyboardGeometry: KeyboardGeometrySpec,
        presence: Set<GeometryField>,
        effective: Map<GeometryField, Float>,
        defaultScheme: KeyboardColors.ColorScheme,
    ): KeyboardColors.ColorScheme = scheme.copy(
        cornerRadius = when {
            KeyboardGeometryField.KEY_RADIUS in keyboardGeometry.presence ->
                keyboardGeometry.values.getValue(KeyboardGeometryField.KEY_RADIUS)
            GeometryField.CORNER_RADIUS in presence ->
                effective.getValue(GeometryField.CORNER_RADIUS)
            else -> scheme.cornerRadius
        },
        keyHMargin = when {
            KeyboardGeometryField.KEY_H_GAP in keyboardGeometry.presence ->
                keyboardGeometry.values.getValue(KeyboardGeometryField.KEY_H_GAP)
            GeometryField.KEY_H_MARGIN in presence ->
                effective.getValue(GeometryField.KEY_H_MARGIN)
            else -> scheme.keyHMargin
        },
        keyVMargin = when {
            KeyboardGeometryField.KEY_V_GAP in keyboardGeometry.presence ->
                keyboardGeometry.values.getValue(KeyboardGeometryField.KEY_V_GAP)
            GeometryField.KEY_V_MARGIN in presence ->
                effective.getValue(GeometryField.KEY_V_MARGIN)
            else -> scheme.keyVMargin
        },
    )

    /**
     * 预处理产物键（详设 §2.6）：bg_<assetHash>_<w>x<h>_b<blur>，
     * blur 取整入键；有调节时追加调节后缀（无调节时后缀为空，
     * 旧产物键逐字节不变，存量产物可复用）。
     */
    fun preparedKey(
        assetSha256: String,
        widthPx: Int,
        heightPx: Int,
        blurRadius: Float,
        imageAdjust: ImageAdjust? = null,
    ): String =
        "bg_${assetSha256}_${widthPx}x${heightPx}_b${blurRadius.roundToInt()}" +
            (imageAdjust?.preparedKeySuffix() ?: "")

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
