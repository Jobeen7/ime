package com.jobeen.ime.skin.store

import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.SkinJson
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 覆盖层存储工程（2026-10-29 item-1 D1）。
 *
 * - 主题文件永不改动：覆盖层是独立文件
 *   `skins/<skinId>/overlay.json`，只叠加在基础皮肤之上；
 *   基础皮肤文件（各代次 skin.json）一律不写。
 * - 三态语义（合并层实现，与解析器无关）：
 *   缺省（覆盖层无此键）＝继承基础值；
 *   空串（覆盖层此键为 ""，对象内同理）＝显式解绑，即从合并
 *   结果中删掉该键，解析器随后取内置默认；
 *   有值＝覆盖（对象按键递归合并，数组整体替换）。
 * - 500ms 防抖原子保存：[saveDebounced] 取消待写任务、500ms
 *   后一次落盘；[flush] 立即写出待写项（测试与关机路径用）。
 * - 版本号防缓存过期：文件内 `version` 每次落盘 +1；快照携
 *   带 [overlayVersion][com.jobeen.ime.skin.model.SkinSnapshot.overlayVersion]，
 *   与快照生成时的版本不一致即视为过期（见
 *   SkinSnapshotProvider.refreshIfOverlayChanged）。
 * - 导入语义（无额外代码，由存放位置自然保证）：
 *   UPDATE（同 id 入新代次）→ 覆盖层文件不动，继续叠加在新
 *   基础之上（可合并）；COPY（冲突换新 id）→ 新 id 下无覆盖层
 *   文件，从干净基础开始（可替换）。
 * - 失败路径只记元数据（操作＋皮肤 id＋异常类名），绝不记
 *   覆盖层内容；损坏的覆盖层文件视为无覆盖（降级，不崩）。
 */
data class OverlayData(
    val version: Long,
    val overrides: JsonObject,
)

object SkinOverlayMerge {

    /** 覆盖层禁写的身份键：皮肤 id 与元信息永远跟基础文件走。 */
    private val RESERVED_KEYS = setOf("id", "meta")

    /**
     * 三态合并：返回（合并后树，问题路径表）。问题只记字段路径
     * 与种类，不记值。合并永不抛：非法覆盖值按解绑处理并记问题。
     * 保留键（身份）先剥离：覆盖层永远改不了皮肤 id 与元信息。
     */
    fun merge(base: JsonObject, overlay: JsonObject): Pair<JsonObject, List<String>> {
        val issues = mutableListOf<String>()
        val cleanOverlay = buildJsonObject {
            for ((key, value) in overlay) {
                if (key in RESERVED_KEYS) {
                    issues += "$key:reserved_skipped"
                } else {
                    put(key, value)
                }
            }
        }
        return mergeObject(base, cleanOverlay, "", issues) to issues
    }

    private fun mergeObject(
        base: JsonObject,
        overlay: JsonObject,
        path: String,
        issues: MutableList<String>,
    ): JsonObject = buildJsonObject {
        for ((key, baseValue) in base) {
            if (key !in overlay) {
                put(key, baseValue) // 缺省＝继承
                continue
            }
            val overlayValue = overlay.getValue(key)
            when {
                overlayValue is JsonNull -> {
                    issues += "$path$key:null_unbind"
                    // 空值按解绑处理：不写入，即删掉该键
                }
                overlayValue is JsonPrimitive && overlayValue.contentOrNull == "" -> {
                    // 显式解绑：不写入，即删掉该键
                }
                baseValue is JsonObject && overlayValue is JsonObject -> {
                    put(key, mergeObject(baseValue, overlayValue, "$path$key.", issues))
                }
                else -> put(key, overlayValue) // 有值＝覆盖（含数组整体替换）
            }
        }
        for ((key, overlayValue) in overlay) {
            if (key in base) continue
            when {
                overlayValue is JsonNull -> issues += "$path$key:null_unbind"
                overlayValue is JsonPrimitive && overlayValue.contentOrNull == "" -> {
                    // 基础无此键时解绑等于无操作
                }
                else -> put(key, overlayValue)
            }
        }
    }
}

