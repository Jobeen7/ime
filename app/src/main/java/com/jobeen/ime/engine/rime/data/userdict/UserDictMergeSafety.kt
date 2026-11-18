// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import timber.log.Timber

/**
 * 词库备份/合并安全流程（松烟思路自研重写，不搬松烟代码）。
 *
 * 只学“合并前备份、可恢复、整批校验、只增不减”的做法，具体实现按
 * Jime 现有链路与 librime 行为从头写：
 *
 * - 导入格式（实测本仓 vendored librime，TableDb::format.parser，
 *   `rime/dict/table_db.cc`）：每行 `词⇥码[⇥权重]`，权重是十进制
 *   整数；第 3 列不是整数时 native 的 stoi 静默失败、权重变 0；
 *   权重为负时 `UserDbImporter::Put`（`rime/dict/user_db.cc`）把
 *   该词条标记为删除——这就是“较小权重覆盖/删除已有词”的唯一
 *   入口：正权重时 native 本就取较大提交次数与较大 dee，
 *   `o.commits = max(o.commits, v.commits)`，
 *   `o.dee = max(o.dee, v.dee)`。
 * - 所以本文件做的三件事是：① 先校验再合并。批级问题（文件非
 *   UTF-8、目标词典名非法、多数行码在前疑似 `.userdb.txt` 快照）
 *   整批拒绝，一行不写；行级问题（列数不对、词/码为空或非法、
 *   权重非法或为负）只跳过该行、其余照常导入，并在日志
 *   里报告跳过的行号——与 librime 本身的导入口径一致（vendored
 *   librime `rime/dict/tsv.cc` 的 TsvReader 遇坏行记警告后继续，
 *   不中断整批；其解析器 `rime/dict/table_db.cc` 也没有码长上限）。
 *   松烟的“整批先校验”同样只是快照身份/格式/版本这一批级检查，
 *   空快照直接跳过（`RimeLearningBackup.mergeSnapshotFiles`），
 *   从不对行级问题整批拒绝。② 合并前把当前词库导出成
 *   `before-merge.json` 安全副本；③ 批内同词条去重取最大权重后
 *   再导入，绝不把负权重行喂给导入器（跳过即不产生删除标记）。
 * - 恢复：native 的 Import/Restore 两条路都只取最大值、没有
 *   “替换回旧值”的入口，所以恢复用已有的导入器本身完成：
 *   合并后新增的词条写一条 `-1` 删除标记行，被抬高的词条先
 *   删标记、再按备份权重重写一条——删除标记行只允许这条用户
 *   确认的恢复流程内部产生，合并流程永远不产生。
 *
 * 内存预算（2026-11-18，报告二 P2：大词典内存预算）：
 * - 单文件三档：手动导入／单词典下载 64 MiB（UserDictManager.MAX_DICT_FILE_BYTES）；
 *   目录同步单文件 256 MiB（WebDavSync.MAX_SYNC_FILE_BYTES）；整轮上传总量
 *   512 MiB（WebDavSync.SYNC_RUN_MAX_BYTES，一次同步入口内所有 PUT 共享，
 *   超限即中止该次上传、不覆盖远端）。下载不计入整轮上传配额，只受单文件上限约束。
 * - 单批三上限（validateBatch 强制执行）：单行 ≤ MAX_LINE_CHARS 字、
 *   检查行数 ≤ MAX_CHECKED_ROWS 行、合格词条 ≤ MAX_TOTAL_ENTRIES 条；
 *   超行数／超词条数整批拒绝并说明原因（未读完无法确认余下行），超长行是行级问题、
 *   只跳过该行。码本身不限长（librime 解析器不限），单行上限只拦整行病态长的文件。
 * - 峰值：校验只记 64 位词条指纹（dictKeyFingerprint，不存词条对象），归一每键
 *   只存权重（不另建 seen 集）；再大仍受 50 万条上限约束（每键约 200–300 字节，
 *   50 万键约 100–150 MB 上限）——全量磁盘去重（有界
 *   外部排序／磁盘索引）未做：它是同步预处理架构级改动，在安全合并路径上新写
 *   数百行外排代码无法在主机侧证明与内存版逐字节一致，故停下、只留有界上限。
 * - 测量：mergeValidatedBatch 在校验／归一／导入三阶段边界采样堆（sampleHeap，
 *   logcat 搜 "DictMemory"）；复现方法见 UserDictMergeSafetyTest 的 heapSamples*
 *   测试（超多短行档与单条巨长行档）。主机测不了真机低内存峰值，只保证采样点与方法。
 *
 * 本文件只含纯函数与文件 IO，不直接碰引擎：导出/导入由调用方
 * 以 lambda 传入（沿用 [UserDictManager.importUserDictInChunks]
 * 的注入口径），调用方负责在引擎线程串行执行。
 * 单元测试见 `UserDictMergeSafetyTest`。
 */
object UserDictMergeSafety {

    /** 安全副本的目录名（filesDir 下）与文件名：每本词典一份。 */
    const val BACKUP_DIR_NAME = "userdict_backups"
    const val BACKUP_FILE_NAME = "before-merge.json"
    const val BACKUP_FORMAT_VERSION = 1

    /** 词的长度上限：正常词条远小于此值，超限基本是选错文件或坏行。码不限长（与 librime 解析器一致）。 */
    const val MAX_PHRASE_CHARS = 128

    /**
     * 单行长度上限（字）：合法行最长约 1036 字（128 字词＋1 制表符＋约 896 字码，
     * 128 音节×每音节约 7 字＋1 制表符＋≤10 位权重）；8192 字≈8 倍余量。
     * 超长行是行级问题：只跳过该行（见 [IssueCode.LINE_TOO_LONG]），不拒绝整批；
     * 有界按行读（[forEachBoundedLine]）保证超长行只占 8192 字内存、不整行物化。
     * 码本身不限长（librime 解析器不限），本上限只拦“整行病态长”的文件。
     */
    const val MAX_LINE_CHARS = 8192

    /**
     * 单批检查行数上限：含跳过的坏行。是词条上限的 2 倍、他本人词典
     * （19,234 条）的约 50 倍；同时约束扫描耗时与跳过行号集合的大小。
     * 超限整批拒绝（校验未读完，无法确认余下行），见 [IssueCode.TOO_MANY_ROWS]。
     */
    const val MAX_CHECKED_ROWS = 1_000_000

    /**
     * 单批合格词条总数上限：去重前合格行数。去重后 distinct 键 ≤ 此数，
     * 故归一映射表有界（按对象结构估算每键约 200–300 字节，50 万键约
     * 100–150 MB 上限；校验的指纹集约 28 MB。边界采样受 GC 影响，单次
     * 差值波动大，见 heapSamples* 测试输出）。他本人词典 19,234 条，
     * 约 26 倍余量。超限整批拒绝，见 [IssueCode.TOO_MANY_ENTRIES]。
     */
    const val MAX_TOTAL_ENTRIES = 500_000

