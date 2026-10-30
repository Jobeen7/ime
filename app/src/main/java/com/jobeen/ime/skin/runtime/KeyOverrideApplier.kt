package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.DynamicTextSource
import com.jobeen.ime.skin.model.KeyForegroundLayer
import com.jobeen.ime.skin.icon.IconAlias
import com.jobeen.ime.skin.icon.MaterialSymbols
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.KeyTextRole
import com.jobeen.ime.skin.model.ResolvedForegroundLayer
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.model.WhitelistAction

/**
 * 覆写装配器（详设 §4.2 + G1 单键模型）：键盘构建期把快照中的
 * 逐键覆写一次性合并进出厂布局，产出普通 KeyDef 列表——按键热路径
 * 零新增查表、零解析。手势引擎只消费 Behavior/Popup 数据，本类
 * 不触碰任何阈值与分发逻辑（定稿一.3 数据 Provider 口径）。
 *
 * 纪律：
 * - 无覆写命中且无全局音效、无样式表、无分档字号时恒等返回输入引用；
 * - 命中才复制 KeyDef，出厂静态 Layout 对象绝不原地修改；
 * - Repeat / DoubleTap / SwipeMoveCursor / Popup.Menu 等不可
 *   覆写槽一律原样带入副本（详设 §3.3-4）。
 *
 * G1 装配规则（字段级回退，永不拒整键、不崩溃）：
 * - 左右滑：与上下滑同一白名单，合并进同一 Behavior.Swipe
 *   （四方向各守各的出厂/覆写值，互不覆盖）。
 * - 图片键面：仅快照已接纳资产写 skinImageAssetId；未接纳时
 *   记 WARN 日志并回退颜色键面（渲染层解码失败亦同链）。
 * - 分档字号：逐键 textSizes > 皮肤级 textSizes > 出厂字号；
 *   符号档按显示文本判定（单字符且非字母数字）。
 * - 几何宽：行内归一化（行宽和不变）；跨行键（rowSpan > 1）
 *   的宽覆写不生效并记 WARN，其余维照常生效。
 * - 副文字：Text 键转 AltText 呈现、AltText 键替换副显；
 *   Image/ImageText/SidePannel 无副文字槽，忽略并记 WARN。
 * - 动态文字：构建期快照值（dynamicTexts 参数）；无值时回退
 *   静态 label；动态值原样使用、不做占位替换、绝不进日志。
 *   运行时跟随：副本另记来源与静态回退，键盘层在候选/组字
 *   变化时以 resolveDynamicDisplayText 刷新主显文本。
 * - 前景层：text 层颜色非法时回落副文字色；image 层未接纳
 *   整层丢弃并记 WARN；层序 = 数组序，后层压前层。
 */
object KeyOverrideApplier {

    /**
     * 动态文字刷新取值（纯函数）：动态值非空取动态值，否则回退
     * 静态文本；动态值本身绝不记日志（调用方只传值、不记日志）。
     */
    fun resolveDynamicDisplayText(
        fallback: String,
        source: String?,
        values: Map<String, String>,
    ): String {
        val value = source?.let { values[it] }?.takeIf { it.isNotEmpty() }
        return value ?: fallback
    }

    /** 逐键音效解析结果（三级链：逐键 → 全局 tap → 出厂，详设 §7.2）。 */
    sealed interface SoundPlan {
        /** 沿用出厂行为（标准点按音）。 */
        data object Inherit : SoundPlan

        /** 播放已接纳的皮肤音效资产。 */
        data class Play(val assetId: String) : SoundPlan

        /** 显式静音（覆写 sound:""）。 */
        data object Muted : SoundPlan
    }

