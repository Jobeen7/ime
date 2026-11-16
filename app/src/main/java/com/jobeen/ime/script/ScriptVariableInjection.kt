// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

/**
 * ScriptVariable → `$key` 全局注入（纯函数，无 Android 依赖，可主机单测）。
 *
 * 口径（WO52 遗留补齐：变量此前只存＋管，未注入）：
 * - 库里每条 [VariableEntry]（key／value／isSecure）注入为一个 JS
 *   全局变量，名 `$key`（如 key=`apiUrl` → 脚本里读 `$apiUrl`），值
 *   一律按字符串注入（库里 value 本就是 String，不存在别的类型）；
 * - 注入点在运行前、eval 之前，与 `$searchText` 等输入同一批进引擎，
 *   脚本 eval 时即可读，`output()` 里也能读；
 * - 注入失败只跳过该变量并记一条 log，不阻断脚本执行。
 *
 * 跳过规则（按此顺序判定，先命中为准）：
 * 1. `isSecure` 为真：敏感变量永不注入——尤其不进隔离进程（密钥
 *    不经 Binder、不进 QuickJS 引擎），只记 log；
 * 2. key 非法：空串、或不是合法 JS 标识符（`[\p{L}_$][\p{L}\p{N}_$]*`）；
 * 3. key 撞保留名：`$searchText`／`$selectedText`／`$pasteboardContent`
 *   （输入优先），`$log`／`$http`／`$setClipboard`／`$insertText`／
 *    `$openURL`／`$userNotification`（宿主 API 不可被变量覆盖）、
 *    `$output`（脚本自己的 output 函数位）；
 * 4. value 超长（单条 > [MAX_VALUE_CHARS]）；
 * 5. 重复 key：第一条生效，后面的跳过；
 * 6. 超量（> [MAX_VARIABLES] 条）或总量超 [MAX_TOTAL_CHARS] 字符：
 *    后面的跳过（Binder 事务约 1 MiB 上限，变量总量钳在 256 KiB 内）。
 *
 * 跳过日志：[skipLogLine] 生成的行（不含 value，敏感值永不进日志），
 * 由运行器写进本次运行的日志列表，随结果一起返回。
 */
object ScriptVariableInjection {

    /** 待注入的一条变量（由调用方从 ScriptVariable 映射过来）。 */
    data class VariableEntry(val key: String, val value: String, val isSecure: Boolean)

    /** 跳过原因。 */
    enum class SkipReason {
        INVALID_KEY,
        RESERVED_KEY,
        SECURE,
        VALUE_TOO_LONG,
        DUPLICATE,
        TOO_MANY,
    }

    /** 一条被跳过的变量（key＋原因；不含 value）。 */
    data class SkippedVariable(val key: String, val reason: SkipReason)

    /** prepare() 的结果：可注入的 key→value＋被跳过的条目。 */
    data class PreparedVariables(
        val values: Map<String, String>,
        val skipped: List<SkippedVariable>,
    )

    /** 单条 value 上限（字符）。 */
    const val MAX_VALUE_CHARS = 16384

    /** 一次运行注入的变量条数上限。 */
    const val MAX_VARIABLES = 50

    /** 一次运行注入的变量总量上限（key＋value 字符数之和）。 */
    const val MAX_TOTAL_CHARS = 262144

    /** 不可被变量覆盖的全局名（去 `$` 后的 key 形态）。 */
    val RESERVED_KEYS: Set<String> = setOf(
        "searchText",
        "selectedText",
        "pasteboardContent",
        "log",
        "http",
        "setClipboard",
        "insertText",
        "openURL",
        "userNotification",
        "output",
    )

    private val VALID_KEY = Regex("""^[\p{L}_$][\p{L}\p{N}_$]*$""")

    /** key 是否为合法 JS 标识符（`$` 前缀由注入时加，不含在 key 里）。 */
    fun isValidKey(key: String): Boolean = VALID_KEY.matches(key)

    /** 准备一次注入：校验＋去重＋钳量，返回可注入值与跳过清单。 */
    fun prepare(
        entries: List<VariableEntry>,
        maxVariables: Int = MAX_VARIABLES,
        maxValueChars: Int = MAX_VALUE_CHARS,
        maxTotalChars: Int = MAX_TOTAL_CHARS,
    ): PreparedVariables {
        val values = linkedMapOf<String, String>()
        val skipped = mutableListOf<SkippedVariable>()
        var totalChars = 0
        for (entry in entries) {
            val key = entry.key
            when {
                entry.isSecure -> skipped.add(SkippedVariable(key, SkipReason.SECURE))
                !isValidKey(key) -> skipped.add(SkippedVariable(key, SkipReason.INVALID_KEY))
                key in RESERVED_KEYS -> skipped.add(SkippedVariable(key, SkipReason.RESERVED_KEY))
                entry.value.length > maxValueChars ->
                    skipped.add(SkippedVariable(key, SkipReason.VALUE_TOO_LONG))
                key in values -> skipped.add(SkippedVariable(key, SkipReason.DUPLICATE))
                values.size >= maxVariables || totalChars + key.length + entry.value.length > maxTotalChars ->
                    skipped.add(SkippedVariable(key, SkipReason.TOO_MANY))
                else -> {
                    values[key] = entry.value
                    totalChars += key.length + entry.value.length
                }
            }
        }
        return PreparedVariables(values, skipped)
    }

    /** 跳过原因的日志文案（不含 value）。 */
    fun reasonText(reason: SkipReason): String = when (reason) {
        SkipReason.INVALID_KEY -> "变量名非法"
        SkipReason.RESERVED_KEY -> "变量名与内置全局冲突"
        SkipReason.SECURE -> "敏感变量不注入"
        SkipReason.VALUE_TOO_LONG -> "值过长"
        SkipReason.DUPLICATE -> "变量名重复"
        SkipReason.TOO_MANY -> "变量过多或总量过大"
    }

    /** 一条跳过日志行（格式：`跳过变量 $key：原因`；不含 value）。 */
    fun skipLogLine(key: String, reason: SkipReason): String =
        "跳过变量 \$$key：${reasonText(reason)}"

    /** 本次运行全部跳过行的日志行列表（按跳过顺序）。 */
    fun skipLogLines(prepared: PreparedVariables): List<String> =
        prepared.skipped.map { skipLogLine(it.key, it.reason) }
}
