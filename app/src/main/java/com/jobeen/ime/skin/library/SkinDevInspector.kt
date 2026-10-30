package com.jobeen.ime.skin.library

import com.jobeen.ime.skin.model.SkinIssue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 开发屏检视用的纯函数（可单测，不触碰 Android API）：
 * skin.json 美化＋截断、校验结论摘要、诊断日志尾行截取。
 * 截断是硬上限：开发屏绝不把整份超大 JSON 一次性铺进 Compose 文本。
 */
object SkinDevInspector {
    const val MAX_PRETTY_CHARS = 100_000
    const val MAX_LOG_LINES = 200

    /** 美化打印；超长时截断并附注剩余字符数（原文不改，只少显示）。 */
    fun prettyPrint(tree: JsonObject, maxChars: Int = MAX_PRETTY_CHARS): String {
        val pretty = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), tree)
        if (pretty.length <= maxChars) return pretty
        return pretty.take(maxChars) +
            "\n…（已截断，剩余 ${pretty.length - maxChars} 字未显示）"
    }

    /** 校验结论一行摘要：只含结论与问题计数，不含皮肤内容。 */
    fun validationSummary(
        valid: Boolean,
        issues: List<SkinIssue>,
        rejectedReason: String?,
    ): String = when {
        !valid -> "已拒绝：${rejectedReason ?: "unknown"}"
        issues.isEmpty() -> "有效，无回填提示"
        else -> "有效，${issues.size} 项回填提示"
    }

    /** 取文本最后 [maxLines] 行（诊断日志查看用）。 */
    fun tailLines(text: String, maxLines: Int = MAX_LOG_LINES): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.lines()
        return if (lines.size <= maxLines) lines else lines.takeLast(maxLines)
    }
}