    /**
     * @param layoutName 布局 NAME（仅用于诊断日志）。
     * @param placeholders 占位符预计算值（[PlaceholderValues.current]）。
     * @param dynamicTexts 动态文字构建期快照（来源 → 值；不传 =
     *   全部回退静态 label；值本身绝不记日志）。
     *
     * 样式装配（2026-10-27 并入项）：除 keyMappings 覆写外，
     * 键自带的样式引用（自创布局键节点的 style/styleOverrides）
     * 也在此按快照解析——快照已含派生展开后的令牌方案，解析
     * 目标即该方案（链路：派生 → 快照 → 样式引用）。
     */
    fun apply(
        layoutName: String,
        factoryLayout: List<List<KeyDef>>,
        snapshot: SkinSnapshot?,
        placeholders: Map<String, String>,
        /**
         * 样式令牌解析目标方案（2026-10-27 第一组）：调用方须
         * 传入当前模式下的生效板（snapshot.effectiveColorScheme）；
         * 缺省 null = 沿用快照浅色板（旧行为，单色板皮肤无差异，
         * 双色板下未传参的调用方在深色模式会解析到浅色板——
         * 全部生产调用方均已传参，缺省仅供旧测试兼容）。
         */
        scheme: KeyboardColors.ColorScheme? = null,
        dynamicTexts: Map<DynamicTextSource, String> = emptyMap(),
        /**
         * 接线轮 R1：当前锁定的通知表（目标样式名 → 通知；构建
         * 期由 SkinRuntimeState 供给）。命中键的样式名时在
         * copyWith 内叠加背景／前景／动作／上下滑动作。
         */
        notifications: Map<String, com.jobeen.ime.skin.notify.SkinNotification> = emptyMap(),
        /** 接线轮 R2：条件样式门控集（见 KeyStyleResolver）。 */
        gatedStyleNames: Set<String> = emptySet(),
        /** 接线轮 R2：当前条件成立的样式名集合。 */
        activeStyleNames: Set<String> = emptySet(),
        /**
         * 拟态开关（2026-10-29 item-1）：助记关 = 皮肤附加小字
         * （subLabel/subLabelTop）装配期压制；装饰关 = 前景叠加层
         * 装配期压制（阴影另在视图层读偏好）。缺省全开（旧行为）。
         */
        mnemonicEnabled: Boolean = true,
        decorationEnabled: Boolean = true,
    ): List<List<KeyDef>> {
        if (snapshot == null) return factoryLayout
        val overrides = snapshot.keyOverrides
        val hasGlobalSound = snapshot.soundBindings.defaultTapAssetId != null
        // 按键动效（2026-10-29 item-1）：降级关闭时规格直接作废；
        // 空格键取空格专属规格（写了节点即取代通用规格）、其余键
        // 取通用规格；规格非空的键必须复制（即使无其他覆写）。
        val animationsOff =
            com.jobeen.ime.skin.model.Degradation.ANIMATIONS_DISABLED in snapshot.degradations
        val keyAnim = snapshot.keyAnimation.takeIf { it.enabled && !animationsOff }
        val spaceAnim = (snapshot.spaceAnimation ?: snapshot.keyAnimation)
            .takeIf { it.enabled && !animationsOff }
        // 样式表非空时也须走装配（自创键可能只带样式引用、无覆写）。
        // G1：皮肤级分档字号非空时同样全局走装配（逐键字号替换）。
        // 接线轮 R1：通知表非空时同样走装配（锁定可能命中样式引用键）。
        // 动效规格非空时同样走装配（全部键须写动效规格）。
        if (overrides.isEmpty() && !hasGlobalSound && snapshot.keyStyles.isEmpty() &&
            snapshot.textSizes.isEmpty() && notifications.isEmpty() &&
            keyAnim == null && spaceAnim == null
        ) {
            return factoryLayout
        }

        val effectiveScheme = scheme ?: snapshot.colorScheme
        var applied = 0
        var mnemonicSuppressed = 0
        var decorationSuppressed = 0
        val result = factoryLayout.map { row ->
            val widths = renormalizedWidths(row, overrides)
            row.mapIndexed { index, def ->
                val rawOverride = def.id?.let { overrides[it] }?.takeIf { it.hasEffect() }
                // 拟态开关压制（冷路径）：助记关去附加小字、装饰关
                // 去前景层；压制后无剩余效果的覆写按无覆写处理。
                val override = rawOverride?.let {
                    var suppressed = it
                    if (!mnemonicEnabled &&
                        (it.subLabel != null || it.subLabelTop != null)
                    ) {
                        mnemonicSuppressed++
                        suppressed = suppressed.copy(subLabel = null, subLabelTop = null)
                    }
                    if (!decorationEnabled && !it.foregroundLayers.isNullOrEmpty()) {
                        decorationSuppressed++
                        suppressed = suppressed.copy(foregroundLayers = null)
                    }
                    suppressed.takeIf { o -> o.hasEffect() }
                }
                val sound = resolveSound(override, snapshot.soundBindings)
                val style = resolveKeyStyle(
                    layoutName, def, override, snapshot, effectiveScheme,
                    gatedStyleNames, activeStyleNames,
                )
                // 接线轮 R1：通知按样式名匹配（布局键自带 styleRef
                // 或覆写 styleRef；无样式引用键不命中）。
                val styleRef = override?.styleRef ?: def.skinStyleRef
                val latched = styleRef?.let { notifications[it] }
                // 行宽归一化改变了本键宽度时也必须复制，否则归一化结果
                // 会随未命中键的原样返回而丢失（行宽和不再守恒）。
                val widthChanged = widths[index] != def.appearance.percentWidth
                val animForKey = if (def.id in com.jobeen.ime.skin.model.SPACE_KEY_IDS) {
                    spaceAnim
                } else {
                    keyAnim
                }
                val needsCopy = override != null || sound != SoundPlan.Inherit ||
                    style != null || widthChanged || latched != null ||
                    animForKey != null ||
                    (snapshot.textSizes.isNotEmpty() &&
                        def.appearance is KeyDef.Appearance.Text)
                if (!needsCopy) {
                    def
                } else {
                    applied++
                    copyWith(
                        def = def,
                        override = override,
                        placeholders = placeholders,
                        dynamicTexts = dynamicTexts,
                        sound = sound,
                        style = style,
                        tiers = snapshot.textSizes,
                        imageAssets = snapshot.imageAssets,
                        scheme = effectiveScheme,
                        percentWidth = widths[index],
                        layoutName = layoutName,
                        latched = latched,
                        keyAnimation = animForKey,
                    )
                }
            }
        }
        if (applied == 0) return factoryLayout
        DiagLog.d(
            "keymap", "override_applied_to_keyboard",
            mapOf("layout" to layoutName, "appliedCount" to applied),
        )
        if (mnemonicSuppressed > 0 || decorationSuppressed > 0) {
            DiagLog.d(
                "keymap", "hint_layers_suppressed",
                mapOf(
                    "layout" to layoutName,
                    "mnemonicSuppressed" to mnemonicSuppressed,
                    "decorationSuppressed" to decorationSuppressed,
                ),
            )
        }
        return result
    }

