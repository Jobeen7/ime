package com.jobeen.ime.base

import android.content.Context
import java.io.File

/**
 * 有界崩溃证据 + 崩溃循环熔断（S4，在 CrashTrap 之上增量，只取证不吞崩溃）。
 *
 * 三层缺口补齐：
 * - 写侧截断：崩溃正文按字素 cap 到 [CrashTrap.MAX_CHARS]，超长时附截断标记
 *   （读侧 LogScreen 本就有显示截断，但旧写侧无上限、导出侧 AboutScreen 亦无上限）。
 * - 轮转：每进程崩溃文件保留最新 3 份（当前 + .1 + .2），旧文件不再被同进程
 *   下一次崩溃静默覆盖丢失。
 * - 日志 + 熔断：Java 崩溃与 Native 信号崩溃各写一行日志
 *   （"<epochMs> <kind>"，Native 行由信号处理器以 O_APPEND 写同一文件）；
 *   10 秒窗口内 ≥3 次崩溃即熔断：之后 10 分钟内 setupEngine 直接返回
 *   （只拦引擎启动；设置页等不依赖引擎的入口不受影响）。冷却过期后重新
 *   按日志计数（过期 trip 文件删除；日志仍 ≥3 则立即重新熔断）。
 *
 * 本文件函数均为纯函数/小文件 IO，可 JVM 单测；Native 信号处理器本身
 * （rime_engine.cc）在 JVM 侧不可测，靠发布构建编译 + dex grep 验证。
 * 熔断判定一律按日志行首 epochMs 解析，格式错的行跳过（不误熔断）。
 */

/** 崩溃日志文件名（crash/ 子目录内；不以 last_crash- 开头，故不被当作崩溃记录读/清）。 */
internal const val JOURNAL_FILE_NAME = "crash_journal.txt"

/** 熔断 trip 文件名（内容为熔断时刻 epochMs 单行）。 */
internal const val TRIP_FILE_NAME = "crash_breaker_trip.txt"

/** 熔断计数窗口：10 秒。 */
internal const val CRASH_WINDOW_MS = 10_000L

/** 熔断阈值：窗口内崩溃次数达到此值即熔断。 */
internal const val CRASH_THRESHOLD = 3

/** 熔断冷却：10 分钟。 */
internal const val COOLDOWN_MS = 600_000L

/** 日志保留行数上限（Java 追加侧修整；Native 追加侧不修整，崩溃即死进程、熔断又拦重启，故增长有界）。 */
internal const val JOURNAL_MAX_LINES = 100

/** 每进程崩溃文件保留份数（含当前文件）。 */
internal const val ROTATION_KEEP = 3

/**
 * 按字素截断崩溃正文：未超上限原样返回；超长时截到上限并附截断标记
 * （标记本身不计入上限字素）。用 offsetByCodePoints 保证不断开代理对。
 */
internal fun truncateCrashText(text: String, maxChars: Int = CrashTrap.MAX_CHARS): String {
    if (text.codePointCount(0, text.length) <= maxChars) return text
    val cut = text.substring(0, text.offsetByCodePoints(0, maxChars))
    return cut + "\n…[已截断，仅保留前 $maxChars 字素]"
}

/**
 * 轮转某进程的崩溃文件：旧 .1→.2（最旧一份丢弃），当前→.1；
 * 调用方随后写新的当前文件。rename 失败时 best-effort（新文件仍会覆盖当前，
 * 只丢一档历史，不抛错——崩溃落盘路径不得再抛）。
 */
internal fun rotateCrashFiles(dir: File, currentName: String, keep: Int = ROTATION_KEEP) {
    if (keep < 2) return
    val base = currentName.removeSuffix(".txt")
    for (i in (keep - 2) downTo 1) {
        val src = File(dir, "$base.$i.txt")
        if (src.isFile) {
            val dst = File(dir, "$base.${i + 1}.txt")
            dst.delete()
            src.renameTo(dst)
        }
    }
    val current = File(dir, currentName)
    if (current.isFile) {
        val first = File(dir, "$base.1.txt")
        first.delete()
        current.renameTo(first)
    }
}

/**
 * 向崩溃日志追加一行；行数超上限时重写保留最后 [maxLines] 行。
 * 读/修整失败时回退为普通追加：日志写入失败不得影响崩溃落盘主流程。
 */
internal fun appendCrashJournal(
    journalFile: File,
    epochMs: Long,
    kind: String,
    maxLines: Int = JOURNAL_MAX_LINES,
) {
    val line = "$epochMs $kind"
    runCatching {
        journalFile.parentFile?.mkdirs()
        val existing = if (journalFile.isFile) journalFile.readLines() else emptyList()
        if (existing.size + 1 <= maxLines) {
            journalFile.appendText(line + "\n")
        } else {
            val kept = (existing + line).takeLast(maxLines)
            journalFile.writeText(kept.joinToString("\n") + "\n")
        }
    }.onFailure {
        runCatching { journalFile.appendText(line + "\n") }
    }
}

/** 数日志中落在 [nowMs - windowMs, nowMs] 内的崩溃行数（行首 epochMs 解析失败的行跳过）。 */
internal fun countRecentCrashes(
    journalFile: File,
    nowMs: Long,
    windowMs: Long = CRASH_WINDOW_MS,
): Int {
    if (!journalFile.isFile) return 0
    val lines = runCatching { journalFile.readLines() }.getOrElse { return 0 }
    var count = 0
    for (line in lines) {
        val ms = line.substringBefore(' ').toLongOrNull() ?: continue
        if (ms in (nowMs - windowMs)..nowMs) count++
    }
    return count
}

/**
 * 熔断判定（副作用：新熔断时写 trip，冷却过期时删 trip）。
 * trip 未过期（即使日志已空）仍熔断；trip 过期后按日志重算，日志仍 ≥ 阈值则立即重新熔断。
 */
internal fun crashBreakerGated(journalFile: File, tripFile: File, nowMs: Long): Boolean {
    val tripMs = runCatching {
        tripFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    }.getOrNull()
    if (tripMs != null) {
        if (nowMs - tripMs < COOLDOWN_MS) return true
        tripFile.delete()
    }
    if (countRecentCrashes(journalFile, nowMs) >= CRASH_THRESHOLD) {
        runCatching {
            tripFile.parentFile?.mkdirs()
            tripFile.writeText(nowMs.toString())
        }
        return true
    }
    return false
}

/** 引擎启动是否被崩溃熔断拦住（filesDir 取不到时不拦：拦不住也比误拦好）。 */
fun isEngineSetupGated(context: Context): Boolean {
    val filesDir = context.filesDir ?: return false
    val dir = CrashTrap.crashDir(filesDir)
    return crashBreakerGated(
        File(dir, JOURNAL_FILE_NAME),
        File(dir, TRIP_FILE_NAME),
        System.currentTimeMillis(),
    )
}
