package com.jobeen.ime.skin.migrate

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.store.ImportTransaction
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 旧主题另存为 v3（定稿八.6 / 详设 §3.3，几何口径 = 裁定甲）：
 * 旧 ColorScheme 32 叶子逐项写实 + appearance 写当前全局偏好
 * 现值（迁移前后外观零变化、此后几何随皮肤固定），meta.id 新
 * 生成并与目录绑定；不写 layoutId / keyMappings / audio / 背景。
 *
 * 入库走导入事务（自建包：单 skin 内容的自生成 manifest），
 * 与外部导入同一原子口径；旧 themes.json 不被触碰（八.1）。
 */
class LegacySkinSaver(
    private val importTransaction: ImportTransaction,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    sealed interface Result {
        data class Saved(val skinId: String) : Result
        data class Failed(val reason: String) : Result
    }

    /** 另存失败记日志（2026-10-27 硬要求）：源主题 id + 原因。 */
    suspend fun save(theme: KeyboardTheme, currentPreferences: GeometryFallbacks): Result {
        val result = saveInner(theme, currentPreferences)
        if (result is Result.Failed) {
            DiagLog.w(
                "migrate", "legacy_save_failed",
                mapOf("themeId" to theme.id, "reason" to result.reason),
            )
        }
        return result
    }

    private suspend fun saveInner(theme: KeyboardTheme, currentPreferences: GeometryFallbacks): Result {
        val skinId = newId()
        val tree = SkinTreeBuilder.buildTree(
            skinId = skinId,
            name = theme.name,
            scheme = theme.colors,
            geometry = currentPreferences,
        )
        val skinJson = Json.encodeToString(JsonObject.serializer(), tree)
        val skinBytes = skinJson.encodeToByteArray()
        val manifest = PackageManifest(
            formatVersion = 3,
            contents = listOf(
                PackageManifest.Content(
                    type = PackageManifest.TYPE_SKIN,
                    id = skinId,
                    path = "skin.json",
                    sha256 = DigestUtil.sha256Hex(skinBytes),
                ),
            ),
        )
        val result = importTransaction.importPackage { staging ->
            val dir = File(staging, "skins/$skinId").apply { mkdirs() }
            File(dir, "skin.json").writeBytes(skinBytes)
            File(dir, "manifest.json").writeText(PackageManifest.toJson(manifest))
            ImportTransaction.BuiltPackage(
                skins = listOf(
                    ImportTransaction.StagedSkin(
                        id = skinId,
                        name = theme.name,
                        dir = dir,
                        hash = DigestUtil.sha256Hex(skinBytes),
                    ),
                ),
            )
        }
        return when (result) {
            is ImportTransaction.Result.Imported -> Result.Saved(skinId)
            is ImportTransaction.Result.Failed -> Result.Failed("${result.stage}:${result.reason}")
        }
    }
}