    /**
     * 行宽归一化（G1 几何宽不变量）：非跨行键按 widthFactor 缩放
     * 后按原行非跨行键宽和归一化；跨行键宽度恒为出厂值（其宽覆写
     * 已在 copyWith 记 WARN）。无宽覆写的行逐字节返回出厂宽度
     * （不做浮点重算，避免无谓漂移）。
     */
    private fun renormalizedWidths(
        row: List<KeyDef>,
        overrides: Map<String, KeyOverride>,
    ): List<Float> {
        val original = row.map { it.appearance.percentWidth }
        var hasWidthOverride = false
        val scaled = row.map { def ->
            val factor = def.id?.let { overrides[it] }?.geometry?.widthFactor
            if (def.appearance.rowSpan > 1 || factor == null || factor == 1f) {
                def.appearance.percentWidth
            } else {
                hasWidthOverride = true
                def.appearance.percentWidth * factor
            }
        }
        if (!hasWidthOverride) return original
        val nonSpan = row.indices.filter { row[it].appearance.rowSpan <= 1 }
        val sumOrig = nonSpan.sumOf { original[it].toDouble() }.toFloat()
        val sumScaled = nonSpan.sumOf { scaled[it].toDouble() }.toFloat()
        if (sumOrig <= 0f || sumScaled <= 0f) return original
        val ratio = sumOrig / sumScaled
        return row.mapIndexed { index, def ->
            if (def.appearance.rowSpan > 1) original[index] else scaled[index] * ratio
        }
    }

    /**
     * 单键样式解析（含警告日志）：优先级 = 键级 styleOverrides >
     * 覆写 styleRef 的表项 > 布局键自带 styleRef 的表项 >
     * 布局键自带 styleOverrides。无样式信息返回 null（不复制）。
     */
    private fun resolveKeyStyle(
        layoutName: String,
        def: KeyDef,
        override: KeyOverride?,
        snapshot: SkinSnapshot,
        scheme: KeyboardColors.ColorScheme,
        gatedStyleNames: Set<String> = emptySet(),
        activeStyleNames: Set<String> = emptySet(),
    ): KeyStyleResolver.ResolvedKeyStyle? {
        val styleRef = override?.styleRef ?: def.skinStyleRef
        val overrides = mergeStyleOverrides(def.skinStyleOverrides, override?.styleOverrides)
        if (styleRef == null && overrides.isNullOrEmpty()) return null
        val roleOverrides = overrides?.mapNotNull { (name, value) ->
            KeyStyleRole.byJsonName(name)?.let { it to value }
        }?.toMap()
        val resolution = KeyStyleResolver.resolve(
            styleRef = styleRef,
            overrides = roleOverrides,
            keyStyles = snapshot.keyStyles,
            scheme = scheme,
            gatedStyleNames = gatedStyleNames,
            activeStyleNames = activeStyleNames,
        )
        if (resolution.warnings.isNotEmpty()) {
            DiagLog.w(
                "keymap", "key_style_fallback",
                mapOf(
                    "layout" to layoutName,
                    "keyId" to def.id,
                    "style" to styleRef,
                    "warnings" to resolution.warnings.joinToString(";"),
                ),
            )
        }
        return resolution.style
    }

    /** 布局键自带覆盖（低）+ 覆写覆盖（高）按角色合并。 */
    private fun mergeStyleOverrides(
        base: Map<String, String>?,
        high: Map<KeyStyleRole, String>?,
    ): Map<String, String>? {
        if (base.isNullOrEmpty() && high.isNullOrEmpty()) return null
        val merged = LinkedHashMap<String, String>()
        base?.let { merged.putAll(it) }
        high?.forEach { (role, value) -> merged[role.jsonName] = value }
        return merged
    }

    /**
     * 覆写是否含有效内容：全为 sequence 占位的覆写不产生任何
     * 运行时差异（占位整槽回退出厂，详设 §3.6），按未命中处理。
     * 纯样式覆写（style/styleOverrides）同样算有效内容。
     */
    private fun KeyOverride.hasEffect(): Boolean {
        if (label != null || soundRef != null) return true
        if (popupChars != null || icon != null) return true
        if (styleRef != null || !styleOverrides.isNullOrEmpty()) return true
        if (image != null || subLabel != null || subLabelTop != null || dynamicText != null) return true
        if (!textSizes.isNullOrEmpty()) return true
        if (geometry != null && !geometry.isEmpty) return true
        if (!foregroundLayers.isNullOrEmpty()) return true
        if (tap != null && tap != SkinAction.SequencePlaceholder) return true
        if (swipeUp != null && swipeUp != SkinAction.SequencePlaceholder) return true
        if (swipeDown != null && swipeDown != SkinAction.SequencePlaceholder) return true
        if (swipeLeft != null && swipeLeft != SkinAction.SequencePlaceholder) return true
        if (swipeRight != null && swipeRight != SkinAction.SequencePlaceholder) return true
        if (longPress != null && longPress.any { it != SkinAction.SequencePlaceholder }) return true
        return false
    }

