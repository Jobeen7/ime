package com.jobeen.ime.input.stats

import android.os.SystemClock
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.store.AtomicFiles
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 输入统计（2026-10-29 item-1 C）：本地仪表盘的数据层。
 *
 * - 只记数量与耗时，绝不记输入内容、候选词、剪贴板、词典：
 *   计数维度是提交次数／字数（提交文本长度）／按键次数／删除
 *   次数／打字时长／小时分布／动作类别／词长分布（提交串长度
 *   分档：1字／2字／3字／4字以上）／误触修正（删除后5秒内再
 *   输入记1次）／键盘热力（仅按键身份计数，见 heatKeyOf；
 *   只记网格集内的按键标签，不记提交文本、候选词、剪贴板、
 *   词典内容；非网格键不映射）。
 * - 周期：日（当日）／周（近 7 天滚动）／月（自然月）／年
 *   （自然年），均由按日存储聚合；另按方案分（方案 id 来自
 *   状态管理器的 setScheme，未知方案记 "" 桶）。
 * - 速度：字数 ÷ 打字时长（按键间隔≤5s 的累计，空闲不计）；
 *   平均＝周期字数 ÷ 周期打字时长；最快＝单日速度最大值
 *   （当日字数≥50 才参与，避免小样本失真）。
 * - 准确率是推断值：提交字数 ÷（提交字数＋删除数），并在界面
 *   注明是推断（删除不一定都是纠错）。
 * - 误触率口径：误触修正数 ÷ 按键总数；0 按键时屏上显“—”。
 *   误触修正＝删除按键后 5000ms 内（与打字时长空闲上限同口径）
 *   又有文字输入按键（输入／空格／选词），一次删除事件只记 1 次；
 *   删除后先出现回车／切换／功能键不消耗该次删除，超窗自动过期。
 * - 词长按提交串 String.length 分档（非字素簇；表情等代理对占 2）。
 *   词长与误触均为向前采集：旧 stats.json 缺字段按 0 读入，不编
 *   历史数据。
 * - 段位来自终身字数（lifetimeChars）：清统计只清周期数据，
 *   终身字数与段位保留。段位为五阶（2026-10-29 Lee 决定）：
 *   炼气期／筑基期／金丹期／元婴期／化神期，阈值取旧七段位
 *   表的合并边界（0／2000／8000／20000／150000），待否决。
 * - 最快速度为派生最大值，无独立存储；“清除极速记录”用基线
 *   日实现：fastestBaseline 之后（含当日之后）的新单日才参评，
 *   其余数据不动；导出／导入连同基线日一起走。
 * - 存储：filesDir/input_stats/stats.json（AtomicFiles 原子写，
 *   5s 防抖落盘＋窗口 detach 时 flush）；本地存储，不上传；
 *   JSON 导出／导入整份状态（导入校验 version＝1，非法拒绝
 *   且不改动现有数据）。
 */
object InputStats {

    /** 动作类别（热力维度，按动作类型，不按字符值）。 */
    enum class Category(val label: String) {
        INPUT("输入"),
        SPACE("空格"),
        DELETE("删除"),
        ENTER("回车"),
        CANDIDATE("选词"),
        SWITCH("切换"),
        FUNCTION("功能"),
    }

    data class Counters(
        val commits: Long = 0,
        val chars: Long = 0,
        val keypresses: Long = 0,
        val deletes: Long = 0,
        val typingMs: Long = 0,
        /** 提交串长度分档：1字／2字／3字／4字以上（向前采集）。 */
        val wordLen1: Long = 0,
        val wordLen2: Long = 0,
        val wordLen3: Long = 0,
        val wordLen4plus: Long = 0,
        /** 误触修正数（删除后 5000ms 内再输入记 1 次，向前采集）。 */
        val corrections: Long = 0,
    ) {
        operator fun plus(other: Counters) = Counters(
            commits + other.commits,
            chars + other.chars,
            keypresses + other.keypresses,
            deletes + other.deletes,
            typingMs + other.typingMs,
            wordLen1 + other.wordLen1,
            wordLen2 + other.wordLen2,
            wordLen3 + other.wordLen3,
            wordLen4plus + other.wordLen4plus,
            corrections + other.corrections,
        )
    }

