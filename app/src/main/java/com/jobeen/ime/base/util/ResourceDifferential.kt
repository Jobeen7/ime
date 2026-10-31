package com.jobeen.ime.base.util

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * 内置数据差量同步（Z2）。
 *
 * 背景：每日冷启动早已零 I/O（version.txt 的 versionCode + 哨兵），
 * 差量的收益只在升级/重装/资源目录被删的启动：旧全量解压每次重写
 * 全部 100MB，本差量只写变化/缺失的条目、跳过未变文件、并修剪
 * shared/ 与 model/ 下新包已删的残留文件。
 *
 * 清单（assets/resource-manifest.json）由 tools/gen_resource_manifest.py
 * 从 resource.zip 的中央目录生成，与 zip 成对提交。清单条目只含文件条目，
 * 目录条目与解压跳过类条目（__MACOSX 等）不在清单内。
 *
 * 三条安全线（均不依赖“清单一定最新”这一假设）：
 * - user/ 整树永不碰：修剪只走 destDir 下的 shared/ 与 model/ 两个子树。
 * - preserve 前缀（opencc 用户词典 Custom_*，配置 JSON 直接引用、
 *   用户可改的纯文本）：存在即跳过（不比对、不覆盖），缺失才填空，
 *   修剪时同样放过。
 * - 修剪双重否定：目标文件仅当既不在清单内、又不在本遍 zip 流里时才删。
 *   清单若过期（zip 已换、清单未再生）：新文件在流里出现过——
 *   解压时按“清单外条目照常解压”（只朝多写偏）已写入，修剪又因流内
 *   出现而放过，绝不会出现“刚写入即被修剪、且已写版本标记”的丢文件；
 *   唯一残留是新包已删的文件多留一版（与旧全量不修剪的行为一致，
 *   下次清单正常时修剪）。故运行侧不校验整包哈希——ZipInputStream
 *   的缓冲预读使流式哈希的覆盖范围不可复现，校验反而恒失配。
 *
 * 失败语义与全量一致：任一单文件落盘失败即整体失败、不写版本标记，
 * 下次启动重走差量（已写条目下次比对跳过，天然可续）。
 */

/** 差量结果：OK（可写标记）／FAILED（整体失败，下次重走差量）。 */
internal enum class DifferentialResult { OK, FAILED }

/** 清单条目：路径（zip 内相对路径）＋未压缩长度＋CRC32（8 位小写十六进制）。 */
@Serializable
internal data class ManifestEntry(
    val path: String,
    val size: Long,
    val crc: String,
)

/**
 * 清单：版本（仅 1）＋整包 SHA-256（仅供人工/工具核对配对，
 * 运行侧不校验，见上）＋preserve 前缀＋条目。
 */
@Serializable
internal data class ResourceManifest(
    val version: Int = 1,
    val zipSha256: String = "",
    val preserve: List<String> = emptyList(),
    val entries: List<ManifestEntry> = emptyList(),
)

private val manifestJson = Json { ignoreUnknownKeys = true }

/**
 * 解析清单：非法 JSON、版本非 1、条目为空时均返回 null
 * （调用方按清单缺失处理：全量重解）。条目为空必是生成失误——
 * 有效清单恒有条目，空清单若放行会把刚写的文件当残留修剪掉。
 */
internal fun parseManifest(json: String): ResourceManifest? {
    val manifest = runCatching {
        manifestJson.decodeFromString<ResourceManifest>(json)
    }.getOrNull() ?: return null
    if (manifest.version != 1) return null
    if (manifest.entries.isEmpty()) return null
    return manifest
}

/** 路径是否在 preserve 前缀内（前缀匹配，如 shared/opencc/wanxiang/Custom_）。 */
internal fun isPreserved(path: String, preserve: List<String>): Boolean =
    preserve.any { prefix -> path.startsWith(prefix) }

