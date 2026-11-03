package com.jobeen.ime.skin.runtime

import java.security.MessageDigest

/**
 * 切换与缓存策略（第 5 批 §3，纯 JVM 部分）：
 *
 * - 内容指纹：对主题本体 + 实际请求过的依赖（含缺失资源）做
 *   “规范路径 + 文件字节 SHA-256”，不依赖 mtime（对方 T17
 *   同款；Jime 既有内容哈希方向一致）。缺失依赖以 MISSING
 *   标记入指纹——依赖补齐后指纹变化，缓存不再命中旧解析。
 * - 小容量缓存：[FingerprintCache] 默认 8 条（对方 8 条同值），
 *   LRU 淘汰；Jime 皮肤切换是用户显式动作，收益边界见报告。
 * - 世代号失效：[SkinGeneration] 切换时 +1；调用方在世代变更
 *   时清图片缓存与失败集（SkinKeyImageStore.clear /
 *   DecodeGate.nextGeneration），绘制期旧图不得混拼。
 * - 视图缓存双条件失效：复用前校验布局版本 + 样式版本
 *   （对方 R24/K32 的“绑定覆盖版本 + 自定义键盘版本”在 Jime
 *   的对应物是 overlayVersion + 快照代次；本函数以调用方传入
 *   的两个版本号判定，不读时间戳）。
 * - 分档成本（对方 R21/R23）：换色只重绘（世代失效 + 原地
 *   重绘）、换键盘只重建当前、换皮肤才重建全部；结构相等
 *   直接不通知。换色／换键盘／换皮肤三档由调用方按事实传入，
 *   本函数只做判定，不猜测变化来源。
 * - 系统夜间态：[NightModePolicy] 以 UiModeManager 显式设置
 *   为准（对方 T22）：显式 YES/NO 优先于 configuration 回调值；
 *   AUTO／未指定时回落回调值。Jime 外观默认跟随系统深浅的
 *   口径不变；本策略不引入第二套深浅控制（单一颜色模式仍是
 *   SkinColorMode 的 SYSTEM/LIGHT/DARK）。
 * - 皮肤列表名字：Jime 库索引已存名字，列表不全量解析快照，
 *   故“只读文件头取名字”优化不做（见报告；前提不成立则不做）。
 */
object SkinSwitchPolicy {

    /** 指纹条目：规范路径 + 文件字节 SHA-256；sha256=null = 依赖缺失。 */
    data class FingerprintEntry(val path: String, val sha256: String?)

    /** 内容指纹：按路径排序后逐项 `path\0sha|MISSING\0` 做 SHA-256。 */
    fun contentFingerprint(entries: List<FingerprintEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (entry in entries.sortedBy { it.path }) {
            digest.update(entry.path.toByteArray(Charsets.UTF_8))
            digest.update(byteArrayOf(0))
            digest.update((entry.sha256 ?: "MISSING").toByteArray(Charsets.UTF_8))
            digest.update(byteArrayOf(0))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 视图缓存失效判定结果。 */
    enum class ViewCacheDecision {
        /** 结构相等：直接 return，不发通知。 */
        NO_NOTIFY,

        /** 换色：世代号失效 + 原地重绘，不重建键盘。 */
        REDRAW_IN_PLACE,

        /** 换键盘：只丢当前键盘缓存重建当前。 */
        REBUILD_CURRENT,

        /** 换皮肤：重建全部。 */
        REBUILD_ALL,
    }

    /**
     * 判定缓存动作。调用方按事实传入，优先级：
     * 结构相等 > 换皮肤 > 布局／样式版本变化 > 换色 > 换键盘 > 已是当前。
     * 版本变化与换键盘同属“只重建当前”档；未知变化默认重建当前
     * （安全方向：不静默沿用可能过期的缓存）。
     */
    fun decideViewCache(
        structureEqual: Boolean,
        skinChanged: Boolean,
        colorOnly: Boolean,
        keyboardOnly: Boolean,
        layoutVersionChanged: Boolean,
        styleVersionChanged: Boolean,
        alreadyCurrent: Boolean = false,
    ): ViewCacheDecision {
        if (structureEqual) return ViewCacheDecision.NO_NOTIFY
        if (skinChanged) return ViewCacheDecision.REBUILD_ALL
        if (layoutVersionChanged || styleVersionChanged) {
            return ViewCacheDecision.REBUILD_CURRENT
        }
        if (colorOnly) return ViewCacheDecision.REDRAW_IN_PLACE
        if (keyboardOnly) return ViewCacheDecision.REBUILD_CURRENT
        if (alreadyCurrent) return ViewCacheDecision.NO_NOTIFY
        return ViewCacheDecision.REBUILD_CURRENT
    }
}

/** 小容量 LRU 指纹缓存（默认 8 条，对方 T17 同值）。 */
class FingerprintCache<V>(private val capacity: Int = 8) {
    private val map = object : LinkedHashMap<String, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean {
            return size > capacity
        }
    }

    @Synchronized
    fun get(fingerprint: String): V? = map[fingerprint]

    @Synchronized
    fun put(fingerprint: String, value: V) {
        map[fingerprint] = value
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() = map.clear()
}

/** 世代号（对方 R35）：切换时 +1，调用方据此清图片缓存与失败集。 */
class SkinGeneration {
    private val lock = Any()
    private var generation: Long = 0L

    fun current(): Long = synchronized(lock) { generation }

    fun bump(): Long = synchronized(lock) {
        generation += 1
        generation
    }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        generation == this.generation
    }
}

/**
 * 系统夜间态判定（对方 T22，纯函数）：
 * explicitMode 取 UiModeManager.getNightMode() 的返回值镜像
 * （MODE_NIGHT_NO=1、MODE_NIGHT_YES=2、MODE_NIGHT_AUTO=0、
 * MODE_NIGHT_UNSPECIFIED=-1，值与 Android 常量一致，JVM 单测
 * 不触 Android 框架）；显式 YES/NO 优先，AUTO／未指定／null
 * 回落 configuration 回调值 reportedNight。
 */
object NightModePolicy {
    const val MODE_NIGHT_UNSPECIFIED = -1
    const val MODE_NIGHT_AUTO = 0
    const val MODE_NIGHT_NO = 1
    const val MODE_NIGHT_YES = 2

    fun resolve(explicitMode: Int?, reportedNight: Boolean): Boolean = when (explicitMode) {
        MODE_NIGHT_YES -> true
        MODE_NIGHT_NO -> false
        else -> reportedNight
    }
}
