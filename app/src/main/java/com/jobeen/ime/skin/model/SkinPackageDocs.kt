// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import java.io.File
import java.nio.charset.CodingErrorAction

/**
 * 皮肤包演示资料（审计 K 余项：包根演示图与说明）：
 *
 * - 演示图 `demo.png`：包根 PNG，用于向用户展示皮肤外观；
 *   缺失不拦包（库页仅不展示预览图）。
 * - 说明 `README.md`：包根纯文说明（如中英文切换方法）；
 *   缺失不拦包。文本按原文保存，展示方必须按纯文本渲染，
 *   不得执行其中的链接/图片（只作文字描述用）。
 *
 * 校验口径（解析期，不拦包）：演示图须为 PNG 魔数开头且
 * 不超上限；说明须为严格 UTF-8、无 NUL 且不超上限。
 * 失效项只报告、不入库（快照中对应项为空）。
 */
data class SkinPackageDocs(
    /** 演示图已校验通过（代次目录下 demo.png 可取）。 */
    val hasDemoImage: Boolean = false,
    /** 已校验的说明原文；缺失/失效时为 null。 */
    val readmeText: String? = null,
)

object SkinPackageDocsParser {

    const val DEMO_FILE_NAME = "demo.png"
    const val README_FILE_NAME = "README.md"

    /** 演示图上限 5MB（库页预览图无需更大）。 */
    const val MAX_DEMO_BYTES = 5 * 1024 * 1024

    /** 说明上限 256KB（纯文说明无需更大）。 */
    const val MAX_README_BYTES = 256 * 1024

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** 从代次目录取演示资料（缺失文件 = 对应项为空，不抛错）。 */
    fun fromDir(genDir: File): SkinPackageDocs {
        val demoBytes = File(genDir, DEMO_FILE_NAME)
            .takeIf { it.isFile }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
        val readmeBytes = File(genDir, README_FILE_NAME)
            .takeIf { it.isFile }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
        return fromBytes(demoBytes, readmeBytes)
    }

    /** 从原始字节取演示资料（导入期用：源包根文件字节）。 */
    fun fromBytes(demoBytes: ByteArray?, readmeBytes: ByteArray?): SkinPackageDocs =
        SkinPackageDocs(
            hasDemoImage = demoBytes != null && isValidDemoImage(demoBytes),
            readmeText = readmeBytes?.let { readmeTextOrNull(it) },
        )

    /** 代次目录下的演示图文件（仅当文件存在且 PNG 魔数通过时返回）。 */
    fun demoImageFile(genDir: File): File? {
        val file = File(genDir, DEMO_FILE_NAME)
        if (!file.isFile) return null
        val header = ByteArray(PNG_SIGNATURE.size)
        val read = runCatching {
            file.inputStream().use { it.read(header) }
        }.getOrNull() ?: return null
        if (read != PNG_SIGNATURE.size) return null
        if (!header.contentEquals(PNG_SIGNATURE)) return null
        if (file.length() > MAX_DEMO_BYTES) return null
        return file
    }

    fun isValidDemoImage(bytes: ByteArray): Boolean {
        if (bytes.size > MAX_DEMO_BYTES || bytes.size < PNG_SIGNATURE.size) return false
        return bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
    }

    /** 严格 UTF-8 解码 + 无 NUL + 上限；任一不满足返回 null。 */
    fun readmeTextOrNull(bytes: ByteArray): String? {
        if (bytes.size > MAX_README_BYTES) return null
        if (bytes.any { it == 0.toByte() }) return null
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }
}
