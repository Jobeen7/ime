package com.jobeen.ime.skin.diag

import android.content.Context
import com.jobeen.ime.data.App
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 诊断日志级别（详设十三.2）：DEBUG 仅在「诊断模式」开启时落盘。 */
enum class DiagLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 诊断日志设施（详设十三章，Batch 1 最早落地的基建，后续各子系统与
 * Batch 2/3 一律接入本设施）。
 *
 * 条目格式：`时间戳 级别 [模块] 事件 k=v ...`，一行一条；异常堆栈
 * 附在条目之后。写盘走单后台线程队列（十三.6：热路径不写盘——调用
 * 方只做一次字符串格式化与入队，队列满时丢弃新条目并计数，绝不
 * 阻塞调用方）。
 *
 * 隐私红线（十三.3）：本设施只记元数据——id、尺寸、计数、结果码、
 * 异常堆栈。调用方**绝不**得传入用户输入文本、候选词、剪贴板内容、
 * 词库内容、WebDAV 凭据或带 token 的 URL。上下文值在入队前统一做
 * 控制字符剥离与长度截断，防止多行文本污染日志结构。
 *
 * 存储与滚动（十三.4）：应用私有目录 `logs/`，每进程一个会话文件，
 * 单文件上限滚动；总保留 ≤7 个文件且 ≤5MB，超限删最旧。不进
 * WebDAV 同步、不进 .jime 导出（本目录从不被那两条管线引用）。
 *
 * 进程级未捕获异常不在本设施重复捕获：由 CrashTrap 单点落盘
 * （filesDir/crash/），导出时由 [DiagExporter] 一并打包（十三.5）。
 */