    data class DayRecord(
        val total: Counters = Counters(),
        val schemes: Map<String, Counters> = emptyMap(),
        val hours: List<Long> = List(24) { 0L },
        val categories: Map<String, Long> = emptyMap(),
        /** 键盘热力：按键身份 → 次数（仅总量桶，不分方案桶）。 */
        val heat: Map<String, Long> = emptyMap(),
    )

    data class PeriodStats(
        val total: Counters,
        val schemes: Map<String, Counters>,
        val hours: List<Long>,
        val categories: Map<String, Long>,
        /** 平均速度（字／分钟），无打字时长时为 null。 */
        val avgSpeed: Double?,
        /** 准确率推断值，无数据时为 null。 */
        val accuracy: Double?,
        /** 键盘热力聚合（全局量；方案过滤快照返回空表，不误归属）。 */
        val heat: Map<String, Long> = emptyMap(),
    )

    data class Rank(
        val name: String,
        val level: Int,
        /** 到下一段位的进度（0..1；已满级为 1）。 */
        val progress: Double,
        val xp: Long,
        val nextThreshold: Long?,
    )

    enum class Period { DAY, WEEK, MONTH, YEAR }

    /**
     * 段位阈值（终身字数）与名称：五阶（2026-10-29 Lee 决定，
     * 取代图示凡键境／初识键盘与旧七段位）。阈值取旧七段位表
     * 的合并边界：炼气＝萌新＋入门段（0–1999），筑基＝熟练段，
     * 金丹＝精通段，元婴＝专家＋大师段，化神＝传奇段（150000＋）。
     * 本映射为提议口径，交回等否决；不得编造用户数据。
     */
    private val RANK_TABLE = listOf(
        0L to "炼气期",
        2000L to "筑基期",
        8000L to "金丹期",
        20000L to "元婴期",
        150000L to "化神期",
    )