    /** 音效三级解析（详设 §7.2-1）：逐键未接纳时按字段级回退到全局级。 */
    fun resolveSound(override: KeyOverride?, bindings: SoundBindings): SoundPlan {
        val ref = override?.soundRef
        if (ref != null) {
            if (ref.isEmpty()) return SoundPlan.Muted
            if (ref in bindings.admitted) return SoundPlan.Play(ref)
            // 引用未接纳（缺失/被剔）→ 回退全局级，不静音不报错
        }
        val tap = bindings.defaultTapAssetId
        return if (tap != null) SoundPlan.Play(tap) else SoundPlan.Inherit
    }

    /** 符号档判定：主显文本为单个非字母数字字符（标点类键）。 */
    fun isSymbolKey(displayText: String): Boolean {
        if (displayText.length != 1) return false
        val c = displayText.single()
        return !c.isLetterOrDigit()
    }

    private fun copyWith(
        def: KeyDef,
        override: KeyOverride?,
        placeholders: Map<String, String>,
        dynamicTexts: Map<DynamicTextSource, String>,
        sound: SoundPlan,
        style: KeyStyleResolver.ResolvedKeyStyle?,
        tiers: Map<KeyTextRole, Float>,
        imageAssets: Map<String, String>,
        scheme: com.jobeen.ime.data.keyboard.theme.KeyboardColors.ColorScheme,
        percentWidth: Float,
        layoutName: String,
        /** 接线轮 R1：本键样式名命中的锁定通知（null = 无命中）。 */
        latched: com.jobeen.ime.skin.notify.SkinNotification? = null,
        /** 按键动效（2026-10-29 item-1）：本键生效规格（null = 无动效）。 */
        keyAnimation: com.jobeen.ime.skin.model.KeyAnimationSpec? = null,
    ): KeyDef {
        // ---- 主显文本：动态快照 > 静态 label（占位替换） > 出厂 ----
        // 静态文本另存为动态回退（运行时刷新无值时回落此处）。
        val factoryText = displayTextOf(def.appearance)
        val staticText = override?.label?.let { substitute(it, placeholders) } ?: factoryText
        val dynamicValue = override?.dynamicText?.let { dynamicTexts[it] }
            ?.takeIf { it.isNotEmpty() }
        val mainText = dynamicValue ?: staticText
        val isSymbol = isSymbolKey(mainText)
        val mainRole = if (isSymbol) KeyTextRole.SYMBOL else KeyTextRole.MAIN
        val mainSize = override?.textSizes?.get(mainRole) ?: tiers[mainRole]
        val altSize = override?.textSizes?.get(KeyTextRole.SUB) ?: tiers[KeyTextRole.SUB]

        // ---- 副文字：Text 转 AltText、AltText 替换副显、其余外观忽略 ----
        // （ImageText 是 Text 子类但无副文字槽，同样属忽略之列。）
        // 顶部小字与底部副文字同槽口径：两者任一存在即走 AltText。
        val subLabel = override?.subLabel
        val subLabelTop = override?.subLabelTop
        val supportsSubLabel = def.appearance is KeyDef.Appearance.AltText ||
            (def.appearance is KeyDef.Appearance.Text &&
                def.appearance !is KeyDef.Appearance.ImageText)
        if (subLabel != null && !supportsSubLabel) {
            DiagLog.w(
                "keymap", "sublabel_unsupported_appearance",
                mapOf("layout" to layoutName, "keyId" to def.id),
            )
        }
        if (subLabelTop != null && !supportsSubLabel) {
            DiagLog.w(
                "keymap", "sublabel_top_unsupported_appearance",
                mapOf("layout" to layoutName, "keyId" to def.id),
            )
        }
        if (override?.geometry?.widthFactor != null && def.appearance.rowSpan > 1) {
            DiagLog.w(
                "keymap", "key_width_span_ignored",
                mapOf("layout" to layoutName, "keyId" to def.id),
            )
        }

        // ---- 图片键面：仅已接纳资产写槽，未接纳记日志回退颜色键面 ----
        val imageAssetId = override?.image?.let { id ->
            if (id in imageAssets) {
                id
            } else {
                DiagLog.w(
                    "keymap", "key_image_fallback",
                    mapOf("layout" to layoutName, "keyId" to def.id, "asset" to id),
                )
                null
            }
        }

        // ---- 前景层：颜色解析、image 层接纳判定（未接纳整层丢弃） ----
        val foreground = resolveForeground(
            override?.foregroundLayers, tiers, override?.textSizes, imageAssets, scheme,
            layoutName, def.id,
        )

        val appearance = rebuildAppearance(
            appearance = def.appearance,
            mainText = mainText,
            mainSize = mainSize,
            altSize = altSize,
            subLabel = subLabel,
            subLabelTop = subLabelTop,
            percentWidth = percentWidth,
            style = style,
            imageAssetId = imageAssetId,
            geometry = override?.geometry,
            foreground = foreground,
            keyAnimation = keyAnimation,
        )

        var behaviors = def.behaviors
        var extraPopup: KeyDef.Popup.Keyboard? = null
        if (override != null) {
            override.tap?.let { tap ->
                if (tap != SkinAction.SequencePlaceholder) {
                    behaviors = behaviors.replaceSlot<KeyDef.Behavior.Press> {
                        KeyDef.Behavior.Press(toRuntime(tap))
                    }
                }
            }
            val upWritten = override.swipeUp?.takeIf { it != SkinAction.SequencePlaceholder }
            val downWritten = override.swipeDown?.takeIf { it != SkinAction.SequencePlaceholder }
            val leftWritten = override.swipeLeft?.takeIf { it != SkinAction.SequencePlaceholder }
            val rightWritten = override.swipeRight?.takeIf { it != SkinAction.SequencePlaceholder }
            if (upWritten != null || downWritten != null ||
                leftWritten != null || rightWritten != null
            ) {
                val factorySwipe = behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().firstOrNull()
                val merged = KeyDef.Behavior.Swipe(
                    upAction = upWritten?.let { toRuntime(it) } ?: factorySwipe?.upAction,
                    downAction = downWritten?.let { toRuntime(it) } ?: factorySwipe?.downAction,
                    leftAction = leftWritten?.let { toRuntime(it) } ?: factorySwipe?.leftAction,
                    rightAction = rightWritten?.let { toRuntime(it) } ?: factorySwipe?.rightAction,
                )
                behaviors = behaviors.replaceSlot<KeyDef.Behavior.Swipe> { merged }
            }
            override.longPress?.let { longPress ->
                val effective = longPress.filter { it != SkinAction.SequencePlaceholder }
                if (effective.size == 1) {
                    behaviors = behaviors.replaceSlot<KeyDef.Behavior.LongPress> {
                        KeyDef.Behavior.LongPress(toRuntime(effective.single()))
                    }
                } else if (effective.size >= 2) {
                    // 多项长按（定稿四.7）：只装 Popup.Keyboard 候选条——
                    // 视图的长按监听是单槽，若同时装 LongPress 行为，
                    // 后注册的行为监听会遮蔽候选条监听、条目不可达；
                    // 单项才退化为 LongPress 直接执行（详设 §4.2-2）。
                    behaviors = behaviors.filterNot { it is KeyDef.Behavior.LongPress }.toSet()
                    extraPopup = KeyDef.Popup.Keyboard(
                        label = mainText,
                        keys = effective.map { toRuntime(it) },
                    )
                }
                // effective 为空（全占位）→ 整槽回退出厂，不动 behaviors
            }
        }

        // 接线轮 R1：锁定通知叠加（状态层，优先于静态覆写）：
        // 背景／前景只改本键已解析出的样式色（非法值回退原色并记
        // WARN，只记目标名与键 id）；动作／上下滑只改通知声明的
        // 方向（左右滑与出厂／覆写值原样保留）。无命中时零改动。
        if (latched != null) {
            latched.backgroundStyle?.let { raw ->
                val color = KeyStyleResolver.resolveValue(raw, scheme)
                if (color != null) {
                    appearance.skinFaceColor = color
                } else {
                    DiagLog.w(
                        "keymap", "notification_style_fallback",
                        mapOf(
                            "layout" to layoutName, "keyId" to def.id,
                            "target" to (override?.styleRef ?: def.skinStyleRef),
                        ),
                    )
                }
            }
            latched.foregroundStyle?.let { raw ->
                val color = KeyStyleResolver.resolveValue(raw, scheme)
                if (color != null) {
                    appearance.skinTextColor = color
                } else {
                    DiagLog.w(
                        "keymap", "notification_style_fallback",
                        mapOf(
                            "layout" to layoutName, "keyId" to def.id,
                            "target" to (override?.styleRef ?: def.skinStyleRef),
                        ),
                    )
                }
            }
            latched.action?.let { action ->
                if (action != SkinAction.SequencePlaceholder) {
                    behaviors = behaviors.replaceSlot<KeyDef.Behavior.Press> {
                        KeyDef.Behavior.Press(toRuntime(action))
                    }
                }
            }
            val upWritten = latched.swipeUpAction
                ?.takeIf { it != SkinAction.SequencePlaceholder }
            val downWritten = latched.swipeDownAction
                ?.takeIf { it != SkinAction.SequencePlaceholder }
            if (upWritten != null || downWritten != null) {
                val factorySwipe = behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().firstOrNull()
                val merged = KeyDef.Behavior.Swipe(
                    upAction = upWritten?.let { toRuntime(it) } ?: factorySwipe?.upAction,
                    downAction = downWritten?.let { toRuntime(it) } ?: factorySwipe?.downAction,
                    leftAction = factorySwipe?.leftAction,
                    rightAction = factorySwipe?.rightAction,
                )
                behaviors = behaviors.replaceSlot<KeyDef.Behavior.Swipe> { merged }
            }
            DiagLog.d(
                "keymap", "notification_applied",
                mapOf(
                    "layout" to layoutName, "keyId" to def.id,
                    "target" to (override?.styleRef ?: def.skinStyleRef),
                ),
            )
        }

        // 弹出字符（参考项）：走现有 KeyboardPopup——与多项
        // 长按的候选条同一视图、同一长按/滑动选中/抬手提交链路。
        // 优先级：多项长按候选条 > 弹出字符 > 出厂 Popup.Keyboard；
        // 弹出字符安装时须摘除 LongPress 行为（行为监听后注册会
        // 遮蔽弹窗的长按监听，与多项长按一案同理），被摘除的长按
        // 动作记 WARN 日志（只记键 id，不记动作原文）。
        val popupCharsPopup: KeyDef.Popup.Keyboard? =
            if (extraPopup == null && override?.popupChars != null) {
                KeyDef.Popup.Keyboard(
                    label = override.label?.let { substitute(it, placeholders) }
                        ?: displayTextOf(def.appearance),
                    keys = override.popupChars.map { KeyboardAction.KeySequenceAction(it) },
                )
            } else {
                null
            }
        if (extraPopup != null && override?.popupChars != null) {
            DiagLog.w(
                "keymap", "popup_chars_superseded_by_long_press_popup",
                mapOf("layout" to layoutName, "keyId" to def.id),
            )
        }
        if (popupCharsPopup != null) {
            val displaced = behaviors.any { it is KeyDef.Behavior.LongPress }
            behaviors = behaviors.filterNot { it is KeyDef.Behavior.LongPress }.toSet()
            if (displaced) {
                DiagLog.w(
                    "keymap", "popup_chars_displaces_long_press",
                    mapOf("layout" to layoutName, "keyId" to def.id),
                )
            }
        }

        val factoryPopups = def.popups
        val popups: Array<KeyDef.Popup>? = when {
            popupCharsPopup != null -> {
                // 弹出字符取代出厂 Popup.Keyboard；Preview/Menu 原样保留。
                val base = factoryPopups?.filterNot { it is KeyDef.Popup.Keyboard }
                    ?: emptyList()
                (base + popupCharsPopup).toTypedArray()
            }
            extraPopup == null -> factoryPopups
            factoryPopups == null -> arrayOf(extraPopup)
            else -> Array(factoryPopups.size + 1) { index ->
                // 出厂弹窗在前，候选条监听最后注册胜出
                if (index < factoryPopups.size) factoryPopups[index] else extraPopup
            }
        }

        return KeyDef(
            appearance = appearance,
            behaviors = behaviors,
            popups = popups,
            id = def.id,
        ).also { copy ->
            when (sound) {
                is SoundPlan.Play -> copy.skinSoundAssetId = sound.assetId
                SoundPlan.Muted -> copy.skinSoundMuted = true
                SoundPlan.Inherit -> Unit
            }
            // 样式原始引用随副本保留（再解析幂等）。
            copy.skinStyleRef = def.skinStyleRef
            copy.skinStyleOverrides = def.skinStyleOverrides
            // G1 动态文字运行时跟随：来源与静态回退随副本保留，
            // 键盘层据此在候选/组字变化时刷新主显文本。
            override?.dynamicText?.let { source ->
                copy.appearance.skinDynamicText = source.jsonName
                copy.appearance.skinDynamicFallback = staticText
            }
            // 单键图标：先经别名层规范化（重点候选
            // 8-7：逻辑名→注册表名，注册表名直通），注册表内才写外观，
            // 未知名回退原文本/图片并记日志（只记作者原图标名）。
            override?.icon?.let { name ->
                val canonical = IconAlias.resolve(name, MaterialSymbols.NAMES.keys)
                if (canonical != null && MaterialSymbols.isKnown(canonical)) {
                    copy.appearance.skinIconSymbol = canonical
                } else {
                    DiagLog.w(
                        "keymap", "key_icon_unknown",
                        mapOf("layout" to layoutName, "keyId" to def.id, "icon" to name),
                    )
                }
            }
            // 按压态独立图标（重点候选 8-2）：同口径规范化＋注册表校验，
            // 未知名记日志回退常态图标（渲染切换另要求常态图标已配）。
            override?.pressedIcon?.let { name ->
                val canonical = IconAlias.resolve(name, MaterialSymbols.NAMES.keys)
                if (canonical != null && MaterialSymbols.isKnown(canonical)) {
                    copy.appearance.skinPressedIconSymbol = canonical
                } else {
                    DiagLog.w(
                        "keymap", "key_pressed_icon_unknown",
                        mapOf("layout" to layoutName, "keyId" to def.id, "icon" to name),
                    )
                }
            }
            // 按压态独立图片键面（重点候选 8-2）：仅已接纳资产写槽，
            // 未接纳记日志回退常态图片（或颜色键面）。
            override?.pressedImage?.let { id ->
                if (id in imageAssets) {
                    copy.appearance.skinPressedImageAssetId = id
                } else {
                    DiagLog.w(
                        "keymap", "key_pressed_image_fallback",
                        mapOf("layout" to layoutName, "keyId" to def.id, "asset" to id),
                    )
                }
            }
        }
    }

