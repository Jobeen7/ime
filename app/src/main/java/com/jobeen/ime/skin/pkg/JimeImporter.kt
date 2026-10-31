package com.jobeen.ime.skin.pkg

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.image.BackgroundPreparer
import com.jobeen.ime.skin.layout.BuiltinLayouts
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.layout.LayoutTreeOps
import com.jobeen.ime.skin.layout.LayoutValidation
import com.jobeen.ime.skin.layout.LayoutValidator
import com.jobeen.ime.skin.media.AudioInfoProbe
import com.jobeen.ime.skin.media.ImageInfoProbe
import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.BindingRejectReason
import com.jobeen.ime.skin.model.DegradationPolicy
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinIssue
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * .jime 导入管线（详设 §3.1，步骤写死）：
 * 解包 → manifest 校验 → JSON 树解析 → 定稿九章校验+回填 →
 * 身份/冲突判定 → 构建不可变包（背景预处理）→ 经 [ImportTransaction]
 * rename 入库 → 出具导入报告（逐项明示，无静默项）。
 *
 * 冲突（定稿七.4）：同 id 同 hash 跳过；同 id 异 hash 在
 * [ConflictMode.FAIL] 下先返回 [Outcome.ConflictPending] 交用户
 * 决定，[ConflictMode.COPY] 下为冲突实体生成新 id 并改写包内
 * 已知引用（未知段落内的引用不承诺改写，报告中明确告警）。
 *
 * 同步落库（详设 §4.4）复用 [importFiles]：下载单元重组为包文件
 * 清单后走同一管线全量校验。
 */
