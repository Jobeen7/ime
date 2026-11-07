package com.jobeen.ime.skin.image

import java.io.File

/**
 * 图片管线硬护栏（第 5 批 §2，纯 JVM 部分）：
 *
 * 解码本体仍走既有 [BoundedBitmapDecoder]（第 1 批共用 helper，
 * 本批不另造第二个上限解码器）；本对象只给各解码点提供同一套
 * 纯护栏常数与判定，Bitmap 侧的调用见 BoundedBitmapDecoder /
 * SkinKeyImageStore。
 *
 * - 解码上限（施工单 5 第 7 条，2026-11-07 定）：超限图不再
 *   直接拒收，降采样到上限内使用——解码结果边长 ≤
 *   [MAX_DECODE_SIDE]、像素 ≤ [MAX_DECODE_PIXELS]（与导入校验
 *   口径 JimeImporter.MAX_IMAGE_SIDE／MAX_IMAGE_PIXELS 同值）；
 *   解码始终有界（inSampleSize），绝不以原尺寸进内存。
 *   旧口径（第 5 批 §2：bounds 像素超 24MP 硬上限直接拒）
 *   已按该决定废止。
 * - 文件名白名单：`[A-Za-z0-9_.-]+` 且 ≠ `..`（对方 K14 同款）。
 * - canonical 目录内校验：相对引用解析后 canonical 必须落在
 *   素材根内，否则返回不放行（调用方按缺失回落）。
 * - 解码并行度：[DECODE_PARALLELISM] = 2（对方 R34 同值；
 *   调用方以 IO limitedParallelism(2) 落地，本对象只定值）。
 * - 在途合并与失败集：见 [DecodeGate]——同一 key 在途只解码
 *   一次；失败后不再重试，直到世代变更（对方“失败满 256 条
 *   清零重获重试机会”是不抄的实现副作用，见 synthesis §2）。
 * - 占位分层：背景缺图可短暂透明或底色兜底、前景图标必须有
 *   文字回落、视频失败必须有静态背景回落（三者不统一成空白）。
 * - 字体缓存键：路径 + mtime + 长度（对方 R17 同款）；皮肤
 *   内嵌字体走内容寻址（sha 即键），切换时清空，见 GlobalFonts。
 */
object ImageDecodePolicy {

    /**
     * 解码结果上限：与导入校验口径同值（JimeImporter.MAX_IMAGE_SIDE／
     * MAX_IMAGE_PIXELS，定稿十一.3）：解码后位图边长 ≤ 8192、像素
     * ≤ 64M；超限原图降采样到上限内使用，不直接拒收。
     */
    const val MAX_DECODE_SIDE = 8192
    const val MAX_DECODE_PIXELS = 64L * 1024 * 1024

    /** 解码并行度：2（对方 R34）。 */
    const val DECODE_PARALLELISM = 2

    /**
     * 键图缓存总字节上限（施工单 6 第 5 条）：单张键面 ≤512 边
     * ARGB_8888 至多 1MB，128MB 可容约 128 张满尺寸键图（典型
     * 键图远小于此）；超限按 FIFO 退役最旧条目（滞留一代再回收），
     * 单张超限不缓存。见 [com.jobeen.ime.skin.runtime.SkinKeyImageStore]。
     */
    const val MAX_CACHE_BYTES = 128L * 1024 * 1024

    /** 快照安装时 IO 预热的键图上限：超出者走键盘构建期异步解码（施工单 6 第 5 条），不阻塞安装。 */
    const val PREWARM_MAX_KEYS = 128

    /** 文件名白名单（对方 K14）。 */
    val FILENAME_PATTERN: Regex = Regex("[A-Za-z0-9_.-]+")

    /** 文件名是否合白名单（≠ `..`，不含路径分隔符）。 */
    fun isValidAssetFileName(name: String): Boolean =
        name.isNotEmpty() && name != ".." && FILENAME_PATTERN.matches(name)

    /**
     * canonical 目录内校验：candidate 的 canonical 路径必须落在
     * root 的 canonical 路径内；解析失败一律不放行。
     */
    fun isInsideRoot(root: File, candidate: File): Boolean {
        return try {
            val rootPath = root.canonicalFile
            val filePath = candidate.canonicalFile
            filePath == rootPath || filePath.path.startsWith(rootPath.path + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    /** 占位分层（对方 R38）：按角色取回落，不统一成空白。 */
    enum class Role { BACKGROUND, FOREGROUND_ICON, VIDEO }

    enum class Placeholder {
        /** 背景：短暂透明或底色兜底（调用方二选一，永不空白键盘）。 */
        TRANSPARENT_OR_SOLID,

        /** 前景图标：必须有文字回落。 */
        TEXT_FALLBACK,

        /** 视频：必须有静态背景回落。 */
        STATIC_BACKGROUND_FALLBACK,
    }

    fun placeholderFor(role: Role): Placeholder = when (role) {
        Role.BACKGROUND -> Placeholder.TRANSPARENT_OR_SOLID
        Role.FOREGROUND_ICON -> Placeholder.TEXT_FALLBACK
        Role.VIDEO -> Placeholder.STATIC_BACKGROUND_FALLBACK
    }

    /** 字体缓存键（对方 R17）：路径 + mtime + 长度；同名文件被替换后自动得新键。 */
    data class FontCacheKey(val path: String, val lastModifiedMs: Long, val lengthBytes: Long)

    fun fontCacheKeyFor(file: File): FontCacheKey =
        FontCacheKey(file.path, file.lastModified(), file.length())
}

/**
 * 解码在途合并 + 失败集（对方 R11 前两点）：
 *
 * - 同一 key 在途只解码一次：[begin] 在已在途或已失败时返回
 *   null（调用方直接取失败回落，不再发起解码）；否则返回
 *   本次解码所属世代号，调用方解码后以该世代号调 [finish]。
 * - 失败后不再重试：失败 key 进失败集，直到 [nextGeneration]
 *   （主题／世代变更）才清零；世代号不匹配的 finish 不记失败
 *   （旧世代解码结果不得污染新世代失败集）。
 * - 线程安全：全部状态同一把锁；调用方不得在锁内解码
 *   （begin/finish 均只做集合操作）。
 */
class DecodeGate {
    private val lock = Any()
    private val inFlight = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private var generation: Long = 0L

    fun currentGeneration(): Long = synchronized(lock) { generation }

    /** 开始解码：返回世代号，或 null（在途／已失败，调用方直接回落）。 */
    fun begin(key: String): Long? = synchronized(lock) {
        if (key in failed || key in inFlight) return null
        inFlight += key
        generation
    }

    /** 结束解码：success=false 且世代仍为 begin 时的世代才记失败。 */
    fun finish(key: String, success: Boolean, generationAtBegin: Long) {
        synchronized(lock) {
            inFlight -= key
            if (!success && generationAtBegin == generation) failed += key
        }
    }

    /** 世代变更（切换皮肤时调用）：清失败集与在途集，旧解码结果不再入集。 */
    fun nextGeneration(): Long = synchronized(lock) {
        generation += 1
        failed.clear()
        inFlight.clear()
        generation
    }

    fun isFailed(key: String): Boolean = synchronized(lock) { key in failed }
}
