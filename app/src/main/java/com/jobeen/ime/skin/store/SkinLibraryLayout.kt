package com.jobeen.ime.skin.store

import java.io.File

/**
 * 皮肤库目录结构（详设 §2.1，根目录名 `skin_library` 已裁定）。
 * 本目录为 v3 新增，与 v1.1.14 数据面隔离：1.1.14 不读、本类不写
 * themes/、词库与设置存储（回退保留，定稿确认记录六）。
 *
 * ```
 * skin_library/
 * ├─ index.json / active.json / last_good.json / sync_state.json
 * ├─ skins/<skinId>/gen-<n>/   一代一目录，只读不改（skin.json + manifest.json + prepared/）
 * ├─ layouts/<layoutId>/gen-<n>/layout.json   B1 只存不用
 * ├─ assets/<sha256>.<ext>     资产实体（内容寻址，定稿七.5）
 * ├─ staging/<uuid>/           导入/同步落库暂存
 * └─ drafts/<skinId>/          编辑草稿（十四.5，T8 落地）
 * ```
 */
class SkinLibraryLayout(val root: File) {
    val indexFile: File get() = File(root, "index.json")
    val activeFile: File get() = File(root, "active.json")
    val lastGoodFile: File get() = File(root, "last_good.json")
    val syncStateFile: File get() = File(root, "sync_state.json")

    val skinsDir: File get() = File(root, "skins")
    val layoutsDir: File get() = File(root, "layouts")
    val assetsDir: File get() = File(root, "assets")
    val stagingDir: File get() = File(root, "staging")
    val draftsDir: File get() = File(root, "drafts")

    fun skinGenDir(skinId: String, generation: Int): File =
        File(skinsDir, "$skinId/gen-$generation")

    fun layoutGenDir(layoutId: String, generation: Int): File =
        File(layoutsDir, "$layoutId/gen-$generation")

    /** generation 相对路径（索引 dir 字段口径）：skins/<id>/gen-<n> */
    fun skinGenRelative(skinId: String, generation: Int): String = "skins/$skinId/gen-$generation"

    fun layoutGenRelative(layoutId: String, generation: Int): String =
        "layouts/$layoutId/gen-$generation"

    fun fileFromRelative(relative: String): File = File(root, relative)

    fun assetFile(sha256: String, ext: String?): File =
        File(assetsDir, if (ext.isNullOrBlank()) sha256 else "$sha256.$ext")

    /** 某 sha256 的全部实体文件（扩展名只作可读性，身份只认 sha256 本体）。 */
    fun assetFiles(sha256: String): List<File> =
        assetsDir.listFiles { f ->
            f.isFile && (f.name == sha256 || f.name.startsWith("$sha256."))
        }?.toList() ?: emptyList()

    /** 皮肤代次完整性：skin.json 与 manifest.json 都在才算完整一代（详设 §2.8 对账口径）。 */
    fun isSkinGenComplete(dir: File): Boolean =
        File(dir, "skin.json").isFile && File(dir, "manifest.json").isFile

    fun isLayoutGenComplete(dir: File): Boolean = File(dir, "layout.json").isFile

    /**
     * 配额统计（详设 §2.1 规则 3）：全树落盘字节，含草稿、全部保留
     * 代次、实体与 prepared 产物；不含运行时解码缓存。
     */
    fun quotaBytes(): Long =
        if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    fun dirBytes(dir: File): Long =
        if (dir.exists()) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    fun ensureDirs() {
        listOf(root, skinsDir, layoutsDir, assetsDir, stagingDir, draftsDir).forEach { it.mkdirs() }
    }
}
