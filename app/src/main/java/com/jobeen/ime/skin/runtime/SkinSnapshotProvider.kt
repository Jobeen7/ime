package com.jobeen.ime.skin.runtime

import android.content.Context
import android.graphics.Bitmap
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import com.jobeen.ime.data.keyboard.theme.GlobalStyleMode
import com.jobeen.ime.data.keyboard.theme.GlobalStylePrefs
import com.jobeen.ime.data.keyboard.theme.GlobalStyleResolver
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.image.AndroidBackgroundPreparer
import com.jobeen.ime.skin.image.BackgroundRebuilder
import com.jobeen.ime.skin.image.BoundedBitmapDecoder
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
     * 启动加载协程作用域（IO：对账等待＋快照构建都在后台做，
     * 永不占主线程）。SupervisorJob：单次加载失败不牵连其它任务。
     */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 安装代次：[install]/[invalidate] 每次递增。异步启动加载在
     * 完成时比对代次——其间若有一次安装（用户应用皮肤、覆盖层
     * 刷新、回退默认等）发生，启动加载的旧快照直接丢弃，绝不
     * 覆盖用户的新选择。
     */
    private var startupLoadGeneration = 0

    /**
     * 启动加载的依赖缝（审计 #1-6 的可测性）：生产实现直调
     * [SkinLibrary]/[loadStartup]/[install]；单测可替换为慢对账
     * 注入与快照录制，验证首帧不阻塞与异步安装。接口直实现
     * （显式 override、无 `by` 委托），不触发 AGENTS.md 的
     * 接口委托增量陷阱。
     */
    internal interface StartupLoadDeps {
        suspend fun awaitBootstrap()
        suspend fun loadSnapshot(context: Context): Pair<SkinSnapshot, Boolean>?
        fun installSnapshot(context: Context, snapshot: SkinSnapshot, pending: Boolean)
    }

    private object RealStartupLoadDeps : StartupLoadDeps {
        override suspend fun awaitBootstrap() {
            SkinLibrary.awaitBootstrap()
        }

        override suspend fun loadSnapshot(context: Context): Pair<SkinSnapshot, Boolean>? =
            loadStartup(context)

        override fun installSnapshot(context: Context, snapshot: SkinSnapshot, pending: Boolean) {
            install(context, snapshot)
            if (pending) {
                SkinRuntime.armPendingVerification(snapshot.ref)
            }
            // 启动快照后到：首帧已按内置默认绘出，须通知窗口
            // 重绘才能换上皮肤（refreshSkinTheme 内部 post，
            // 任意线程调用安全）。
            SkinRuntime.requestRefresh()
        }
    }

    internal var startupLoadDeps: StartupLoadDeps = RealStartupLoadDeps

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

    /**
     * 启动加载（审计 #1-6）：首次调用只标记「已发起」并立即
     * 返回，绝不在调用线程等待对账收敛——旧实现在同步锁内
     * `runBlocking { SkinLibrary.awaitBootstrap() }`，冷启动时
     * 首次 keyboard snapshot 查询发生在主线程会同步卡住最多
     * 3 秒、延迟键盘首帧。
     *
     * 新口径：首帧先用当前快照（冷启动时为 null，即内置默认，
     * 与无皮肤生效时同一回退）；对账与快照构建在 IO 线程异步
     * 跑，完成后原子安装并通知窗口重绘。超时放行仍在
     * [SkinLibrary.awaitBootstrap] 内（上限 3 秒）：对账超时
     * 时按现有 store 状态加载，不阻塞首帧。
     *
     * 并发：多次调用只发起一次（[loadAttempted] 同步锁内判定）；
     * 安装期间若用户已应用皮肤，启动加载的旧快照按代次丢弃。
     */
    fun ensureLoaded(context: Context) {
        val generation: Int
        synchronized(lock) {
            if (loadAttempted) return
            loadAttempted = true
            generation = startupLoadGeneration
        }
        // context 原样传进协程：applicationContext 的解析留在
        // 依赖实现里（生产路径才需要；单测的假依赖不碰它，
        // 故桩 Context 也能驱动本编排）。
        startupScope.launch {
            runCatching { startupLoadDeps.awaitBootstrap() }
            val loaded = runCatching { startupLoadDeps.loadSnapshot(context) }.getOrNull()
                ?: return@launch
            val stillCurrent = synchronized(lock) {
                generation == startupLoadGeneration && currentSnapshot == null
            }
            if (!stillCurrent) return@launch
            runCatching {
                startupLoadDeps.installSnapshot(context, loaded.first, loaded.second)
            }
        }
    }

    /** 应用事务验证完成后切换快照（ref 为 null = 回到内置默认）。 */
    fun install(context: Context, snapshot: SkinSnapshot?) {
        synchronized(lock) {
            loadAttempted = true
            // 安装代次递增：在途的异步启动加载完成时按代次丢弃，
            // 不覆盖本次安装。
            startupLoadGeneration++
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
            // 代次递增：在途的异步启动加载完成时按代次丢弃，
            // 下次 ensureLoaded 重新发起加载。
            startupLoadGeneration++
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
        syncSkinFontsLocked(snapshot)
        updateBackgroundBitmapLocked(context, snapshot)
        // 性能埋点（2026-10-29 item-1 B）：快照安装完成时采样一次
        // 内存（冷路径，安装频率低，不进热路径）。
        com.jobeen.ime.skin.perf.PerfMonitor.sampleMemory(context)
        // 第 5 批 §2：IO 预热——键图与内嵌字体在 IO 段先解进缓存，
        // 使后续键盘构建／绘制期命中缓存、不在 UI 线程解码、不
        // 因首次加载加异步回调而闪烁；安装本身不等待预热完成。
        val appContext = context.applicationContext
        rebuildScope.launch {
            runCatching { SkinKeyImageStore.prewarm(snapshot.imageAssets.keys) }
            runCatching { GlobalFonts.prewarmSkinFonts(appContext, snapshot) }
        }
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
                            BoundedBitmapDecoder.decodeFile(
                                outcome.file,
                                background.targetWidthPx, background.targetHeightPx,
                            )
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

    /**
     * 皮肤内嵌字体字节源同步（审计 C 项）：快照切换时清空旧
     * 缓存并按当前快照的接纳表注入内容寻址读取（查无文件
     * 返回 null，渲染回落下一级字体，与单键图片同款口径）。
     */
    private fun syncSkinFontsLocked(snapshot: SkinSnapshot) {
        GlobalFonts.clearSkinFontCache()
        if (snapshot.skinFontAssets.isEmpty()) {
            GlobalFonts.skinFontBytesProvider = null
            return
        }
        val layout = SkinLibrary.layout
        val shaById = snapshot.skinFontAssets
        GlobalFonts.skinFontBytesProvider = { assetId ->
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

    /** 解码预处理产物（目标尺寸上限两遍式：合法产物采样率 1，超大产物降采样到目标；产物缺失返回 null，背景回退纯色）。 */
    private fun decodePrepared(context: Context, snapshot: SkinSnapshot): Bitmap? {
        val background = snapshot.background ?: return null
        val genDir = SkinLibrary.layout.skinGenDir(snapshot.ref.skinId, snapshot.ref.generation)
        val file = File(File(genDir, "prepared"), "${background.preparedKey}.webp")
        if (!file.isFile) return null
        return runCatching {
            BoundedBitmapDecoder.decodeFile(
                file, background.targetWidthPx, background.targetHeightPx,
            )
        }.getOrNull()
    }

    /**
     * 启动加载：采 verified 或保留的 pending 记录（代次完整、hash 一致，
     * ActiveResolver 同口径）；返回快照与「记录是否为 pending」。
     * suspend：调用方在协程里直接读 store，不再 runBlocking。
     */
    private suspend fun loadStartup(context: Context): Pair<SkinSnapshot, Boolean>? {
        val store = SkinLibrary.store
        val layout = SkinLibrary.layout
        val active = store.readActive()
        val record = (active as? com.jobeen.ime.skin.store.SkinLibraryStore.ReadResult.Ok)?.value
            ?: return null
        val skinId = record.skinId ?: return null
        if (record.state != ApplyState.VERIFIED && record.state != ApplyState.PENDING) return null
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
        val snapshot = SkinSnapshotFactory.buildFromGenDir(
            appContext, layout, SkinGenRef(skinId, record.hash!!, record.generation),
        ) ?: return null
        return snapshot to (record.state == ApplyState.PENDING)
    }
}