    /** 前景层装配期解析（text 层颜色非法回落副文字色；image 层未接纳丢弃）。 */
    private fun resolveForeground(
        layers: List<KeyForegroundLayer>?,
        tiers: Map<KeyTextRole, Float>,
        keySizes: Map<KeyTextRole, Float>?,
        imageAssets: Map<String, String>,
        scheme: com.jobeen.ime.data.keyboard.theme.KeyboardColors.ColorScheme,
        layoutName: String,
        keyId: String?,
    ): List<ResolvedForegroundLayer>? {
        if (layers.isNullOrEmpty()) return null
        val resolved = mutableListOf<ResolvedForegroundLayer>()
        for (layer in layers) {
            when (layer.type) {
                "text" -> {
                    val color = KeyStyleResolver.resolveValue(layer.colorRaw, scheme)
                        ?: scheme.altText
                    resolved += ResolvedForegroundLayer(
                        type = "text",
                        text = layer.text,
                        color = color,
                        size = layer.size ?: keySizes?.get(KeyTextRole.SUB)
                            ?: tiers[KeyTextRole.SUB],
                        biasX = layer.biasX ?: 0.5f,
                        biasY = layer.biasY ?: 0.5f,
                    )
                }
                "image" -> {
                    val assetId = layer.image
                    if (assetId == null || assetId !in imageAssets) {
                        DiagLog.w(
                            "keymap", "foreground_image_fallback",
                            mapOf(
                                "layout" to layoutName, "keyId" to keyId,
                                "asset" to assetId,
                            ),
                        )
                        continue
                    }
                    resolved += ResolvedForegroundLayer(
                        type = "image",
                        imageAssetId = assetId,
                        biasX = layer.biasX ?: 0.5f,
                        biasY = layer.biasY ?: 0.5f,
                    )
                }
                else -> Unit // 解析期已过滤类型，此处防御性跳过
            }
        }
        return resolved.ifEmpty { null }
    }

