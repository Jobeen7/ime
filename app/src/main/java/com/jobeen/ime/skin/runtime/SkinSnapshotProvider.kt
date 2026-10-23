package com.jobeen.ime.skin.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.ActiveResolver
import com.jobeen.ime.skin.store.ApplyState
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibrary
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 运行态快照持有者（详设 §2.7）：resolve() 的分流依据。
 *
 * - 快照只经「已验证」路径进入：启动加载只采 verified 记录
 *   （pending 视为未验证，不采用，与八.3/R5 口径一致）；
 *   应用事务在首渲验证完成后经 [install] 切换。
 * - 背景位图按定稿十一.3 上限持有：当前皮肤 1 份 + 编辑器预览
 *   1 份；位图在冷路径（加载/应用/刷新）解码，热路径只读引用。
 */
object SkinSnapshotProvider {

    @Volatile
    private var currentSnapshot: SkinSnapshot? = null

    @Volatile
    private var currentBitmap: Bitmap? = null

    @Volatile
    private var currentBitmapKey: String? = null

    @Volatile
    private var previewBitmap: Bitmap? = null

    @Volatile
    private var loadAttempted = false

    private val lock = Any()

    /** 当前已验证快照（触发一次启动加载）；无 v3 皮肤生效时为 null。 */
    fun current(context: Context): SkinSnapshot? {
        ensureLoaded(context)
        return currentSnapshot
    }

    /** 不触发加载的只读视图（设置页状态展示用）。 */
    fun peek(): SkinSnapshot? = currentSnapshot

    /** 当前快照的背景位图（已在冷路径解码；无背景/产物缺失时为 null）。 */
    fun currentBackgroundBitmap(): Bitmap? = currentBitmap

    fun ensureLoaded(context: Context) {
        if (loadAttempted) return
        synchronized(lock) {
            if (loadAttempted) return
            loadAttempted = true
            val snapshot = runCatching { loadVerified(context) }.getOrNull()
            if (snapshot != null) {
                installLocked(context, snapshot)
            }
        }
    }

    /** 应用事务验证完成后切换快照（ref 为 null = 回到内置默认）。 */
    fun install(context: Context, snapshot: SkinSnapshot?) {
        synchronized(lock) {
            loadAttempted = true
            if (snapshot == null) {
                currentSnapshot = null
                releaseBitmapLocked()
            } else {
                installLocked(context, snapshot)
            }
        }
    }

    /** 进程内失效（如库被外部清理后由设置页触发重读）。 */
    fun invalidate() {
        synchronized(lock) {
            loadAttempted = false
            currentSnapshot = null
            releaseBitmapLocked()
        }
    }

    // ---- 编辑器预览位图槽（定稿十一.3 的第二份） ----

    fun setPreviewBitmap(bitmap: Bitmap?) {
        val old = previewBitmap
        previewBitmap = bitmap
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
    }

    fun previewBitmap(): Bitmap? = previewBitmap

    /** 内存压力淘汰（详设 §6.3）：先淘汰预览份。 */
    fun evictPreviewBitmap() = setPreviewBitmap(null)

    // ---- 内部 ----

    private fun installLocked(context: Context, snapshot: SkinSnapshot) {
        currentSnapshot = snapshot
        val key = snapshot.background?.preparedKey
        if (key != currentBitmapKey) {
            releaseBitmapLocked()
            currentBitmapKey = key
            if (key != null) {
                currentBitmap = decodePrepared(context, snapshot)
                if (currentBitmap == null) {
                    DiagLog.w(
                        "runtime", "background_bitmap_missing",
                        mapOf("skinId" to snapshot.ref.skinId, "key" to key),
                    )
                }
            }
        }
    }

    private fun releaseBitmapLocked() {
        currentBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentBitmap = null
        currentBitmapKey = null
    }

    /** 解码预处理产物（已是目标尺寸，采样率 1）；产物缺失返回 null，背景回退纯色。 */
    private fun decodePrepared(context: Context, snapshot: SkinSnapshot): Bitmap? {
        val background = snapshot.background ?: return null
        val genDir = SkinLibrary.layout.skinGenDir(snapshot.ref.skinId, snapshot.ref.generation)
        val file = File(File(genDir, "prepared"), "${background.preparedKey}.webp")
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /** 启动加载：只采 verified 且代次完整、hash 一致的记录（ActiveResolver 同口径）。 */
    private fun loadVerified(context: Context): SkinSnapshot? {
        val store = SkinLibrary.store
        val layout = SkinLibrary.layout
        val active = runBlocking { store.readActive() }
        val record = (active as? com.jobeen.ime.skin.store.SkinLibraryStore.ReadResult.Ok)?.value
            ?: return null
        val skinId = record.skinId ?: return null
        if (record.state != ApplyState.VERIFIED) return null
        val valid = ActiveResolver.isTargetValid(skinId, record.generation, record.hash) { id, gen ->
            val dir = layout.skinGenDir(id, gen)
            val complete = layout.isSkinGenComplete(dir)
            val hash = if (complete) {
                runCatching { DigestUtil.sha256Hex(File(dir, "skin.json")) }.getOrNull()
            } else {
                null
            }
            ActiveResolver.GenState(complete, hash)
        }
        if (!valid) return null
        val appContext = context.applicationContext
        return SkinSnapshotFactory.buildFromGenDir(
            appContext, layout, SkinGenRef(skinId, record.hash!!, record.generation),
        )
    }
}
