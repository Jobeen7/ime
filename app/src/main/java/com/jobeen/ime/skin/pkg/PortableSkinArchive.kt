package com.jobeen.ime.skin.pkg

import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 可移植皮肤归档校验（第 5 批 §1，Jime 形映射）：
 *
 * 对方范式是 keyboard.json + assets 清单；Jime 不搬该 schema——
 * Jime 的可移植形态就是既有 .jime 包：manifest.json + skin.json
 * + layouts + assets。本校验只补 .jime 既有校验未覆盖的缺口：
 *
 * - 清单逐项大小：[PackageManifest.Content.sizeBytes] 若在场，
 *   必须等于包内实际字节数，否则整包拒绝（既有校验只验 SHA）。
 * - 配置上限：skin.json 字节数 ≤ [CONFIG_MAX_BYTES]。
 * - 条目数上限：清单条目 ≤ [ENTRIES_MAX]（zip 层已卡 100 条，
 *   此处是归档语义层的第二道，与对方 4097 条同档）。
 * - 无重名：既有 manifest 校验已拒重复 id/path，此处复核，
 *   不另起口径。
 *
 * 明确不映射（硬搬会拒收既有合法包，故只记结论不设拒绝）：
 * - id 前缀：对方要求 `custom_` 前缀；Jime id 命名空间是
 *   UUID／内置名，与该前缀无对应，强制前缀等于拒收既有包。
 * - 根页无环：对方页图有 secondaryOf 边；Jime 布局不引用
 *   布局（skin → layout 是单向绑定，layout 无出边），故页图
 *   天然无环——[findCycle] 恒返回 null，见函数注释。
 * - 页数 ≤512：Jime 对应物是 keyboards 表项数；超限记为
 *   [Issue.PAGES_EXCEEDED] 并拒绝（表项数超 512 的皮肤在
 *   既有解析层亦无意义，属于结构错）。
 * - 键映射条目 ≤4097：同档换算，超限拒绝。
 *
 * 纯 JVM，可直接单测；导入路径只在硬拒绝项上接线（见
 * JimeImporter），其余项不改变既有包的接纳口径。
 */
object PortableSkinArchive {

    /** 配置（skin.json）上限：32MB（与对方配置上限同档；zip 层单条目 8MB 更严，实际到不了此处）。 */
    const val CONFIG_MAX_BYTES = 32L * 1024 * 1024

    /** 清单条目上限：4097（对方条目上限同档）。 */
    const val ENTRIES_MAX = 4097

    /** keyboards 表项（页）上限：512（对方页上限同档）。 */
    const val PAGES_MAX = 512

    /** keyMappings 条目上限：4097（对方条目上限同档）。 */
    const val KEY_MAPPINGS_MAX = 4097

    sealed interface Issue {
        val reason: String
        /** true = 整包拒绝；false = 仅报告（本批硬拒绝项全部为 true，保留分层以备后批）。 */
        val rejecting: Boolean
    }

    private data class HardIssue(override val reason: String) : Issue {
        override val rejecting: Boolean = true
    }

    data class Result(val issues: List<Issue>) {
        val rejected: Issue? get() = issues.firstOrNull { it.rejecting }
        val ok: Boolean get() = rejected == null
    }

    /**
     * 校验已解包的归档（files 按包内 path 索引）。
     * @param skinTree 已解析的 skin.json 树；null = 跳过树相关项（调用方已另行拒包）。
     */
    fun validate(
        manifest: PackageManifest,
        filesByPath: Map<String, ZipSafeExtractor.ExtractedFile>,
        skinTree: JsonObject?,
    ): Result {
        val issues = mutableListOf<Issue>()
        if (manifest.contents.size > ENTRIES_MAX) {
            issues += HardIssue("portable_entries_exceeded:${manifest.contents.size}")
        }
        val ids = manifest.contents.map { it.id }
        if (ids.size != ids.toSet().size) issues += HardIssue("portable_duplicate_id")
        val paths = manifest.contents.map { it.path }
        if (paths.size != paths.toSet().size) issues += HardIssue("portable_duplicate_path")
        for (content in manifest.contents) {
            val file = filesByPath[content.path]
                ?: return Result(issues + HardIssue("portable_file_missing:${content.path}"))
            if (content.sizeBytes != null) {
                if (content.sizeBytes < 0) {
                    issues += HardIssue("portable_size_negative:${content.path}")
                } else if (content.sizeBytes != file.bytes.size.toLong()) {
                    issues += HardIssue("portable_size_mismatch:${content.path}")
                }
            }
        }
        val skinContent = manifest.skin()
        if (skinContent != null) {
            val skinBytes = filesByPath[skinContent.path]?.bytes?.size?.toLong()
            if (skinBytes != null && skinBytes > CONFIG_MAX_BYTES) {
                issues += HardIssue("portable_config_too_large:$skinBytes")
            }
        }
        if (skinTree != null) {
            val pages = SkinJson.parseKeyboards(skinTree).size
            if (pages > PAGES_MAX) issues += HardIssue("portable_pages_exceeded:$pages")
            val mappings = (skinTree["keyMappings"] as? JsonObject)?.size ?: 0
            if (mappings > KEY_MAPPINGS_MAX) {
                issues += HardIssue("portable_key_mappings_exceeded:$mappings")
            }
        }
        return Result(issues)
    }

    /**
     * 根页无环（Jime 形映射）：边只存在于 skin → layout（顶层
     * layoutId／layoutIdLandscape／keyboards 表四向绑定），
     * layout 节点无出边，故任何 Jime 包都不可能成环；本函数
     * 恒返回 null，调用方不得以此为由拒包。保留函数是为了让
     * “无环已核”有可测的落点，而不是一句注释。
     */
    fun findCycle(skinTree: JsonObject?): List<String>? = null

    /**
     * 模型引用素材收集（删皮肤用）：在皮肤树全部字符串叶值中
     * 取与清单资产 id 相交者。交集口径是安全方向——误收只会
     * 让资产多留（对账／剪枝后处理），绝不会误删未被模型
     * 引用的资产；权威引用图仍是清单 contents（见 AssetRefcounts）。
     */
    fun modelReferencedAssetIds(tree: JsonObject, assetIds: Set<String>): Set<String> {
        val found = mutableSetOf<String>()
        fun walk(element: kotlinx.serialization.json.JsonElement) {
            when (element) {
                is JsonPrimitive -> {
                    val text = element.contentOrNull
                    if (text != null && text in assetIds) found += text
                }
                is JsonObject -> element.values.forEach(::walk)
                is kotlinx.serialization.json.JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(tree)
        return found
    }

    /**
     * 所有权判定（删皮肤用）：canonical 路径必须落在自有素材
     * 根内（含根本身相等）才可删；canonical 解析失败一律
     * 判为不归属（不删，安全方向）。
     */
    fun isOwnedAssetFile(assetsRoot: File, candidate: File): Boolean {
        return try {
            val root = assetsRoot.canonicalFile
            val file = candidate.canonicalFile
            file == root || file.path.startsWith(root.path + File.separator)
        } catch (_: Exception) {
            false
        }
    }
}