    /**
     * 统一外观重建（G1）：出厂外观永不原地修改；主显/副显/字号/
     * 样式色/图片/几何/前景一次写完。AltText/ImageText 是 Text 子类，
     * 必须先于 Text 匹配。
     */
    private fun rebuildAppearance(
        appearance: KeyDef.Appearance,
        mainText: String,
        mainSize: Float?,
        altSize: Float?,
        subLabel: String?,
        subLabelTop: String?,
        percentWidth: Float,
        style: KeyStyleResolver.ResolvedKeyStyle?,
        imageAssetId: String?,
        geometry: com.jobeen.ime.skin.model.KeyGeometryOverride?,
        foreground: List<ResolvedForegroundLayer>?,
        keyAnimation: com.jobeen.ime.skin.model.KeyAnimationSpec?,
    ): KeyDef.Appearance {
        val rebuilt: KeyDef.Appearance = when (appearance) {
            is KeyDef.Appearance.AltText -> KeyDef.Appearance.AltText(
                displayText = mainText,
                altText = subLabel ?: appearance.altText,
                altTextTranslationY = appearance.altTextTranslationY,
                mainTextTranslationY = appearance.mainTextTranslationY,
                altTextSize = altSize ?: appearance.altTextSize,
                textSize = mainSize ?: appearance.textSize,
                textStyle = appearance.textStyle,
                percentWidth = percentWidth,
                variant = appearance.variant,
                border = appearance.border,
                margin = appearance.margin,
                viewId = appearance.viewId,
                visibility = appearance.visibility,
                rowSpan = appearance.rowSpan,
                alignSpanEnd = appearance.alignSpanEnd,
            )
            is KeyDef.Appearance.ImageText -> KeyDef.Appearance.ImageText(
                displayText = mainText,
                textSize = mainSize ?: appearance.textSize,
                textStyle = appearance.textStyle,
                src = appearance.src,
                percentWidth = percentWidth,
                variant = appearance.variant,
                border = appearance.border,
                margin = appearance.margin,
                viewId = appearance.viewId,
                rowSpan = appearance.rowSpan,
                alignSpanEnd = appearance.alignSpanEnd,
            )
            is KeyDef.Appearance.Text -> if (subLabel != null || subLabelTop != null) {
                // Text 键无副文字槽：转为 AltText 呈现（副文字角色渲染）。
                // 仅顶部小字时底部副显为空串（空行不占视觉内容），
                // 主文字仍走 AltText 的 0.25 偏置（与既有 subLabel
                // 转换同口径，不另立布局分支）。
                KeyDef.Appearance.AltText(
                    displayText = mainText,
                    altText = subLabel ?: "",
                    altTextSize = altSize ?: 11f,
                    textSize = mainSize ?: appearance.textSize,
                    textStyle = appearance.textStyle,
                    percentWidth = percentWidth,
                    variant = appearance.variant,
                    border = appearance.border,
                    margin = appearance.margin,
                    viewId = appearance.viewId,
                    visibility = appearance.visibility,
                    rowSpan = appearance.rowSpan,
                    alignSpanEnd = appearance.alignSpanEnd,
                )
            } else {
                KeyDef.Appearance.Text(
                    displayText = mainText,
                    textSize = mainSize ?: appearance.textSize,
                    textStyle = appearance.textStyle,
                    percentWidth = percentWidth,
                    variant = appearance.variant,
                    border = appearance.border,
                    margin = appearance.margin,
                    viewId = appearance.viewId,
                    visibility = appearance.visibility,
                    rowSpan = appearance.rowSpan,
                    alignSpanEnd = appearance.alignSpanEnd,
                )
            }
            is KeyDef.Appearance.Image -> KeyDef.Appearance.Image(
                src = appearance.src,
                percentWidth = percentWidth,
                variant = appearance.variant,
                border = appearance.border,
                margin = appearance.margin,
                viewId = appearance.viewId,
                rowSpan = appearance.rowSpan,
                alignSpanEnd = appearance.alignSpanEnd,
            )
            is KeyDef.Appearance.SidePannel -> KeyDef.Appearance.SidePannel(
                rowSpan = appearance.rowSpan,
                visableRow = appearance.visableRow,
                percentWidth = percentWidth,
                variant = appearance.variant,
                border = appearance.border,
                margin = appearance.margin,
                viewId = appearance.viewId,
            )
        }
        rebuilt.skinFaceColor = style?.face
        rebuilt.skinPressedColor = style?.pressed
        rebuilt.skinTextColor = style?.text
        rebuilt.skinAltTextColor = style?.altText
        rebuilt.skinBorderColor = style?.border
        rebuilt.skinIconTintColor = style?.iconTint
        rebuilt.skinImageAssetId = imageAssetId
        rebuilt.skinTextSize = mainSize
        rebuilt.skinAltTextSize = altSize
        rebuilt.skinTopText = subLabelTop
        rebuilt.skinContentBiasX = geometry?.contentCenterX
        rebuilt.skinContentBiasY = geometry?.contentCenterY
        rebuilt.skinHInsetDp = geometry?.hInsetDp
        rebuilt.skinVInsetDp = geometry?.vInsetDp
        rebuilt.skinHeightFactor = geometry?.heightFactor
        rebuilt.skinForegroundLayers = foreground
        rebuilt.skinIconSymbol = appearance.skinIconSymbol
        rebuilt.skinKeyAnimation = keyAnimation
        return rebuilt
    }

