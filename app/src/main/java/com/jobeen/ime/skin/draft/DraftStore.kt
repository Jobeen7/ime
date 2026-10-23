package com.jobeen.ime.skin.draft

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.store.AtomicFiles
import com.jobeen.ime.skin.store.SkinLibraryLayout
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 草稿元信息（详设 §5.1）：draft.meta.json 与 draft.json 分离存放，
 * 元信息损坏时草稿按不存在处理（不阻塞编辑器进入）。
 */
@Serializable
data class DraftMeta(
    /** 目标皮肤 id（编辑已有皮肤 = 其 id；新建 = 预生成的新 id）。 */
    val skinId: String,
    /**
     * 基线内容 hash：已有皮肤 = 复制时源 skin.json 的内容 hash；
     * 预设/旧主题/空白来源 = 初始草稿内容（draft.json 字节）的
     * sha256（§5.1 待核回填口径：无源实体时以初始内容自锚，
     * 实现位置即本类 create()，漂移判定对新建草稿恒不触发）。
     */
    val baseHash: String,
    /** 基线代次：已有皮肤为源代次；新建为 0。 */
    val baseGeneration: Int,
    /** existing / preset / legacy / blank */
    val source: String,
    val updatedAt: Long,
    /**
     * 锚二（皮肤 v3 B3 §4.4，本批补齐）：皮肤草稿立稿时其 layoutId
     * 所绑布局的 id 与当时 hash——皮肤草稿应用前据此做布局漂移
     * 提示（草稿期间布局被别处改动时，存量 keyMappings 覆写的
     * 命中对象可能已变，用户须知情确认）。布局草稿本身不用这两
     * 个字段（其基线即 baseHash，对布局实体成立，语义同皮肤锚）。
     * 默认 null：旧 meta 文件与未绑布局的草稿都兼容。
     */
    val layoutAnchorId: String? = null,
    val layoutAnchorHash: String? = null,
)

/**
 * 编辑器草稿区（定稿十四.5 / 详设 §5）：
 *
 * ```
 * drafts/<skinId>/draft.json      草稿内容（完整皮肤树）
 * drafts/<skinId>/draft.meta.json 基线与来源（漂移判定）
 * drafts/<skinId>/asset-<sha>.<ext>  草稿期新选的背景图字节（应用时随包入库）
 * ```
 *
 * 草稿是工作副本：编辑只落草稿 + 预览，应用才走导入管线成新
 * 代次（Draft/Applied 分离）；进程死亡后凭目录恢复，继续/丢弃
 * 由用户显式决定；草稿不进 .jime 导出与 WebDAV 同步（十四.5），
 * 但计入库配额（layout.quotaBytes 全树口径已含 drafts/）。
 */