class JimeImporter(
    private val layout: SkinLibraryLayout,
    private val store: SkinLibraryStore,
    private val importTransaction: ImportTransaction,
    private val imageProbe: ImageInfoProbe,
    private val audioProbe: AudioInfoProbe,
    private val backgroundPreparer: BackgroundPreparer,
    /** 内置默认方案（背景合成底色与样式回落末级）；由装配层注入，单测可替换。 */
    private val defaultScheme: KeyboardColors.ColorScheme,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** 视频资产校验器（2026-10-29 外观轮）；默认真实 retriever 实现，单测可替换。 */
    private val videoValidator: VideoAssetValidator = AndroidVideoAssetValidator(),
) {

    data class Context(
        val currentVersionCode: Int,
        val fallbacks: GeometryFallbacks,
        val tier: MemoryTier,
        val targetWidthPx: Int,
        val targetHeightPx: Int,
    )

    /**
     * FAIL：冲突待决（UI 提示）；COPY：冲突实体换新 id 副本入库
     * （定稿七.4）；UPDATE：同 id 异 hash 直接入新代次（同步
     * 快进更新专用，详设 §4.4.3——只入库不自动生效）。
     */
    enum class ConflictMode { FAIL, COPY, UPDATE }

    data class ImportReport(
        val importedSkinIds: List<String> = emptyList(),
        val importedLayoutIds: List<String> = emptyList(),
        val skippedSkinIds: List<String> = emptyList(),
        val skippedLayoutIds: List<String> = emptyList(),
        /** 副本映射：旧 id → 新 id（仅冲突实体）。 */
        val copies: Map<String, String> = emptyMap(),
        val issues: List<SkinIssue> = emptyList(),
        /** 背景因资源问题被回退为纯色（详设 §3.1 资源回退项）。 */
        val backgroundDropped: Boolean = false,
        /**
         * 未映射键（2026-10-29 item-1 D2）：校验期被剔除的绑定中
         * 原因＝未知键 id 的去重键 id 表；UI 提示用户在编辑器里
         * 重绑（见库页 formatImportReport 的未映射段）。
         */
        val unmappedKeys: List<String> = emptyList(),
    )

    sealed interface Outcome {
        data class Imported(val report: ImportReport) : Outcome
        data class ConflictPending(val conflicts: List<String>, val report: ImportReport) : Outcome
        data class Rejected(val reason: String, val issues: List<SkinIssue>) : Outcome
    }

    suspend fun importZip(zipFile: File, ctx: Context, mode: ConflictMode): Outcome {
        return when (val extracted = ZipSafeExtractor.extract(zipFile)) {
            is ZipSafeExtractor.Result.Rejected ->
                rejected("zip:${extracted.reason}", emptyList())
            is ZipSafeExtractor.Result.Ok -> importFiles(extracted.files, ctx, mode)
        }
    }

    /**
     * 拒绝出口（2026-10-27 硬要求）：全部导入拒绝必写日志，
     * 不许只弹 Toast 了事；只记元数据（原因 + id + 条数）。
     * 布局无效那路已有 layout_validation_rejected 专 log，不走此处。
     */
    private fun rejected(
        reason: String,
        issues: List<SkinIssue>,
        skinId: String? = null,
        layoutId: String? = null,
    ): Outcome.Rejected {
        DiagLog.w(
            "store", "import_rejected",
            buildMap {
                put("reason", reason)
                put("issueCount", issues.size)
                if (skinId != null) put("skinId", skinId)
                if (layoutId != null) put("layoutId", layoutId)
            },
        )
        return Outcome.Rejected(reason, issues)
    }

    suspend fun importFiles(
        files: List<ZipSafeExtractor.ExtractedFile>,
        ctx: Context,
        mode: ConflictMode,
    ): Outcome {
        val issues = mutableListOf<SkinIssue>()

        // 配额（定稿八.6）：超限禁止导入并提示清理
        val incomingBytes = files.sumOf { it.bytes.size.toLong() }
        if (layout.quotaBytes() + incomingBytes > QUOTA_BYTES) {
            return rejected("quota_exceeded", issues)
        }

        var byPath = files.associateBy { it.path }
        // ---- manifest（定稿七.1/7.3） ----
        val manifestFiles = files.filter { it.path == MANIFEST_PATH }
        if (manifestFiles.size != 1) {
            return rejected("manifest_count:${manifestFiles.size}", issues)
        }
        var manifest = PackageManifest.parse(
            manifestFiles.single().bytes.decodeToString(),
        ) ?: return rejected("manifest_unparseable", issues)
        if (manifest.formatVersion > SkinValidator.SUPPORTED_FORMAT_VERSION) {
            return rejected("manifest_format_version_unsupported", issues)
        }
        validateManifestContents(manifest, byPath)?.let {
            return rejected(it, issues)
        }

        // 导入加固（2026-10-29 item-1 D2）：资源白名单＋预算——
        // 非白名单／超预算资产逐项警告并排除出包，不拦整包；
        // 排除项从清单与文件表中滤掉，后续校验／分级／落盘皆
        // 不再见到它们，引用走既有缺失回退。视频资产不受本
        // 策略影响（仍走三重门硬拒绝）。
        val assetPolicy = ImportAssetPolicy.evaluate(manifest.assets()) { content ->
            byPath[content.path]?.bytes?.size?.toLong()
        }
        issues += assetPolicy.warnings
        if (assetPolicy.excluded.isNotEmpty()) {
            val excludedPaths = assetPolicy.excluded.map { it.path }.toSet()
            manifest = manifest.copy(
                contents = manifest.contents.filter { it.path !in excludedPaths },
            )
            byPath = byPath.filterKeys { it !in excludedPaths }
            DiagLog.w(
                "import", "import_assets_excluded",
                mapOf(
                    "excludedCount" to assetPolicy.excluded.size,
                    "excludedIds" to assetPolicy.excluded.joinToString(",") { it.id },
                ),
            )
        }

        // 视频资产校验（2026-10-29 外观轮）：数量＋单文件＋解码维度
        // 三重门；任一失败整包拒绝（不把不可播的大文件装进库占
        // 配额）；retriever 暂存文件在校验器 finally 内删除；只记元数据。
        val videoContents = manifest.assets().filter {
            PackageManifest.assetKindOf(it.path) == AssetKind.VIDEO
        }
        if (!VideoAssetValidator.countOk(videoContents.size)) {
            return rejected("video_count_exceeded", issues)
        }
        for (content in videoContents) {
            val bytes = byPath[content.path]?.bytes
                ?: return rejected("video_missing:${content.id}", issues)
            if (!VideoAssetValidator.sizeOk(bytes.size.toLong())) {
                return rejected("video_file_too_large", issues)
            }
            when (val check = videoValidator.check(bytes, layout.stagingDir)) {
                is VideoAssetValidator.VideoCheck.Failed ->
                    return rejected("video_invalid:${check.reason}", issues)
                is VideoAssetValidator.VideoCheck.Ok ->
                    if (!VideoAssetValidator.dimensionsOk(check.width, check.height)) {
                        return rejected("video_dimensions_invalid", issues)
                    }
            }
        }

        val skinContent = manifest.skin()
            ?: return rejected("skin_missing", issues)
        if (manifest.contents.count { it.type == PackageManifest.TYPE_SKIN } != 1) {
            return rejected("skin_count_not_one", issues)
        }
        val skinBytes = byPath.getValue(skinContent.path).bytes
        val skinTree = SkinJson.parseObject(skinBytes.decodeToString())
            ?: return rejected("skin_unparseable", issues, skinId = skinContent.id)
        // 身份权威在 manifest：皮肤树自报 id 与清单不一致即身份含混，整包拒绝
        val validatedPreview = SkinValidator.validate(
            skinTree, ctx.currentVersionCode, ctx.fallbacks, { null },
        )
        val previewSkin = when (validatedPreview) {
            is SkinValidator.Result.Rejected ->
                return rejected(validatedPreview.reason, issues, skinId = skinContent.id)
            is SkinValidator.Result.Valid -> validatedPreview.skin
        }
        if (previewSkin.metaId != null && previewSkin.metaId != skinContent.id) {
            return rejected("skin_meta_id_mismatch", issues, skinId = skinContent.id)
        }

        // 布局结构校验（B3 §5.1）：与运行时同一校验器。整布局无效
        // ---- 布局校验（九.4/十一.3/十二.2）：自创布局无效 → 整包拒绝
        // （定稿九章归类）并逐项列出字段路径；逐字段回退/丢槽项只入
        // 报告，不拦包（与资源损坏回退同哲学、不同类）。
        // 内置布局（2026-10-28 分流）：tap 可缺省（沿用出厂行为），
        // 且无效时只丢该内置覆盖、不拦整包——坏排版不得砖掉整皮肤，
        // 键盘构建期回退出厂布局（快照构建期另记 builtin_layout_dropped）。
        val layoutTrees = mutableMapOf<String, JsonObject>()
        val droppedBuiltinLayouts = mutableSetOf<String>()
        for (layoutContent in manifest.layouts()) {
            val bytes = byPath.getValue(layoutContent.path).bytes
            val tree = SkinJson.parseObject(bytes.decodeToString())
                ?: return rejected("layout_unparseable:${layoutContent.id}", issues, layoutId = layoutContent.id)
            if (SkinJson.depth(tree) > SkinValidator.MAX_JSON_DEPTH ||
                SkinJson.maxStringLength(tree) > SkinValidator.MAX_STRING_LENGTH
            ) {
                return rejected(
                    "layout_json_limits_exceeded:${layoutContent.id}", issues,
                    layoutId = layoutContent.id,
                )
            }
            val isBuiltinLayout = BuiltinLayouts.isBuiltin(layoutContent.id)
            when (val validation = LayoutValidator.validate(
                tree, layoutContent.id, allowMissingTap = isBuiltinLayout,
            )) {
                is LayoutValidation.Invalid -> {
                    val mapped = validation.issues.map { issue ->
                        SkinIssue(
                            path = "layouts.${layoutContent.id}" +
                                if (issue.path.isEmpty()) "" else ".${issue.path}",
                            kind = SkinIssue.Kind.FIELD_FALLBACK,
                            detail = issue.reason,
                        )
                    }
                    DiagLog.w(
                        "layout", "layout_validation_rejected",
                        mapOf(
                            "layoutId" to layoutContent.id, "source" to "import",
                            "issueCount" to validation.issues.size,
                            "firstPath" to validation.issues.firstOrNull()?.path,
                            "firstReason" to validation.issues.firstOrNull()?.reason,
                        ),
                    )
                    if (isBuiltinLayout) {
                        droppedBuiltinLayouts += layoutContent.id
                        issues += mapped
                        continue
                    }
                    return Outcome.Rejected(
                        "layout_invalid:${layoutContent.id}", issues + mapped,
                    )
                }
                is LayoutValidation.Valid -> {
                    for (issue in validation.issues) {
                        issues += SkinIssue(
                            path = "layouts.${layoutContent.id}.${issue.path}",
                            kind = SkinIssue.Kind.FIELD_FALLBACK,
                            detail = issue.reason,
                        )
                        DiagLog.w(
                            "layout", "layout_field_fallback",
                            mapOf(
                                "layoutId" to layoutContent.id,
                                "path" to issue.path, "reason" to issue.reason,
                            ),
                        )
                    }
                }
            }
            layoutTrees[layoutContent.id] = tree
        }

        // ---- 字段校验（资产类别以清单为准） ----
        val assetKindById = manifest.assets().associate {
            it.id to PackageManifest.assetKindOf(it.path)
        }
        // G1 两道接线（此前缺失，坐标覆写与超限图片到不了校验口径）：
        // (1) 随包布局的坐标 ID 在内置登记表之外另行可覆写，否则
        //     坐标覆写在导入期即被当成未知键剔掉，运行时链路接不到；
        // (2) 单键图片 1MB 上限以包内实际字节数执行（此前 assetBytes
        //     恒为缺省 null，超限检查在生产路径上从未生效）。
        val coordIds = layoutTrees.flatMap { (layoutId, tree) ->
            LayoutModel.logicalIdsFromTree(tree, layoutId)
        }.toSet()
        val assetBytesById = manifest.assets().associate { content ->
            content.id to (byPath[content.path]?.bytes?.size?.toLong())
        }
        val validation = SkinValidator.validate(
            skinTree, ctx.currentVersionCode, ctx.fallbacks, { assetKindById[it] },
            isOverridable = { it in coordIds || KeyIds.isOverridable(it) },
            assetBytes = { assetBytesById[it] },
        )
        val validated = when (validation) {
            is SkinValidator.Result.Rejected ->
                return rejected(validation.reason, issues, skinId = skinContent.id)
            is SkinValidator.Result.Valid -> validation.skin
        }
        issues += validation.issues
        // 未映射键（2026-10-29 item-1 D2）：校验期被剔除的绑定中
        // 原因＝未知键 id 的去重键 id 表，进报告供 UI 提示重绑；
        // 逐条明细已在 validation.issues 里（FIELD_FALLBACK），
        // 此处只做结构化摘要，不重复记日志。
        val unmappedKeys = validated.keyRejections
            .filter { it.reason == BindingRejectReason.UNKNOWN_ID }
            .map { it.keyId }
            .distinct()
        // 校验层字段回退/派生/引用警告（2026-10-27 硬要求）：入报告
        // 之外再记一条汇总日志（逐条记会刷屏；路径只记字段名）。
        if (validation.issues.isNotEmpty()) {
            DiagLog.w(
                "store", "skin_validation_issues",
                mapOf(
                    "skinId" to skinContent.id,
                    "issueCount" to validation.issues.size,
                    "firstPaths" to validation.issues.take(3).joinToString(",") { it.path },
                ),
            )
        }

        // ---- 资源校验（九.4/十一.3/十一.4/十二.2）：坏资源回退，不连累整包 ----
        var backgroundDropped = false
        val backgroundSpec = validated.appearance.background
        if (backgroundSpec != null) {
            val assetContent = manifest.byId(backgroundSpec.assetId)
            val bytes = assetContent?.let { byPath[it.path]?.bytes }
            val size = bytes?.let {
                try {
                    imageProbe.bounds(it)
                } catch (oom: OutOfMemoryError) {
                    null
                }
            }
            when {
                bytes == null || size == null -> {
                    backgroundDropped = true
                    issues += SkinIssue(
                        "appearance.background", SkinIssue.Kind.RESOURCE_FALLBACK,
                        "background_undecodable",
                    )
                }
                size.width > MAX_IMAGE_SIDE || size.height > MAX_IMAGE_SIDE ||
                    size.pixels > MAX_IMAGE_PIXELS -> {
                    backgroundDropped = true
                    issues += SkinIssue(
                        "appearance.background", SkinIssue.Kind.RESOURCE_FALLBACK,
                        "background_oversized",
                    )
                }
            }
        }
        checkAudioAssets(manifest, byPath, issues)
        checkImageAssets(manifest, byPath, issues, backgroundSpec?.assetId)

        // ---- 身份/冲突判定（七.4） ----
        val index = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
        val skinHash = DigestUtil.sha256Hex(skinBytes)
        val conflicts = mutableListOf<String>()
        var skinSkipped = false
        val existingSkin = index?.skins?.firstOrNull { it.id == skinContent.id }
        if (existingSkin != null) {
            if (existingSkin.hash == skinHash) skinSkipped = true else conflicts += skinContent.id
        }
        val layoutHashes = manifest.layouts().associate {
            it.id to DigestUtil.sha256Hex(byPath.getValue(it.path).bytes)
        }
        val skippedLayouts = mutableListOf<String>()
        for (layoutContent in manifest.layouts()) {
            // 内置布局不进全局布局索引（分属各皮肤代次目录），
            // 永不参与同 id 冲突判定（两皮肤各写各的 T9 覆盖不冲突）。
            if (BuiltinLayouts.isBuiltin(layoutContent.id)) continue
            val existing = index?.layouts?.firstOrNull { it.id == layoutContent.id }
            if (existing != null) {
                if (existing.hash == layoutHashes.getValue(layoutContent.id)) {
                    skippedLayouts += layoutContent.id
                } else {
                    conflicts += layoutContent.id
                }
            }
        }
        val baseReport = ImportReport(
            skippedSkinIds = if (skinSkipped) listOf(skinContent.id) else emptyList(),
            skippedLayoutIds = skippedLayouts,
            issues = issues,
            backgroundDropped = backgroundDropped,
            unmappedKeys = unmappedKeys,
        )
        if (conflicts.isNotEmpty() && mode == ConflictMode.FAIL) {
            return Outcome.ConflictPending(conflicts, baseReport)
        }

        // ---- 副本改写（七.4）：冲突实体换新 id，改写包内已知引用 ----
        // UPDATE 模式不改写：同 id 异 hash 由事务层入新代次（同步快进）。
        val copies = mutableMapOf<String, String>()
        if (conflicts.isNotEmpty() && mode == ConflictMode.COPY) {
            for (conflictId in conflicts) copies[conflictId] = newId()
            issues += SkinIssue(
                "package", SkinIssue.Kind.INFO,
                "copied_with_new_ids:${conflicts.joinToString(",")}",
            )
            issues += SkinIssue(
                "package", SkinIssue.Kind.INFO,
                "unknown_section_refs_not_rewritten",
            )
        }
        val finalSkinId = copies[skinContent.id] ?: skinContent.id
        var finalSkinTree = skinTree
        if (copies.isNotEmpty()) {
            finalSkinTree = rewriteSkinTreeRefs(skinTree, finalSkinId, copies)
        }
        val finalSkinBytes = if (copies.isEmpty()) {
            skinBytes
        } else {
            Json.encodeToString(JsonObject.serializer(), finalSkinTree).encodeToByteArray()
        }
        val finalSkinHash = DigestUtil.sha256Hex(finalSkinBytes)
        // 布局最终字节/hash：副本布局须把 meta.id 改写为新 id 后
        // 再落盘（铁律 3），hash 随改写字节重算；非副本字节原样
        val finalLayoutData = mutableMapOf<String, Pair<ByteArray, String>>()
        for (layoutContent in manifest.layouts()) {
            val copyId = copies[layoutContent.id]
            if (copyId == null) {
                finalLayoutData[layoutContent.id] =
                    byPath.getValue(layoutContent.path).bytes to
                        layoutHashes.getValue(layoutContent.id)
            } else {
                val rewritten = LayoutTreeOps.rewriteMetaId(
                    layoutTrees.getValue(layoutContent.id), copyId,
                )
                val bytes = Json.encodeToString(JsonObject.serializer(), rewritten)
                    .encodeToByteArray()
                finalLayoutData[layoutContent.id] = bytes to DigestUtil.sha256Hex(bytes)
            }
        }
        val finalLayoutHashes = finalLayoutData.mapValues { it.value.second }
        val finalManifest = rebuildManifest(manifest, copies, finalSkinHash, finalLayoutHashes)
        val finalManifestJson = PackageManifest.toJson(finalManifest)

        // 背景预处理参数（与快照构建同口径：合成方案底色 + 生效模糊/尺寸）
        val policy = DegradationPolicy.forTier(ctx.tier)
        val snapshotForBackground = if (backgroundSpec != null && !backgroundDropped) {
            val shaById = manifest.assets().associate { it.id to it.sha256 }
            SkinSnapshotBuilder.build(
                validated = validated,
                ref = SkinGenRef(finalSkinId, finalSkinHash, 0),
                defaultScheme = defaultScheme,
                tier = ctx.tier,
                assetSha256 = { shaById[it] },
                targetWidthPx = ctx.targetWidthPx,
                targetHeightPx = ctx.targetHeightPx,
            )
        } else {
            null
        }

        // ---- 构建不可变包并入库（ImportTransaction 机制层） ----
        val stagedSkinName = validated.metaName.ifBlank { finalSkinId }
        val result = importTransaction.importPackage {
            val stagedSkins = mutableListOf<ImportTransaction.StagedSkin>()
            val stagedLayouts = mutableListOf<ImportTransaction.StagedLayout>()
            val stagedAssets = mutableListOf<ImportTransaction.StagedAsset>()

            if (!skinSkipped) {
                val skinDir = File(it, "skins/$finalSkinId").apply { mkdirs() }
                File(skinDir, "skin.json").writeBytes(finalSkinBytes)
                File(skinDir, "manifest.json").writeText(finalManifestJson)
                // 背景预处理（冷路径，§2.6）：失败回退纯色并报告，不连累整包
                val background = snapshotForBackground?.background
                if (background != null) {
                    val assetContent = manifest.byId(background.assetId)
                    val assetBytes = assetContent?.let { c -> byPath[c.path]?.bytes }
                    if (assetBytes != null) {
                        val scaledW = (ctx.targetWidthPx * policy.backgroundScale)
                            .roundToInt().coerceAtLeast(1)
                        val scaledH = (ctx.targetHeightPx * policy.backgroundScale)
                            .roundToInt().coerceAtLeast(1)
                        val prepared = try {
                            backgroundPreparer.prepare(
                                BackgroundPreparer.Request(
                                    assetBytes = assetBytes,
                                    assetSha256 = background.assetSha256,
                                    genDir = skinDir,
                                    targetWidthPx = scaledW,
                                    targetHeightPx = scaledH,
                                    blurRadiusDp = background.blurRadius,
                                    imageAdjust = background.imageAdjust,
                                    overlayColorArgb = snapshotForBackground.colorScheme.background,
                                    overlayAlpha = background.overlayAlpha,
                                    preparedKey = background.preparedKey,
                                ),
                            )
                        } catch (oom: OutOfMemoryError) {
                            null
                        }
                        if (prepared == null) {
                            backgroundDropped = true
                            issues += SkinIssue(
                                "appearance.background", SkinIssue.Kind.RESOURCE_FALLBACK,
                                "background_prepare_oom",
                            )
                        } else if (prepared is BackgroundPreparer.Result.Failed) {
                            backgroundDropped = true
                            issues += SkinIssue(
                                "appearance.background", SkinIssue.Kind.RESOURCE_FALLBACK,
                                "background_prepare_failed:${prepared.reason}",
                            )
                        }
                    }
                }
                stagedSkins += ImportTransaction.StagedSkin(
                    id = finalSkinId, name = stagedSkinName, dir = skinDir, hash = finalSkinHash,
                )
                // 内置布局覆盖（2026-10-28）：随皮肤代次落盘（皮肤目录
                // 内 layouts/<内置id>.json），不进全局布局索引；无效者
                // 已在校验期丢弃，此处不再落盘。皮肤整体被跳过（同 hash
                // 重导）时沿用既有代次目录，不补写。
                val skinLayoutsDir = File(skinDir, "layouts")
                for (layoutContent in manifest.layouts()) {
                    if (!BuiltinLayouts.isBuiltin(layoutContent.id)) continue
                    if (layoutContent.id in droppedBuiltinLayouts) continue
                    val (bytes, _) = finalLayoutData.getValue(layoutContent.id)
                    skinLayoutsDir.apply { mkdirs() }
                    File(skinLayoutsDir, "${layoutContent.id}.json").writeBytes(bytes)
                }
            }
            for (layoutContent in manifest.layouts()) {
                if (BuiltinLayouts.isBuiltin(layoutContent.id)) continue
                if (layoutContent.id in skippedLayouts) continue
                val finalLayoutId = copies[layoutContent.id] ?: layoutContent.id
                val layoutDir = File(it, "layouts/$finalLayoutId").apply { mkdirs() }
                val (bytes, hash) = finalLayoutData.getValue(layoutContent.id)
                File(layoutDir, "layout.json").writeBytes(bytes)
                File(layoutDir, "manifest.json").writeText(finalManifestJson)
                stagedLayouts += ImportTransaction.StagedLayout(
                    id = finalLayoutId, dir = layoutDir, hash = hash,
                )
            }
            val stagedAssetShas = mutableSetOf<String>()
            for (assetContent in manifest.assets()) {
                if (!stagedAssetShas.add(assetContent.sha256)) continue
                val assetDir = File(it, "assets").apply { mkdirs() }
                val ext = assetContent.path.substringAfterLast('.', "")
                val fileName = if (ext.isBlank()) assetContent.sha256 else "${assetContent.sha256}.$ext"
                val file = File(assetDir, fileName)
                file.writeBytes(byPath.getValue(assetContent.path).bytes)
                stagedAssets += ImportTransaction.StagedAsset(assetContent.sha256, file)
            }
            ImportTransaction.BuiltPackage(stagedSkins, stagedLayouts, stagedAssets)
        }

        return when (result) {
            is ImportTransaction.Result.Imported -> Outcome.Imported(
                baseReport.copy(
                    importedSkinIds = result.skins.map { entry -> entry.id },
                    importedLayoutIds = result.layouts.map { entry -> entry.id },
                    copies = copies,
                    issues = issues,
                    backgroundDropped = backgroundDropped,
                ),
            )
            is ImportTransaction.Result.Failed ->
                Outcome.Rejected("transaction:${result.stage}:${result.reason}", issues)
        }
    }

    /** 清单级校验（定稿七.3）：任一命中整包拒绝，返回原因或 null。 */
    private fun validateManifestContents(
        manifest: PackageManifest,
        byPath: Map<String, ZipSafeExtractor.ExtractedFile>,
    ): String? {
        val ids = manifest.contents.map { it.id }
        if (ids.size != ids.toSet().size) return "manifest_duplicate_id"
        val normalizedPaths = manifest.contents.map { ZipSafeExtractor.normalizePath(it.path) }
        if (normalizedPaths.size != normalizedPaths.toSet().size) {
            return "manifest_duplicate_path"
        }
        val listedPaths = manifest.contents.map { it.path }.toSet()
        for (file in byPath.keys) {
            if (file != MANIFEST_PATH && file !in listedPaths) return "unlisted_file:$file"
        }
        for (content in manifest.contents) {
            val file = byPath[content.path] ?: return "manifest_file_missing:${content.path}"
            if (DigestUtil.sha256Hex(file.bytes) != content.sha256) {
                return "manifest_sha_mismatch:${content.path}"
            }
            if (content.type !in KNOWN_TYPES) return "manifest_unknown_type:${content.type}"
        }
        return null
    }

    /**
     * 音效接纳校验（定稿十一.4/十二.2，B1 只验不播）：清单顺序接纳，
     * 个数 ≤16、单件 ≤250KB、总字节 ≤2MB、时长 ≤5s、采样率 ≤48kHz、
     * 声道 ≤2；未接纳逐项明示（静默口径在 B2 播放侧落地）。
     */
    private fun checkAudioAssets(
        manifest: PackageManifest,
        byPath: Map<String, ZipSafeExtractor.ExtractedFile>,
        issues: MutableList<SkinIssue>,
    ) {
        var acceptedCount = 0
        var acceptedBytes = 0L
        for (content in manifest.assets()) {
            if (PackageManifest.assetKindOf(content.path) != AssetKind.AUDIO) continue
            val bytes = byPath[content.path]?.bytes ?: continue
            val reason = when {
                acceptedCount >= MAX_AUDIO_COUNT -> "audio_count_exceeded"
                bytes.size > MAX_AUDIO_FILE_BYTES -> "audio_file_too_large"
                acceptedBytes + bytes.size > MAX_AUDIO_TOTAL_BYTES -> "audio_total_too_large"
                else -> {
                    val info = audioProbe.probe(bytes)
                    when {
                        info == null -> "audio_undecodable"
                        info.durationMs > MAX_AUDIO_DURATION_MS -> "audio_too_long"
                        info.sampleRate > MAX_AUDIO_SAMPLE_RATE -> "audio_sample_rate_too_high"
                        info.channels > MAX_AUDIO_CHANNELS -> "audio_too_many_channels"
                        info.sampleRate <= 0 || info.channels <= 0 -> "audio_invalid_format"
                        else -> null
                    }
                }
            }
            if (reason != null) {
                issues += SkinIssue("audio:${content.id}", SkinIssue.Kind.INFO, reason)
            } else {
                acceptedCount++
                acceptedBytes += bytes.size
            }
        }
    }

    /**
     * 图片资产维度校验（背景图除外，背景图已在上文单独校验并有
     * 专属回退旗标）：清单内全部 IMAGE 类资产逐项探尺寸，不可解码
     * 或超上限（与背景图同口径：边长 8192、像素 64M）只记
     * RESOURCE_FALLBACK 报告项，不剔除文件、不中断导入——运行期
     * 解码已两遍降采样兜底，超大图不会以原尺寸进内存；本校验的
     * 价值是让报告显式指出哪项资产超限（运行期将以降低分辨率
     * 使用）。OOM 视为不可解码，继续校验下一项，不崩导入。
     */
    private fun checkImageAssets(
        manifest: PackageManifest,
        byPath: Map<String, ZipSafeExtractor.ExtractedFile>,
        issues: MutableList<SkinIssue>,
        backgroundAssetId: String?,
    ) {
        for (content in manifest.assets()) {
            if (PackageManifest.assetKindOf(content.path) != AssetKind.IMAGE) continue
            if (content.id == backgroundAssetId) continue
            val bytes = byPath[content.path]?.bytes
            val size = bytes?.let {
                try {
                    imageProbe.bounds(it)
                } catch (oom: OutOfMemoryError) {
                    null
                }
            }
            when {
                bytes == null || size == null -> issues += SkinIssue(
                    "image:${content.id}", SkinIssue.Kind.RESOURCE_FALLBACK,
                    "image_undecodable",
                )
                size.width > MAX_IMAGE_SIDE || size.height > MAX_IMAGE_SIDE ||
                    size.pixels > MAX_IMAGE_PIXELS -> issues += SkinIssue(
                    "image:${content.id}", SkinIssue.Kind.RESOURCE_FALLBACK,
                    "image_oversized",
                )
            }
        }
    }

    /**
     * 副本改写：只改格式已知的引用位置（meta.id、layoutId、
     * keyMappings 的自创布局键前缀），其余节点原样（七.4）。
     * keyMappings 前缀改写是 B3 补齐项（详设 §5.2 待核 1）：自创
     * 布局键的逻辑 ID 以 `{layoutId}:` 起头，布局换副本 id 时，
     * 引用它的覆写条目键名必须同步改写，否则覆写全部悬空。
     */
    private fun rewriteSkinTreeRefs(
        tree: JsonObject,
        finalSkinId: String,
        copies: Map<String, String>,
    ): JsonObject {
        var result = tree
        val meta = result["meta"] as? JsonObject
        if (meta != null) {
            result = JsonObject(
                result + ("meta" to JsonObject(meta + ("id" to JsonPrimitive(finalSkinId)))),
            )
        }
        val layoutId = (result["layoutId"] as? JsonPrimitive)?.content
        val copiedLayoutId = layoutId?.let { copies[it] }
        if (copiedLayoutId != null) {
            result = JsonObject(result + ("layoutId" to JsonPrimitive(copiedLayoutId)))
        }
        val mappings = result["keyMappings"] as? JsonObject
        if (mappings != null) {
            var changed = false
            val rewritten = LinkedHashMap<String, JsonElement>()
            for ((keyId, entry) in mappings) {
                val parsed = LayoutModel.parseLogicalId(keyId)
                val newLayoutId = parsed?.let { copies[it.first] }
                if (parsed != null && newLayoutId != null) {
                    rewritten[LayoutModel.logicalId(newLayoutId, parsed.second)] = entry
                    changed = true
                } else {
                    rewritten[keyId] = entry
                }
            }
            if (changed) {
                result = JsonObject(result + ("keyMappings" to JsonObject(rewritten)))
            }
        }
        return result
    }

    /** 副本导入后的清单：id 与 skin 内容 hash 按改写结果更新（资产 id 包内作用域，不变）。 */
    private fun rebuildManifest(
        manifest: PackageManifest,
        copies: Map<String, String>,
        finalSkinHash: String,
        layoutHashes: Map<String, String>,
    ): PackageManifest {
        if (copies.isEmpty()) return manifest
        return manifest.copy(
            contents = manifest.contents.map { content ->
                when (content.type) {
                    PackageManifest.TYPE_SKIN -> content.copy(
                        id = copies[content.id] ?: content.id,
                        sha256 = finalSkinHash,
                    )
                    PackageManifest.TYPE_LAYOUT -> content.copy(
                        id = copies[content.id] ?: content.id,
                        sha256 = layoutHashes[content.id] ?: content.sha256,
                    )
                    else -> content
                }
            },
        )
    }

    companion object {
        const val MANIFEST_PATH = "manifest.json"

        /** 总配额暂定 200MB（定稿八.6，落盘口径见详设 §2.1 规则 3）。 */
        const val QUOTA_BYTES = 200L * 1024 * 1024

        /** 图片上限（定稿十一.3）。 */
        const val MAX_IMAGE_SIDE = 8192
        const val MAX_IMAGE_PIXELS = 64L * 1024 * 1024

        /** 音效上限（定稿十一.4/十二.2）。 */
        const val MAX_AUDIO_COUNT = 16
        const val MAX_AUDIO_FILE_BYTES = 250 * 1024
        const val MAX_AUDIO_TOTAL_BYTES = 2 * 1024 * 1024
        const val MAX_AUDIO_DURATION_MS = 5000L
        const val MAX_AUDIO_SAMPLE_RATE = 48000
        const val MAX_AUDIO_CHANNELS = 2

        private val KNOWN_TYPES = setOf(
            PackageManifest.TYPE_SKIN, PackageManifest.TYPE_LAYOUT, PackageManifest.TYPE_ASSET,
        )
    }
}