class DiagLogCore(
    val rootDir: File,
    private val debugEnabled: () -> Boolean = { false },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    private sealed interface QueueItem {
        class Line(val text: String) : QueueItem
        class Flush(val latch: CountDownLatch) : QueueItem
        object Stop : QueueItem
    }

    private val queue = ArrayBlockingQueue<QueueItem>(QUEUE_CAPACITY)
    private val droppedCount = AtomicLong(0)
    private val fileSeq = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var writerThread: Thread? = null

    fun d(tag: String, event: String, context: Map<String, Any?> = emptyMap()) =
        log(DiagLevel.DEBUG, tag, event, context, null)

    fun i(tag: String, event: String, context: Map<String, Any?> = emptyMap()) =
        log(DiagLevel.INFO, tag, event, context, null)

    fun w(tag: String, event: String, context: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) =
        log(DiagLevel.WARN, tag, event, context, throwable)

    fun e(tag: String, event: String, context: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) =
        log(DiagLevel.ERROR, tag, event, context, throwable)

    fun log(
        level: DiagLevel,
        tag: String,
        event: String,
        context: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null,
    ) {
        if (level == DiagLevel.DEBUG && !debugEnabled()) return
        val line = formatLine(level, tag, event, context, throwable)
        ensureWriter()
        if (!queue.offer(QueueItem.Line(line))) {
            droppedCount.incrementAndGet()
        }
    }

    /** 等待队列中已入队的条目全部落盘（导出前调用；超时不抛异常）。 */
    fun flush(timeoutMs: Long = FLUSH_TIMEOUT_MS) {
        if (writerThread == null) return
        val latch = CountDownLatch(1)
        if (queue.offer(QueueItem.Flush(latch), timeoutMs, TimeUnit.MILLISECONDS)) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    /** 停止后台线程（进程内一般不调，主要供测试收尾）。 */
    fun close() {
        flush()
        queue.offer(QueueItem.Stop)
        writerThread?.join(FLUSH_TIMEOUT_MS)
        writerThread = null
    }

    internal fun formatLine(
        level: DiagLevel,
        tag: String,
        event: String,
        context: Map<String, Any?>,
        throwable: Throwable?,
    ): String {
        val sb = StringBuilder()
        // SimpleDateFormat 非线程安全，log() 可被多线程并发调用——每次新建
        sb.append(SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date(clock())))
        sb.append(' ').append(level.name)
        sb.append(" [").append(sanitize(tag, MAX_TAG_CHARS)).append("] ")
        sb.append(sanitize(event, MAX_EVENT_CHARS))
        for ((key, value) in context) {
            sb.append(' ').append(sanitize(key, MAX_KEY_CHARS)).append('=')
            sb.append(sanitize(value?.toString() ?: "null", MAX_VALUE_CHARS))
        }
        if (throwable != null) {
            val writer = StringWriter()
            PrintWriter(writer).use { throwable.printStackTrace(it) }
            sb.append('\n').append(writer.toString().trimEnd())
        }
        return sb.toString()
    }

    private fun ensureWriter() {
        if (writerThread != null) return
        synchronized(this) {
            if (writerThread != null) return
            val thread = Thread({ writerLoop() }, "diag-log-writer")
            thread.isDaemon = true
            writerThread = thread
            thread.start()
        }
    }

    private fun writerLoop() {
        var currentFile: File? = null
        var currentSize = 0L
        fun rollIfNeeded(nextLineBytes: Int) {
            if (currentFile == null || (currentSize > 0 && currentSize + nextLineBytes > maxFileBytes)) {
                currentFile = newLogFile()
                currentSize = if (currentFile!!.exists()) currentFile!!.length() else 0L
                prune(excluding = currentFile)
            }
        }
        while (true) {
            val item = runCatching { queue.take() }.getOrNull() ?: break
            when (item) {
                is QueueItem.Stop -> break
                is QueueItem.Flush -> item.latch.countDown()
                is QueueItem.Line -> {
                    runCatching {
                        val dropped = droppedCount.getAndSet(0)
                        val text = if (dropped > 0) {
                            formatLine(
                                DiagLevel.WARN, "diag", "entries_dropped",
                                mapOf("count" to dropped), null,
                            ) + "\n" + item.text
                        } else {
                            item.text
                        }
                        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
                        rollIfNeeded(bytes.size)
                        currentFile!!.appendBytes(bytes)
                        currentSize += bytes.size
                    }
                    // 写盘失败静默：诊断设施自身不得成为故障源
                }
            }
        }
    }

    private fun newLogFile(): File {
        rootDir.mkdirs()
        val stamp = SimpleDateFormat(FILE_STAMP_PATTERN, Locale.US).format(Date(clock()))
        // 文件名带进程内单调序号：同毫秒多次滚动/多进程同刻建文件时，
        // 名字序仍与创建序一致（剪枝与导出都按名序取旧→新）
        while (true) {
            val seq = fileSeq.getAndIncrement()
            val candidate = File(rootDir, "$FILE_PREFIX$stamp-${seq.toString().padStart(3, '0')}$FILE_SUFFIX")
            if (candidate.createNewFile()) return candidate
        }
    }

    /** 滚动保留：按名序（即创建序）从旧到新删，直到文件数与总量都回上限内；当前写入文件不删。 */
    private fun prune(excluding: File?) {
        val files = rootDir.listFiles { f -> f.isFile && isLogFileName(f.name) }
            ?.sortedBy { it.name } ?: return
        var total = files.sumOf { it.length() }
        var remaining = files.size
        var index = 0
        while (index < files.size && (remaining > maxFiles || total > maxTotalBytes)) {
            val victim = files[index++]
            if (victim == excluding) continue
            total -= victim.length()
            remaining--
            runCatching { victim.delete() }
        }
    }

    companion object {
        const val DEFAULT_MAX_FILES = 7
        const val DEFAULT_MAX_TOTAL_BYTES = 5L * 1024 * 1024
        const val DEFAULT_MAX_FILE_BYTES = 1L * 1024 * 1024
        private const val QUEUE_CAPACITY = 1024
        private const val FLUSH_TIMEOUT_MS = 2_000L
        private const val MAX_TAG_CHARS = 32
        private const val MAX_EVENT_CHARS = 64
        private const val MAX_KEY_CHARS = 48
        private const val MAX_VALUE_CHARS = 256
        private const val FILE_PREFIX = "diag-"
        private const val FILE_SUFFIX = ".log"
        private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
        private const val FILE_STAMP_PATTERN = "yyyyMMdd-HHmmss-SSS"

        fun isLogFileName(name: String): Boolean =
            name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

        /** 控制字符（含换行）一律换空格并截断：日志一行一条的结构不可被上下文值破坏。 */
        internal fun sanitize(value: String, maxChars: Int): String {
            val cleaned = value.map { ch -> if (ch < ' ' || ch.code == 0x7F) ' ' else ch }
                .joinToString("")
            return if (cleaned.length > maxChars) cleaned.substring(0, maxChars) + "…" else cleaned
        }
    }
}

/**
 * 进程级门面：`ImeApplication.onCreate` 里 [init] 一次（CrashTrap 之后），
 * 其余代码直接 `DiagLog.i/w/e(...)`；未初始化时全部静默无操作，保证
 * 任何调用顺序安全（单元测试、子进程早期路径）。
 */
object DiagLog {
    @Volatile
    private var core: DiagLogCore? = null

    fun init(context: Context) {
        if (core != null) return
        synchronized(this) {
            if (core != null) return
            val appContext = context.applicationContext
            val created = DiagLogCore(
                rootDir = App.logsDir,
                debugEnabled = { DiagPrefs.isDiagnosticMode(appContext) },
            )
            core = created
            created.i("diag", "init", mapOf("dir" to created.rootDir.name))
        }
    }

    fun d(tag: String, event: String, context: Map<String, Any?> = emptyMap()) =
        core?.d(tag, event, context) ?: Unit

    fun i(tag: String, event: String, context: Map<String, Any?> = emptyMap()) =
        core?.i(tag, event, context) ?: Unit

    fun w(tag: String, event: String, context: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) =
        core?.w(tag, event, context, throwable) ?: Unit

    fun e(tag: String, event: String, context: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) =
        core?.e(tag, event, context, throwable) ?: Unit

    fun flush() = core?.flush() ?: Unit

    fun logsDir(): File? = core?.rootDir
}