class DraftStore(
    private val layout: SkinLibraryLayout,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    data class Draft(
        val meta: DraftMeta,
        val tree: JsonObject,
        /** 当前草稿内容字节的 sha256（与 baseHash 比对即漂移判定的内容侧）。 */
        val contentHash: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun draftDir(skinId: String): File = File(layout.draftsDir, skinId)

    fun encodeTree(tree: JsonObject): ByteArray =
        Json.encodeToString(JsonObject.serializer(), tree).encodeToByteArray()

    /**
     * 新建草稿：initialTree 的 meta.id 必须已等于 skinId。
     * [baseHash] 已有皮肤传入源 skin.json 的内容 hash；为 null 时
     * 以初始草稿内容自锚（预设/旧主题/空白口径，见 DraftMeta）。
     */
    suspend fun create(
        skinId: String,
        initialTree: JsonObject,
        source: String,
        baseGeneration: Int,
        baseHash: String? = null,
        layoutAnchorId: String? = null,
        layoutAnchorHash: String? = null,
    ): Draft {
        val bytes = encodeTree(initialTree)
        val contentHash = DigestUtil.sha256Hex(bytes)
        val meta = DraftMeta(
            skinId = skinId,
            baseHash = baseHash ?: contentHash,
            baseGeneration = baseGeneration,
            source = source,
            updatedAt = clock(),
            layoutAnchorId = layoutAnchorId,
            layoutAnchorHash = layoutAnchorHash,
        )
        writeDraftFiles(skinId, bytes, meta)
        return Draft(meta, initialTree, contentHash)
    }

    suspend fun load(skinId: String): Draft? {
        val dir = draftDir(skinId)
        val metaFile = File(dir, META_FILE)
        val treeFile = File(dir, TREE_FILE)
        if (!metaFile.isFile || !treeFile.isFile) return null
        val meta = runCatching {
            json.decodeFromString<DraftMeta>(metaFile.readText())
        }.getOrNull() ?: return null
        val bytes = runCatching { treeFile.readBytes() }.getOrNull() ?: return null
        val tree = com.jobeen.ime.skin.model.SkinJson.parseObject(bytes.decodeToString())
            ?: return null
        return Draft(meta, tree, DigestUtil.sha256Hex(bytes))
    }

    /** 保存编辑内容（草稿自动保存与显式保存共用；tmp+rename 原子写）。 */
    suspend fun save(skinId: String, tree: JsonObject): Draft? {
        val existing = load(skinId) ?: return null
        val bytes = encodeTree(tree)
        val meta = existing.meta.copy(updatedAt = clock())
        writeDraftFiles(skinId, bytes, meta)
        return Draft(meta, tree, DigestUtil.sha256Hex(bytes))
    }

    /** 显式丢弃/应用成功后清除：整目录删除，不留副本（十四.5）。 */
    suspend fun delete(skinId: String) {
        draftDir(skinId).deleteRecursively()
    }

    suspend fun listMetas(): List<DraftMeta> =
        layout.draftsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                runCatching {
                    json.decodeFromString<DraftMeta>(File(dir, META_FILE).readText())
                }.getOrNull()
            }
            ?: emptyList()

    /** 草稿期背景图文件（内容寻址命名，与库内资产同一 sha 口径）。 */
    fun draftAssetFile(skinId: String, sha256: String, ext: String?): File {
        val name = if (ext.isNullOrBlank()) "asset-$sha256" else "asset-$sha256.$ext"
        return File(draftDir(skinId), name)
    }

    fun draftAssetFiles(skinId: String): List<File> =
        draftDir(skinId).listFiles()
            ?.filter { it.isFile && it.name.startsWith("asset-") }
            ?: emptyList()

    /**
     * 草稿音效索引（B2）：逻辑 id → sha256 的认领表。编辑器导入的
     * 音效文件与背景图同为 asset-<sha>.<ext> 命名，靠本索引区分
     * 归属——提交时被索引认领的文件按音效资产组包，其余未认领
     * 文件才按背景处理（B1 单资产口径）。索引损坏按空表处理。
     */
    fun readDraftSoundIndex(skinId: String): Map<String, String> {
        val file = File(draftDir(skinId), SOUND_INDEX_FILE)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(file.readText()) as? JsonObject
                ?: return emptyMap()
            obj.mapNotNull { (id, node) ->
                val primitive = node as? kotlinx.serialization.json.JsonPrimitive
                // 严格字符串读法：数字/布尔的 contentOrNull 会返回
                // 字面文本，必须先核 isString
                if (primitive != null && primitive.isString) {
                    id to primitive.content
                } else {
                    null
                }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun writeDraftSoundIndex(skinId: String, index: Map<String, String>) {
        val dir = draftDir(skinId).apply { mkdirs() }
        val obj = JsonObject(
            index.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) },
        )
        AtomicFiles.writeAtomically(
            dir, SOUND_INDEX_FILE,
            json.encodeToString(JsonObject.serializer(), obj).encodeToByteArray(),
        )
    }

    private fun writeDraftFiles(skinId: String, treeBytes: ByteArray, meta: DraftMeta) {
        val dir = draftDir(skinId).apply { mkdirs() }
        AtomicFiles.writeAtomically(dir, TREE_FILE, treeBytes)
        AtomicFiles.writeAtomically(dir, META_FILE, json.encodeToString(meta))
    }

    companion object {
        const val TREE_FILE = "draft.json"
        const val META_FILE = "draft.meta.json"
        const val SOUND_INDEX_FILE = "sound-index.json"
        const val SOURCE_EXISTING = "existing"
        const val SOURCE_PRESET = "preset"
        const val SOURCE_LEGACY = "legacy"
        const val SOURCE_BLANK = "blank"

        /**
         * 布局草稿的 key 前缀（B3 §4.4）：布局草稿与皮肤草稿同处
         * 一个草稿区（不新开 [SkinLibraryLayout] 根目录、不动既有
         * 草稿文件结构），靠 key = `layout:<layoutId>` 区分；布局
         * 草稿的 DraftMeta.skinId 字段即此 key。皮肤草稿列表等
         * 消费方须按此前缀过滤，布局草稿只在布局语境出现。
         */
        const val LAYOUT_DRAFT_KEY_PREFIX = "layout:"

        /** 布局草稿的草稿区 key。 */
        fun layoutDraftKey(layoutId: String): String = LAYOUT_DRAFT_KEY_PREFIX + layoutId
    }
}