    /** 校验失败时最多向用户列出的行问题数（其余只计总数）。 */
    const val MAX_ISSUES = 20

    /** 校验问题码：供单测与调用方分支，[Issue.detail] 是给用户看的中文说明。 */
    object IssueCode {
        const val FILE_NOT_UTF8 = "file_not_utf8"
        const val EMPTY_BATCH = "empty_batch"
        const val DICT_NAME = "dict_name"
        const val BAD_COLUMNS = "bad_columns"
        const val EMPTY_PHRASE = "empty_phrase"
        const val BAD_PHRASE = "bad_phrase"
        const val EMPTY_CODE = "empty_code"
        const val BAD_CODE = "bad_code"
        const val BAD_WEIGHT = "bad_weight"
        const val NEGATIVE_WEIGHT = "negative_weight"
        const val COLUMN_ORDER = "column_order"
        const val LINE_TOO_LONG = "line_too_long"
        const val TOO_MANY_ROWS = "too_many_rows"
        const val TOO_MANY_ENTRIES = "too_many_entries"
    }

    /** 一条词条：phrase⇥code，commits 为 null 表示该行无权重列。 */
    data class Entry(val phrase: String, val code: String, val commits: Int?)

    /** （词，码）键：同一词条在不同权重下只保留最大者。 */
    data class DictKey(val phrase: String, val code: String)

    /** 一条校验问题：lineNumber 为 0 表示整批/文件级问题。 */
    data class Issue(val lineNumber: Int, val code: String, val detail: String)

    sealed interface BatchValidation {
        /** 校验通过：可直接进入“备份→去重→导入”。 */
        data class Valid(
            /** 将导入的行数（只计校验通过的行，不含跳过的坏行）。 */
            val entryCount: Int,
            val distinctKeys: Int,
            val maxCommits: Int?,
            /** 来源文件名声明的词典（如 `wanxiang.userdict.txt` 中的 wanxiang），无声明为 null。 */
            val sourceDict: String?,
            /** 来源词典与目标词典不同：跨词典并入（现有功能，允许，仅告知）。 */
            val crossDict: Boolean,
            /** 跳过的坏行（只列前 [MAX_ISSUES] 条，其余只计总数）：行级问题
             *  只跳过该行，不拒绝整批；调用方须把这些行排除在导入文件之外，
             *  并在日志里报告行号。 */
            val skipped: List<Issue> = emptyList(),
            /** 跳过的坏行总数（含未列出的）。 */
            val skippedRows: Int = 0,
            /** 全部跳过行的行号（不截断，供导入文件过滤用）。 */
            val skippedLineNumbers: Set<Int> = emptySet(),
            /** 损坏的跳过行行号（行内含 U+FFFD 替换字符，全集不截断）：
             *  这类行不是词，丢掉无损失；守卫只拦其余有效词被跳过。 */
            val corruptSkippedLineNumbers: Set<Int> = emptySet(),
        ) : BatchValidation

        /** 校验不通过：批级问题（文件非 UTF-8、词典名非法、列序可疑、
         *  空批）整批拒绝，一行不写。行级问题不在此列，见 [Valid.skipped]。 */
        data class Invalid(
            val batchLabel: String,
            val issues: List<Issue>,
            /** 有问题的行数（只计行，不计问题条数）。 */
            val badRows: Int,
            /** 检查过的数据行数（不含空行与注释行）。 */
            val checkedRows: Int,
        ) : BatchValidation {
            /** 给用户看的整批不合格说明：哪一批、共多少行、为什么。 */
            fun userMessage(): String = buildString {
                append("“$batchLabel”这一批不合格，已整批拒绝，未写入任何词条。")
                if (checkedRows > 0) {
                    append("共检查 $checkedRows 行，发现 $badRows 行问题：")
                } else {
                    append("原因：")
                }
                for (issue in issues) {
                    append("\n")
                    if (issue.lineNumber > 0) append("第 ${issue.lineNumber} 行：") else append("整批：")
                    append(issue.detail)
                }
                if (badRows > issues.size) {
                    append("\n……另有 ${badRows - issues.size} 行同类问题未列出")
                }
            }
        }
    }

    /** 合并被中止时抛出的异常：message 已是给用户看的中文说明，调用方直接展示。 */
    class UserDictMergeException(message: String) : IllegalStateException(message)

    /**
     * 一次安全合并的结果（1.6）：只返回导入条数时，跳过的坏行没有任何
     * 返回值／UI 通道交给上层，同步随后无条件裸 PUT 会把远端坏行覆盖
     * 掉。跳过明细随结果返回，由调用方决定是展示还是中止后续 PUT。
     */
    data class MergeResult(
        /** 实际导入的词条数（去重后）。 */
        val importedCount: Int,
        /** 跳过的坏行总数（含未列出的）。 */
        val skippedRows: Int = 0,
        /** 全部跳过行的行号（不截断）。 */
        val skippedLineNumbers: Set<Int> = emptySet(),
        /** 损坏的跳过行行号（行内含 U+FFFD，全集不截断）；守卫只拦其余行。 */
        val corruptSkippedLineNumbers: Set<Int> = emptySet(),
        /** 跳过行的原因（只列前 MAX_ISSUES 条，其余只计总数）。 */
        val skipped: List<Issue> = emptyList(),
        /** 校验／归一／导入三阶段边界的堆采样（见 [sampleHeap]），供测量与诊断用。 */
        val heapSamples: List<HeapSample> = emptyList(),
    )

    /** 一次堆采样（阶段边界用，见 [sampleHeap]）：used = total - free。 */
    data class HeapSample(
        val stage: String,
        val usedBytes: Long,
        val totalBytes: Long,
        val maxBytes: Long,
    ) {
        fun usedMiB(): Double = usedBytes / 1024.0 / 1024.0
    }

    /**
     * 在 [stage] 采样当前堆并记日志（logcat 搜 "DictMemory"）。
     *
     * 测量方法（可复现）：跑 UserDictMergeSafetyTest 里 heapSamples* 的
     * 两个测试——超多短行档与单条巨长行档；测试把四次边界采样打印到
     * 标准输出，对比 validate-after / normalize-after 两档的 usedMiB。
     * 注意这是边界采样、不是真峰值：GC 可能在采样之间回收；真机低内存
     * 机上的真峰值需在同步大词典时看 logcat 的 DictMemory 行，或走
     * Android Studio profiler。主机测不了真机低内存峰值，只保证采样点与方法。
     */
    fun sampleHeap(stage: String): HeapSample {
        val runtime = Runtime.getRuntime()
        val sample = HeapSample(
            stage = stage,
            usedBytes = runtime.totalMemory() - runtime.freeMemory(),
            totalBytes = runtime.totalMemory(),
            maxBytes = runtime.maxMemory(),
        )
        Timber.d(
            "DictMemory stage=%s used=%.1fMiB total=%.1fMiB max=%.1fMiB",
            stage,
            sample.usedMiB(),
            sample.totalBytes / 1024.0 / 1024.0,
            sample.maxBytes / 1024.0 / 1024.0,
        )
        return sample
    }

