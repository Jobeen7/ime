package com.jobeen.ime.skin.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.media.AndroidAudioInfoProbe
import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.AudioAssetFacts
import com.jobeen.ime.skin.model.BindingRejectReason
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibraryLayout
import java.io.File

/**
 * 快照工厂（Android 适配层）：把磁盘上的一代皮肤（skin.json +
 * manifest.json）经校验器与构建器变成运行快照。校验不通过 →
 * null + 记日志，调用方按无 v3 皮肤处理（绝不崩溃/空白，定稿八.3）。
 *
 * 目标尺寸口径（详设 §2.6）：解码目标 = 屏幕宽 × 键盘实际高度，
 * 键盘高度由现行高度百分比设置（竖/横屏分别取）决定。
 */
object SkinSnapshotFactory {

    fun geometryFallbacks(context: Context): GeometryFallbacks = GeometryFallbacks(
        cornerRadius = KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat(),
        keyHMargin = KeyboardManager.Keyboard.Gap.getHorizontalDp(context).toFloat(),
        keyVMargin = KeyboardManager.Keyboard.Gap.getVerticalDp(context).toFloat(),
        bottomInsetDp = KeyboardManager.Keyboard.Padding.getBottomDp(context).toFloat(),
    )

    fun evaluateTier(context: Context): MemoryTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val base = MemoryTier.of(am.isLowRamDevice, am.memoryClass)
        // 内存压力升级（详设 §6.3）：trim 置位后，下一次构建降一档（一次性消费）
        if (SkinRuntime.trimEscalated) {
            SkinRuntime.trimEscalated = false
            return base.escalated()
        }
        return base
    }

    fun currentVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)

    /** 背景解码目标尺寸（宽 px, 高 px）。 */
    fun backgroundTargetPx(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val landscape = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val percent = if (landscape) {
            KeyboardManager.Keyboard.getHeightPercentLandscape(context)
        } else {
            KeyboardManager.Keyboard.getHeightPercent(context)
        }
        val height = (metrics.heightPixels * percent / 100f).toInt().coerceAtLeast(1)
        return metrics.widthPixels.coerceAtLeast(1) to height
    }

    /**
     * 从代次目录构建快照。manifest 副本缺失/损坏时退化为空清单
     * （背景/布局引用随之按缺失处理——校验器会逐项回退并报告，
     * 但工厂路径只记日志，不出报告）。
     */
    fun buildFromGenDir(
        context: Context,
        layout: SkinLibraryLayout,
        ref: SkinGenRef,
    ): SkinSnapshot? {
        val genDir = layout.skinGenDir(ref.skinId, ref.generation)
        return buildFromFiles(
            context = context,
            skinJsonFile = File(genDir, "skin.json"),
            manifestFile = File(genDir, "manifest.json"),
            ref = ref,
            libraryLayout = layout,
        )
    }

    fun buildFromFiles(
        context: Context,
        skinJsonFile: File,
        manifestFile: File,
        ref: SkinGenRef,
        libraryLayout: SkinLibraryLayout? = null,
    ): SkinSnapshot? {
        val tree = runCatching { SkinJson.parseObject(skinJsonFile.readText()) }.getOrNull()
        if (tree == null) {
            DiagLog.w("runtime", "snapshot_skin_unparseable", mapOf("skinId" to ref.skinId))
            return null
        }
        val manifest = runCatching {
            PackageManifest.parse(manifestFile.readText())
        }.getOrNull() ?: PackageManifest()
        return buildFromTree(context, tree, manifest, ref, libraryLayout)
    }

    fun buildFromTree(
        context: Context,
        tree: kotlinx.serialization.json.JsonObject,
        manifest: PackageManifest,
        ref: SkinGenRef,
        libraryLayout: SkinLibraryLayout? = null,
    ): SkinSnapshot? {
        val assetKindById = manifest.assets().associate { content ->
            content.id to PackageManifest.assetKindOf(content.path)
        }
        val result = SkinValidator.validate(
            tree = tree,
            currentVersionCode = currentVersionCode(context),
            fallbacks = geometryFallbacks(context),
            assetKind = { assetKindById[it] },
        )
        val validated = when (result) {
            is SkinValidator.Result.Rejected -> {
                DiagLog.w(
                    "runtime", "snapshot_validation_rejected",
                    mapOf("skinId" to ref.skinId, "reason" to result.reason),
                )
                return null
            }
            is SkinValidator.Result.Valid -> result.skin
        }
        val assetShaById = manifest.assets().associate { it.id to it.sha256 }
        val (targetW, targetH) = backgroundTargetPx(context)
        // 音效事实（详设 §7.1 接纳输入）：清单条目 + 内容寻址文件 + 解码元信息；
        // 无库布局时（纯树构建）恒 null，音效按缺失处理
        val contentById = manifest.assets().associateBy { it.id }
        val audioFacts: (String) -> AudioAssetFacts? = { assetId ->
            val content = contentById[assetId]
            val libLayout = libraryLayout
            if (content == null || libLayout == null) {
                null
            } else {
                val ext = content.path.substringAfterLast('.', "").lowercase()
                val file = libLayout.assetFile(content.sha256, ext.ifEmpty { null })
                if (!file.isFile) {
                    null
                } else {
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    AudioAssetFacts(
                        sha256 = content.sha256,
                        ext = ext,
                        byteSize = file.length(),
                        info = bytes?.let { AndroidAudioInfoProbe.probe(it) },
                    )
                }
            }
        }
        val snapshot = SkinSnapshotBuilder.build(
            validated = validated,
            ref = ref,
            defaultScheme = KeyboardTheme.DEFAULT.colors,
            tier = evaluateTier(context),
            assetSha256 = { assetShaById[it] },
            targetWidthPx = targetW,
            targetHeightPx = targetH,
            audioAssetOrder = manifest.assets().map { it.id },
            audioFacts = audioFacts,
        )
        logBatch2Events(ref, validated, snapshot)
        return snapshot
    }

    /** B2 诊断事件面（详设 §八）：只记 keyId/槽位/原因枚举/计数，绝不记 label/value 原文。 */
    private fun logBatch2Events(
        ref: SkinGenRef,
        validated: com.jobeen.ime.skin.model.ValidatedSkin,
        snapshot: SkinSnapshot,
    ) {
        if (validated.keyOverrides.isNotEmpty() || validated.keyRejections.isNotEmpty()) {
            DiagLog.i(
                "keymap", "override_snapshot_built",
                mapOf(
                    "skinId" to ref.skinId,
                    "overrideCount" to validated.keyOverrides.size,
                    "rejectedCount" to validated.keyRejections.size,
                    "sequenceCount" to validated.sequenceBindingCount,
                ),
            )
        }
        for (rejection in validated.keyRejections) {
            if (rejection.reason == BindingRejectReason.ANCHOR_NOT_OVERRIDABLE) {
                DiagLog.i(
                    "keymap", "anchor_not_overridable",
                    mapOf("keyId" to rejection.keyId),
                )
            } else {
                DiagLog.w(
                    "keymap", "binding_rejected",
                    mapOf(
                        "keyId" to rejection.keyId,
                        "slot" to rejection.slot?.jsonName,
                        "reason" to rejection.reason.name.lowercase(),
                    ),
                )
            }
        }
        for ((assetId, sound) in snapshot.soundBindings.admitted) {
            DiagLog.i(
                "audio", "sound_admitted",
                mapOf("assetId" to assetId, "pcmBytes" to sound.pcmBytes),
            )
        }
        for (rejected in snapshot.soundBindings.rejected) {
            DiagLog.w(
                "audio", "sound_rejected",
                mapOf(
                    "assetId" to rejected.assetId,
                    "reason" to rejected.reason.name.lowercase(),
                ),
            )
        }
    }
}
