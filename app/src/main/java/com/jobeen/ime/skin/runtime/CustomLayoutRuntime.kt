package com.jobeen.ime.skin.runtime

import com.jobeen.ime.input.keyboard.impl.EmojiKeyboard
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.VariantResolver
import com.jobeen.ime.skin.model.VariantTree
import com.jobeen.ime.skin.model.WhitelistAction

/**
 * 自创布局运行时接缝（B3 详设 §3.3）：运行时名、生效布局装配、
 * 主键盘位目标解析三件事的唯一实现处，键盘工厂与状态管理器
 * 只调用本类，不各自拼规则。
 */
object CustomLayoutRuntime {

    /** 自创布局运行时名前缀：`custom:<layoutId>`。 */
    const val PREFIX: String = WhitelistAction.CUSTOM_LAYOUT_PREFIX

    /** 主键盘位布局名全集（待核 2 定死：方案默认键盘只会是这三者）。 */
    val MAIN_LAYOUT_NAMES: Set<String> = setOf(
        QwertyKeyboard.NAME, T9Keyboard.NAME, T15Keyboard.NAME,
    )

    fun nameFor(layoutId: String): String = PREFIX + layoutId

    /** 运行时名 → layoutId；非自创布局名返回 null。 */
    fun layoutIdOf(name: String): String? =
        if (name.startsWith(PREFIX)) {
            name.removePrefix(PREFIX).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /**
     * 生效布局：快照已解析的基础结构过 keyMappings 覆写装配
     * （与出厂布局同一装配器、同一语义，B3 §3.1）。无已解析
     * 布局返回 null，调用方走回退链（§3.5）。
     * @param landscape 横屏时取横屏结构（2026-10-27 第一组 +
     * 矩阵扩展）：有横屏专属绑定且已解析时取绑定布局的横屏
     * 变体；否则取竖屏布局的横屏权重变体（baseLayoutLandscape）；
     * 竖屏布局不存在但横屏绑定存在时（内置布局竖屏 + 横屏
     * 专属布局）仍取绑定布局——横屏绑定缺失/非法时逐级回退，
     * 永不返回 null 布局以外的空白（null 只表示该方向根本
     * 无布局，调用方再回退方案默认键盘）。
     * @param isDark 样式令牌解析取板（双色板下令牌色须与当前
     * 模式同板，否则深色模式下单键样式仍解析到浅色板）。
     */
    fun effectiveLayout(
        snapshot: SkinSnapshot?,
        placeholders: Map<String, String>,
        landscape: Boolean = false,
        isDark: Boolean = false,
        /** 拟态开关（2026-10-29 item-1）：缺省全开，生产调用方传偏好。 */
        mnemonicEnabled: Boolean = true,
        decorationEnabled: Boolean = true,
    ): List<List<KeyDef>>? {
        if (snapshot == null) return null
        val resolved = snapshot.resolvedLayout
        val landscapeResolved = snapshot.resolvedLayoutLandscape
        // 四变体回退链布局轴（2026-10-29 item-1）：横屏树经本链
        // 决策；绑定已声明但解析失败（MISSING/INVALID）时记一步
        // 诊断日志（只记元数据）；未声明绑定（NOT_SET）属正常
        // 竖屏回退，不记。权重变体（同树竖屏/横屏权重）与树选择正交。
        val (tree, treeStep) = VariantResolver.resolveTree(
            landscape, landscapeResolved != null,
        )
        if (treeStep != null &&
            snapshot.landscapeLayoutResolution != LayoutResolution.NOT_SET
        ) {
            DiagLog.d(
                "layout", "variant_tree_fallback",
                mapOf(
                    "skinId" to snapshot.ref.skinId,
                    "step" to treeStep,
                    "resolution" to snapshot.landscapeLayoutResolution.name,
                ),
            )
        }
        val treeResolved = if (tree == VariantTree.LANDSCAPE) landscapeResolved else resolved
        val base = if (landscape) {
            treeResolved?.baseLayoutLandscape
                ?: resolved?.baseLayoutLandscape
                ?: return null
        } else {
            resolved?.baseLayout ?: return null
        }
        val layoutId = if (landscape && landscapeResolved != null) {
            landscapeResolved.layoutId
        } else {
            resolved!!.layoutId
        }
        return KeyOverrideApplier.apply(
            nameFor(layoutId), base, snapshot, placeholders,
            scheme = snapshot.effectiveColorScheme(isDark),
            // 接线轮 R1/R2：构建期取当前锁定通知与条件门控集。
            notifications = SkinRuntimeState.latchedNotifications(),
            gatedStyleNames = SkinRuntimeState.gatedStyleNames(),
            activeStyleNames = SkinRuntimeState.activeStyleNames(),
            mnemonicEnabled = mnemonicEnabled,
            decorationEnabled = decorationEnabled,
        )
    }

    /** 覆写命中数（诊断日志 layout_applied_to_keyboard 的计数口径）。 */
    fun overrideHitCount(snapshot: SkinSnapshot?, landscape: Boolean = false): Int {
        if (snapshot == null || snapshot.keyOverrides.isEmpty()) return 0
        val effective = if (landscape) {
            snapshot.resolvedLayoutLandscape?.baseLayoutLandscape
                ?: snapshot.resolvedLayout?.baseLayoutLandscape
        } else {
            snapshot.resolvedLayout?.baseLayout
        } ?: return 0
        val ids = effective.flatten().mapNotNull { it.id }.toSet()
        return snapshot.keyOverrides.keys.count { it in ids }
    }

    /**
     * 主键盘位目标解析（§3.3 优先级表写死，2026-10-27 矩阵扩展
     * 增加横屏分支）：
     *
     * 1. 场景强制键盘（Number/电话/密码）与工具键盘（Symbol/
     *    Emoji）不经本解析（状态管理器对场景目标走直达通道），
     *    本函数对这类名字也恒原样返回；
     * 2. `custom:<id>` 只在 id 恰为当前方向已解析布局时成立：
     *    竖屏认竖屏绑定，横屏另认横屏绑定；指向其他/已失效
     *    布局时先回退方案默认名，再按第 3 条归一；
     * 3. 主键盘名（Qwerty/T9/T15）在有已解析布局时改指自创
     *    布局——竖屏有竖屏绑定时指竖屏绑定；竖屏绑定不存在
     *    但横屏绑定存在且当前为横屏时指横屏绑定（内置布局
     *    竖屏 + 横屏专属布局）；自创布局占据主键盘位，方案
     *    默认/resume 回归/以主键盘为目标的布局切换都落到它。
     *    横屏绑定不改竖屏行为：竖屏下无竖屏绑定时主键盘名
     *    恒原样返回内置布局。
     */
    /**
     * 类型目标解析（多主槽一期）：（类型 + 方向）→ 键盘运行时名。
     * 旧 [resolveSwitchTarget]（按名解析）保持冻结语义，本函数
     * 是类型维度的唯一新解析入口；调用方不得混用两者。
     *
     * 回退链（任一步命中即停，永不空白）：
     * 1. 表项该方向已解析布局 → `custom:<layoutId>`；
     * 2. 仅 pinyin：表项无有效绑定时回落旧顶层绑定（竖屏取
     *    resolvedLayout，横屏取 resolvedLayoutLandscape、无则
     *    取 resolvedLayout 的横屏权重变体所指布局）；
     * 3. 内置出厂键盘（[builtinKeyboardName]）：emojis → 表情
     *    键盘；images → 暂无 images 键盘实现前以表情键盘兜底
     *    （实现落地后改指 images 键盘，见 Wave-4 接线项）；
     *    numeric/numberPad → 数字键盘（两者现共用同一数字
     *    键盘实现，表项仍可各自绑定不同自定义布局与高度）；
     *    symbolic → 符号键盘；alphabetic → 全键盘；pinyin →
     *    方案默认布局；未声明的自定义类型 → 方案默认布局。
     * 表项已声明但绑定解析失败（缺失/非法）与未声明同等处理：
     * 一律走第 2/3 步，绝不因声明了坏绑定而空白。
     */
    fun resolveTypeTarget(
        snapshot: SkinSnapshot?,
        type: String,
        schemaDefault: String,
        landscape: Boolean = false,
    ): String {
        val entry = snapshot?.resolvedKeyboards?.get(type)
        val resolved = entry?.resolvedFor(landscape)
        if (resolved != null) return nameFor(resolved.layoutId)
        if (type == KeyboardTypes.PINYIN) {
            val legacy = if (landscape) {
                snapshot?.resolvedLayoutLandscape ?: snapshot?.resolvedLayout
            } else {
                snapshot?.resolvedLayout
            }
            if (legacy != null) return nameFor(legacy.layoutId)
        }
        return builtinKeyboardName(type, schemaDefault)
    }

    /**
     * 类型 → 内置出厂键盘名（回退链末级，永不返回未知名）：
     * createKeyboard 对未知名亦回落全键盘，故本表外再无空白
     * 路径。images 暂指表情键盘是过渡口径，不是类型语义。
     */
    fun builtinKeyboardName(type: String, schemaDefault: String): String = when (type) {
        KeyboardTypes.ALPHABETIC -> QwertyKeyboard.NAME
        KeyboardTypes.PINYIN -> schemaDefault
        KeyboardTypes.EMOJIS -> EmojiKeyboard.NAME
        KeyboardTypes.IMAGES -> EmojiKeyboard.NAME
        KeyboardTypes.NUMERIC, KeyboardTypes.NUMBER_PAD -> NumberKeyboard.NAME
        KeyboardTypes.SYMBOLIC -> SymbolKeyboard.NAME
        else -> schemaDefault
    }

    /**
     * 按类型取生效布局（多主槽一期）：与 [effectiveLayout] 同一
     * 装配器、同一覆写语义，只改布局来源——先取表项该方向
     * 已解析布局，pinyin 无表项时回落旧顶层绑定；其余类型无
     * 绑定时返回 null，调用方走内置键盘（绝不空白）。
     * 旧 [effectiveLayout] 不改：它仍只认旧单布局字段，供
     * 旧主键盘位路径使用；新类型路径的构建接线（按名建键盘
     * 时传入类型）属后续接线轮，本函数先行提供布局来源。
     */
    fun effectiveLayoutForType(
        snapshot: SkinSnapshot?,
        type: String,
        placeholders: Map<String, String>,
        landscape: Boolean = false,
        isDark: Boolean = false,
        mnemonicEnabled: Boolean = true,
        decorationEnabled: Boolean = true,
    ): List<List<KeyDef>>? {
        if (snapshot == null) return null
        val resolved = snapshot.resolvedKeyboards[type]?.resolvedFor(landscape)
            ?: if (type == KeyboardTypes.PINYIN) {
                if (landscape) {
                    snapshot.resolvedLayoutLandscape ?: snapshot.resolvedLayout
                } else {
                    snapshot.resolvedLayout
                }
            } else {
                null
            }
            ?: return null
        return applyResolved(
            snapshot, resolved, placeholders, landscape, isDark,
            mnemonicEnabled, decorationEnabled,
        )
    }

    /**
     * 按布局 id 取生效布局（多主槽一期）：供按名建键盘的调用方
     * 在类型未知时按 `custom:<layoutId>` 反查——先查类型表两
     * 方向，再查旧单布局字段；查无返回 null（调用方走回退链）。
     */
    fun effectiveLayoutForLayoutId(
        snapshot: SkinSnapshot?,
        layoutId: String,
        placeholders: Map<String, String>,
        landscape: Boolean = false,
        isDark: Boolean = false,
        mnemonicEnabled: Boolean = true,
        decorationEnabled: Boolean = true,
    ): List<List<KeyDef>>? {
        if (snapshot == null) return null
        val fromTable = snapshot.resolvedKeyboards.values.firstNotNullOfOrNull { entry ->
            if (landscape) entry.landscape?.takeIf { it.layoutId == layoutId }
                ?: entry.portrait?.takeIf { it.layoutId == layoutId } else {
                entry.portrait?.takeIf { it.layoutId == layoutId }
            }
        }
        val resolved = fromTable
            ?: snapshot.resolvedLayout?.takeIf { it.layoutId == layoutId }
            ?: snapshot.resolvedLayoutLandscape?.takeIf { it.layoutId == layoutId }
            ?: return null
        return applyResolved(
            snapshot, resolved, placeholders, landscape, isDark,
            mnemonicEnabled, decorationEnabled,
        )
    }

    /** 已解析布局过 keyMappings 覆写装配（与 [effectiveLayout] 同一调用）。 */
    private fun applyResolved(
        snapshot: SkinSnapshot,
        resolved: com.jobeen.ime.skin.model.ResolvedLayout,
        placeholders: Map<String, String>,
        landscape: Boolean,
        isDark: Boolean,
        mnemonicEnabled: Boolean,
        decorationEnabled: Boolean,
    ): List<List<KeyDef>>? {
        val base = if (landscape) resolved.baseLayoutLandscape else resolved.baseLayout
        return KeyOverrideApplier.apply(
            nameFor(resolved.layoutId), base, snapshot, placeholders,
            scheme = snapshot.effectiveColorScheme(isDark),
            notifications = SkinRuntimeState.latchedNotifications(),
            gatedStyleNames = SkinRuntimeState.gatedStyleNames(),
            activeStyleNames = SkinRuntimeState.activeStyleNames(),
            mnemonicEnabled = mnemonicEnabled,
            decorationEnabled = decorationEnabled,
        )
    }

    /**
     * 布局名 → 键盘类型反查（Wave-4 布局切换键用，纯决策）：
     * - 表内绑定：任一方向的已解析布局 id 命中 → 该类型；
     * - 旧顶层绑定（竖屏／横屏）id 命中 → pinyin（旧绑定即
     *   pinyin 主槽回落，与 resolveTypeTarget 第 2 步同口径）；
     * - 内置名：Qwerty → alphabetic（全键盘即英文键盘），
     *   T9/T15 → pinyin（中文布局），Symbol → symbolic，
     *   Emoji → emojis，Number → numeric（numberPad 为场景
     *   键盘，不由皮肤切换键寻址）；
     * - 其余（未声明自创 id、未知名）→ null，调用方沿用旧按
     *   名切换（类型不动）。
     * 注意 Qwerty 的歧义：pinyin 类型在无绑定时亦可回落到方案
     * 默认全键盘；但皮肤作者写 switch_layout "Qwerty" 时指的
     * 是英文键盘本身，故此处恒指 alphabetic。
     */
    fun typeForLayoutName(
        snapshot: SkinSnapshot?,
        name: String,
        landscape: Boolean = false,
    ): String? {
        val customId = layoutIdOf(name)
        if (customId != null) {
            val tableType = snapshot?.resolvedKeyboards?.entries?.firstNotNullOfOrNull { (type, entry) ->
                val hit = if (landscape) {
                    entry.landscape?.layoutId == customId || entry.portrait?.layoutId == customId
                } else {
                    entry.portrait?.layoutId == customId
                }
                if (hit) type else null
            }
            if (tableType != null) return tableType
            val legacyId = if (landscape) {
                snapshot?.resolvedLayoutLandscape?.layoutId
                    ?: snapshot?.resolvedLayout?.layoutId
            } else {
                snapshot?.resolvedLayout?.layoutId
            }
            if (customId == legacyId) return KeyboardTypes.PINYIN
            return null
        }
        return when (name) {
            QwertyKeyboard.NAME -> KeyboardTypes.ALPHABETIC
            T9Keyboard.NAME, T15Keyboard.NAME -> KeyboardTypes.PINYIN
            SymbolKeyboard.NAME -> KeyboardTypes.SYMBOLIC
            EmojiKeyboard.NAME -> KeyboardTypes.EMOJIS
            NumberKeyboard.NAME -> KeyboardTypes.NUMERIC
            else -> null
        }
    }

    fun resolveSwitchTarget(
        snapshot: SkinSnapshot?,
        requested: String,
        schemaDefault: String,
        landscape: Boolean = false,
    ): String {
        val resolved = snapshot?.resolvedLayout
        val landscapeResolved = snapshot?.resolvedLayoutLandscape
        var target = requested
        val customId = layoutIdOf(target)
        if (customId != null) {
            val valid = customId == resolved?.layoutId ||
                (landscape && customId == landscapeResolved?.layoutId)
            if (!valid) {
                target = schemaDefault
            }
        }
        if (target in MAIN_LAYOUT_NAMES) {
            if (resolved != null) {
                target = nameFor(resolved.layoutId)
            } else if (landscape && landscapeResolved != null) {
                target = nameFor(landscapeResolved.layoutId)
            }
        }
        return target
    }
}