    /**
     * 有界按行读 [reader]：每行最多保留 [MAX_LINE_CHARS] 字，超长行只把
     * 前 MAX_LINE_CHARS+1 字交给 [onLine] 并置 overlong=true，余下字符
     * 直接丢弃（不进内存）；行号照常递增，超长行之后的行号不错位。
     *
     * 行尾口径与 BufferedReader.readLine 一致（\n、\r\n、\r 都是行尾；
     * 末行无行尾也算一行）。[onLine] 返回 false 即提前停读。
     * 调用方不得关闭 reader（由外层 use 负责）；编码异常直接抛给调用方。
     */
    internal fun forEachBoundedLine(
        reader: BufferedReader,
        onLine: (lineNumber: Int, text: String, overlong: Boolean) -> Boolean,
    ) {
        val buf = CharArray(8192)
        val cur = StringBuilder()
        var lineNumber = 0
        var overlong = false
        var skipLf = false
        fun emit(): Boolean {
            lineNumber++
            val text = cur.toString()
            cur.setLength(0)
            val wasOverlong = overlong
            overlong = false
            return onLine(lineNumber, text, wasOverlong)
        }
        while (true) {
            val read = reader.read(buf)
            if (read < 0) break
            var i = 0
            while (i < read) {
                val ch = buf[i++]
                if (skipLf) {
                    skipLf = false
                    if (ch == '\n') continue
                }
                when (ch) {
                    '\r' -> {
                        if (!emit()) return
                        skipLf = true
                    }
                    '\n' -> {
                        if (!emit()) return
                    }
                    else -> {
                        if (!overlong) {
                            cur.append(ch)
                            if (cur.length > MAX_LINE_CHARS) overlong = true
                        }
                    }
                }
            }
        }
        if (cur.isNotEmpty() || overlong) {
            emit()
        }
    }

    /**
     * 词条指纹（FNV-1a 64 位，取 phrase + '\u0000' + code 的字符序列）：
     * 校验只用它数 distinct（见 [BatchValidation.Valid.distinctKeys]），
     * 不存词条对象——50 万 distinct 键时指纹集约 28 MB，原 HashSet<DictKey>
     * 约 75 MB。碰撞只会让 distinctKeys 少计（50 万条时概率约 3e-8），
     * 且 distinctKeys 只是上报数，不影响任何一行的接受／拒绝与导入内容。
     * 分隔符 '\u0000' 是控制字符，校验通过的行里不可能出现（词／码都禁
     * 控制字符），故指纹无歧义。
     */
    internal fun dictKeyFingerprint(phrase: String, code: String): Long {
        // FNV-1a 64 位：offset basis 14695981039346656037（有符号值为下）、prime 1099511628211。
        var hash = -3750763034362895579L
        for (ch in phrase) {
            hash = (hash xor ch.code.toLong()) * 1099511628211L
        }
        // 分隔符 '\u0000'：xor 0（值不变）再乘一次 prime，相当于多混入一个 0 字符。
        hash *= 1099511628211L
        for (ch in code) {
            hash = (hash xor ch.code.toLong()) * 1099511628211L
        }
        return hash
    }

    /** 行数超限的整批拒绝（校验未读完，无法确认余下行，只能整批拒）。 */
    private fun tooManyRows(batchLabel: String, lineNumber: Int, checkedRows: Int): BatchValidation.Invalid =
        BatchValidation.Invalid(
            batchLabel = batchLabel,
            issues = listOf(
                Issue(
                    0,
                    IssueCode.TOO_MANY_ROWS,
                    "检查到第 $lineNumber 行时已超过单批上限 " +
                        "${String.format(Locale.US, "%,d", MAX_CHECKED_ROWS)} 行，整批已拒绝，" +
                        "一行未写；请把文件拆成每批不超过 " +
                        "${String.format(Locale.US, "%,d", MAX_CHECKED_ROWS)} 行再导入",
                ),
            ),
            badRows = 1,
            checkedRows = checkedRows,
        )

    /** 词条总数超限的整批拒绝（去重前合格行数已超上限）。 */
    private fun tooManyEntries(batchLabel: String, lineNumber: Int, validRows: Int): BatchValidation.Invalid =
        BatchValidation.Invalid(
            batchLabel = batchLabel,
            issues = listOf(
                Issue(
                    0,
                    IssueCode.TOO_MANY_ENTRIES,
                    "检查到第 $lineNumber 行时合格词条已超过单批上限 " +
                        "${String.format(Locale.US, "%,d", MAX_TOTAL_ENTRIES)} 条，整批已拒绝，" +
                        "一行未写；请把文件拆成每批不超过 " +
                        "${String.format(Locale.US, "%,d", MAX_TOTAL_ENTRIES)} 条再导入",
                ),
            ),
            badRows = 1,
            checkedRows = validRows,
        )

    // ---- 校验 ----

