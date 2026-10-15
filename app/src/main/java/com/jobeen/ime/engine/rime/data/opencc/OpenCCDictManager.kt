package com.jobeen.ime.engine.rime.data.opencc

import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.engine.rime.data.opencc.dict.Dictionary
import com.jobeen.ime.engine.rime.data.opencc.dict.OpenCCDictionary
import com.jobeen.ime.engine.rime.data.opencc.dict.TextDictionary
import com.jobeen.ime.base.util.appContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import kotlin.system.measureTimeMillis

object OpenCCDictManager {
    init {
        System.loadLibrary("rime_jni")
    }

    private val sharedDir = File(DataManager.sharedDataDir, "opencc").also { it.mkdirs() }
    private val userDir get() = File(DataManager.userDataDir, "opencc").also { it.mkdirs() }

    fun sharedDictionaries(): List<Dictionary> =
        sharedDir.listFiles()?.mapNotNull { Dictionary.new(it) } ?: listOf()

    fun userDictionaries(): List<Dictionary> =
        userDir.listFiles()?.mapNotNull { Dictionary.new(it) } ?: listOf()

    fun getAllDictionaries(): List<Dictionary> = sharedDictionaries() + userDictionaries()

    fun importFromFile(file: File): OpenCCDictionary {
        val raw = Dictionary.new(file)
            ?: throw IllegalArgumentException("${file.path} is not a opencc/text dictionary")
        // convert to opencc format in dictionaries dir
        // preserve original file name
        val new = raw.toOpenCCDictionary(
            File(
                userDir,
                file.nameWithoutExtension + ".${Dictionary.Type.OCD2.ext}",
            ),
        )
        Timber.d("Converted $raw to $new")
        return new
    }

    /** 构建串行锁：同步/异步构建共用同一把，避免两份转换同时写同一输出文件 */
    private val buildLock = Any()

    /**
     * 已转换文本词典的进程内缓存：源文件绝对路径 → 源指纹
     * （mtime＋长度）。buildOpenCCDict 在每次启动/部署的 bootstrap
     * 都跑，此前对每本词典无条件全量重转（JNI 整表转换），词典没变
     * 时纯属浪费、拖慢启动。指纹一致且产物 .ocd2 仍在盘上时跳过；
     * 源文件被替换（方案更新/用户导入）时指纹变化、自动失效重建，
     * 产物被删时同样重建。只在 buildLock 内读写，无需并发容器。
     */
    private val convertedFingerprints = HashMap<String, Pair<Long, Long>>()

    /**
     * Convert internal text dict to opencc format.
     *
     * 同步构建是部署路径的正确性要求：librime 在 bootstrap 时就加载
     * .ocd2，异步构建与部署并发时新词典要等下一次部署才被读到
     * （方案更新后繁简转换沿用旧词典）。Rime.startRime 在 bootstrap
     * 之前同步调用本函数，保证本次部署即用新词典。
     */
    @JvmStatic
    fun buildOpenCCDict() = synchronized(buildLock) {
        for (d in getAllDictionaries()) {
            if (d is TextDictionary) {
                val src = d.file
                // 与 Dictionary.toOpenCCDictionary() 无参版同一产物路径
                val dest = src.resolveSibling("${d.name}.${Dictionary.Type.OCD2.ext}")
                val fingerprint = src.lastModified() to src.length()
                if (convertedFingerprints[src.absolutePath] == fingerprint && dest.isFile) {
                    Timber.d("Skip unchanged opencc dict $d")
                    continue
                }
                val result: Result<OpenCCDictionary>
                measureTimeMillis {
                    result = runCatching { d.toOpenCCDictionary() }
                }.also {
                    result.onSuccess { r ->
                        convertedFingerprints[src.absolutePath] = fingerprint
                        Timber.d("Took $it to convert to $r")
                    }.onFailure {
                        Timber.e(it, "Failed to convert $d")
                    }
                }
            }
        }
    }

    // 已删除 importFromInputStream：全仓零调用方的死代码，且其无上限 copyTo
    // 落盘 + 以外部 name 直接拼 cacheDir 路径（可穿越目录）都是隐患；
    // 将来若要接回流式导入，先加体积上限与文件名净化再恢复。

    @JvmStatic
    fun convertLine(
        input: String,
        configFileName: String,
    ): String {
        if (configFileName.isEmpty()) return input
        with(File(userDir, configFileName)) {
            if (exists()) return openCCLineConv(input, path)
        }
        with(File(sharedDir, configFileName)) {
            if (exists()) return openCCLineConv(input, path)
        }
        Timber.w("Specified config $configFileName doesn't exist, returning raw input ...")
        return input
    }

    @JvmStatic
    external fun openCCDictConv(
        src: String,
        dest: String,
        mode: Boolean,
    )

    @JvmStatic
    external fun openCCLineConv(
        input: String,
        configFileName: String,
    ): String

    const val MODE_BIN_TO_TXT = true // OCD(2) to TXT
    const val MODE_TXT_TO_BIN = false // TXT to OCD2
}
