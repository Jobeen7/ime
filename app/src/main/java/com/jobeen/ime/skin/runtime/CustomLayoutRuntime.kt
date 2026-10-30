package com.jobeen.ime.skin.runtime

import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.skin.diag.DiagLog
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