    /**
     * 整批校验 [file]：格式、词典归属、与当前 librime 版本的兼容性。
     *
     * 批级问题（整批拒绝，一行不写）：
     * - 文件必须是合法 UTF-8；不是则整批拒绝（native 会按字节读出乱码词条）。
     * - 目标词典名须过 [WebDavSync.isSafeDictName]。
     * - 全批投票：多数特征行呈“码在前”（第 1 列像拼音且第 2 列含汉字）
     *   时，疑似拿 `.userdb.txt` 原生快照当 `.userdict.txt` 导入，
     *   整批拒绝（列序错位会把码当词、词当码全量写坏）。
     * - 文件里没有任何词条（只有空行或注释行）：整批拒绝——手动导入
     *   时用户选错了空文件应被告知；同步路径（allowEmpty）下空文件
     *   是“无词可合”的正常状态，由 [mergeValidatedBatch] 直接跳过。
     * - 单批上限（内存预算，2026-11-18）：检查行数超过 [MAX_CHECKED_ROWS]
     *   行、或合格词条超过 [MAX_TOTAL_ENTRIES] 条时整批拒绝（校验未读完，
     *   无法确认余下行，只能整批拒、不能只取前 N 行）；超限说明点名上限数值，
     *   由调用方原样展示给用户。
     *
     * 行级问题（只跳过该行、其余照常导入，见 [BatchValidation.Valid.skipped]）：
     * - 单行超过 [MAX_LINE_CHARS] 字：只跳过该行（有界读行保证超长行不整行
     *   物化）；其余行照常导入。码本身不限长，本上限只拦整行病态长的文件。
     * - 每行（空行与 `#` 注释行除外）必须是 `词⇥码` 或 `词⇥码⇥权重`，
     *   列数不对跳过该行。
     * - 词非空、≤128 字、无控制字符；码非空、无控制字符、
     *   只能是小写字母/数字/空格（去声调后的拼音编码形态）、无连续空格。
     *   码不限长（librime 解析器本身不限码长，2026-11-17 删掉 Jime 自定的 64 字上限）。
     * - 权重列若有：必须是十进制整数且能装进 Int；负数是 librime 的
     *   删除标记，跳过该行（永不喂给导入器）；`c=5 d=… t=…` 这类
     *   快照值不是整数，同样跳过该行。
     * - 词典归属：来源文件名若声明了另一本词典（如 `wanxiang.userdict.txt`），
     *   只记录为跨词典并入、不拒绝——跨词典并入是现有功能，用户在界面上
     *   选文件/远端文件时已定归属；行内没有词典列，归属只能查到这一层。
     *
     * 口径与对端一致：librime 的 TsvReader 遇坏行记警告后继续导入
     * （`rime/dict/tsv.cc`），松烟只做快照批级校验、空快照直接跳过，
     * Trime 不做行级校验、直接交给 native 导入——三家都不因个别坏行
     * 拒绝整批。
     */
    fun validateBatch(file: File, batchLabel: String, dictName: String): BatchValidation {
        if (!WebDavSync.isSafeDictName(dictName)) {
            return BatchValidation.Invalid(
                batchLabel = batchLabel,
                issues = listOf(
                    Issue(0, IssueCode.DICT_NAME, "目标词典名“$dictName”非法，拒绝合并"),
                ),
                badRows = 1,
                checkedRows = 0,
            )
        }
        val skipped = ArrayList<Issue>()
        var skippedRows = 0
        val skippedLineNumbers = HashSet<Int>()
        val corruptSkippedLineNumbers = HashSet<Int>()
        var checkedRows = 0
        var validRows = 0
        var codeFirstVotes = 0
        var phraseFirstVotes = 0
        // distinct 只记 64 位指纹（dictKeyFingerprint），不存词条对象：
        // 50 万 distinct 键时约 28 MB，原 HashSet<DictKey> 约 75 MB。
        val keyFingerprints = HashSet<Long>()
        var maxCommits: Int? = null
        // 超限即整批拒绝（校验未读完，无法确认余下行）：停读后在循环外返回。
        var limitRejection: BatchValidation.Invalid? = null
        try {
            strictUtf8Reader(file).use { reader ->
                forEachBoundedLine(reader) { lineNumber, raw, overlong ->
                    var line = raw
                    // 首行 BOM：Windows 记事本存的 UTF-8 自带 BOM，首词会被
                    // 粘上 \uFEFF 而永远查不到；校验时容忍，归一化时去掉。
                    if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                        line = line.substring(1)
                    }
                    // 超长行：行级问题，只跳过该行（不 split、不进指纹集）。
                    if (overlong) {
                        checkedRows++
                        if (checkedRows > MAX_CHECKED_ROWS) {
                            limitRejection = tooManyRows(batchLabel, lineNumber, checkedRows)
                            return@forEachBoundedLine false
                        }
                        skippedRows++
                        skippedLineNumbers += lineNumber
                        if (isCorruptLine(raw)) corruptSkippedLineNumbers += lineNumber
                        if (skipped.size < MAX_ISSUES) {
                            skipped += Issue(
                                lineNumber,
                                IssueCode.LINE_TOO_LONG,
                                "单行超过 $MAX_LINE_CHARS 字，已跳过（单行上限 $MAX_LINE_CHARS 字，" +
                                    "合法行最长约 1036 字）；请检查该行是否选错文件或行尾缺失",
                            )
                        }
                        return@forEachBoundedLine true
                    }
                    if (line.isBlank() || line.startsWith("#")) return@forEachBoundedLine true
                    checkedRows++
                    if (checkedRows > MAX_CHECKED_ROWS) {
                        limitRejection = tooManyRows(batchLabel, lineNumber, checkedRows)
                        return@forEachBoundedLine false
                    }
                    val cols = line.split('\t')
                    // 列序投票：先于逐行校验——码在前的行逐行校验必报
                    // BAD_CODE（第 2 列是汉字），若等校验通过再投票，
                    // 码在前多数永远投不出来（COLUMN_ORDER 成死码）。
                    // 与 WebDavSync.decideUserDbColumnOrder 同口径（第 1 列
                    // 像拼音且第 2 列含汉字→码在前，反之→词在前）；不足
                    // 2 列的行不投票。
                    if (cols.size >= 2) {
                        val a = cols[0].trim()
                        val b = cols[1].trim()
                        if (isPinyinLike(a) && containsCjk(b)) codeFirstVotes++
                        else if (containsCjk(a) && isPinyinLike(b)) phraseFirstVotes++
                    }
                    val issue = validateRow(line, lineNumber)
                    if (issue != null) {
                        // 行级问题只跳过该行：其余行照常导入，调用方按
                        // skippedLineNumbers 把这些行排除在导入文件之外。
                        skippedRows++
                        skippedLineNumbers += lineNumber
                        if (isCorruptLine(line)) corruptSkippedLineNumbers += lineNumber
                        if (skipped.size < MAX_ISSUES) skipped += issue
                        return@forEachBoundedLine true
                    }
                    validRows++
                    if (validRows > MAX_TOTAL_ENTRIES) {
                        limitRejection = tooManyEntries(batchLabel, lineNumber, validRows)
                        return@forEachBoundedLine false
                    }
                    val phrase = cols[0]
                    val code = cols[1].trim()
                    val commits = cols.getOrNull(2)?.trim()
                        ?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                    keyFingerprints += dictKeyFingerprint(phrase, code)
                    if (commits != null) maxCommits = maxOf(maxCommits ?: commits, commits)
                    true
                }
            }
        } catch (e: java.nio.charset.CharacterCodingException) {
            return BatchValidation.Invalid(
                batchLabel = batchLabel,
                issues = listOf(
                    Issue(0, IssueCode.FILE_NOT_UTF8, "文件不是合法的 UTF-8 文本，无法按行校验"),
                ),
                badRows = 1,
                checkedRows = checkedRows,
            )
        }
        limitRejection?.let { return it }
        if (checkedRows == 0) {
            return BatchValidation.Invalid(
                batchLabel = batchLabel,
                issues = listOf(
                    Issue(0, IssueCode.EMPTY_BATCH, "文件里没有任何词条（只有空行或注释行）"),
                ),
                badRows = 1,
                checkedRows = 0,
            )
        }
        if (codeFirstVotes > phraseFirstVotes && codeFirstVotes > 0) {
            // 批级问题：多数行码在前，疑似拿原生快照当导入文件，整批拒绝。
            // 行级问题不另列——它们是列序错位的后果，不是独立问题。
            return BatchValidation.Invalid(
                batchLabel = batchLabel,
                issues = listOf(
                    Issue(
                        0,
                        IssueCode.COLUMN_ORDER,
                        "多数行是“码在前、词在后”，疑似 Rime 原生快照（.userdb.txt），" +
                            "不是导入要的“词⇥码⇥权重”格式；直接导入会把码当词全量写坏，" +
                            "请用 WebDAV 下载 .userdb.txt（会自动转置）或改成词在前的格式再导入",
                    ),
                ),
                badRows = 1,
                checkedRows = checkedRows,
            )
        }
        val sourceDict = sourceDictFromLabel(batchLabel)
        return BatchValidation.Valid(
            entryCount = validRows,
            distinctKeys = keyFingerprints.size,
            maxCommits = maxCommits,
            sourceDict = sourceDict,
            crossDict = sourceDict != null && sourceDict != dictName,
            skipped = skipped,
            skippedRows = skippedRows,
            skippedLineNumbers = skippedLineNumbers,
            corruptSkippedLineNumbers = corruptSkippedLineNumbers,
        )
    }

    /**
     * 损坏行判定：行内含 U+FFFD（替换字符）——原始字节无法解码、早先环节已用
     * 替换字符占位，或文件里就写着它；这种行不是词，丢掉无损失。口径收紧：
     * 只认 U+FFFD；码含大写/声调、负权重等有效词被跳过仍要拦。
     */
    private fun isCorruptLine(text: String): Boolean = text.contains('\uFFFD')

    /** 校验单行（已排除空行与注释行）：有问题返回第一条问题，否则 null。 */
    private fun validateRow(line: String, lineNumber: Int): Issue? {
        val cols = line.split('\t')
        if (cols.size < 2 || cols.size > 3) {
            return Issue(
                lineNumber,
                IssueCode.BAD_COLUMNS,
                "应为“词⇥码”或“词⇥码⇥权重”（2 或 3 列），实际 ${cols.size} 列",
            )
        }
        val phrase = cols[0]
        if (phrase.isBlank()) {
            return Issue(lineNumber, IssueCode.EMPTY_PHRASE, "词为空")
        }
        if (phrase.length > MAX_PHRASE_CHARS || phrase.any { it.code < 0x20 || it.code == 0x7F }) {
            return Issue(
                lineNumber,
                IssueCode.BAD_PHRASE,
                "词过长（超过 $MAX_PHRASE_CHARS 字）或含控制字符",
            )
        }
        val code = cols[1].trim()
        if (code.isEmpty()) {
            return Issue(lineNumber, IssueCode.EMPTY_CODE, "码为空")
        }
        if (code.any { it.code < 0x20 || it.code == 0x7F } ||
            !code.all { it in 'a'..'z' || it in '0'..'9' || it == ' ' } ||
            code.contains("  ")
        ) {
            // 逐因点名：三种原因各给一句话，不混报。
            // 回显截断到 64 字：码本身可达数百字，全文进日志会把诊断撑爆。
            val shownCode = code.take(64)
            val reason = when {
                code.any { it.code < 0x20 || it.code == 0x7F } ->
                    "码“$shownCode”含控制字符"
                !code.all { it in 'a'..'z' || it in '0'..'9' || it == ' ' } ->
                    "码“$shownCode”不是小写字母/数字/空格组成的拼音编码" +
                        "（含大写、声调符号的码导入后永远查不到）"
                else -> "码“$shownCode”含连续空格（导入后永远查不到）"
            }
            return Issue(lineNumber, IssueCode.BAD_CODE, reason)
        }
        val weight = cols.getOrNull(2)?.trim().orEmpty()
        if (weight.isNotEmpty()) {
            val parsed = weight.toIntOrNull()
            if (parsed == null) {
                return Issue(
                    lineNumber,
                    IssueCode.BAD_WEIGHT,
                    "权重“$weight”不是整数（当前 librime 会把它静默当成 0，" +
                        "这行等于没导入）；快照值如 c=5 d=… t=… 请走 .userdb.txt 转置导入",
                )
            }
            if (parsed < 0) {
                return Issue(
                    lineNumber,
                    IssueCode.NEGATIVE_WEIGHT,
                    "权重 $parsed 为负：负权重在 librime 里是删除标记，" +
                        "会删掉本地已有的同词条，整批已拒绝",
                )
            }
        }
        return null
    }

    /** 从批次名（如 `wanxiang.userdict.txt`）取来源词典名，无声明返回 null。 */
    private fun sourceDictFromLabel(batchLabel: String): String? {
        val name = batchLabel.substringAfterLast('/').substringAfterLast('\\')
        for (suffix in listOf(".userdict.txt", ".userdb.txt")) {
            if (name.endsWith(suffix, ignoreCase = true)) {
                return name.dropLast(suffix.length).takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** 是否像拼音编码：去声调后仅含小写字母和空格（与 WebDavSync 同口径）。 */
    private fun isPinyinLike(s: String): Boolean {
        val norm = stripTones(s)
        return norm.isNotEmpty() && norm.all { it in 'a'..'z' || it == ' ' }
    }

    /** 是否包含 CJK 汉字（基本区 + 扩展 A 区，与 WebDavSync 同口径）。 */
    private fun containsCjk(s: String): Boolean =
        s.any { it in '㐀'..'䶿' || it in '一'..'鿿' }

    /** 去掉拼音里的声调符号：`yào` → `yao`，ü 按 Rime 习惯转为 v。 */
    private fun stripTones(s: String): String {
        if (s.isEmpty()) return s
        val pre = s.replace('ü', 'v').replace('Ü', 'V')
            .replace('ǖ', 'v').replace('ǘ', 'v').replace('ǚ', 'v').replace('ǜ', 'v')
        val decomposed = Normalizer.normalize(pre, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (ch !in '̀'..'ͯ') sb.append(ch)
        }
        return sb.toString()
    }

    private fun strictUtf8Reader(file: File): BufferedReader {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return BufferedReader(InputStreamReader(FileInputStream(file), decoder))
    }

    // ---- 批内去重：同词条保留较大权重 ----

    /**
     * 批内去重：同一（词，码）只留一行，权重取批内最大值；无权重行与
     * 有权重行并存时取有权重的那行；注释行与空行原样保留；首行 BOM 去掉。
     * [skipLines] 是校验跳过的坏行行号（见 [BatchValidation.Valid.skippedLineNumbers]）：
     * 这些行两遍都不进入归一结果，永不喂给导入器。
     *
     * native 导入器本身也会逐行取大，但先归一可保证：切块边界、重复行
     * 不会让“较小权重后写”这种时序问题有任何可乘之机，且导入文件
     * 本身就是“较大权重”的定稿。
     * 无重复行、无 BOM 且无跳过行时直接返回 [input]（不重写）；否则写新文件并返回它，
     * 调用方负责删除返回的文件（与入参不同时）。
     */
    fun normalizeBatchFile(input: File, workDir: File, skipLines: Set<Int> = emptySet()): File {
        // 每键只存权重（Int?，null = 无权重行），不存 Entry 对象：
        // 写出时词/码取当前行自身的列——同键各行的词/码按定义相等，
        // 输出与旧版存 Entry 逐字节一致，省掉一份 Entry 对象。
        // 第二遍不另建 seen 集：首现行写出后即从表里删掉该键，后续
        // 重复行查不到键即跳过——与旧版 seen 集逐行等价。
        // 两遍都走有界读行：超长行不参与去重计算；触发重写时被丢弃，
        // 绝不写出截断行（无重写时直接返回原文件）。
        val weights = LinkedHashMap<DictKey, Int?>()
        var hasDuplicate = false
        var hasBom = false
        strictUtf8Reader(input).use { reader ->
            forEachBoundedLine(reader) { lineNumber, raw, overlong ->
                var line = raw
                if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1)
                    hasBom = true
                }
                if (overlong) return@forEachBoundedLine true
                if (lineNumber in skipLines) return@forEachBoundedLine true
                if (line.isBlank() || line.startsWith("#")) return@forEachBoundedLine true
                val cols = line.split('\t')
                if (cols.size < 2 || cols.size > 3) return@forEachBoundedLine true
                val key = DictKey(cols[0], cols[1].trim())
                val commits = cols.getOrNull(2)?.trim()
                    ?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                if (key in weights) {
                    hasDuplicate = true
                    val prev = weights.getValue(key)
                    val best = when {
                        prev == null -> commits
                        commits == null -> prev
                        else -> maxOf(prev, commits)
                    }
                    weights[key] = best
                } else {
                    weights[key] = commits
                }
                true
            }
        }
        if (!hasDuplicate && !hasBom && skipLines.isEmpty()) return input
        val out = File(workDir, "${input.name}.normalized-${System.nanoTime()}")
        out.bufferedWriter(Charsets.UTF_8).use { writer ->
            strictUtf8Reader(input).use { reader ->
                forEachBoundedLine(reader) { lineNumber, raw, overlong ->
                    var line = raw
                    if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                        line = line.substring(1)
                    }
                    if (overlong) return@forEachBoundedLine true
                    if (lineNumber in skipLines) return@forEachBoundedLine true
                    if (line.isBlank() || line.startsWith("#")) {
                        writer.write(line)
                        writer.newLine()
                        return@forEachBoundedLine true
                    }
                    val cols = line.split('\t')
                    if (cols.size < 2 || cols.size > 3) return@forEachBoundedLine true
                    val key = DictKey(cols[0], cols[1].trim())
                    if (key !in weights) return@forEachBoundedLine true
                    val commits = weights.remove(key)
                    writer.write(cols[0])
                    writer.write("\t")
                    writer.write(cols[1].trim())
                    if (commits != null) {
                        writer.write("\t")
                        writer.write(commits.toString())
                    }
                    writer.newLine()
                    true
                }
            }
        }
        return out
    }

    // ---- 合并前安全副本 before-merge.json ----

    /** 合并前备份快照：当前词库的全部词条 + 批次来源（可恢复的依据）。 */
    data class BackupSnapshot(
        val dictName: String,
        val sourceLabel: String,
        val createdAtMs: Long,
        val entries: List<Entry>,
    )

    /** 该词典的安全副本路径：`<baseDir>/userdict_backups/<词典>/before-merge.json`。 */
    fun backupFileFor(baseDir: File, dictName: String): File {
        require(WebDavSync.isSafeDictName(dictName)) { "词典名非法，拒绝备份" }
        return File(File(baseDir, BACKUP_DIR_NAME), "$dictName/$BACKUP_FILE_NAME")
    }

    /** 是否已有合并前备份（恢复入口有无可恢复内容的依据）。 */
    fun hasBeforeMergeBackup(baseDir: File, dictName: String): Boolean =
        runCatching { backupFileFor(baseDir, dictName).isFile }.getOrDefault(false)

    /** 读备份元数据（时间/条数/来源，供界面展示；无备份返回 null）。 */
    fun readBackupMeta(baseDir: File, dictName: String): BackupSnapshot? {
        val file = runCatching { backupFileFor(baseDir, dictName) }.getOrNull() ?: return null
        if (!file.isFile) return null
        return runCatching { readBeforeMergeBackup(file) }.getOrNull()
    }

    /**
     * 解析引擎导出的词典文本（`词⇥码⇥权重`，TableDb 导出格式）：
     * 注释行与空行跳过；权重列必须存在且为 ≥0 的整数，否则抛错——
     * 引擎自己导出的文件本应全是干净行，脏行说明导出已坏，备份
     * 不能建立在脏数据上。
     */
    fun parseExportFile(file: File): List<Entry> {
        val entries = ArrayList<Entry>()
        strictUtf8Reader(file).useLines { lines ->
            var lineNumber = 0
            for (raw in lines) {
                lineNumber++
                var line = raw
                if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1)
                }
                if (line.isBlank() || line.startsWith("#")) continue
                val cols = line.split('\t')
                if (cols.size != 3) {
                    throw IllegalStateException("导出文件第 $lineNumber 行不是“词⇥码⇥权重”")
                }
                val commits = cols[2].trim().toIntOrNull()
                    ?: throw IllegalStateException("导出文件第 $lineNumber 行权重不是整数")
                if (commits < 0) {
                    throw IllegalStateException("导出文件第 $lineNumber 行权重为负")
                }
                entries += Entry(cols[0], cols[1].trim(), commits)
            }
        }
        return entries
    }

    /**
     * 把 [exportFile]（当前词库的导出文本）写成安全副本。
     * 先写临时文件再改名换位：写一半崩了也不会留半截备份冒充完整备份。
     */
    fun writeBeforeMergeBackup(
        baseDir: File,
        dictName: String,
        sourceLabel: String,
        exportFile: File,
        nowMs: Long = System.currentTimeMillis(),
    ): BackupSnapshot {
        val snapshot = BackupSnapshot(dictName, sourceLabel, nowMs, parseExportFile(exportFile))
        val target = backupFileFor(baseDir, dictName)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$BACKUP_FILE_NAME.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(buildBackupJson(snapshot), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                // 同目录改名失败多半是旧备份占位：删旧再改名，仍失败才报错。
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw IllegalStateException("合并前备份换位失败")
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return snapshot
    }

    /** 读安全副本：格式不对（版本/字段/类型）一律抛错，不读半截数据。 */
    fun readBeforeMergeBackup(file: File): BackupSnapshot {
        val root = JsonParser(file.readText(Charsets.UTF_8)).parse()
            as? Map<*, *> ?: throw IllegalStateException("备份文件损坏：顶层不是对象")
        val version = (root["version"] as? Number)?.toLong()
            ?: throw IllegalStateException("备份文件损坏：缺少 version")
        if (version != BACKUP_FORMAT_VERSION.toLong()) {
            throw IllegalStateException("备份文件版本 $version 不受支持")
        }
        val dictName = root["dict"] as? String
            ?: throw IllegalStateException("备份文件损坏：缺少 dict")
        val sourceLabel = root["source"] as? String
            ?: throw IllegalStateException("备份文件损坏：缺少 source")
        val createdAtMs = (root["createdAtMs"] as? Number)?.toLong()
            ?: throw IllegalStateException("备份文件损坏：缺少 createdAtMs")
        val rawEntries = root["entries"] as? List<*>
            ?: throw IllegalStateException("备份文件损坏：缺少 entries")
        val entries = rawEntries.map { raw ->
            val obj = raw as? Map<*, *>
                ?: throw IllegalStateException("备份文件损坏：词条不是对象")
            val phrase = obj["p"] as? String
                ?: throw IllegalStateException("备份文件损坏：词条缺少 p")
            val code = obj["c"] as? String
                ?: throw IllegalStateException("备份文件损坏：词条缺少 c")
            val commits = (obj["w"] as? Number)?.toInt()
            Entry(phrase, code, commits)
        }
        return BackupSnapshot(dictName, sourceLabel, createdAtMs, entries)
    }

    private fun buildBackupJson(snapshot: BackupSnapshot): String = buildString {
        append("{\"version\":").append(BACKUP_FORMAT_VERSION)
        append(",\"dict\":").append(jsonString(snapshot.dictName))
        append(",\"source\":").append(jsonString(snapshot.sourceLabel))
        append(",\"createdAtMs\":").append(snapshot.createdAtMs)
        append(",\"entries\":[")
        snapshot.entries.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append("{\"p\":").append(jsonString(entry.phrase))
            append(",\"c\":").append(jsonString(entry.code))
            if (entry.commits != null) append(",\"w\":").append(entry.commits)
            append('}')
        }
        append("]}")
    }

    private fun jsonString(s: String): String = "\"${jsonEscape(s)}\""

    internal fun jsonEscape(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    /** 只够读本文件写出的 JSON 的最小解析器（不引入新依赖；平台 org.json 在单测里不可用）。 */
    internal class JsonParser(private val text: String) {
        private var pos = 0

        fun parse(): Any? {
            val value = parseValue()
            skipWs()
            if (pos != text.length) throw IllegalStateException("备份文件损坏：尾部多余内容")
            return value
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= text.length) throw IllegalStateException("备份文件损坏：意外结束")
            return when (text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expectLiteral("true").let { true }
                'f' -> expectLiteral("false").let { false }
                'n' -> expectLiteral("null").let { null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                map[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalStateException("备份文件损坏：对象缺少 , 或 }")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list += parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalStateException("备份文件损坏：数组缺少 , 或 ]")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) break
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > text.length) {
                                    throw IllegalStateException("备份文件损坏：\\u 转义不完整")
                                }
                                val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw IllegalStateException("备份文件损坏：\\u 转义非法")
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> throw IllegalStateException("备份文件损坏：未知转义 \\$esc")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalStateException("备份文件损坏：字符串未闭合")
        }

        private fun parseNumber(): Long {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (start == pos || (start + 1 == pos && text[start] == '-')) {
                throw IllegalStateException("备份文件损坏：数字非法")
            }
            return text.substring(start, pos).toLongOrNull()
                ?: throw IllegalStateException("备份文件损坏：数字越界")
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, pos)) {
                throw IllegalStateException("备份文件损坏：期望 $literal")
            }
            pos += literal.length
        }

        private fun skipWs() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        private fun peek(): Char =
            if (pos < text.length) text[pos] else throw IllegalStateException("备份文件损坏：意外结束")

        private fun expect(ch: Char) {
            skipWs()
            if (peek() != ch) throw IllegalStateException("备份文件损坏：期望 $ch")
            pos++
        }
    }

    // ---- 恢复：按备份把合并抹掉 ----

    /** 恢复计划：先删标记、再按备份重写（恢复文件里的行顺序即此顺序）。 */
    data class RestorePlan(
        /** 要删的词条：合并后新增的 + 权重被抬高的（写 -1 删除标记行）。 */
        val toDelete: List<Entry>,
        /** 要按备份权重重写的词条（删除标记行之后写）。 */
        val toReadd: List<Entry>,
        val unchanged: Int,
        /** 合并后新增、将被删掉的词条数。 */
        val removed: Int,
        /** 权重被恢复（或失而复得）的词条数。 */
        val restored: Int,
    ) {
        val totalRows: Int get() = toDelete.size + toReadd.size
    }

    data class RestoreReport(
        val removed: Int,
        val restored: Int,
        val unchanged: Int,
        val importedRows: Int,
    )

    /**
     * 按备份算恢复计划（纯函数）。
     *
     * - 当前有、备份没有 → 合并后新增的：删掉。
     * - 当前权重 > 备份权重 → 合并抬高的：先删标记、再按备份权重重写。
     * - 当前权重 ≤ 备份权重 → 不动（native 只取大，合并没动过它）。
     * - 备份有、当前没有（正常合并不会发生，防御）→ 按备份补回。
     *
     * 局限（native 导入器只取大的固有限制，恢复流程如实遵守）：
     * dee（词频衰减值）恢复时取新旧较大者，不保证逐字节回到备份值；
     * 提交次数与词条集合可精确恢复。
     */
    fun planRestore(backup: List<Entry>, current: List<Entry>): RestorePlan {
        val backupMap = backup.associateBy { DictKey(it.phrase, it.code) }
        val currentMap = current.associateBy { DictKey(it.phrase, it.code) }
        val toDelete = ArrayList<Entry>()
        val toReadd = ArrayList<Entry>()
        var unchanged = 0
        var removed = 0
        var restored = 0
        for ((key, cur) in currentMap) {
            val old = backupMap[key]
            when {
                old == null -> {
                    toDelete += Entry(cur.phrase, cur.code, -1)
                    removed++
                }
                (cur.commits ?: 0) > (old.commits ?: 0) -> {
                    toDelete += Entry(cur.phrase, cur.code, -1)
                    toReadd += old
                    restored++
                }
                else -> unchanged++
            }
        }
        for ((key, old) in backupMap) {
            if (key !in currentMap) {
                toReadd += old
                restored++
            }
        }
        return RestorePlan(toDelete, toReadd, unchanged, removed, restored)
    }

    /** 把恢复计划写成可导入的临时文件：删除标记行在前、重写行在后。 */
    fun writeRestoreFile(plan: RestorePlan, dest: File): Int {
        dest.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (entry in plan.toDelete) {
                writer.write(entry.phrase)
                writer.write("\t")
                writer.write(entry.code)
                writer.write("\t-1")
                writer.newLine()
            }
            for (entry in plan.toReadd) {
                writer.write(entry.phrase)
                writer.write("\t")
                writer.write(entry.code)
                if (entry.commits != null) {
                    writer.write("\t")
                    writer.write(entry.commits.toString())
                }
                writer.newLine()
            }
        }
        return plan.totalRows
    }

    // ---- 一站式安全合并：校验 → 备份 → 去重 → 导入 ----

    /**
     * 安全合并一批词条（手动导入与同步合并共用此入口）。
     *
     * 顺序：① 校验。批级问题（文件非 UTF-8、词典名非法、列序可疑、
     * 空批）抛 [UserDictMergeException]（一行不写）；行级问题只跳过
     * 该行、其余照常导入，并在日志里报告跳过的行号。② 导出当前词库、
     * 写 `before-merge.json` 安全副本，导出或写备份失败同样抛错中止
     * （没备份就不合并）；③ 批内去重取最大权重（跳过的行已排除）；
     * ④ 经调用方传入的 [importFile] 导入（调用方负责在引擎线程串行执行，
     * 通常是 `importUserDictInChunks` + `importUserDictLive`）。
     *
     * [allowEmpty]：同步路径传 true——远端文件里没有任何词条是“无词
     * 可合”的正常状态，直接返回 0，不记失败、不跑导出/备份/导入；
     * 手动导入传 false（默认）：用户亲手选了个空文件，应报错告知。
     *
     * 返回 [MergeResult]：导入条数＋跳过明细（数量／行号／原因）＋
     * 校验／归一／导入三阶段边界的堆采样（[HeapSample]，供测量与诊断）。
     * 调用方不得只取条数丢弃跳过明细——同步上传路径有跳过行时必须
     * 中止 PUT（见 WebDavSync.checkNoSkippedRowsForUpload），手动
     * 导入成功后须展示跳过数。
     *
     * [exportCurrent]/[importFile] 收到的是调用方准备好的临时文件路径，
     * 返回引擎的条数（<0 为失败）。临时文件由本函数负责清理；
     * [batchFile] 仍归调用方（暂存/下载的临时文件按原有 finally 删除）。
     */
    suspend fun mergeValidatedBatch(
        dictName: String,
        batchFile: File,
        batchLabel: String,
        filesDir: File,
        cacheDir: File,
        exportCurrent: suspend (File) -> Int,
        importFile: suspend (File) -> Int,
        allowEmpty: Boolean = false,
    ): MergeResult {
        // 三阶段边界堆采样（内存预算测量点）：校验前→校验后→归一后→导入后。
        val heapSamples = ArrayList<HeapSample>()
        heapSamples += sampleHeap("validate-before")
        val valid = when (val validation = validateBatch(batchFile, batchLabel, dictName)) {
            is BatchValidation.Invalid -> {
                if (allowEmpty && validation.issues.size == 1 &&
                    validation.issues[0].code == IssueCode.EMPTY_BATCH
                ) {
                    // 空词典同步：无词可合不是失败（松烟同口径：空快照直接跳过）。
                    Timber.i("跳过空词典合并：$batchLabel（文件里没有任何词条）")
                    heapSamples += sampleHeap("validate-after")
                    return MergeResult(0, heapSamples = heapSamples)
                }
                throw UserDictMergeException(validation.userMessage())
            }
            is BatchValidation.Valid -> {
                if (validation.skipped.isNotEmpty()) {
                    Timber.w(
                        "“$batchLabel”跳过 ${validation.skippedRows} 行不合格词条，" +
                            "其余 ${validation.entryCount} 行照常导入：" +
                            validation.skipped.joinToString("；") {
                                "第 ${it.lineNumber} 行：${it.detail}"
                            } +
                            if (validation.skippedRows > validation.skipped.size) {
                                "；另有 ${validation.skippedRows - validation.skipped.size} 行未列出"
                            } else {
                                ""
                            },
                    )
                }
                validation
            }
        }
        heapSamples += sampleHeap("validate-after")
        val exportTmp = File(cacheDir, "before-merge-export-$dictName-${System.nanoTime()}.txt")
        try {
            val exported = exportCurrent(exportTmp)
            if (exported < 0) {
                throw UserDictMergeException("导出当前用户词典失败，合并已中止，未写入任何词条")
            }
            try {
                writeBeforeMergeBackup(filesDir, dictName, batchLabel, exportTmp)
            } catch (e: Exception) {
                throw UserDictMergeException("合并前备份失败，合并已中止，未写入任何词条（${e.message}）")
            }
        } finally {
            exportTmp.delete()
        }
        val normalized = normalizeBatchFile(batchFile, cacheDir, valid.skippedLineNumbers)
        heapSamples += sampleHeap("normalize-after")
        try {
            val count = importFile(normalized)
            if (count < 0) {
                throw UserDictMergeException("导入用户词典失败")
            }
            heapSamples += sampleHeap("import-after")
            return MergeResult(
                importedCount = count,
                skippedRows = valid.skippedRows,
                skippedLineNumbers = valid.skippedLineNumbers,
                skipped = valid.skipped,
                corruptSkippedLineNumbers = valid.corruptSkippedLineNumbers,
                heapSamples = heapSamples,
            )
        } finally {
            if (normalized != batchFile) normalized.delete()
        }
    }

    /**
     * 从 `before-merge.json` 恢复：把合并后新增的词删掉、被抬高的权重
     * 恢复到备份值。删除标记行（-1）只允许在这条用户确认的恢复流程
     * 内部产生，合并流程（[mergeValidatedBatch]）永远不产生。
     *
     * 无备份、备份损坏、导出失败、导入失败都返回 failure，不动词库；
     * 当前已与备份一致时成功返回 importedRows=0 的报告（无需恢复）。
     */
    suspend fun restoreBeforeMerge(
        dictName: String,
        filesDir: File,
        cacheDir: File,
        exportCurrent: suspend (File) -> Int,
        importFile: suspend (File) -> Int,
    ): Result<RestoreReport> {
        val backupFile = try {
            backupFileFor(filesDir, dictName)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        if (!backupFile.isFile) {
            return Result.failure(IllegalStateException("没有“$dictName”的合并前备份，无法恢复"))
        }
        val backup = try {
            readBeforeMergeBackup(backupFile)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("合并前备份已损坏，无法恢复（${e.message}）"))
        }
        val exportTmp = File(cacheDir, "restore-current-$dictName-${System.nanoTime()}.txt")
        val restoreTmp = File(cacheDir, "restore-plan-$dictName-${System.nanoTime()}.txt")
        try {
            val exported = exportCurrent(exportTmp)
            if (exported < 0) {
                return Result.failure(IllegalStateException("导出当前用户词典失败，恢复已中止"))
            }
            val current = try {
                parseExportFile(exportTmp)
            } catch (e: Exception) {
                return Result.failure(IllegalStateException("当前词库导出内容异常，恢复已中止（${e.message}）"))
            }
            val plan = planRestore(backup.entries, current)
            if (plan.totalRows == 0) {
                return Result.success(
                    RestoreReport(
                        removed = 0,
                        restored = 0,
                        unchanged = plan.unchanged,
                        importedRows = 0,
                    ),
                )
            }
            writeRestoreFile(plan, restoreTmp)
            val imported = importFile(restoreTmp)
            if (imported < 0) {
                return Result.failure(IllegalStateException("恢复导入失败，词库可能处于中间状态，请重试恢复"))
            }
            return Result.success(
                RestoreReport(
                    removed = plan.removed,
                    restored = plan.restored,
                    unchanged = plan.unchanged,
                    importedRows = imported,
                ),
            )
        } finally {
            exportTmp.delete()
            restoreTmp.delete()
        }
    }
}
