// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.keyboard.theme

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.edit
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.ui.theme.AppAppearance
import java.io.File

/**
 * 全局字体（外观轮第 3 项）：按 Jime 文本角色重映射的 8 角色。
 *
 * 分层链与全局样式一致：皮肤内逐角色 ＞ 皮肤内字体 ＞ 全局字体
 * ＞ 系统默认（2026-10-29 item-1 补皮肤逐角色层；此前为皮肤
 * 单值 fontFamily 指定时全部角色都用它）。皮肤未指定时各角色
 * 独立取全局选择。未知字体／缺失文件一律回落系统默认，绝不
 * 空白、绝不崩。
 *
 * 偏好文件与应用外观共用 `appearance_settings`（键名前缀
 * `font.*`）；每次字体保存版本＋1（缓存失效与键盘刷新门控用）。
 */
enum class FontRole(val stored: String) {
    KEY("key"),
    CANDIDATE("candidate"),
    COMMENT("comment"),
    INDEX("index"),
    POPUP("popup"),
    PREEDIT("preedit"),
    TOOLBAR("toolbar"),
    SIDEPANEL("sidepanel"),
}

/** 字体选择：跟随／系统族／导入文件。 */
sealed class FontChoice {
    data object Follow : FontChoice()
    data class System(val family: String) : FontChoice()
    data class Imported(val fileId: String) : FontChoice()

    companion object {
        fun parse(raw: String?): FontChoice {
            return when {
                raw == null || raw == "follow" -> Follow
                raw == "sans" -> System("sans-serif")
                raw == "serif" -> System("serif")
                raw == "mono" -> System("monospace")
                raw.startsWith("file:") && raw.length > 5 -> Imported(raw.removePrefix("file:"))
                else -> Follow
            }
        }

        fun store(choice: FontChoice): String = when (choice) {
            Follow -> "follow"
            is System -> when (choice.family) {
                "sans-serif" -> "sans"
                "serif" -> "serif"
                "monospace" -> "mono"
                else -> "follow"
            }
            is Imported -> "file:${choice.fileId}"
        }
    }
}

object GlobalFonts {
    const val PREFS_NAME = AppAppearance.PREFS_NAME
    private const val KEY_VERSION = "font.version"
    private const val FONTS_DIR = "fonts"
    const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
    val IMPORT_EXTENSIONS: Set<String> = setOf("ttf", "otf")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keyFor(role: FontRole): String = "font.${role.stored}"

    /** 全部字体偏好键（服务监听路由用：字体键变化→强制刷新）。 */
    val ALL_KEYS: Set<String> =
        FontRole.entries.map { keyFor(it) }.toSet() + KEY_VERSION

    /** 字体版本（每次字体保存＋1；0＝未保存过）。 */
    fun version(context: Context): Int =
        prefs(context).getInt(KEY_VERSION, 0)

    fun getChoice(context: Context, role: FontRole): FontChoice =
        FontChoice.parse(prefs(context).getString(keyFor(role), null))

    /** 保存选择并把版本＋1（选择与版本同一次编辑提交）。 */
    fun setChoice(context: Context, role: FontRole, choice: FontChoice) {
        val p = prefs(context)
        p.edit {
            putString(keyFor(role), FontChoice.store(choice))
            putInt(KEY_VERSION, p.getInt(KEY_VERSION, 0) + 1)
        }
        clearCaches()
    }

    fun fontsDir(context: Context): File = File(context.filesDir, FONTS_DIR)

