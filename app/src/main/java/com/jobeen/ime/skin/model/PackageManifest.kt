package com.jobeen.ime.skin.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * .jime 包清单（定稿七.1）：一包恰一份 manifest.json，
 * contents 逐条 {type, id, path, sha256}，type ∈ {skin, layout, asset}。
 */
@Serializable
data class PackageManifest(
    val formatVersion: Int = SkinValidator.SUPPORTED_FORMAT_VERSION,
    val contents: List<Content> = emptyList(),
) {
    @Serializable
    data class Content(
        val type: String,
        val id: String,
        val path: String,
        val sha256: String,
    )

    fun skin(): Content? = contents.firstOrNull { it.type == TYPE_SKIN }
    fun layouts(): List<Content> = contents.filter { it.type == TYPE_LAYOUT }
    fun assets(): List<Content> = contents.filter { it.type == TYPE_ASSET }
    fun byId(id: String): Content? = contents.firstOrNull { it.id == id }

    companion object {
        const val TYPE_SKIN = "skin"
        const val TYPE_LAYOUT = "layout"
        const val TYPE_ASSET = "asset"

        /** 图片扩展名（资产类别判定，定稿七.3 引用类型校验用）。 */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

        /** 音效扩展名（定稿十二.2 格式 .ogg/.wav）。 */
        val AUDIO_EXTENSIONS = setOf("ogg", "wav")

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): PackageManifest? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun toJson(manifest: PackageManifest): String =
            Json { prettyPrint = true }.encodeToString(serializer(), manifest)

        /** 按包内 path 的扩展名判资产类别（path 仅是存储位置，类别以内容条目为准）。 */
        fun assetKindOf(path: String): AssetKind {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                in IMAGE_EXTENSIONS -> AssetKind.IMAGE
                in AUDIO_EXTENSIONS -> AssetKind.AUDIO
                else -> AssetKind.OTHER
            }
        }
    }
}