class SkinOverlayStore(private val skinsDir: File) {

    companion object {
        /** 防抖窗口：连续写入 500ms 内无新写入时落盘一次。 */
        const val DEBOUNCE_MS = 500L
        const val OVERLAY_FILE = "overlay.json"
    }

    private val lock = Any()
    private val schedulerLazy = lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "skin-overlay-writer").apply { isDaemon = true }
        }
    }
    private val pending = mutableMapOf<String, JsonObject>()
    private val pendingTasks = mutableMapOf<String, ScheduledFuture<*>>()

    fun overlayFile(skinId: String): File = File(skinsDir, "$skinId/$OVERLAY_FILE")

    /** 读覆盖层：无文件返回 null；损坏文件记日志后同样返回 null（降级）。 */
    fun read(skinId: String): OverlayData? {
        val file = overlayFile(skinId)
        if (!file.isFile) return null
        val tree = runCatching { SkinJson.parseObject(file.readText()) }.getOrNull()
            ?: run {
                DiagLog.w(
                    "overlay", "overlay_unparseable",
                    mapOf("skinId" to skinId),
                )
                return null
            }
        val version = (tree["version"] as? JsonPrimitive)?.longOrNull
        val overrides = tree["overrides"] as? JsonObject
        if (version == null || version < 1L || overrides == null) {
            DiagLog.w(
                "overlay", "overlay_malformed",
                mapOf("skinId" to skinId),
            )
            return null
        }
        return OverlayData(version, overrides)
    }

    /** 立即原子保存：版本号 = 现有版本 +1（无文件时为 1）。 */
    fun saveNow(skinId: String, overrides: JsonObject): Long {
        synchronized(lock) {
            val version = (readLocked(skinId)?.version ?: 0L) + 1L
            val file = overlayFile(skinId)
            val text = buildJsonObject {
                put("version", version)
                put("overrides", overrides)
            }.toString()
            runCatching {
                AtomicFiles.writeAtomically(file.parentFile, OVERLAY_FILE, text)
            }.onFailure {
                DiagLog.e(
                    "overlay", "overlay_save_failed",
                    mapOf("skinId" to skinId, "error" to it.javaClass.simpleName),
                )
                throw it
            }
            return version
        }
    }

    /** 防抖保存：500ms 内重复调用只落盘最后一次；版本号在落盘时确定。 */
    fun saveDebounced(skinId: String, overrides: JsonObject) {
        synchronized(lock) {
            pending[skinId] = overrides
            pendingTasks[skinId]?.cancel(false)
            pendingTasks[skinId] = schedulerLazy.value.schedule(
                { flushOne(skinId) },
                DEBOUNCE_MS, TimeUnit.MILLISECONDS,
            )
        }
    }

    /** 立即写出全部待写项（测试与进程退出前用）。 */
    fun flush() {
        val ids = synchronized(lock) { pending.keys.toList() }
        for (skinId in ids) flushOne(skinId)
    }

    /** 清除覆盖层：文件删除后该皮肤回到纯基础状态。 */
    fun clear(skinId: String) {
        synchronized(lock) {
            pendingTasks[skinId]?.cancel(false)
            pendingTasks.remove(skinId)
            pending.remove(skinId)
            runCatching { overlayFile(skinId).delete() }
        }
    }

    fun shutdown() {
        if (schedulerLazy.isInitialized()) schedulerLazy.value.shutdown()
    }

    private fun flushOne(skinId: String) {
        val overrides = synchronized(lock) {
            pendingTasks.remove(skinId)
            pending.remove(skinId)
        } ?: return
        runCatching { saveNow(skinId, overrides) }
            .onFailure {
                DiagLog.e(
                    "overlay", "overlay_flush_failed",
                    mapOf("skinId" to skinId, "error" to it.javaClass.simpleName),
                )
            }
    }

    /** 锁内读：避免 saveNow 与并发读之间版本竞态（仅版本号用）。 */
    private fun readLocked(skinId: String): OverlayData? = read(skinId)
}