    /** 导入字体文件：扩展名＋大小＋平台可建三重校验，失败返回原因（不抛）。 */
    fun importFont(context: Context, bytes: ByteArray, ext: String): ImportResult {
        val normExt = ext.lowercase()
        if (normExt !in IMPORT_EXTENSIONS) return ImportResult.Failed("ext_unsupported")
        if (bytes.isEmpty() || bytes.size > MAX_IMPORT_BYTES) {
            return ImportResult.Failed("size_invalid")
        }
        val dir = fontsDir(context)
        if (!dir.exists() && !dir.mkdirs()) return ImportResult.Failed("dir_failed")
        val id = sha12(bytes) + "." + normExt
        val target = File(dir, id)
        if (!target.exists()) {
            val tmp = File(dir, "$id.tmp")
            try {
                tmp.writeBytes(bytes)
                // 平台建字族失败＝文件不可用：删暂存、拒绝导入。
                Typeface.createFromFile(tmp)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return ImportResult.Failed("move_failed")
                }
            } catch (e: Exception) {
                tmp.delete()
                DiagLog.w(
                    "font", "font_import_invalid",
                    mapOf("reason" to (e.javaClass.simpleName ?: "unknown")),
                )
                return ImportResult.Failed("font_invalid")
            }
        }
        return ImportResult.Ok(id)
    }

    sealed class ImportResult {
        data class Ok(val fileId: String) : ImportResult()
        data class Failed(val reason: String) : ImportResult()
    }

    // ---- 解析 ----

    private val systemCache = LinkedHashMap<Pair<String, Int>, Typeface>()
    private val fileCache = LinkedHashMap<Pair<String, Int>, Typeface>()
    private val loggedUnknownSkinFamilies = mutableSetOf<String>()
    private val loggedMissingFiles = mutableSetOf<String>()
    private var cachedVersion = -1

    @Synchronized
    fun clearCaches() {
        systemCache.clear()
        fileCache.clear()
        cachedVersion = -1
    }

    private fun sha12(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun systemTypeface(family: String, style: Int): Typeface? {
        return try {
            systemCache.getOrPut(family to style) { Typeface.create(family, style) }
        } catch (e: Exception) {
            DiagLog.w(
                "font", "font_create_failed",
                mapOf("family" to family, "style" to style.toString()),
            )
            null
        }
    }

    private fun importedTypeface(context: Context, fileId: String, style: Int): Typeface? {
        val file = File(fontsDir(context), fileId)
        if (!file.isFile) {
            // 备份恢复／用户清理后文件缺失：记一次、回落默认（绝不空白）。
            if (loggedMissingFiles.add(fileId)) {
                DiagLog.w("font", "font_file_missing", mapOf("fileId" to fileId))
            }
            return null
        }
        return try {
            fileCache.getOrPut(fileId to style) { Typeface.createFromFile(file) }
        } catch (e: Exception) {
            DiagLog.w(
                "font", "font_create_failed",
                mapOf("fileId" to fileId, "style" to style.toString()),
            )
            null
        }
    }

    /**
     * 角色字族 Typeface；null＝调用方用系统默认。
     * 分层（2026-10-29 item-1）：皮肤逐角色 ＞ 皮肤 fontFamily
     * ＞ 全局逐角色 ＞ 系统默认。皮肤逐角色只由皮肤内 roleFonts
     * 给出（校验期已规范化，此处再经白名单复核）；未知皮肤族名
     * 记一次 WARN（只记族名字符串，不记输入内容）。
     */
    @Synchronized
    fun typefaceFor(context: Context, role: FontRole, style: Int): Typeface? {
        val v = version(context)
        if (v != cachedVersion) {
            systemCache.clear()
            fileCache.clear()
            cachedVersion = v
        }
        val snapshot = SkinSnapshotProvider.peek()
        val roleFamily = snapshot?.roleFonts?.get(role)?.let { SkinFonts.resolveFamily(it) }
        if (roleFamily != null) return systemTypeface(roleFamily, style)
        val rawSkin = snapshot?.fontFamily
        val skinFamily = SkinFonts.resolveFamily(rawSkin)
        if (skinFamily != null) return systemTypeface(skinFamily, style)
        if (rawSkin != null && rawSkin.isNotBlank() &&
            !rawSkin.trim().equals("default", ignoreCase = true) &&
            loggedUnknownSkinFamilies.add(rawSkin.trim())
        ) {
            DiagLog.w(
                "font", "skin_font_unknown_family",
                mapOf("family" to rawSkin.trim()),
            )
        }
        return when (val choice = getChoice(context, role)) {
            FontChoice.Follow -> null
            is FontChoice.System -> systemTypeface(choice.family, style)
            is FontChoice.Imported -> importedTypeface(context, choice.fileId, style)
        }
    }

    /** 角色生效说明（纯解析，可单测）：皮肤逐角色／皮肤族名／全局选择／系统默认四态。 */
    fun effectiveSource(
        skinFamilyRaw: String?,
        choice: FontChoice,
        roleFonts: Map<FontRole, String> = emptyMap(),
        role: FontRole? = null,
    ): EffectiveSource {
        val roleFamily = role?.let { roleFonts[it] }?.let { SkinFonts.resolveFamily(it) }
        if (roleFamily != null) return EffectiveSource.SkinRole(roleFamily)
        val skinFamily = SkinFonts.resolveFamily(skinFamilyRaw)
        if (skinFamily != null) return EffectiveSource.Skin(skinFamily)
        return when (choice) {
            FontChoice.Follow -> EffectiveSource.Default
            is FontChoice.System -> EffectiveSource.Global(choice.family)
            is FontChoice.Imported -> EffectiveSource.GlobalFile(choice.fileId)
        }
    }

    sealed class EffectiveSource {
        data class Skin(val family: String) : EffectiveSource()
        /** 皮肤内逐角色（2026-10-29 item-1）：高于皮肤单值 fontFamily。 */
        data class SkinRole(val family: String) : EffectiveSource()
        data class Global(val family: String) : EffectiveSource()
        data class GlobalFile(val fileId: String) : EffectiveSource()
        data object Default : EffectiveSource()
    }
}