    /** 替换某一类槽位行为：同类旧行为移除、新行为加入；其余原样。 */
    private inline fun <reified T : KeyDef.Behavior> Set<KeyDef.Behavior>.replaceSlot(
        replacement: () -> T,
    ): Set<KeyDef.Behavior> =
        filterNot { it is T }.toSet() + replacement()

    private fun displayTextOf(appearance: KeyDef.Appearance): String = when (appearance) {
        is KeyDef.Appearance.Text -> appearance.displayText
        is KeyDef.Appearance.AltText -> appearance.displayText
        is KeyDef.Appearance.ImageText -> appearance.displayText
        is KeyDef.Appearance.Image, is KeyDef.Appearance.SidePannel -> ""
    }

    private val TOKEN = Regex("\\$[A-Za-z][A-Za-z0-9]*")

    /**
     * 占位符替换（详设 §3.7）：只作用于显示文本，未知记号原样
     * 保留 + 一条 WARN 日志（只记记号名，绝不记模板原文）。
     * 频率有界：仅布局构建期调用（键盘构造/编辑器预览），
     * 非每次按键；未解析记号属于皮肤缺陷，须进导出日志可见
     * （2026-10-27 硬要求：渲染回退必须记日志）。
     */
    fun substitute(template: String, placeholders: Map<String, String>): String =
        TOKEN.replace(template) { match ->
            val name = match.value.substring(1)
            placeholders[name] ?: run {
                DiagLog.w("keymap", "placeholder_unresolved", mapOf("token" to name))
                match.value
            }
        }

