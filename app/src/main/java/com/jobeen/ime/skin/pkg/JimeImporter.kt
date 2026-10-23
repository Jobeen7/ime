package com.jobeen.ime.skin.pkg

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.image.BackgroundPreparer
import com.jobeen.ime.skin.media.AudioInfoProbe
import com.jobeen.ime.skin.media.ImageInfoProbe
import com.jobeen.ime.skin.model.AssetKind
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
    )

    sealed interface Outcome {
        data class Imported(val report: ImportReport) : Outcome
        data class ConflictPending(val conflicts: List<String>, val report: ImportReport) : Outcome
        data class Rejected(val reason: String, val issues: List<SkinIssue>) : Outcome
    }

    suspend fun importZip(zipFile: File, ctx: Context, mode: ConflictMode): Outcome {
        return when (val extracted = ZipSafeExtractor.extract(zipFile)) {
            is ZipSafeExtractor.Result.Rejected ->
                Outcome.Rejected("zip:${extracted.reason}", emptyList())
            is ZipSafeExtractor.Result.Ok -> importFiles(extracted.files, ctx, mode)
        }
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
            return Outcome.Rejected("quota_exceeded", issues)
        }

        val byPath = files.associateBy { it.path }
        // ---- manifest（定稿七.1/7.3） ----
        val manifestFiles = files.filter { it.path == MANIFEST_PATH }
        if (manifestFiles.size != 1) {
            return Outcome.Rejected("manifest_count:${manifestFiles.size}", issues)
        }
        val manifest = PackageManifest.parse(
            manifestFiles.single().bytes.decodeToString(),
        ) ?: return Outcome.Rejected("manifest_unparseable", issues)
        if (manifest.formatVersion > SkinValidator.SUPPORTED_FORMAT_VERSION) {
            return Outcome.Rejected("manifest_format_version_unsupported", issues)
        }
        validateManifestContents(manifest, byPath)?.let {
            return Outcome.Rejected(it, issues)
        }

        val skinContent = manifest.skin()
            ?: return Outcome.Rejected("skin_missing", issues)
        if (manifest.contents.count { it.type == PackageManifest.TYPE_SKIN } != 1) {
            return Outcome.Rejected("skin_count_not_one", issues)
        }
        val skinBytes = byPath.getValue(skinContent.path).bytes
        val skinTree = SkinJson.parseObject(skinBytes.decodeToString())
            ?: return Outcome.Rejected("skin_unparseable", issues)
        // 身份权威在 manifest：皮肤树自报 id 与清单不一致即身份含混，整包拒绝
        val validatedPreview = SkinValidator.validate(
            skinTree, ctx.currentVersionCode, ctx.fallbacks, { null },
        )
        val previewSkin = when (validatedPreview) {
            is SkinValidator.Result.Rejected ->
                return Outcome.Rejected(validatedPreview.reason, issues)
            is SkinValidator.Result.Valid -> validatedPreview.skin
        }
        if (previewSkin.metaId != null && previewSkin.metaId != skinContent.id) {
            return Outcome.Rejected("skin_meta_id_mismatch", issues)
        }

        // 布局树结构校验（语义校验是 B3；B1 只存不用，但结构性损坏整包拒绝，九.3）
        val layoutTrees = mutableMapOf<String, JsonObject>()
        for (layoutContent in manifest.layouts()) {
            val bytes = byPath.getValue(layoutContent.path).bytes
            val tree = SkinJson.parseObject(bytes.decodeToString())
                ?: return Outcome.Rejected("layout_unparseable:${layoutContent.id}", issues)
            if (SkinJson.depth(tree) > SkinValidator.MAX_JSON_DEPTH ||
                SkinJson.maxStringLength(tree) > SkinValidator.MAX_STRING_LENGTH
            ) {
                return Outcome.Rejected("layout_json_limits_exceeded:${layoutContent.id}", issues)
            }
            layoutTrees[layoutContent.id] = tree
        }

        // ---- 字段校验（资产类别以清单为准） ----
        val assetKindById = manifest.assets().associate {
            it.id to PackageManifest.assetKindOf(it.path)
        }
        val validation = SkinValidator.validate(
            skinTree, ctx.currentVersionCode, ctx.fallbacks, { assetKindById[it] },
        )
        val validated = when (validation) {
            is SkinValidator.Result.Rejected ->
                return Outcome.Rejected(validation.reason, issues)
            is SkinValidator.Result.Valid -> validation.skin
        }
        issues += validation.issues

        // ---- 资源校验（九.4/十一.3/十一.4/十二.2）：坏资源回退，不连累整包 ----
        var backgroundDropped = false
        val backgroundSpec = validated.appearance.background
        if (backgroundSpec != null) {
            val assetContent = manifest.byId(backgroundSpec.assetId)
            val bytes = assetContent?.let { byPath[it.path]?.bytes }
            val size = bytes?.let { imageProbe.bounds(it) }
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
        val finalManifest = rebuildManifest(manifest, copies, finalSkinHash, layoutHashes)
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
                        val prepared = backgroundPreparer.prepare(
                            BackgroundPreparer.Request(
                                assetBytes = assetBytes,
                                assetSha256 = background.assetSha256,
                                genDir = skinDir,
                                targetWidthPx = scaledW,
                                targetHeightPx = scaledH,
                                blurRadiusDp = background.blurRadius,
                                overlayColorArgb = snapshotForBackground.colorScheme.background,
                                overlayAlpha = background.overlayAlpha,
                                preparedKey = background.preparedKey,
                            ),
                        )
                        if (prepared is BackgroundPreparer.Result.Failed) {
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
            }
            for (layoutContent in manifest.layouts()) {
                if (layoutContent.id in skippedLayouts) continue
                val finalLayoutId = copies[layoutContent.id] ?: layoutContent.id
                val layoutDir = File(it, "layouts/$finalLayoutId").apply { mkdirs() }
                val bytes = byPath.getValue(layoutContent.path).bytes
                File(layoutDir, "layout.json").writeBytes(bytes)
                File(layoutDir, "manifest.json").writeText(finalManifestJson)
                stagedLayouts += ImportTransaction.StagedLayout(
                    id = finalLayoutId, dir = layoutDir,
                    hash = layoutHashes.getValue(layoutContent.id),
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

    /** 副本改写：只改格式已知的引用位置（meta.id、layoutId），其余节点原样（七.4）。 */
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