    /**
     * 键盘热力网格集（与屏上 QWERTY 网格完全对应，有界存储）：
     * 26 字母（小写归一化）＋中文／符号占位外的功能键标签。
     * 标点折叠：全角“，”→“，”，全角“。”→“.”；“/”本身。
     * 符号键无唯一动作身份（LayoutSwitch 目标不定），不映射；
     * T9 数字键等非网格键不映射。
     */
    val HEAT_GRID: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("shift", "z", "x", "c", "v", "b", "n", "m", "退格"),
        listOf("中文", "符号", "，", "空格", ".", "/", "回车"),
    )
    private val HEAT_KEYS: Set<String> = HEAT_GRID.flatten().toSet()

    /** 按键动作 → 热力身份（null＝不在网格集内，不记）。 */
    fun heatKeyOf(action: KeyboardAction): String? {
        val raw: String = when (action) {
            is KeyboardAction.KeySequenceAction -> action.sequence
            is KeyboardAction.SpaceAction -> "空格"
            is KeyboardAction.BackspaceAction,
            is KeyboardAction.DeleteForwardAction -> "退格"
            is KeyboardAction.ReturnAction,
            is KeyboardAction.MultiReturnAction -> "回车"
            is KeyboardAction.CapsAction -> "shift"
            is KeyboardAction.LangSwitchAction -> "中文"
            else -> null
        } ?: return null
        val folded = when (raw) {
            "，" -> "，"
            "。" -> "."
            else -> raw.lowercase()
        }
        return folded.takeIf { it in HEAT_KEYS }
    }

    /** 打字时长累计：单次按键间隔超过它视为空闲，不计入。 */
    const val TYPING_GAP_CAP_MS = 5000L

    /** 最快速度参评门槛：单日字数低于它不参与最快统计。 */
    const val FASTEST_MIN_CHARS = 50L

    const val STATS_FILE = "stats.json"
    const val FLUSH_DEBOUNCE_MS = 5000L

    internal var clockMs: () -> Long = { SystemClock.elapsedRealtime() }
    internal var wallMs: () -> Long = { System.currentTimeMillis() }

    private val lock = Any()
    private var statsDir: File? = null
    private var days: MutableMap<String, DayRecord> = mutableMapOf()
    private var lifetimeChars: Long = 0L
    /** 清除极速记录的基线日（yyyyMMdd，可空＝无基线）；该日及之前不参评。 */
    private var fastestBaseline: String? = null
    private var currentScheme: String = ""
    private var lastKeyMs: Long = 0L

    /** 未消耗的删除事件时刻（elapsedRealtime 毫秒；0＝无待消耗删除）。 */
    private var pendingDeleteMs: Long = 0L

    /** 文字输入类别：误触修正只认这类按键（回车／切换／功能不算输入）。 */
    private val INPUT_TEXT_CATEGORIES = setOf(
        Category.INPUT, Category.SPACE, Category.CANDIDATE,
    )

    private val schedulerLazy = lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "input-stats-writer").apply { isDaemon = true }
        }
    }
    private var pendingFlush: ScheduledFuture<*>? = null

    /** 应用启动时调用一次（filesDir 锚定存储目录，并读回已有数据）。 */
    fun configure(filesDir: File) {
        synchronized(lock) {
            statsDir = File(filesDir, "input_stats")
            loadLocked()
        }
    }

    /** 当前方案（状态管理器在方案变化时调用；未知时传 ""）。 */
    fun setScheme(schemeId: String) {
        synchronized(lock) { currentScheme = schemeId }
    }

    /**
     * 按键计数（BaseKeyboard.onAction 入口）：每次分发记 1 次
     * 按键；语音拖动探针不算按键；删除类动作另记删除数。
     */
    fun noteKeyPress(action: KeyboardAction) {
        if (action is KeyboardAction.VoiceDragPosition ||
            action is KeyboardAction.VoiceDragUp
        ) {
            return
        }
        val now = clockMs()
        val hour = hourOfDay()
        synchronized(lock) {
            val gap = if (lastKeyMs > 0L) now - lastKeyMs else -1L
            lastKeyMs = now
            val typingAdd = if (gap in 1..TYPING_GAP_CAP_MS) gap else 0L
            val isDelete = action is KeyboardAction.BackspaceAction ||
                action is KeyboardAction.DeleteForwardAction
            val cat = categoryOf(action)
            // 误触修正：删除事件只记一次——删除刷新待消耗时刻；
            // 其后 5000ms 内的首个文字输入按键记 1 次并消耗；
            // 超窗自动过期；回车／切换／功能键不消耗。
            var correctionsAdd = 0L
            if (isDelete) {
                pendingDeleteMs = now
            } else {
                if (pendingDeleteMs > 0L && now - pendingDeleteMs > TYPING_GAP_CAP_MS) {
                    pendingDeleteMs = 0L
                }
                if (pendingDeleteMs > 0L && cat in INPUT_TEXT_CATEGORIES) {
                    correctionsAdd = 1
                    pendingDeleteMs = 0L
                }
            }
            updateLocked(
                commitsAdd = 0,
                charsAdd = 0,
                keypressesAdd = 1,
                deletesAdd = if (isDelete) 1 else 0,
                typingAdd = typingAdd,
                hour = hour,
                category = cat,
                correctionsAdd = correctionsAdd,
                heatKey = heatKeyOf(action),
            )
        }
        scheduleFlush()
    }

    /**
     * 提交计数（MessageHandler 的 Commit 处理）：提交次数＋字数＋
     * 词长分档（提交串长度：1／2／3／4以上；空串不记）。
     */
    fun noteCommit(text: String) {
        if (text.isEmpty()) return
        val hour = hourOfDay()
        val wordLenBucket = when (text.length) {
            1 -> 0
            2 -> 1
            3 -> 2
            else -> 3
        }
        synchronized(lock) {
            updateLocked(
                commitsAdd = 1,
                charsAdd = text.length.toLong(),
                keypressesAdd = 0,
                deletesAdd = 0,
                typingAdd = 0,
                hour = hour,
                category = null,
                wordLenBucket = wordLenBucket,
            )
            lifetimeChars += text.length
        }
        scheduleFlush()
    }

    /** 立即落盘（窗口 detach 与关机路径用）。 */
    fun flush() {
        val dir = synchronized(lock) {
            pendingFlush?.cancel(false)
            pendingFlush = null
            statsDir
        } ?: return
        val text = synchronized(lock) { serializeLocked() }
        runCatching {
            AtomicFiles.writeAtomically(dir, STATS_FILE, text)
        }.onFailure {
            DiagLog.w(
                "stats", "stats_save_failed",
                mapOf("error" to it.javaClass.simpleName),
            )
        }
    }

    /** 周期聚合（scheme 为 null = 全部方案，否则只取该方案桶）。 */
    fun snapshot(period: Period, scheme: String? = null): PeriodStats {
        val keys = periodDayKeys(period)
        var total = Counters()
        val schemeTotals = mutableMapOf<String, Counters>()
        val hours = MutableList(24) { 0L }
        val categories = mutableMapOf<String, Long>()
        val heat = mutableMapOf<String, Long>()
        synchronized(lock) {
            for (key in keys) {
                val day = days[key] ?: continue
                if (scheme == null) {
                    total += day.total
                    for ((id, c) in day.schemes) {
                        schemeTotals[id] = (schemeTotals[id] ?: Counters()) + c
                    }
                    for (i in 0 until 24) hours[i] += day.hours[i]
                    for ((cat, n) in day.categories) {
                        categories[cat] = (categories[cat] ?: 0L) + n
                    }
                    for ((hk, n) in day.heat) {
                        heat[hk] = (heat[hk] ?: 0L) + n
                    }
                } else {
                    val c = day.schemes[scheme] ?: continue
                    total += c
                    schemeTotals[scheme] = (schemeTotals[scheme] ?: Counters()) + c
                }
            }
        }
        val avgSpeed = if (total.typingMs > 0L) {
            total.chars * 60_000.0 / total.typingMs
        } else {
            null
        }
        val accuracy = if (total.chars + total.deletes > 0L) {
            total.chars.toDouble() / (total.chars + total.deletes)
        } else {
            null
        }
        return PeriodStats(total, schemeTotals, hours, categories, avgSpeed, accuracy, heat)
    }

    /** 本周期内各方案 id（仪表盘方案筛选器用）。 */
    fun schemeIds(period: Period): List<String> {
        val keys = periodDayKeys(period)
        val ids = mutableSetOf<String>()
        synchronized(lock) {
            for (key in keys) {
                days[key]?.schemes?.keys?.let { ids += it }
            }
        }
        return ids.sorted()
    }

    /** 最快单日速度（字／分钟），无合格单日时为 null。 */
    fun fastestDaySpeed(): Double? {
        var best: Double? = null
        synchronized(lock) {
            val baseline = fastestBaseline
            for ((key, day) in days) {
                if (baseline != null && key <= baseline) continue
                if (day.total.chars < FASTEST_MIN_CHARS || day.total.typingMs <= 0L) continue
                val speed = day.total.chars * 60_000.0 / day.total.typingMs
                if (best == null || speed > best!!) best = speed
            }
        }
        return best
    }

    /** 清除极速记录：仅把基线日推进到当日，其余数据不动。 */
    fun clearFastest() {
        synchronized(lock) {
            fastestBaseline = dayKeyOf(wallMs())
        }
        flush()
    }

    /** 展示层用：某段位的下限阈值（防展示层另抄一份阈值表漂移）。 */
    internal fun stageFloor(level: Int): Long =
        RANK_TABLE.getOrNull(level)?.first ?: 0L

    /** 生涯段位（终身字数驱动，清统计不影响）。 */
    fun rank(): Rank {
        val xp = synchronized(lock) { lifetimeChars }
        var level = 0
        for (i in RANK_TABLE.indices) {
            if (xp >= RANK_TABLE[i].first) level = i
        }
        val cur = RANK_TABLE[level].first
        val next = RANK_TABLE.getOrNull(level + 1)?.first
        val progress = if (next == null) {
            1.0
        } else {
            (xp - cur).toDouble() / (next - cur)
        }
        return Rank(RANK_TABLE[level].second, level, progress, xp, next)
    }

    /** 清统计：只清周期数据，终身字数与段位保留。 */
    fun clearPeriods() {
        synchronized(lock) {
            days.clear()
            lastKeyMs = 0L
            pendingDeleteMs = 0L
        }
        flush()
    }

    /** 导出整份状态 JSON（分享／备份用，不含任何输入内容）。 */
    fun exportJson(): String = synchronized(lock) { serializeLocked() }

    /**
     * 导入整份状态 JSON：version＝1 且结构合法时替换现有数据
     * 并落盘，返回 true；非法时拒绝且不改动现有数据，返回 false。
     */
    fun importJson(text: String): Boolean {
        val parsed = parseLocked(text) ?: return false
        synchronized(lock) {
            days = parsed.first
            lifetimeChars = parsed.second
            fastestBaseline = parsed.third
        }
        flush()
        return true
    }

    /** 仅测试用：清全部内存状态（不碰磁盘配置）。 */
    internal fun resetForTest() {
        synchronized(lock) {
            days.clear()
            lifetimeChars = 0L
            fastestBaseline = null
            currentScheme = ""
            lastKeyMs = 0L
            pendingDeleteMs = 0L
        }
    }

    // ---- 内部 ----

    private fun categoryOf(action: KeyboardAction): Category = when (action) {
        is KeyboardAction.SpaceAction -> Category.SPACE
        is KeyboardAction.BackspaceAction,
        is KeyboardAction.DeleteForwardAction -> Category.DELETE
        is KeyboardAction.ReturnAction,
        is KeyboardAction.MultiReturnAction -> Category.ENTER
        is KeyboardAction.SelectCandidatePinYin -> Category.CANDIDATE
        is KeyboardAction.LayoutSwitchAction,
        is KeyboardAction.RotateSchema,
        is KeyboardAction.SwitchSchemaByValue,
        is KeyboardAction.SelectSchema,
        is KeyboardAction.LangSwitchAction -> Category.SWITCH
        is KeyboardAction.KeySequenceAction,
        is KeyboardAction.CommitAction,
        is KeyboardAction.LiteralCommitAction -> Category.INPUT
        else -> Category.FUNCTION
    }

    private fun hourOfDay(): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = wallMs()
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    private fun dayKeyOf(timeMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timeMs))

    private fun periodDayKeys(period: Period): List<String> {
        val now = wallMs()
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        return when (period) {
            Period.DAY -> listOf(dayKeyOf(now))
            Period.WEEK -> (0 until 7).map { offset ->
                val c = Calendar.getInstance()
                c.timeInMillis = now
                c.add(Calendar.DAY_OF_YEAR, -offset)
                dayKeyOf(c.timeInMillis)
            }
            Period.MONTH -> {
                val prefix = SimpleDateFormat("yyyyMM", Locale.US).format(Date(now))
                (1..31).map { day -> prefix + "%02d".format(day) }
            }
            Period.YEAR -> {
                val prefix = SimpleDateFormat("yyyy", Locale.US).format(Date(now))
                (1..12).flatMap { month ->
                    (1..31).map { day ->
                        prefix + "%02d".format(month) + "%02d".format(day)
                    }
                }
            }
        }
    }

    private fun updateLocked(
        commitsAdd: Long,
        charsAdd: Long,
        keypressesAdd: Long,
        deletesAdd: Long,
        typingAdd: Long,
        hour: Int,
        category: Category?,
        /** 词长分档：-1＝不记，0＝1字，1＝2字，2＝3字，3＝4字以上。 */
        wordLenBucket: Int = -1,
        correctionsAdd: Long = 0,
        heatKey: String? = null,
    ) {
        val key = dayKeyOf(wallMs())
        val day = days[key] ?: DayRecord()
        val total = day.total.copy(
            commits = day.total.commits + commitsAdd,
            chars = day.total.chars + charsAdd,
            keypresses = day.total.keypresses + keypressesAdd,
            deletes = day.total.deletes + deletesAdd,
            typingMs = day.total.typingMs + typingAdd,
            wordLen1 = day.total.wordLen1 + if (wordLenBucket == 0) 1 else 0,
            wordLen2 = day.total.wordLen2 + if (wordLenBucket == 1) 1 else 0,
            wordLen3 = day.total.wordLen3 + if (wordLenBucket == 2) 1 else 0,
            wordLen4plus = day.total.wordLen4plus + if (wordLenBucket == 3) 1 else 0,
            corrections = day.total.corrections + correctionsAdd,
        )
        val schemes = day.schemes.toMutableMap()
        val prev = schemes[currentScheme] ?: Counters()
        schemes[currentScheme] = prev.copy(
            commits = prev.commits + commitsAdd,
            chars = prev.chars + charsAdd,
            keypresses = prev.keypresses + keypressesAdd,
            deletes = prev.deletes + deletesAdd,
            typingMs = prev.typingMs + typingAdd,
            wordLen1 = prev.wordLen1 + if (wordLenBucket == 0) 1 else 0,
            wordLen2 = prev.wordLen2 + if (wordLenBucket == 1) 1 else 0,
            wordLen3 = prev.wordLen3 + if (wordLenBucket == 2) 1 else 0,
            wordLen4plus = prev.wordLen4plus + if (wordLenBucket == 3) 1 else 0,
            corrections = prev.corrections + correctionsAdd,
        )
        val hours = day.hours.toMutableList()
        if (commitsAdd > 0 && hour in 0 until 24) hours[hour] += commitsAdd
        val categories = day.categories.toMutableMap()
        if (category != null) {
            categories[category.name] = (categories[category.name] ?: 0L) + keypressesAdd
        }
        val heat = day.heat.toMutableMap()
        if (heatKey != null && keypressesAdd > 0L) {
            heat[heatKey] = (heat[heatKey] ?: 0L) + keypressesAdd
        }
        // 保留最近 365 天，防止文件无限增长
        if (days.size >= 365 && key !in days) {
            days.keys.minOrNull()?.let { days.remove(it) }
        }
        days[key] = DayRecord(total, schemes, hours, categories, heat)
    }

    private fun scheduleFlush() {
        if (statsDir == null) return
        synchronized(lock) {
            pendingFlush?.cancel(false)
            pendingFlush = schedulerLazy.value.schedule(
                { flush() },
                FLUSH_DEBOUNCE_MS, TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun loadLocked() {
        val dir = statsDir ?: return
        val file = File(dir, STATS_FILE)
        if (!file.isFile) return
        val parsed = runCatching { parseLocked(file.readText()) }.getOrNull()
        if (parsed == null) {
            DiagLog.w("stats", "stats_corrupt_fallback_empty")
            return
        }
        days = parsed.first
        lifetimeChars = parsed.second
        fastestBaseline = parsed.third
    }

    private fun serializeLocked(): String = buildJsonObject {
        put("version", 1)
        put("lifetimeChars", lifetimeChars)
        val baseline = fastestBaseline
        if (baseline != null) put("fastestBaseline", baseline)
        put("days", buildJsonObject {
            for ((key, day) in days) {
                put(key, buildJsonObject {
                    put("commits", day.total.commits)
                    put("chars", day.total.chars)
                    put("keypresses", day.total.keypresses)
                    put("deletes", day.total.deletes)
                    put("typingMs", day.total.typingMs)
                    put("wordLen1", day.total.wordLen1)
                    put("wordLen2", day.total.wordLen2)
                    put("wordLen3", day.total.wordLen3)
                    put("wordLen4plus", day.total.wordLen4plus)
                    put("corrections", day.total.corrections)
                    put("schemes", buildJsonObject {
                        for ((id, c) in day.schemes) {
                            put(id, countersJson(c))
                        }
                    })
                    put("hours", buildJsonObject {
                        for (i in 0 until 24) put(i.toString(), day.hours[i])
                    })
                    put("categories", buildJsonObject {
                        for ((cat, n) in day.categories) put(cat, n)
                    })
                    put("heat", buildJsonObject {
                        for ((hk, n) in day.heat) put(hk, n)
                    })
                })
            }
        })
    }.toString()

    private fun countersJson(c: Counters): JsonObject = buildJsonObject {
        put("commits", c.commits)
        put("chars", c.chars)
        put("keypresses", c.keypresses)
        put("deletes", c.deletes)
        put("typingMs", c.typingMs)
        put("wordLen1", c.wordLen1)
        put("wordLen2", c.wordLen2)
        put("wordLen3", c.wordLen3)
        put("wordLen4plus", c.wordLen4plus)
        put("corrections", c.corrections)
    }

    private fun parseLocked(
        text: String,
    ): Triple<MutableMap<String, DayRecord>, Long, String?>? {
        val tree = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null
        if (tree["version"]?.jsonPrimitive?.intOrNull != 1) return null
        val lifetime = tree["lifetimeChars"]?.jsonPrimitive?.longOrNull ?: return null
        val baseline = runCatching {
            tree["fastestBaseline"]?.jsonPrimitive?.content
        }.getOrNull()
        val daysObj = tree["days"]?.jsonObject ?: return null
        val parsed = mutableMapOf<String, DayRecord>()
        for ((key, dayEl) in daysObj) {
            if (!key.matches(Regex("\\d{8}"))) return null
            val dayObj = dayEl.jsonObject
            fun longField(name: String): Long? =
                dayObj[name]?.jsonPrimitive?.longOrNull
            // 旧字段缺失＝文件非法（沿用严格口径）；新字段缺失＝旧文件，
            // 按 0 读入（向前兼容，历史无法重建，不编数据）。
            val total = Counters(
                commits = longField("commits") ?: return null,
                chars = longField("chars") ?: return null,
                keypresses = longField("keypresses") ?: return null,
                deletes = longField("deletes") ?: return null,
                typingMs = longField("typingMs") ?: return null,
                wordLen1 = longField("wordLen1") ?: 0L,
                wordLen2 = longField("wordLen2") ?: 0L,
                wordLen3 = longField("wordLen3") ?: 0L,
                wordLen4plus = longField("wordLen4plus") ?: 0L,
                corrections = longField("corrections") ?: 0L,
            )
            val schemes = mutableMapOf<String, Counters>()
            val schemesObj = dayObj["schemes"]?.jsonObject ?: return null
            for ((id, cEl) in schemesObj) {
                val cObj = cEl.jsonObject
                fun cf(name: String): Long? = cObj[name]?.jsonPrimitive?.longOrNull
                schemes[id] = Counters(
                    commits = cf("commits") ?: return null,
                    chars = cf("chars") ?: return null,
                    keypresses = cf("keypresses") ?: return null,
                    deletes = cf("deletes") ?: return null,
                    typingMs = cf("typingMs") ?: return null,
                    wordLen1 = cf("wordLen1") ?: 0L,
                    wordLen2 = cf("wordLen2") ?: 0L,
                    wordLen3 = cf("wordLen3") ?: 0L,
                    wordLen4plus = cf("wordLen4plus") ?: 0L,
                    corrections = cf("corrections") ?: 0L,
                )
            }
            val hoursObj = dayObj["hours"]?.jsonObject ?: return null
            val hours = (0 until 24).map { i ->
                hoursObj[i.toString()]?.jsonPrimitive?.longOrNull ?: return null
            }
            val categories = mutableMapOf<String, Long>()
            val catsObj = dayObj["categories"]?.jsonObject ?: return null
            for ((cat, nEl) in catsObj) {
                categories[cat] = nEl.jsonPrimitive.longOrNull ?: return null
            }
            // 热力为新字段：缺失＝旧文件，按空读入（向前采集，不编历史）。
            val heat = mutableMapOf<String, Long>()
            val heatObj = dayObj["heat"]?.jsonObject
            if (heatObj != null) {
                for ((hk, nEl) in heatObj) {
                    heat[hk] = nEl.jsonPrimitive.longOrNull ?: return null
                }
            }
            parsed[key] = DayRecord(total, schemes, hours, categories, heat)
        }
        return Triple(parsed, lifetime, baseline)
    }
}