    /** 强类型动作 → 运行时动作（详设 §3.2 映射表；占位类型不得到达此处）。 */
    fun toRuntime(action: SkinAction): KeyboardAction = when (action) {
        is SkinAction.Text -> KeyboardAction.KeySequenceAction(action.value)
        is SkinAction.Command -> when (action.action) {
            WhitelistAction.COMMIT_DIRECT ->
                KeyboardAction.LiteralCommitAction(requireNotNull(action.value))
            WhitelistAction.LITERAL_NEWLINE -> KeyboardAction.CommitAction("\n")
            WhitelistAction.ENTER -> KeyboardAction.ReturnAction()
            WhitelistAction.DELETE -> KeyboardAction.BackspaceAction
            WhitelistAction.DELETE_FORWARD -> KeyboardAction.DeleteForwardAction
            WhitelistAction.SPACE -> KeyboardAction.SpaceAction
            WhitelistAction.CURSOR_LEFT -> KeyboardAction.CursorMoveAction(-1)
            WhitelistAction.CURSOR_RIGHT -> KeyboardAction.CursorMoveAction(1)
            WhitelistAction.ROTATE_SCHEMA -> KeyboardAction.RotateSchema
            WhitelistAction.SWITCH_SCHEMA ->
                KeyboardAction.SwitchSchemaByValue(requireNotNull(action.value))
            WhitelistAction.OPEN_SCHEMA_PICKER -> KeyboardAction.ShowInputMethodPickerAction
            WhitelistAction.SWITCH_SYSTEM_IME -> KeyboardAction.LangSwitchAction
            WhitelistAction.OPEN_SYSTEM_IME_PICKER -> KeyboardAction.OpenSystemImePickerAction
            WhitelistAction.HIDE_KEYBOARD -> KeyboardAction.HideKeyboardAction
            WhitelistAction.CAPS -> KeyboardAction.CapsAction
            WhitelistAction.SWITCH_LAYOUT ->
                KeyboardAction.LayoutSwitchAction(requireNotNull(action.value))
            WhitelistAction.RESUME -> KeyboardAction.ResumeAction
            WhitelistAction.CLEAR -> KeyboardAction.ClearAction
            WhitelistAction.UNDO -> KeyboardAction.UndoAction
            WhitelistAction.REDO -> KeyboardAction.RedoAction
            WhitelistAction.VOICE_INPUT -> KeyboardAction.VoiceInputAction()
        }
        SkinAction.SequencePlaceholder ->
            error("sequence 占位不得转运行时动作（详设 §3.6）")
    }
}
