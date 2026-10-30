package com.jobeen.ime.skin.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.jobeen.ime.data.keyboard.theme.GlobalStyleMode
import com.jobeen.ime.data.keyboard.theme.GlobalStylePrefs
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.layout.BuiltinLayoutResolution
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.layout.LayoutResolver
import com.jobeen.ime.skin.layout.resolveBuiltinLayouts
import com.jobeen.ime.skin.media.AndroidAudioInfoProbe
import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.AudioAssetFacts
import com.jobeen.ime.skin.model.BindingRejectReason
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinOverlayMerge
import com.jobeen.ime.skin.store.SkinOverlayStore
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
        // 覆盖层（2026-10-29 item-1 D1）：有覆盖文件即叠加在基础
        // 树上再走同一校验管线，基础文件本身不改；覆盖层损坏 =
        // 无覆盖（降级，不崩）。全部运行态入口（启动加载、应用
        // 事务、库页预览）皆经 buildFromGenDir → 本函数，故一次
        // 接线即全量生效。
        var overlayVersion = 0L
        var effectiveTree = tree
        if (libraryLayout != null) {
            val overlay = runCatching {
                SkinOverlayStore(libraryLayout.skinsDir).read(ref.skinId)
            }.getOrNull()
            if (overlay != null) {
                val (merged, issues) = SkinOverlayMerge.merge(tree, overlay.overrides)
                if (issues.isNotEmpty()) {
                    DiagLog.w(
                        "overlay", "overlay_merge_issues",
                        mapOf(
                            "skinId" to ref.skinId,
                            "issues" to issues.joinToString(";"),
                        ),
                    )
                }
                effectiveTree = merged
                overlayVersion = overlay.version
            }
        }
        val snapshot = buildFromTree(context, effectiveTree, manifest, ref, libraryLayout)
        return snapshot?.copy(overlayVersion = overlayVersion)
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
        val assetShaById = manifest.assets().associate { it.id to it.sha256 }
        // G1：与导入端同口径——皮肤所指布局的坐标 ID 另行可覆写，
        // 单键图片 1MB 上限以库内文件实际大小执行；任一步取不到时
        // 静默回落缺省口径（不改变此前行为，只补此前缺失的接线）。
        val coordIds = layoutCoordIds(tree, libraryLayout)
        val result = SkinValidator.validate(
            tree = tree,
            currentVersionCode = currentVersionCode(context),
            fallbacks = geometryFallbacks(context),
            assetKind = { assetKindById[it] },
            isOverridable = { it in coordIds || KeyIds.isOverridable(it) },
            assetBytes = { id ->
                assetShaById[id]?.let { sha ->
                    libraryLayout?.assetFiles(sha)?.firstOrNull { it.isFile }?.length()
                }
            },
        )
        val validated = when (result) {
            is SkinValidator.Result.Rejected -> {
                DiagLog.w(
                    "runtime", "snapshot_validation_rejected",
                    mapOf("skinId" to ref.skinId, "reason" to result.reason),
                )
                return null
            }
            is SkinValidator.Result.Valid -> {
                // 校验通过但带回退 issue（派生/样式/字段回填等）：
                // 导入路径有 skin_validation_issues 汇总，快照路径此前
                // 直接丢弃 issues——应用时发生的回退在导出 zip 里无影。
                // 与导入同口径补一条汇总（只记路径前 3 条，不记取值）。
                if (result.issues.isNotEmpty()) {
                    DiagLog.w(
                        "runtime", "snapshot_validation_issues",
                        mapOf(
                            "skinId" to ref.skinId,
                            "issueCount" to result.issues.size,
                            "firstPaths" to result.issues.take(3).joinToString(",") { it.path },
                        ),
                    )
                }
                result.skin
            }
        }
        val (targetW, targetH) = backgroundTargetPx(context)
        val audioFacts = manifestAudioFacts(libraryLayout, manifest)
        val (resolvedLayout, layoutResolution) = resolveSnapshotLayout(
            context, libraryLayout, validated.layoutId, ref,
        )
        // 横屏绑定（2026-10-27 第一组 + 矩阵扩展）：与竖屏绑定
        // 独立解析——无竖屏绑定时横屏绑定仍可激活（内置布局
        // 竖屏 + 横屏专属布局，见 CustomLayoutRuntime 的主键盘
        // 位映射）。横屏绑定缺失/文件损坏/校验无效时一律回退：
        // 有竖屏布局时回退竖屏布局的横屏权重变体，无竖屏布局
        // 时回退方案默认键盘，永不空白。
        val (resolvedLandscape, landscapeResolution) =
            if (validated.layoutIdLandscape != null) {
                resolveSnapshotLayout(
                    context, libraryLayout, validated.layoutIdLandscape, ref,
                    binding = "landscape",
                )
            } else {
                null to LayoutResolution.NOT_SET
            }
        val assetExtById = manifest.assets().associate {
            it.id to it.path.substringAfterLast('.', "").lowercase()
        }
        // 内置布局覆盖（2026-10-28）：从本皮肤代次目录读
        // layouts/<内置id>.json（导入期内置分流落盘处），与全局
        // 布局索引无关——同内置 id 的覆盖分属各皮肤，互不冲突。
        // 无效文件只丢该内置覆盖并记日志，皮肤照常成立（回退出厂布局）。
        val builtinResolution = if (libraryLayout != null) {
            resolveBuiltinLayouts(libraryLayout.skinGenDir(ref.skinId, ref.generation))
        } else {
            BuiltinLayoutResolution(emptyMap(), emptyMap())
        }
        for ((layoutId, issues) in builtinResolution.invalid) {
            DiagLog.w(
                "layout", "builtin_layout_dropped",
                mapOf(
                    "skinId" to ref.skinId, "layoutId" to layoutId,
                    "issueCount" to issues.size,
                    "firstReason" to issues.firstOrNull()?.reason,
                ),
            )
        }
        val snapshot = SkinSnapshotBuilder.build(
            validated = validated,
            ref = ref,
            defaultScheme = KeyboardTheme.DEFAULT.colors,
            tier = evaluateTier(context),
            assetSha256 = { assetShaById[it] },
            assetExt = { assetExtById[it] },
            targetWidthPx = targetW,
            targetHeightPx = targetH,
            audioAssetOrder = manifest.assets().map { it.id },
            audioFacts = audioFacts,
            resolvedLayout = resolvedLayout,
            layoutResolution = layoutResolution,
            resolvedLayoutLandscape = resolvedLandscape,
            landscapeLayoutResolution = landscapeResolution,
            builtinLayouts = builtinResolution.layouts,
            globalBlur = GlobalStylePrefs.get(context, GlobalStyleMode.CUSTOM).backgroundBlur,
        )
        logBatch2Events(ref, validated, snapshot)
        return snapshot
    }

    /**
     * G1 坐标放宽口径（校验前 Best-effort）：皮肤 layoutId 所指
     * 库内布局的全部逻辑 ID；任一步失败返回空集（回落缺省口径）。
     * 与 [resolveSnapshotLayout] 读同一索引与同一 layout.json，
     * 只是提前到校验之前，使坐标覆写不被校验器当成未知键剔掉。
     */
    private fun layoutCoordIds(
        tree: kotlinx.serialization.json.JsonObject,
        libraryLayout: SkinLibraryLayout?,
    ): Set<String> {
        val layout = libraryLayout ?: return emptySet()
        val layoutId = (tree["layoutId"] as? JsonPrimitive)?.contentOrNull
            ?: return emptySet()
        val index = runCatching {
            runBlocking { SkinLibraryStore(layout).readIndex() }
                as? SkinLibraryStore.ReadResult.Ok
        }.getOrNull()?.value ?: return emptySet()
        val entry = index.layouts.firstOrNull { it.id == layoutId } ?: return emptySet()
        val file = File(layout.fileFromRelative(entry.dir), "layout.json")
        val layoutTree = runCatching {
            SkinJson.parseObject(file.readText())
        }.getOrNull() ?: return emptySet()
        return LayoutModel.logicalIdsFromTree(layoutTree, layoutId)
    }

    /**
     * 布局引用解析接线（B3 §3.4/§3.5）：索引 → 当前代次文件 →
     * 校验 → 构建。失败一律回退为「未设 layoutId」：快照照常
     * 成立、皮肤其余部分全部生效，逐项记日志（铁律 4）；不触发
     * Last-Good（布局回退已在解析层完成，不属于新皮肤生效失败）。
     */
    private fun resolveSnapshotLayout(
        context: Context,
        libraryLayout: SkinLibraryLayout?,
        layoutId: String?,
        ref: SkinGenRef,
        /** 绑定角色：portrait = 竖屏主绑定（日志口径与改前一致， 不新增字段）；landscape = 横屏绑定（日志带 orientation 区分， 回退目标是竖屏布局而非内置默认）。 */
        binding: String = "portrait",
    ): Pair<ResolvedLayout?, LayoutResolution> {
        if (layoutId == null) return null to LayoutResolution.NOT_SET
        // 横屏绑定日志与竖屏主绑定同事件名、靠 orientation 区分；
        // 竖屏主绑定保持改前日志形状（不新增字段，避免诊断消费方断裂）。
        val orientationField: Map<String, Any?> =
            if (binding == "landscape") mapOf("orientation" to "landscape") else emptyMap()
        if (libraryLayout == null) {
            // 竖屏主绑定保持改前静默口径；横屏绑定是新增失败路径，
            // 按硬要求记日志（回退目标仍是竖屏布局）。
            if (binding == "landscape") {
                DiagLog.w(
                    "layout", "layout_fallback_default",
                    mapOf(
                        "skinId" to ref.skinId, "layoutId" to layoutId,
                        "reason" to "library_unavailable",
                    ) + orientationField,
                )
            }
            return null to LayoutResolution.MISSING
        }
        val index = (runBlocking { SkinLibraryStore(libraryLayout).readIndex() }
            as? SkinLibraryStore.ReadResult.Ok)?.value
        val entry = index?.layouts?.firstOrNull { it.id == layoutId }
        if (entry == null) {
            DiagLog.w(
                "layout", "layout_fallback_default",
                mapOf(
                    "skinId" to ref.skinId, "layoutId" to layoutId,
                    "reason" to "missing",
                ) + orientationField,
            )
            return null to LayoutResolution.MISSING
        }
        val file = File(libraryLayout.fileFromRelative(entry.dir), "layout.json")
        val tree = runCatching { SkinJson.parseObject(file.readText()) }.getOrNull()
        if (tree == null) {
            // 代次目录损坏/文件缺失（§3.5 第 5 项）：按 missing 处理，
            // 索引对账（B1 启动对账）另行收敛
            DiagLog.w(
                "layout", "layout_fallback_default",
                mapOf(
                    "skinId" to ref.skinId, "layoutId" to layoutId,
                    "reason" to "layout_file_unreadable",
                ) + orientationField,
            )
            return null to LayoutResolution.MISSING
        }
        return when (
            val outcome = LayoutResolver.resolve(
                tree, layoutId, entry.generation, PlaceholderValues.current(context),
            )
        ) {
            is LayoutResolver.Outcome.Invalid -> {
                val first = outcome.issues.firstOrNull()
                DiagLog.w(
                    "layout", "layout_validation_rejected",
                    mapOf(
                        "layoutId" to layoutId, "source" to "snapshot",
                        "issueCount" to outcome.issues.size,
                        "firstPath" to first?.path, "firstReason" to first?.reason,
                    ) + orientationField,
                )
                DiagLog.w(
                    "layout", "layout_fallback_default",
                    mapOf(
                        "skinId" to ref.skinId, "layoutId" to layoutId,
                        "reason" to "invalid",
                    ) + orientationField,
                )
                null to LayoutResolution.INVALID
            }
            is LayoutResolver.Outcome.Resolved -> {
                for (issue in outcome.issues) {
                    DiagLog.w(
                        "layout", "layout_field_fallback",
                        mapOf(
                            "layoutId" to layoutId, "path" to issue.path,
                            "reason" to issue.reason,
                        ) + orientationField,
                    )
                }
                val resolved = outcome.resolved
                DiagLog.i(
                    "layout", "layout_resolved",
                    mapOf(
                        "layoutId" to layoutId, "generation" to resolved.generation,
                        "keyCount" to resolved.baseLayout.sumOf { it.size },
                        "rowCount" to resolved.baseLayout.size,
                    ) + orientationField,
                )
                resolved to LayoutResolution.RESOLVED
            }
        }
    }

    /**
     * 清单音效事实（详设 §7.1 接纳输入；快照构建与编辑器预览共用）：
     * 资产 id → sha/扩展名/字节数/解码元信息；无库布局、未入清单
     * 或文件缺失时为 null（接纳时按 MISSING 处理）。
     */
    fun manifestAudioFacts(
        layout: SkinLibraryLayout?,
        manifest: PackageManifest,
    ): (String) -> AudioAssetFacts? {
        val contentById = manifest.assets().associateBy { it.id }
        return { assetId ->
            val content = contentById[assetId]
            if (content == null || layout == null) {
                null
            } else {
                val ext = content.path.substringAfterLast('.', "").lowercase()
                val file = layout.assetFile(content.sha256, ext.ifEmpty { null })
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
            if (rejection.reason == BindingRejectReason.SEQUENCE_REMOVED) {
                // sequence 占位删除的落点信号（B3 §八）：仅键/槽位/原因
                DiagLog.w(
                    "keymap", "sequence_removed_binding",
                    mapOf(
                        "keyId" to rejection.keyId,
                        "slot" to rejection.slot?.jsonName,
                    ),
                )
            } else if (rejection.reason == BindingRejectReason.ANCHOR_NOT_OVERRIDABLE) {
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
        // 工具栏构成（2026-10-27）：只记元数据（id/计数/原因），绝不记
        // 图标内容；WARN 级自动镜像进导出诊断日志（DiagLog 镜像链路）。
        val toolbarSpec = validated.toolbar
        if (toolbarSpec.present) {
            DiagLog.i(
                "toolbar", "toolbar_snapshot_built",
                mapOf(
                    "skinId" to ref.skinId,
                    "buttonCount" to (snapshot.toolbar.buttons?.size ?: -1),
                    "hasExpandIcon" to (snapshot.toolbar.expandIcon != null),
                    "hasCollapseIcon" to (snapshot.toolbar.collapseIcon != null),
                    // 符号名计数（只记个数，不记符号内容以外的元数据；
                    // 符号名本身是皮肤公开字段，不属用户隐私）。
                    "symbolCount" to (
                        (snapshot.toolbar.buttons?.count { it.symbol != null } ?: 0) +
                            (if (snapshot.toolbar.expandSymbol != null) 1 else 0) +
                            (if (snapshot.toolbar.collapseSymbol != null) 1 else 0)
                        ),
                ),
            )
            for (drop in toolbarSpec.dropped) {
                DiagLog.w(
                    "toolbar", "toolbar_item_dropped",
                    mapOf(
                        "skinId" to ref.skinId,
                        "field" to drop.field,
                        "id" to drop.id,
                        "reason" to drop.reason,
                    ),
                )
            }
            val snapshotIconIds = buildSet {
                snapshot.toolbar.buttons?.mapNotNullTo(this) { it.icon?.assetId }
                snapshot.toolbar.expandIcon?.assetId?.let { add(it) }
                snapshot.toolbar.collapseIcon?.assetId?.let { add(it) }
            }
            val specIconIds = buildSet {
                toolbarSpec.buttons?.mapNotNullTo(this) { it.iconAssetId }
                toolbarSpec.expandIconAssetId?.let { add(it) }
                toolbarSpec.collapseIconAssetId?.let { add(it) }
            }
            for (assetId in specIconIds - snapshotIconIds) {
                DiagLog.w(
                    "toolbar", "toolbar_icon_unresolved",
                    mapOf("skinId" to ref.skinId, "assetId" to assetId),
                )
            }
        }
    }
}
