package com.jobeen.ime.skin.model

/**
 * 动态文字与前景层（自 KeyModelG1.kt 迁出的活声明；
 * KeyDef.skinForegroundLayers 与动态文字供给链仍在用）。
 */

/**
 * 前景层解析结果（装配期产物：颜色已按快照方案解析为 ARGB，
 * image 层只保留已接纳资产 id；渲染层只读本类型，不再解析）。
 */
data class ResolvedForegroundLayer(
    val type: String,
    val text: String? = null,
    val imageAssetId: String? = null,
    val color: Int? = null,
    val size: Float? = null,
    val biasX: Float = 0.5f,
    val biasY: Float = 0.5f,
    /** image 层内容模式（审计 D 项）；null = 旧口径 FIT_CENTER。 */
    val contentMode: ImageContentMode? = null,
    /**
     * 前景层实时变量模板（审计 K 余项）：原文含对端特殊变量
     * （$rimePreedit/$rimeCandidate/$rimeCandidateComment/
     * $rimeSchemaName/$returnKeyType）时记原文，否则 null；
     * 视图层据此在候选/组字/方案/回车类型变化时原地刷新，
     * 与主显动态文字同一供给链（见 pushDynamicTexts）。
     * text 字段恒为构建期快照解析值。
     */
    val dynamicTemplate: String? = null,
)

/**
 * 动态文字来源（keyMappings 条目 `dynamicText` 字符串）：
 * 构建期快照解析 + 运行时跟随（键盘层在候选/组字变化时刷新，
 * 见 KeyOverrideApplier.resolveDynamicDisplayText 与窗口推送）。
 *
 * - candidateFirst：首选候选词（候选条首项；无候选时回退静态 label）。
 * - composing：正在输入的拼音/组字串（组字区当前串；无组字时回退静态 label）。
 * - schemeName：当前方案名（方案切换时跟随）。
 * - returnKeyType：回车键类型（GO/SEND/SEARCH/NEXT/PREVIOUS/DONE/ENTER）。
 * - candidateComment：首候选注释（无注释时回退静态 label）。
 * - optionLabel$<开关名> / optionLabelAbbreviated$<开关名>：Rime
 *   开关的当前态标签／首字缩写（重点候选 8-8；解析见
 *   OptionLabelResolver；值由供给链按开关配置＋选项值算出，
 *   无值时回退静态 label）。
 *
 * 值绝不进日志：装配/渲染只记来源名与键 id，动态值本身只存在
 * 于本次构建的 KeyDef 文本中。
 */
sealed interface DynamicTextSource {
    val jsonName: String

    data object CANDIDATE_FIRST : DynamicTextSource {
        override val jsonName: String = "candidateFirst"
    }

    data object COMPOSING : DynamicTextSource {
        override val jsonName: String = "composing"
    }

    data object SCHEME_NAME : DynamicTextSource {
        override val jsonName: String = "schemeName"
    }

    data object RETURN_KEY_TYPE : DynamicTextSource {
        override val jsonName: String = "returnKeyType"
    }

    data object CANDIDATE_COMMENT : DynamicTextSource {
        override val jsonName: String = "candidateComment"
    }

    /** 选项标签键（参数化来源）：开关名＋是否缩写。 */
    data class OptionLabel(
        val switchName: String,
        val abbreviated: Boolean,
    ) : DynamicTextSource {
        override val jsonName: String
            get() = (if (abbreviated) OPTION_LABEL_ABBREV_PREFIX else OPTION_LABEL_PREFIX) +
                switchName
    }

    companion object {
        const val OPTION_LABEL_PREFIX = "optionLabel$"
        const val OPTION_LABEL_ABBREV_PREFIX = "optionLabelAbbreviated$"

        /**
         * 对端 text 特殊变量 → 本地动态文字来源（审计 K 余项）：
         * $rimePreedit = 组字串，$rimeCandidate = 首选候选，
         * $rimeCandidateComment = 首候选注释，$rimeSchemaName =
         * 当前方案名，$returnKeyType = 回车键类型；其余记号
         * 返回 null（调用方原样保留，与占位替换同口径）。
         */
        fun fromPeerVariable(token: String): DynamicTextSource? = when (token) {
            "\$rimePreedit" -> COMPOSING
            "\$rimeCandidate" -> CANDIDATE_FIRST
            "\$rimeCandidateComment" -> CANDIDATE_COMMENT
            "\$rimeSchemaName" -> SCHEME_NAME
            "\$returnKeyType" -> RETURN_KEY_TYPE
            else -> null
        }
    }
}