private fun crc32Of(file: File): Long {
    val crc = CRC32()
    file.inputStream().buffered().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            crc.update(buf, 0, n)
        }
    }
    return crc.value
}

private fun crcMatches(file: File, entry: ManifestEntry): Boolean {
    if (!file.isFile) return false
    if (file.length() != entry.size) return false
    val expected = entry.crc.toLongOrNull(16) ?: return false
    return runCatching { crc32Of(file) }.getOrNull() == expected
}

/**
 * 差量解压（无 Context 入口，供主机单测）。
 *
 * 清单外流条目照常解压（只朝多写偏）；修剪只删“清单外且流外”
 * 的文件（见文件头安全线）。任一单文件落盘失败即 FAILED 立返，
 * 不修剪、不写标记。
 */
internal fun extractDifferential(
    input: InputStream,
    destDir: File,
    manifest: ResourceManifest,
): DifferentialResult {
    val byPath = manifest.entries.associateBy { it.path }
    val streamPaths = mutableSetOf<String>()
    var written = 0
    ZipInputStream(input).use { zip ->
        val destCanonicalPath = destDir.canonicalPath
        var entry = zip.nextEntry
        while (entry != null) {
            val rawName = entry.name
            val destFile = ResourceExtractorUtil.resolveDestFile(destDir, destCanonicalPath, rawName)
            if (destFile == null) {
                zip.closeEntry()
                entry = zip.nextEntry
                continue
            }
            if (!entry.isDirectory) {
                val name = rawName.trimEnd('/')
                streamPaths.add(name)
                val listed = byPath[name]
                val skip = when {
                    isPreserved(name, manifest.preserve) && destFile.exists() -> true
                    listed != null && crcMatches(destFile, listed) -> true
                    else -> false
                }
                if (skip) {
                    Timber.d("  differential skipped (unchanged/preserved): %s", name)
                } else {
                    if (!ResourceExtractorUtil.writeEntryFile(zip, destFile)) {
                        return DifferentialResult.FAILED
                    }
                    written++
                    Timber.d("  differential extracted: %s", name)
                }
            } else {
                destFile.mkdirs()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    // 修剪：只走 shared/ 与 model/ 两个子树、只删文件；清单内、
    // 本遍流内、preserve 前缀内的文件一律保留；修剪失败同样整体失败。
    for (subtree in listOf("shared", "model")) {
        val root = File(destDir, subtree)
        if (!root.isDirectory) continue
        val surplus = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(destDir).path.replace(File.separatorChar, '/') }
            .filter { path ->
                path !in byPath && path !in streamPaths &&
                    !isPreserved(path, manifest.preserve)
            }
            .toList()
        for (path in surplus) {
            val file = File(destDir, path)
            if (!file.delete()) {
                Timber.w("  differential prune failed: %s", path)
                return DifferentialResult.FAILED
            }
            Timber.d("  differential pruned: %s", path)
        }
    }
    Timber.d("Differential extraction complete: %d files written", written)
    return DifferentialResult.OK
}

/**
 * 带清单的资源解压（AppStartup 用，与 [ResourceExtractorUtil.extract]
 * 同合同：true → 调用方可写版本标记，false → 不写标记下次重走）。
 * 清单缺失/非法时直接全量重解。
 */
fun extractWithManifest(
    context: Context,
    zipAsset: String,
    manifestAsset: String,
    destDir: File,
): Boolean {
    val manifestText = runCatching {
        context.assets.open(manifestAsset).bufferedReader().readText()
    }.getOrNull()
    val manifest = manifestText?.let { parseManifest(it) }
    if (manifest == null) {
        Timber.w("Resource manifest missing/unparseable; falling back to full extraction")
        return ResourceExtractorUtil.extract(context, zipAsset, destDir)
    }
    val result = context.assets.open(zipAsset).use { input ->
        extractDifferential(input, destDir, manifest)
    }
    return result == DifferentialResult.OK
}
