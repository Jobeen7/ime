package com.jobeen.ime.skin.runtime

import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.skin.model.SkinSnapshot
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
     */
    fun effectiveLayout(
        snapshot: SkinSnapshot?,
        placeholders: Map<String, String>,
    ): List<List<KeyDef>>? {
        val resolved = snapshot?.resolvedLayout ?: return null
        return KeyOverrideApplier.apply(
            nameFor(resolved.layoutId), resolved.baseLayout, snapshot, placeholders,
        )
    }

    /** 覆写命中数（诊断日志 layout_applied_to_keyboard 的计数口径）。 */
    fun overrideHitCount(snapshot: SkinSnapshot?): Int {
        val resolved = snapshot?.resolvedLayout ?: return 0
        if (snapshot.keyOverrides.isEmpty()) return 0
        val ids = resolved.baseLayout.flatten().mapNotNull { it.id }.toSet()
        return snapshot.keyOverrides.keys.count { it in ids }
    }

    /**
     * 主键盘位目标解析（§3.3 优先级表写死）：
     *
     * 1. 场景强制键盘（Number/电话/密码）与工具键盘（Symbol/
     *    Emoji）不经本解析（状态管理器对场景目标走直达通道），
     *    本函数对这类名字也恒原样返回；
     * 2. `custom:<id>` 只在 id 恰为当前已解析布局时成立；指向
     *    其他/已失效布局时先回退方案默认名，再按第 3 条归一；
     * 3. 主键盘名（Qwerty/T9/T15）在有已解析布局时改指自创
     *    布局——自创布局占据主键盘位，方案默认/resume 回归/
     *    以主键盘为目标的布局切换都落到它。
     */
    fun resolveSwitchTarget(
        snapshot: SkinSnapshot?,
        requested: String,
        schemaDefault: String,
    ): String {
        val resolved = snapshot?.resolvedLayout
        var target = requested
        val customId = layoutIdOf(target)
        if (customId != null && (resolved == null || resolved.layoutId != customId)) {
            target = schemaDefault
        }
        if (target in MAIN_LAYOUT_NAMES && resolved != null) {
            target = nameFor(resolved.layoutId)
        }
        return target
    }
}
