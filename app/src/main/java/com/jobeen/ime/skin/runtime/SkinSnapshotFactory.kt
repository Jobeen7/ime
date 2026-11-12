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
import com.jobeen.ime.skin.layout.LandscapePatch
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.layout.LayoutResolver
import com.jobeen.ime.skin.layout.LayoutValidation
import com.jobeen.ime.skin.layout.LayoutValidator
import com.jobeen.ime.skin.layout.resolveBuiltinLayouts
import com.jobeen.ime.skin.media.AndroidAudioInfoProbe
import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.AudioAssetFacts
import com.jobeen.ime.skin.model.BindingRejectReason
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.KeyboardTypeResolved
import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinPackageDocs
import com.jobeen.ime.skin.model.SkinPackageDocsParser
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.YamlSpriteDescriptor
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.store.LayoutIndexEntry
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinOverlayMerge
import com.jobeen.ime.skin.store.SkinOverlayStore
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
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
     * 从代次目录构建快照。manifest 缺失／损坏时拒绝构建（返回
     * null，调用方按构建失败处理：应用事务回滚、启动加载回退默认
     * ——2026-11-12 审计 K-M3，与编辑器侧 fail-closed 同口径；
     * 旧口径退化为空清单会让清单损坏的皮肤带病安装、全部资产
     * 引用按缺失回落且无任何提示）。
     */
    suspend fun buildFromGenDir(
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

    /**
     * 构建入口（挂起函数）：skin.json／manifest／覆盖层／布局
     * 文件的读＋解析全在 IO 线程（审计 #2）；调用方在主线程
     * 发起也不阻塞 UI。构建期内索引只读一次、各 layout.json
     * 只解析一次（见 [LayoutLookup]），本函数内不再有 runBlocking。
     */
    suspend fun buildFromFiles(
        context: Context,
        skinJsonFile: File,
        manifestFile: File,
        ref: SkinGenRef,
        libraryLayout: SkinLibraryLayout? = null,
    ): SkinSnapshot? = withContext(Dispatchers.IO) {
        buildFromFilesOnIo(context, skinJsonFile, manifestFile, ref, libraryLayout)
    }

    private suspend fun buildFromFilesOnIo(
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
        // 清单是资产引用的唯一可信索引：文件缺失／不可读／解析失败
        // 一律拒绝构建（K-M3 fail-closed），不得退化为空清单。
        // 调用方（应用事务／启动加载／预览）均已按 null=构建失败处理。
        val manifest = runCatching {
            PackageManifest.parse(manifestFile.readText())
        }.getOrNull() ?: run {
            DiagLog.w(
                "runtime", "snapshot_manifest_invalid",
                mapOf(
                    "skinId" to ref.skinId,
                    "missing" to !manifestFile.isFile,
                ),
            )
            return null
        }
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
        // 演示资料（审计 K 余项）：与 skin.json 同代次目录的
        // demo.png / README.md，校验后随快照表面（UI 取图仍按
        // 代次目录读文件，本处只记校验结果，不读图进内存）。
        val packageDocs = skinJsonFile.parentFile?.let {
            SkinPackageDocsParser.fromDir(it)
        } ?: SkinPackageDocs()
        if (snapshot == null) return null
        var finalSnapshot = snapshot.copy(
            overlayVersion = overlayVersion, packageDocs = packageDocs,
        )
        // 精灵图 sidecar（工具项 7）：代次目录 sidecars/<资产 sha>.yaml，
        // 按清单资产逻辑 id 合并进精灵图表（文件胜 JSON 表，冲突记日志）。
        val sidecarMaps = skinJsonFile.parentFile?.let {
            YamlSpriteDescriptor.loadSidecarMaps(it, manifest.assets())
        } ?: emptyMap()
        if (sidecarMaps.isNotEmpty()) {
            val merged = YamlSpriteDescriptor.mergeSpriteMaps(
                finalSnapshot.spriteMaps, sidecarMaps,
            )
            if (merged.conflicts.isNotEmpty()) {
                DiagLog.w(
                    "sprite", "sidecar_conflicts",
                    mapOf(
                        "skinId" to ref.skinId,
                        "conflicts" to merged.conflicts.joinToString(";") {
                            "${it.asset}:${it.image}"
                        },
                    ),
                )
            }
            finalSnapshot = finalSnapshot.copy(spriteMaps = merged.maps)
        }
        return finalSnapshot
    }

    suspend fun buildFromTree(
        context: Context,
        tree: kotlinx.serialization.json.JsonObject,
        manifest: PackageManifest,
        ref: SkinGenRef,
        libraryLayout: SkinLibraryLayout? = null,
    ): SkinSnapshot? {
        // 单次构建的布局来源：索引只读一次、各 layout.json 只解析
        // 一次（审计 #2）；以下全部布局解析共用同一实例。
        val layouts = LayoutLookup(libraryLayout)
        val assetKindById = manifest.assets().associate { content ->
            content.id to PackageManifest.assetKindOf(content.path)
        }
        val assetShaById = manifest.assets().associate { it.id to it.sha256 }
        // G1：与导入端同口径——皮肤所指布局的坐标 ID 另行可覆写，
        // 单键图片 1MB 上限以库内文件实际大小执行；任一步取不到时
        // 静默回落缺省口径（不改变此前行为，只补此前缺失的接线）。
        val coordIds = layoutCoordIds(tree, layouts)
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
        // 多主槽一期：`keyboards` 表逐项解析——每项的每个方向
        // 各走一次既有 resolveSnapshotLayout（同一索引、同一
        // 校验、同一回退口径），表项解析失败只影响该项该方向，
        // 不影响皮肤其余部分与旧单布局字段。无表皮肤得空表，
        // 旧行为逐字节不变。
        // 注意：本函数入参 tree 即调用方传来的生效树（覆盖层已
        // 合并），解析它即解析生效树；effectiveTree 只存在于
        // buildFromFiles 的作用域，不可在此引用。
        val keyboardEntries = SkinJson.parseKeyboards(tree)
        val resolvedKeyboards: Map<String, KeyboardTypeResolved> =
            keyboardEntries.mapValues { (_, entry) ->
                val (portrait, portraitResolution) =
                    if (entry.layoutId != null) {
                        resolveSnapshotLayout(context, layouts, entry.layoutId, ref)
                    } else {
                        null to LayoutResolution.NOT_SET
                    }
                val (landscape, landscapeResolution) =
                    if (entry.layoutIdLandscape != null) {
                        resolveSnapshotLayout(
                            context, layouts, entry.layoutIdLandscape, ref,
                            binding = "landscape",
                        )
                    } else {
                        null to LayoutResolution.NOT_SET
                    }
                // G4：深色绑定各走一次同口径解析（失败只影响该项该方向，
                // 深色缺失时解析层回落由 resolvedFor 处理，不在此补浅色）。
                val (portraitDark, portraitDarkResolution) =
                    if (entry.layoutIdDark != null) {
                        resolveSnapshotLayout(context, layouts, entry.layoutIdDark, ref)
                    } else {
                        null to LayoutResolution.NOT_SET
                    }
                val (landscapeDark, landscapeDarkResolution) =
                    if (entry.layoutIdLandscapeDark != null) {
                        resolveSnapshotLayout(
                            context, layouts, entry.layoutIdLandscapeDark, ref,
                            binding = "landscape",
                        )
                    } else {
                        null to LayoutResolution.NOT_SET
                    }
                KeyboardTypeResolved(
                    portrait = portrait,
                    landscape = landscape,
                    portraitResolution = portraitResolution,
                    landscapeResolution = landscapeResolution,
                    portraitDark = portraitDark,
                    landscapeDark = landscapeDark,
                    portraitDarkResolution = portraitDarkResolution,
                    landscapeDarkResolution = landscapeDarkResolution,
                    heightPercent = entry.heightPercent,
                    heightPercentLandscape = entry.heightPercentLandscape,
                )
            }
        // 旧单布局字段桥接：顶层绑定优先（与改前同一解析；若
        // 该 id 恰为某表项同方向绑定则复用表项结果，避免同一
        // 布局解析两次、诊断日志重复）；未设顶层绑定时以
        // pinyin 项桥接——有表无顶层绑定的皮肤，主键盘旧路径
        // 仍能命中 pinyin 布局；pinyin 项亦无有效绑定时旧字段
        // 为空，旧路径走方案默认键盘，永不空白。
        val pinyinResolved = resolvedKeyboards[KeyboardTypes.PINYIN]
        val (resolvedLayout, layoutResolution) = when (validated.layoutId) {
            null -> pinyinResolved?.portrait to
                (pinyinResolved?.portraitResolution ?: LayoutResolution.NOT_SET)
            // 按表项“声明 id”匹配（而非已解析 id）：绑定失败时
            // 已解析为 null 仍命中表项，复用其失败结论，不重复
            // 解析、不重复记日志；同一 id 在多项出现时结果相同。
            else -> keyboardEntries.entries.firstOrNull {
                it.value.layoutId == validated.layoutId
            }?.let { resolvedKeyboards.getValue(it.key) }
                ?.let { it.portrait to it.portraitResolution }
                ?: resolveSnapshotLayout(context, layouts, validated.layoutId, ref)
        }
        // 横屏绑定（2026-10-27 第一组 + 矩阵扩展）：与竖屏绑定
        // 独立解析——无竖屏绑定时横屏绑定仍可激活（内置布局
        // 竖屏 + 横屏专属布局，见 CustomLayoutRuntime 的主键盘
        // 位映射）。横屏绑定缺失/文件损坏/校验无效时一律回退：
        // 有竖屏布局时回退竖屏布局的横屏权重变体，无竖屏布局
        // 时回退方案默认键盘，永不空白。未设顶层横屏绑定时以
        // pinyin 项横屏绑定桥接（无横屏绑定则为 null，消费方
        // 回落竖屏布局的横屏权重变体，与改前一致）。
        val (resolvedLandscape, landscapeResolution) = when (validated.layoutIdLandscape) {
            null -> pinyinResolved?.landscape to
                (pinyinResolved?.landscapeResolution ?: LayoutResolution.NOT_SET)
            else -> keyboardEntries.entries.firstOrNull {
                it.value.layoutIdLandscape == validated.layoutIdLandscape
            }?.let { resolvedKeyboards.getValue(it.key) }
                ?.let { it.landscape to it.landscapeResolution }
                ?: resolveSnapshotLayout(
                    context, layouts, validated.layoutIdLandscape, ref,
                    binding = "landscape",
                )
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
            resolvedKeyboards = resolvedKeyboards,
            // G1：类型样式集引用（表项 styleSet；缺失集名在 builder 层丢弃）。
            typeStyleRefs = keyboardEntries.mapNotNull { (type, entry) ->
                entry.styleSet?.takeIf { it.isNotBlank() }?.let { type to it }
            }.toMap(),
            builtinLayouts = builtinResolution.layouts,
            globalBlur = GlobalStylePrefs.get(context, GlobalStyleMode.CUSTOM).backgroundBlur,
            backgroundScaleMode = GlobalStylePrefs.get(context, GlobalStyleMode.CUSTOM).backgroundScaleMode,
        )
        logBatch2Events(ref, validated, snapshot)
        return snapshot
    }

    /**
     * 覆写坐标的布局 id 并集（纯函数，可单测）：顶层竖屏／横屏
     * 绑定，加上 `keyboards` 表每项的竖屏／横屏绑定。只收非空
     * 字符串；表项形态错误由 [SkinJson.parseKeyboards] 的字段级
     * 回退口径先行丢弃（不进并集、不报错）。
     */
    internal fun overrideLayoutIds(
        tree: kotlinx.serialization.json.JsonObject,
    ): Set<String> {
        val ids = LinkedHashSet<String>()
        fun add(raw: String?) {
            if (!raw.isNullOrBlank()) ids += raw
        }
        add((tree["layoutId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
        add((tree["layoutIdLandscape"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
        for (entry in SkinJson.parseKeyboards(tree).values) {
            add(entry.layoutId)
            add(entry.layoutIdLandscape)
            // G4：深色绑定同属可覆写布局 id。
            add(entry.layoutIdDark)
            add(entry.layoutIdLandscapeDark)
        }
        return ids
    }

    /**
     * 单次构建的布局来源（审计 #2）：索引只读一次，各 layout.json
     * 只解析一次。旧实现每次解析都 `runBlocking { readIndex() }`
     * 再各读各的文件——单布局构建至少读两次索引、同一 layout.json
     * 解析两次（坐标并集一次、解析一次），多槽皮肤可达四次。
     * 本类由 [buildFromTree] 每构建新建、只在构建期内用，不跨
     * 构建共享（索引快照不跨构建复用，代次切换不读脏数据）。
     */
    internal class LayoutLookup(private val libraryLayout: SkinLibraryLayout?) {
        private val store: SkinLibraryStore? = libraryLayout?.let { SkinLibraryStore(it) }
        private var index: LibraryIndex? = null
        private var indexLoaded = false
        private val trees = HashMap<String, JsonObject?>()

        /** 库布局是否存在（false = 无库布局，走 MISSING 回退口径）。 */
        val hasLibrary: Boolean get() = libraryLayout != null

        /** 索引：首次调用读盘，此后复用同一快照。 */
        suspend fun index(): LibraryIndex? {
            if (!indexLoaded) {
                indexLoaded = true
                index = (store?.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            }
            return index
        }

        /** 索引中的布局条目（无条目 = 索引缺失该布局）。 */
        suspend fun entry(layoutId: String): LayoutIndexEntry? =
            index()?.layouts?.firstOrNull { it.id == layoutId }

        /**
         * 布局文件树：同一 id 在构建期内只读＋解析一次；条目缺失
         * 或文件不可读返回 null（调用方按既有口径区分 missing 与
         * unreadable：先查 [entry]，有条目无树 = 文件不可读）。
         */
        suspend fun layoutTree(layoutId: String): JsonObject? {
            if (trees.containsKey(layoutId)) return trees.getValue(layoutId)
            val layout = libraryLayout
            val tree = if (layout == null) {
                null
            } else {
                entry(layoutId)?.let {
                    val file = File(layout.fileFromRelative(it.dir), "layout.json")
                    runCatching { SkinJson.parseObject(file.readText()) }.getOrNull()
                }
            }
            trees[layoutId] = tree
            return tree
        }
    }

    /**
     * G1 坐标放宽口径（校验前 Best-effort）：并集内各布局的
     * 全部逻辑 ID；单个布局缺失／文件损坏只跳过该布局（不
     * 拖垮其余布局的坐标），无库布局／无绑定时为空集（回落
     * 缺省口径）。与 [resolveSnapshotLayout] 读同一索引与同
     * 一 layout.json，只是提前到校验之前，使坐标覆写不被校验
     * 器当成未知键剔掉。旧口径只取顶层竖屏绑定：仅主类型绑
     * 定的布局坐标可覆写，其余类型专属布局的坐标在校验期即
     * 被当成未知键剔掉（导入端早已按包内全布局并集接纳，
     * 快照端与之脱节）；本并集补齐该脱节。编辑器保存期亦用
     * 同一口径（[com.jobeen.ime.skin.library.SkinEditorSaver]，
     * 校验前 Best-effort 计算坐标并集），故为 internal。
     */
    internal suspend fun layoutCoordIds(
        tree: kotlinx.serialization.json.JsonObject,
        lookup: LayoutLookup,
    ): Set<String> {
        if (!lookup.hasLibrary) return emptySet()
        val ids = LinkedHashSet<String>()
        for (layoutId in overrideLayoutIds(tree)) {
            val layoutTree = lookup.layoutTree(layoutId) ?: continue
            ids += LayoutModel.logicalIdsFromTree(layoutTree, layoutId)
        }
        return ids
    }

    /**
     * 布局引用解析接线（B3 §3.4/§3.5）：索引 → 当前代次文件 →
     * 校验 → 构建。失败一律回退为「未设 layoutId」：快照照常
     * 成立、皮肤其余部分全部生效，逐项记日志（铁律 4）；不触发
     * Last-Good（布局回退已在解析层完成，不属于新皮肤生效失败）。
     */
    private suspend fun resolveSnapshotLayout(
        context: Context,
        lookup: LayoutLookup,
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
        if (!lookup.hasLibrary) {
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
        val entry = lookup.entry(layoutId)
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
        // 同一构建内 layout.json 只解析一次（[LayoutLookup] 缓存）：
        // 有条目无树 = 文件不可读，与改前口径一致。
        val tree = lookup.layoutTree(layoutId)
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
        // 横屏补丁展开（2026-11-02 工具项 6）：横屏绑定指向的布局
        // 文件若声明 patchOf，则它不是完整布局而是对竖屏布局的
        // 增量——在此展开成完整横屏布局再返回；任何展开失败都按
        // 横屏绑定无效处理，调用方回退竖屏布局的横屏权重变体，
        // 永不渲染半截布局。竖屏绑定不认领 patchOf。
        if (binding == "landscape" && tree["patchOf"] != null) {
            return expandLandscapePatch(
                context, lookup, tree, layoutId, entry.generation, ref,
                orientationField,
            )
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
     * 横屏补丁展开（工具项 6，resolveSnapshotLayout 的横屏分支）：
     * 补丁文件本身须校验通过；patchOf 指向的竖屏布局须存在且
     * 校验通过、且本身不是补丁（补丁不链式展开，防循环与半截
     * 展开）。任一失败返回 INVALID，调用方回退竖屏布局。
     */
    private suspend fun expandLandscapePatch(
        context: Context,
        lookup: LayoutLookup,
        tree: JsonObject,
        layoutId: String,
        generation: Int,
        ref: SkinGenRef,
        orientationField: Map<String, Any?>,
    ): Pair<ResolvedLayout?, LayoutResolution> {
        fun fail(reason: String): Pair<ResolvedLayout?, LayoutResolution> {
            DiagLog.w(
                "layout", "layout_fallback_default",
                mapOf(
                    "skinId" to ref.skinId, "layoutId" to layoutId,
                    "reason" to reason,
                ) + orientationField,
            )
            return null to LayoutResolution.INVALID
        }
        if (!lookup.hasLibrary) return fail("library_unavailable")
        val patch = (LayoutValidator.validate(tree, layoutId) as? LayoutValidation.Valid)
            ?.layout ?: return fail("patch_invalid")
        // 校验器对非法声明记 issue 回退为 null；原始声明仍在，
        // 故此处 null 一律是声明非法（含自指），不是“无补丁”。
        val portraitId = patch.patchOf ?: return fail("patch_of_invalid")
        val portrait = loadPatchPortrait(lookup, portraitId)
            ?: return fail("patch_portrait_missing_or_invalid")
        if (portrait.patchOf != null) return fail("patch_chain")
        val resolved = LandscapePatch.expandToResolved(
            patch, portrait, layoutId, generation, PlaceholderValues.current(context),
        )
        DiagLog.i(
            "layout", "layout_resolved",
            mapOf(
                "layoutId" to layoutId, "generation" to generation,
                "keyCount" to resolved.baseLayout.sumOf { it.size },
                "rowCount" to resolved.baseLayout.size,
                "patchOf" to portraitId,
            ) + orientationField,
        )
        return resolved to LayoutResolution.RESOLVED
    }

    /**
     * 补丁的竖屏布局原模型（只读，不走横屏展开路径，故补丁
     * 链在此截断）：索引缺失、文件不可读、解析失败、校验无效
     * 任一命中返回 null。
     */
    private suspend fun loadPatchPortrait(
        lookup: LayoutLookup,
        portraitId: String,
    ): LayoutModel? {
        val entry = lookup.entry(portraitId) ?: return null
        val tree = lookup.layoutTree(portraitId) ?: return null
        return (LayoutValidator.validate(tree, portraitId) as? LayoutValidation.Valid)?.layout
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
