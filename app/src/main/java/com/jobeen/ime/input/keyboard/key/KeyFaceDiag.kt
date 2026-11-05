// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.key

import com.jobeen.ime.skin.diag.DiagLog
import java.util.Locale

/**
 * 逐键面色诊断（2026-11-05 真机灰诊断行，诊断-only）：
 * 在 KeyView.setupBackgroundWithPress 的最终取色点记录每键的
 * 面色来源与取值——快照 id/代次、逐键样式解析结果（命中具名
 * 样式／回落默认的原因）、面色最终取值与来源。只记元数据
 * （键标识、颜色值、原因码），绝不记用户输入文本。
 *
 * 开销：调用方只在解析签名变化时调 [log]（键盘重建／快照换代／
 * 运行时覆盖变化才发一条）；[log] 本身走 DiagLog.d，诊断模式
 * 关时门控直接丢弃。渲染结果一字不动。
 */
object KeyFaceDiag {

    /** 面色来源：运行时覆盖／具名样式命中／变体默认回落。 */
    enum class KeyFaceSource { RUNTIME_OVERRIDE, NAMED_STYLE, VARIANT_DEFAULT }

    /**
     * 回落原因（仅 [KeyFaceSource.VARIANT_DEFAULT] 时有值）：
     * - no-snapshot：快照未就绪（peek 为 null），整版走出厂布局；
     * - no-key-styles：快照无 keyStyles 表，装配器原样返回出厂布局；
     * - no-style-for-key：快照有样式表，但本键未解析出面色（无
     *   样式引用／未知样式名／样式无面色——装配器内部分支，渲染
     *   层只见结果为 null，原因以此码统记）。
     */
    enum class KeyFaceFallback { NO_SNAPSHOT, NO_KEY_STYLES, NO_STYLE_FOR_KEY }

    data class KeyFaceInfo(
        val skinId: String?,
        val generation: Int?,
        val keyLabel: String,
        val source: KeyFaceSource,
        /** 最终面色 ARGB（与渲染取用的 bkgColor 同值）。 */
        val faceColor: Int,
        val fallback: KeyFaceFallback?,
    ) {
        /** 面色十六进制（#AARRGGBB），与日志行同格式。 */
        val faceHex: String get() = "#%08X".format(faceColor)
    }

    /**
     * 纯判定：按 setupBackgroundWithPress 的同一优先级取色
     * （运行时覆盖 ＞ 具名样式面色 ＞ 变体默认），并给出回落原因。
     * [skinId]/[generation] 为 null 即无快照；[keyStylesEmpty] 为
     * 快照 keyStyles 表是否为空（无快照时忽略）。
     */
    fun resolve(
        keyLabel: String,
        skinId: String?,
        generation: Int?,
        keyStylesEmpty: Boolean,
        overrideColor: Int?,
        skinFaceColor: Int?,
        variantDefaultColor: Int,
    ): KeyFaceInfo {
        if (overrideColor != null) {
            return KeyFaceInfo(
                skinId, generation, keyLabel,
                KeyFaceSource.RUNTIME_OVERRIDE, overrideColor, null,
            )
        }
        if (skinFaceColor != null) {
            return KeyFaceInfo(
                skinId, generation, keyLabel,
                KeyFaceSource.NAMED_STYLE, skinFaceColor, null,
            )
        }
        val fallback = when {
            skinId == null -> KeyFaceFallback.NO_SNAPSHOT
            keyStylesEmpty -> KeyFaceFallback.NO_KEY_STYLES
            else -> KeyFaceFallback.NO_STYLE_FOR_KEY
        }
        return KeyFaceInfo(
            skinId, generation, keyLabel,
            KeyFaceSource.VARIANT_DEFAULT, variantDefaultColor, fallback,
        )
    }

    /** 发出诊断行（DEBUG 级：只在诊断模式开启时落盘）。 */
    fun log(info: KeyFaceInfo) {
        val context = mutableMapOf<String, Any?>(
            "skin" to (info.skinId ?: "none"),
            "gen" to (info.generation?.toString() ?: "none"),
            "key" to info.keyLabel,
            "source" to info.source.name.lowercase(Locale.ROOT),
            "face" to info.faceHex,
        )
        info.fallback?.let { context["reason"] = it.name.lowercase(Locale.ROOT) }
        DiagLog.d("keyface", "key_face", context)
    }
}
