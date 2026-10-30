package com.jobeen.ime.skin.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.GlobalStyleMode
import com.jobeen.ime.data.keyboard.theme.GlobalStylePrefs
import com.jobeen.ime.data.keyboard.theme.GlobalStyleResolver
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.image.AndroidBackgroundPreparer
import com.jobeen.ime.skin.image.BackgroundRebuilder
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.store.ActiveResolver
import com.jobeen.ime.skin.store.ApplyState
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibrary
import com.jobeen.ime.skin.store.SkinOverlayStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /**
     * 背景产物自愈（2026-10-28 修复轮）：导入期写产物、运行期按键
     * 取产物，档位/方向/高度任一漂移即键分裂永久缺失；缺失时在 IO
     * 线程按快照自带键重建一次，成功后装载并刷新窗口（冷路径，
     * 不阻塞应用主流程）。同键单飞：重复安装不重复重建。
     */
    private val rebuildScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rebuildingKeys = mutableSetOf<String>()

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
                InputFeedbacks.clearSkinSounds()
                SkinKeyImageStore.clear()
                // 运行态同步清空：旧皮肤的通知锁定不带到无皮肤态。
                SkinRuntimeState.reset()
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
            InputFeedbacks.clearSkinSounds()
            SkinKeyImageStore.clear()
            SkinRuntimeState.reset()
        }
    }

    /**
     * 覆盖层版本过期检查（2026-10-29 item-1 D1）：当前快照的
     * overlayVersion 与磁盘覆盖层版本不一致时重建并安装，返回
     * true；无快照、无变化或重建失败时返回 false（失败只记
     * 日志，不崩）。供覆盖层写入方（后续设置 UI）在防抖落盘后
     * 调用；运行态本身不轮询磁盘。
     */
    fun refreshIfOverlayChanged(context: Context): Boolean {
        val current = synchronized(lock) { currentSnapshot } ?: return false
        val layout = SkinLibrary.layout
        val diskVersion = runCatching {
            SkinOverlayStore(layout.skinsDir).read(current.ref.skinId)?.version ?: 0L
        }.getOrNull() ?: return false
        if (diskVersion == current.overlayVersion) return false
        val rebuilt = runCatching {
            SkinSnapshotFactory.buildFromGenDir(context.applicationContext, layout, current.ref)
        }.getOrNull() ?: run {
            DiagLog.w(
                "overlay", "overlay_refresh_rebuild_failed",
                mapOf("skinId" to current.ref.skinId),
            )
            return false
        }
        install(context, rebuilt)
        return true
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
        // 运行态同步切换：新皮肤的通知表／条件表即刻生效，旧锁定丢弃。
        SkinRuntimeState.reset(snapshot.notifications, snapshot.conditionStyles)
        syncSkinSoundsLocked(context, snapshot)
        syncKeyImagesLocked(snapshot)
        updateBackgroundBitmapLocked(context, snapshot)
        // 性能埋点（2026-10-29 item-1 B）：快照安装完成时采样一次
        // 内存（冷路径，安装频率低，不进热路径）。
        com.jobeen.ime.skin.perf.PerfMonitor.sampleMemory(context)
    }

    /**
     * 背景位图键处理（安装与模糊重建共用）：产物键变化时释放旧
     * 位图、解码新产物；产物缺失时走异步自愈重建（成功后刷新窗口）。
     */
    private fun updateBackgroundBitmapLocked(context: Context, snapshot: SkinSnapshot) {
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
                    triggerBackgroundRebuild(context, snapshot)
                }
            }
        }
    }

    /**
     * 全局背景模糊重建（2026-10-29 外观轮）：只换当前快照的
     * 背景段（模糊半径＋调节＋产物键），不碰音效／图片／通知
     * 状态、不重建快照；产物键变化走同一自愈重建链路，成功后
     * 刷新窗口。无快照／无背景／生效值不变时直接返回。
     * 只记元数据（皮肤 id＋产物键），不记图片内容。
     */
    fun refreshBackgroundBlur(context: Context) {
        val snapshot = synchronized(lock) { currentSnapshot } ?: return
        val bg = snapshot.background ?: return
        val (blur, adjust) = GlobalStyleResolver.effectiveBlur(
            bg.specBlurRadius, bg.specImageAdjust, bg.blurDisabled,
            GlobalStylePrefs.get(context, GlobalStyleMode.CUSTOM).backgroundBlur,
        )
        if (blur == bg.blurRadius && adjust == bg.imageAdjust) return
        val newBg = bg.copy(
            blurRadius = blur,
            imageAdjust = adjust,
            preparedKey = SkinSnapshotBuilder.preparedKey(
                bg.assetSha256, bg.targetWidthPx, bg.targetHeightPx, blur, adjust,
            ),
        )
        synchronized(lock) {
            // 换背景期间快照可能已被其它安装替换：只在仍为同一
            // 代次时换背景，否则丢弃（新快照构建期已含最新全局值）。
            val current = currentSnapshot
            if (current != null && current.ref == snapshot.ref) {
                currentSnapshot = current.copy(background = newBg)
                updateBackgroundBitmapLocked(context, currentSnapshot!!)
            }
        }
    }

    /**
     * 缺失产物异步重建：资产按内容寻址取（无需读清单），产物写回
     * 本代次 prepared/（派生缓存，非源数据——导出时仍排除，接收端
     * 冷路径重建口径不变）。成功且快照仍为当前时装载并刷新窗口；
     * 失败只记 background_rebuild_failed（与 missing 区分，便于诊断
     * 区分“文件缺失”与“重建失败”）。
     */
    private fun triggerBackgroundRebuild(context: Context, snapshot: SkinSnapshot) {
        val background = snapshot.background ?: return
        val key = background.preparedKey
        synchronized(lock) {
            if (!rebuildingKeys.add(key)) return
        }
        val appContext = context.applicationContext
        rebuildScope.launch {
            try {
                val layout = SkinLibrary.layout
                val assetBytes = layout.assetFiles(background.assetSha256)
                    .firstOrNull { it.isFile }
                    ?.let { runCatching { it.readBytes() }.getOrNull() }
                if (assetBytes == null) {
                    DiagLog.w(
                        "runtime", "background_rebuild_failed",
                        mapOf(
                            "skinId" to snapshot.ref.skinId, "key" to key,
                            "reason" to "asset_missing",
                        ),
                    )
                    return@launch
                }
                val genDir = layout.skinGenDir(snapshot.ref.skinId, snapshot.ref.generation)
                val outcome = BackgroundRebuilder.rebuild(
                    genDir = genDir,
                    background = background,
                    overlayColorArgb = snapshot.colorScheme.background,
                    assetBytes = assetBytes,
                    preparer = AndroidBackgroundPreparer(appContext.resources.displayMetrics.density),
                )
                when (outcome) {
                    is BackgroundRebuilder.Outcome.Failed -> DiagLog.w(
                        "runtime", "background_rebuild_failed",
                        mapOf(
                            "skinId" to snapshot.ref.skinId, "key" to key,
                            "reason" to outcome.reason,
                        ),
                    )
                    is BackgroundRebuilder.Outcome.Rebuilt -> {
                        val bitmap = runCatching {
                            BitmapFactory.decodeFile(outcome.file.absolutePath)
                        }.getOrNull()
                        if (bitmap == null) {
                            DiagLog.w(
                                "runtime", "background_rebuild_failed",
                                mapOf(
                                    "skinId" to snapshot.ref.skinId, "key" to key,
                                    "reason" to "decode_failed",
                                ),
                            )
                            return@launch
                        }
                        val stillCurrent = synchronized(lock) {
                            if (currentSnapshot?.ref == snapshot.ref && currentBitmapKey == key) {
                                val old = currentBitmap
                                currentBitmap = bitmap
                                if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
                                true
                            } else {
                                false
                            }
                        }
                        if (stillCurrent) {
                            SkinRuntime.requestRefresh()
                        } else {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }
            } finally {
                synchronized(lock) { rebuildingKeys.remove(key) }
            }
        }
    }

    /**
     * 快照切换同步皮肤音效（详设 §7.2-1）：已接纳音效按内容寻址
     * 文件整表安装（load 异步，就绪前播出厂 pop）；无接纳整表卸载。
     */
    private fun syncSkinSoundsLocked(context: Context, snapshot: SkinSnapshot) {
        val admitted = snapshot.soundBindings.admitted
        if (admitted.isEmpty()) {
            InputFeedbacks.clearSkinSounds()
            return
        }
        val files = admitted.mapNotNull { (assetId, sound) ->
            val file = SkinLibrary.layout.assetFile(sound.sha256, sound.ext)
            if (file.isFile) assetId to file else null
        }.toMap()
        InputFeedbacks.installSkinSounds(context, files)
    }

    /**
     * G1 单键图片字节源同步：快照切换时清空旧缓存并按当前快照
     * 的接纳表注入内容寻址读取（查无文件返回 null，渲染回退颜色键面）。
     */
    private fun syncKeyImagesLocked(snapshot: SkinSnapshot) {
        SkinKeyImageStore.clear()
        if (snapshot.imageAssets.isEmpty()) return
        val layout = SkinLibrary.layout
        val shaById = snapshot.imageAssets
        SkinKeyImageStore.bytesProvider = { assetId ->
            val sha = shaById[assetId]
            if (sha == null) {
                null
            } else {
                val file = layout.assetFiles(sha).firstOrNull { it.isFile }
                runCatching { file?.readBytes() }.getOrNull()
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
