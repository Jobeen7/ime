// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinLibrary
import java.io.File

/**
 * 主题视频背景控制器（2026-10-29 外观轮）。
 *
 * 渲染：TextureView＋MediaPlayer、静音循环、居中裁剪；
 * 绝不请求音频焦点（只 setVolume(0,0)，不调 requestAudioFocus）。
 *
 * 播放门（四门全开才播，任一关即暂停解码——省电）：
 * attach（窗口已挂载）＋inputStarted（startInputView 后、
 * finishInputView 前）＋isShown＋窗口可见（VISIBLE）。
 * detach（窗口销毁）／surface 销毁时 release；播放失败时
 * 释放并回调宿主回落静态背景，失败文件在身份变化前不再重试。
 *
 * 优先级：视频 ＞ 动画 GIF ＞ 静态图 ＞ 纯色；imageAdjust
 * 只作用静态图，不作用视频帧（MediaPlayer 无此管线）。
 *
 * 缓存失效等价手段：同步键＝（皮肤 id，代次，视频 sha，
 * 开关）四元组；任一变化才重建播放器，同键重复同步直接返回。
 * 视频文件经库内容寻址（assets/<sha>.<ext>），不走全局路径。
 */
class VideoBackgroundController(
    private val host: ViewGroup,
    private val onVideoFailed: () -> Unit = {},
) : TextureView.SurfaceTextureListener {

    /** 懒建视图：无视频皮肤不建 TextureView、不进层级。 */
    var view: TextureView? = null
        private set

    private var player: MediaPlayer? = null
    private var surface: SurfaceTexture? = null
    private var videoWidth = 0
    private var videoHeight = 0

    private var attached = false
    private var inputStarted = false
    private var viewShown = false
    private var windowVisible = false

    private var currentFile: File? = null
    private var currentKey: String? = null
    private var currentSkinId: String? = null
    private var failedKeys = mutableSetOf<String>()
    private var prepared = false

    /** 宿主 onViewAdded 复位用：未建视图时为 null（不触发懒建）。 */
    val viewIfCreated: TextureView? get() = view

    fun onAttached() {
        attached = true
        maybePlay()
    }

    fun onDetached() {
        attached = false
        inputStarted = false
        releasePlayer()
        view?.let { if (it.parent === host) host.removeView(it) }
    }

    fun onInputStarted() {
        inputStarted = true
        maybePlay()
    }

    fun onInputFinished() {
        inputStarted = false
        pauseOnly()
    }

    fun onWindowVisibilityChanged(visible: Boolean) {
        windowVisible = visible
        maybePlay()
    }

    fun onViewShownChanged(shown: Boolean) {
        viewShown = shown
        maybePlay()
    }

    /**
     * 与当前快照同步：返回是否正显示视频层（宿主据此启停 GIF 层）。
     * 无快照／无视频／开关关时撤掉视频层；同键重复同步直接返回。
     */
    fun sync(snapshot: SkinSnapshot?, videoEnabled: Boolean): Boolean {
        val bg = snapshot?.background
        val file = if (videoEnabled && bg?.videoSha256 != null) {
            SkinLibrary.layout.assetFiles(bg.videoSha256)
                .firstOrNull { it.isFile }
        } else {
            null
        }
        val key = if (file != null && snapshot != null) {
            "${snapshot.ref.skinId}:${snapshot.ref.generation}:${bg?.videoSha256}:$videoEnabled"
        } else {
            null
        }
        if (key == currentKey) return view?.parent === host
        releasePlayer()
        currentKey = key
        currentFile = file
        currentSkinId = snapshot?.ref?.skinId
        if (file == null) {
            view?.let { if (it.parent === host) host.removeView(it) }
            return false
        }
        if (key in failedKeys) {
            // 失败文件在身份变化前不再重试：直接回落静态背景。
            view?.let { if (it.parent === host) host.removeView(it) }
            return false
        }
        val textureView = view ?: TextureView(host.context).also {
            it.surfaceTextureListener = this
            it.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            view = it
        }
        if (textureView.parent == null) host.addView(textureView, 0)
        if (surface != null) preparePlayer()
        return true
    }

    private fun preparePlayer() {
        val file = currentFile ?: return
        val texture = surface ?: return
        releasePlayer()
        prepared = false
        val mp = try {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setSurface(android.view.Surface(texture))
                setVolume(0f, 0f)
                isLooping = true
                setOnVideoSizeChangedListener { _, w, h ->
                    this@VideoBackgroundController.videoWidth = w
                    this@VideoBackgroundController.videoHeight = h
                    applyCropTransform()
                }
                setOnPreparedListener {
                    prepared = true
                    applyCropTransform()
                    maybePlay()
                }
                setOnErrorListener { _, what, extra ->
                    DiagLog.w(
                        "runtime", "video_playback_failed",
                        mapOf(
                            "skinId" to (currentSkinId ?: ""),
                            "what" to what,
                            "extra" to extra,
                        ),
                    )
                    currentKey?.let { failedKeys += it }
                    releasePlayer()
                    view?.let { if (it.parent === host) host.removeView(it) }
                    onVideoFailed()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            DiagLog.w(
                "runtime", "video_playback_failed",
                mapOf(
                    "skinId" to (currentSkinId ?: ""),
                    "reason" to (e.javaClass.simpleName ?: "unknown"),
                ),
            )
            currentKey?.let { failedKeys += it }
            view?.let { if (it.parent === host) host.removeView(it) }
            onVideoFailed()
            return
        }
        player = mp
    }

    private fun maybePlay() {
        val mp = player ?: return
        if (!prepared) return
        if (attached && inputStarted && viewShown && windowVisible) {
            if (!mp.isPlaying) runCatching { mp.start() }
        } else {
            pauseOnly()
        }
    }

    private fun pauseOnly() {
        val mp = player ?: return
        if (prepared && mp.isPlaying) runCatching { mp.pause() }
    }

    private fun releasePlayer() {
        prepared = false
        player?.let { runCatching { it.release() } }
        player = null
    }

    /** 居中裁剪：按覆盖比例缩放、居中（TextureView 变换矩阵）。 */
    private fun applyCropTransform() {
        val textureView = view ?: return
        if (videoWidth <= 0 || videoHeight <= 0 ||
            textureView.width <= 0 || textureView.height <= 0
        ) {
            return
        }
        val scale = maxOf(
            textureView.width.toFloat() / videoWidth,
            textureView.height.toFloat() / videoHeight,
        )
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (textureView.width - videoWidth * scale) / 2f,
            (textureView.height - videoHeight * scale) / 2f,
        )
        textureView.setTransform(matrix)
    }

    override fun onSurfaceTextureAvailable(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        this.surface = surface
        if (currentFile != null) preparePlayer()
    }

    override fun onSurfaceTextureSizeChanged(
        surface: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        applyCropTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // surface 销毁即 release（绝不后台持有解码器）。
        if (this.surface === surface) this.surface = null
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // 帧回调不做事（不拦截渲染热路径）。
    }
}
