package com.jobeen.ime.skin.export

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.layout.BuiltinLayouts
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * .jime 导出（详设 §3.2）：
 *
 * - 依赖闭包：目标皮肤 + 其 layoutId / layoutIdLandscape
 *   引用的布局 + 清单内资产
 *   （导入包本身即闭包，自建皮肤清单按闭包生成，见迁移/编辑器）。
 * - manifest 重建：sha256 对导出字节现算；路径归一到固定布局
 *   （skin.json、layouts/<id>.json、assets/<原文件名>）。
 * - 内容原样：skin.json / layout.json 以存储的原始字节导出，
 *   未知/未支持节点逐字节无损（定稿十六.2 round-trip）。
 * - 不含草稿（定稿十四.5）、不含 prepared 产物（接收端冷路径重建）。
 */
class JimeExporter(
    private val layout: SkinLibraryLayout,
    private val store: SkinLibraryStore,
) {

    sealed interface Result {
        data class Exported(
            val skinId: String,
            val layoutIds: List<String>,
            val assetIds: List<String>,
        ) : Result

        data class Failed(val reason: String) : Result
    }

    /** 导出失败记日志（2026-10-27 硬要求）：皮肤 id + 原因。 */
    suspend fun export(skinId: String, out: OutputStream): Result {
        val result = exportInner(skinId, out)
        if (result is Result.Failed) {
            DiagLog.w(
                "export", "export_failed",
                mapOf("skinId" to skinId, "reason" to result.reason),
            )
        }
        return result
    }

    private suspend fun exportInner(skinId: String, out: OutputStream): Result {
        val index = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return Result.Failed("index_unavailable")
        val skinEntry = index.skins.firstOrNull { it.id == skinId }
            ?: return Result.Failed("skin_not_found")
        val skinGenDir = layout.fileFromRelative(skinEntry.dir)
        val skinBytes = runCatching { File(skinGenDir, "skin.json").readBytes() }.getOrNull()
            ?: return Result.Failed("skin_file_missing")
        val sourceManifest = runCatching {
            PackageManifest.parse(File(skinGenDir, "manifest.json").readText())
        }.getOrNull() ?: return Result.Failed("manifest_missing")

        val skinTree = SkinJson.parseObject(skinBytes.decodeToString())
            ?: return Result.Failed("skin_unparseable")
        val referencedLayoutId = (skinTree["layoutId"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content
        // 横屏专属绑定（2026-10-27 第一组）同样属于依赖闭包：
        // 只含 layoutId 时清单内的横屏布局会被导出丢掉，接收端
        // 横屏回退竖屏布局——导出前后行为不一致，故一并打包。
        // （绑定指向的布局不在清单内时与主绑定同口径：本循环
        // 只导出清单内布局，缺失项不单独失败——该情形由导入/
        // 编辑器侧的清单完整性保证，见 JimeImporter。）
        // 与 SkinValidator 2298da6 同口径：非字符串形态不接受
        // （数字 42 的 content 会读成 "42"，不得当成布局 id 导出）。
        val landscapePrimitive = skinTree["layoutIdLandscape"]
            as? kotlinx.serialization.json.JsonPrimitive
        val referencedLayoutIdLandscape =
            if (landscapePrimitive?.isString == true) landscapePrimitive.content else null
        val referencedLayoutIds = setOfNotNull(referencedLayoutId, referencedLayoutIdLandscape)

        // 布局闭包：清单内被皮肤引用的布局（字节取库内当前代次）；
        // 内置覆盖不属绑定引用，但属皮肤自身——清单内者一律打包
        // （字节取本皮肤代次目录），否则导出丢覆盖、接收端行为不一致。
        val layoutContents = mutableListOf<PackageManifest.Content>()
        val layoutPayloads = mutableListOf<Pair<String, ByteArray>>()
        for (content in sourceManifest.layouts()) {
            if (BuiltinLayouts.isBuiltin(content.id)) {
                // 内置覆盖缺文件 = 无覆盖（导入期无效已丢弃，或文件被
                // 清理）：跳过而非失败，否则一次坏排版会砖掉整包导出。
                val builtinFile = File(File(skinGenDir, "layouts"), "${content.id}.json")
                if (!builtinFile.isFile) continue
                val bytes = runCatching { builtinFile.readBytes() }.getOrNull()
                    ?: return Result.Failed("layout_file_missing:${content.id}")
                val path = "layouts/${content.id}.json"
                layoutContents += PackageManifest.Content(
                    PackageManifest.TYPE_LAYOUT, content.id, path, DigestUtil.sha256Hex(bytes),
                )
                layoutPayloads += path to bytes
                continue
            }
            if (content.id !in referencedLayoutIds) continue
            val entry = index.layouts.firstOrNull { it.id == content.id }
                ?: return Result.Failed("layout_missing:${content.id}")
            val bytes = runCatching {
                File(layout.fileFromRelative(entry.dir), "layout.json").readBytes()
            }.getOrNull() ?: return Result.Failed("layout_file_missing:${content.id}")
            val path = "layouts/${content.id}.json"
            layoutContents += PackageManifest.Content(
                PackageManifest.TYPE_LAYOUT, content.id, path, DigestUtil.sha256Hex(bytes),
            )
            layoutPayloads += path to bytes
        }

        // 资产闭包：清单内全部资产（包在导入时即为闭包；自建清单同口径）
        val assetContents = mutableListOf<PackageManifest.Content>()
        val assetPayloads = mutableListOf<Pair<String, ByteArray>>()
        for (content in sourceManifest.assets()) {
            val file = layout.assetFiles(content.sha256).firstOrNull()
                ?: return Result.Failed("asset_missing:${content.id}")
            val bytes = runCatching { file.readBytes() }.getOrNull()
                ?: return Result.Failed("asset_unreadable:${content.id}")
            val fileName = content.path.substringAfterLast('/')
            val path = "assets/$fileName"
            assetContents += PackageManifest.Content(
                PackageManifest.TYPE_ASSET, content.id, path, DigestUtil.sha256Hex(bytes),
            )
            assetPayloads += path to bytes
        }

        val manifest = PackageManifest(
            formatVersion = sourceManifest.formatVersion,
            contents = listOf(
                PackageManifest.Content(
                    PackageManifest.TYPE_SKIN, skinId, "skin.json",
                    DigestUtil.sha256Hex(skinBytes),
                ),
            ) + layoutContents + assetContents,
        )

        ZipOutputStream(out.buffered()).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("manifest.json", PackageManifest.toJson(manifest).encodeToByteArray())
            put("skin.json", skinBytes)
            for ((path, bytes) in layoutPayloads) put(path, bytes)
            for ((path, bytes) in assetPayloads) put(path, bytes)
        }
        return Result.Exported(
            skinId = skinId,
            layoutIds = layoutContents.map { it.id },
            assetIds = assetContents.map { it.id },
        )
    }
}
