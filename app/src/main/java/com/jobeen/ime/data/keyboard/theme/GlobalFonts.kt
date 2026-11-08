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

/**
 * 内嵌字体字重匹配谓词（P5，纯函数，可单测）：未声明字重
 * 的文件匹配任意请求；否则粗体请求取字重 ≥600，常规请求
 * 取字重 <600（与 skinCandidateTypeface 的过滤同口径）。
 */
internal fun fontWeightMatches(faceWeight: Int?, bold: Boolean): Boolean =
    faceWeight == null || (bold && faceWeight >= 600) || (!bold && faceWeight < 600)

object GlobalFonts {
    const val PREFS_NAME = AppAppearance.PREFS_NAME
    private const val KEY_VERSION = "font.version"
    private const val FONTS_DIR = "fonts"
    const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
    val IMPORT_EXTENSIONS: Set<String> = setOf("ttf", "otf")

    /**
     * 可删除的导入字体扩展名（Mie 增量①口径：ttf/otf/ttc/otc；
     * 导入只收 ttf/otf，ttc/otc 可能随备份恢复落盘，列表同样
     * 覆盖，删时一并清理）。
     */
    val DELETABLE_EXTENSIONS: Set<String> = setOf("ttf", "otf", "ttc", "otc")

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

    /** 已导入字体文件（管理列表用）。 */
    data class ImportedFont(
        val fileId: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        /** 引用该文件的角色（为空＝无角色在用，删后不影响当前选择）。 */
        val usedBy: List<FontRole>,
    )

    sealed class DeleteResult {
        /** 文件已删；resetRoles＝被改回跟随的角色（空＝无角色在用）。 */
        data class Deleted(val resetRoles: List<FontRole>) : DeleteResult()
        /** 文件不存在，但残留引用已清理（resetRoles 同上；都不存在时为空）。 */
        data class NotFound(val resetRoles: List<FontRole>) : DeleteResult()
        data class Failed(val reason: String) : DeleteResult()
    }

    /**
     * 导入字体文件名校验（纯函数）：单段文件名、无路径穿越、
     * 扩展名在可删除集合内。导入 id 是内容寻址（sha12＋扩展名），
     * 手动落盘的同扩展名文件同样可管。
     */
    fun isDeletableFileName(fileId: String): Boolean {
        if (fileId.isEmpty()) return false
        if (fileId.contains('/') || fileId.contains('\\') || fileId.contains("..")) {
            return false
        }
        val ext = fileId.substringAfterLast('.', "").lowercase()
        if (ext !in DELETABLE_EXTENSIONS) return false
        return fileId.substringBeforeLast('.', "").isNotEmpty()
    }

    /**
     * 删除后的引用清理（纯函数）：把引用该文件的所有角色改回跟随，
     * 返回（新选择表，被重置的角色）。文件不存在但有引用时同样清理，
     * 残留引用不留死角。
     */
    fun purgeReferences(
        choices: Map<FontRole, FontChoice>,
        fileId: String,
    ): Pair<Map<FontRole, FontChoice>, List<FontRole>> {
        val reset = choices.filter { (_, choice) ->
            choice is FontChoice.Imported && choice.fileId == fileId
        }.keys.toList()
        if (reset.isEmpty()) return choices to emptyList()
        val updated = choices.toMutableMap()
        reset.forEach { updated[it] = FontChoice.Follow }
        return updated to reset
    }

    /**
     * 列出已导入字体文件（管理列表用）：fonts 目录下四种扩展名的
     * 文件，按文件名排序，附引用角色。Jime 只有一个导入目录
     * （filesDir/fonts），Mie 的 custom_fonts＋rime fonts 两目录
     * 在此合为一处。
     */
    fun listImported(context: Context): List<ImportedFont> {
        val dir = fontsDir(context)
        val files = dir.listFiles()?.toList().orEmpty()
        val choices = FontRole.entries.associateWith { getChoice(context, it) }
        return files
            .filter { it.isFile && isDeletableFileName(it.name) }
            .sortedBy { it.name }
            .map { file ->
                ImportedFont(
                    fileId = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    usedBy = choices.filter { (_, choice) ->
                        choice is FontChoice.Imported && choice.fileId == file.name
                    }.keys.toList(),
                )
            }
    }

    /**
     * 删除已导入字体＋purge（Mie 增量①）：
     * 删文件（不存在也继续）→ 引用该文件的全部角色改回跟随
     * → 版本＋1（一次编辑提交）→ 清缓存（刷新 fallback）。
     * 文件名非法（路径穿越）直接拒绝，不动任何状态。
     */
    fun deleteImported(context: Context, fileId: String): DeleteResult {
        if (!isDeletableFileName(fileId)) return DeleteResult.Failed("invalid_name")
        val dir = fontsDir(context)
        val target = File(dir, fileId)
        val existed = target.isFile
        if (existed && !target.delete()) return DeleteResult.Failed("delete_failed")
        val choices = FontRole.entries.associateWith { getChoice(context, it) }
        val (_, reset) = purgeReferences(choices, fileId)
        if (existed || reset.isNotEmpty()) {
            val p = prefs(context)
            p.edit {
                reset.forEach { putString(keyFor(it), FontChoice.store(FontChoice.Follow)) }
                putInt(KEY_VERSION, p.getInt(KEY_VERSION, 0) + 1)
            }
            clearCaches()
            loggedMissingFiles.remove(fileId)
        }
        return if (existed) DeleteResult.Deleted(reset) else DeleteResult.NotFound(reset)
    }

    // ---- 解析 ----

    private val systemCache = LinkedHashMap<Pair<String, Int>, Typeface>()

    /**
     * 导入字体文件缓存键（第 5 批 §2）：文件 id + 字形 + 文件长度
     * + mtime——同名文件被替换后自动得新键，不再以旧文件 id
     * 命中 stale Typeface（对方 R17：路径 + mtime + 长度同款）。
     */
    private data class FileFontKey(
        val fileId: String,
        val style: Int,
        val lengthBytes: Long,
        val lastModifiedMs: Long,
    )

    private val fileCache = LinkedHashMap<FileFontKey, Typeface>()
    private val loggedUnknownSkinFamilies = mutableSetOf<String>()
    private val loggedMissingFiles = mutableSetOf<String>()
    private var cachedVersion = -1

    /**
     * 皮肤内嵌字体字节源（审计 C 项）：由快照安装时注入
     * （内容寻址资产文件，见 SkinSnapshotProvider），快照
     * 卸载／无内嵌字体时置 null。测试／预览可直接注入。
     */
    @Volatile
    var skinFontBytesProvider: ((String) -> ByteArray?)? = null
    private val skinFontCache = LinkedHashMap<Pair<String, Int>, Typeface>()
    private val loggedMissingSkinFonts = mutableSetOf<String>()

    @Synchronized
    fun clearCaches() {
        systemCache.clear()
        fileCache.clear()
        skinFontCache.clear()
        cachedVersion = -1
    }

    /** 快照切换时清空内嵌字体缓存与缺失日志（新皮肤同名资产可能已补齐）。 */
    @Synchronized
    fun clearSkinFontCache() {
        skinFontCache.clear()
        loggedMissingSkinFonts.clear()
    }

    /**
     * IO 预热（第 5 批 §2）：快照安装后在 IO 段把皮肤内嵌字体
     * 常用字形先建进缓存，使绘制期首次加载不加异步回调（否则
     * 闪烁，对方 R19 同款）；坏字体逐个返回 null、调用方继续
     * 下一级（整条链保留，见 typefaceFor），绝不拖垮整组回落。
     * 调用方必须在 IO 线程调本函数（Typeface.createFromFile
     * 不进 UI 线程）；失败静默（缺失／损坏已在 bundledTypeface
     * 内记一次日志，渲染回落下一级）。
     */
    @Synchronized
    fun prewarmSkinFonts(
        context: Context,
        snapshot: com.jobeen.ime.skin.model.SkinSnapshot,
    ) {
        val assetIds = snapshot.skinFontFaces.map { it.assetId }.distinct()
        for (assetId in assetIds) {
            runCatching { bundledTypeface(context, assetId, Typeface.NORMAL) }
            runCatching { bundledTypeface(context, assetId, Typeface.BOLD) }
        }
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
            val key = FileFontKey(fileId, style, file.length(), file.lastModified())
            fileCache.getOrPut(key) { Typeface.createFromFile(file) }
        } catch (e: Exception) {
            DiagLog.w(
                "font", "font_create_failed",
                mapOf("fileId" to fileId, "style" to style.toString()),
            )
            null
        }
    }

    /**
     * 皮肤候选值解析（审计 C 项）：内嵌字体名优先于系统字族名。
     * 内嵌字体字节缺失／损坏返回 null，调用方继续下一级（绝不
     * 以缺失字体占位）；系统族名经白名单复核，未知值返回 null
     * （调用方继续下一级，与旧“未知回落全局”同口径）。
     */
    private fun skinCandidateTypeface(
        context: Context,
        snapshot: com.jobeen.ime.skin.model.SkinSnapshot,
        candidate: String,
        style: Int,
    ): Typeface? {
        // P5：同名字族按字重匹配——请求粗体取字重 ≥600 的文件，
        // 请求常规取字重 <600 的文件；未声明字重的文件匹配任意
        // 请求（不过滤）。无字重匹配时返回 null，调用方继续下
        // 一候选（不以错字重文件占位）。
        val faces = snapshot.skinFontFaces.filter {
            it.name.equals(candidate.trim(), ignoreCase = true)
        }
        val bold = (style and Typeface.BOLD) != 0
        val face = faces.firstOrNull { face -> fontWeightMatches(face.weight, bold) }
        if (face != null) return bundledTypeface(context, face.assetId, style)
        val family = SkinFonts.resolveFamily(candidate) ?: return null
        return systemTypeface(family, style)
    }

    /**
     * 内嵌字体 Typeface（审计 C 项）：字节经注入源取，落盘到
     * cacheDir/skin-fonts/<内容哈希>.bin 后 createFromFile（与
     * 全局导入同款建族口径，minSdk 24 下无 ByteBuffer 方案）。
     * 字节缺失／建族失败返回 null（调用方回落下一级），只记
     * 资产 id，不记字体内容；缓存文件按内容哈希命名，旧皮肤
     * 残留文件不影响新皮肤（cacheDir 由系统按需清理）。
     */
    private fun bundledTypeface(context: Context, assetId: String, style: Int): Typeface? {
        skinFontCache[assetId to style]?.let { return it }
        val bytes = runCatching { skinFontBytesProvider?.invoke(assetId) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            if (loggedMissingSkinFonts.add(assetId)) {
                DiagLog.w("font", "skin_font_missing", mapOf("asset" to assetId))
            }
            return null
        }
        return try {
            val dir = File(context.cacheDir, "skin-fonts")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, sha12(bytes) + ".bin")
            if (!file.isFile) file.writeBytes(bytes)
            val typeface = Typeface.createFromFile(file)
            skinFontCache[assetId to style] = typeface
            typeface
        } catch (e: Exception) {
            if (loggedMissingSkinFonts.add(assetId)) {
                DiagLog.w(
                    "font", "skin_font_invalid",
                    mapOf("asset" to assetId, "reason" to (e.javaClass.simpleName ?: "unknown")),
                )
            }
            null
        }
    }

    /**
     * 区间段字族 Typeface（审计 C 项，渲染层按段调用）：
     * family 为内嵌名 → 内嵌字体（字节缺失／损坏返回 null，
     * 调用方用基础字体）；系统名 → 系统字族；未知 → null。
     * 快照外（无皮肤）时只认系统字族名。
     */
    @Synchronized
    fun typefaceForFamily(context: Context, family: String, style: Int): Typeface? {
        val snapshot = SkinSnapshotProvider.peek()
        if (snapshot != null) {
            return skinCandidateTypeface(context, snapshot, family, style)
        }
        return SkinFonts.resolveFamily(family)?.let { systemTypeface(it, style) }
    }

    /**
     * 角色字族 Typeface；null＝调用方用系统默认。
     * 分层（2026-10-29 item-1，审计 C 项扩展）：皮肤逐角色 ＞
     * 皮肤 fontFamily ＞ 全局逐角色 ＞ 系统默认；每层候选值先
     * 试内嵌字体、再试系统字族，内嵌缺失／未知族名都继续下
     * 一级。未知皮肤族名（既非内嵌名、亦非系统名）记一次 WARN
     * （只记族名字符串，不记输入内容）。
     */
    @Synchronized
    fun typefaceFor(context: Context, role: FontRole, style: Int): Typeface? {
        val v = version(context)
        if (v != cachedVersion) {
            systemCache.clear()
            fileCache.clear()
            skinFontCache.clear()
            cachedVersion = v
        }
        val snapshot = SkinSnapshotProvider.peek()
        if (snapshot != null) {
            // 审计 C 项：按候选顺序试取，首个可解析者胜出；
            // 内嵌缺失／未知族名都继续下一候选、下一级。
            // 旧手工快照可能只填单值字段：列表为空时回落单值。
            val roleCandidates = snapshot.roleFontFamilies[role]
                ?: snapshot.roleFonts[role]?.let { listOf(it) } ?: emptyList()
            for (candidate in roleCandidates) {
                skinCandidateTypeface(context, snapshot, candidate, style)?.let { return it }
            }
            val familyCandidates = snapshot.fontFamilies.ifEmpty {
                snapshot.fontFamily?.let { listOf(it) } ?: emptyList()
            }
            for (candidate in familyCandidates) {
                skinCandidateTypeface(context, snapshot, candidate, style)?.let { return it }
            }
        }
        val rawSkin = snapshot?.fontFamily
        val isFace = rawSkin != null && snapshot?.skinFontFaces?.any {
            it.name.equals(rawSkin.trim(), ignoreCase = true)
        } == true
        if (rawSkin != null && rawSkin.isNotBlank() &&
            !rawSkin.trim().equals("default", ignoreCase = true) &&
            !isFace && SkinFonts.resolveFamily(rawSkin) == null &&
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

    /** 角色生效说明（纯解析，可单测）：皮肤内嵌／皮肤逐角色／皮肤族名／全局选择／系统默认。 */
    fun effectiveSource(
        skinFamilyRaw: String?,
        choice: FontChoice,
        roleFonts: Map<FontRole, String> = emptyMap(),
        role: FontRole? = null,
        /** 皮肤声明的内嵌字体名（声明原文；匹配大小写不敏感）。 */
        skinFaceNames: List<String> = emptyList(),
        /** 审计 C 项：完整候选顺序（空时回落单值字段，同 typefaceFor）。 */
        skinFamilyCandidates: List<String> = emptyList(),
        roleFontCandidates: Map<FontRole, List<String>> = emptyMap(),
    ): EffectiveSource {
        val roleCandidateList = roleFontCandidates[role]
            ?: role?.let { roleFonts[it] }?.let { listOf(it) } ?: emptyList()
        for (candidate in roleCandidateList) {
            SkinFonts.findFaceName(candidate, skinFaceNames)?.let {
                return EffectiveSource.SkinBundled(it)
            }
            SkinFonts.resolveFamily(candidate)?.let {
                return EffectiveSource.SkinRole(it)
            }
        }
        val familyCandidateList = skinFamilyCandidates.ifEmpty {
            skinFamilyRaw?.let { listOf(it) } ?: emptyList()
        }
        for (candidate in familyCandidateList) {
            SkinFonts.findFaceName(candidate, skinFaceNames)?.let {
                return EffectiveSource.SkinBundled(it)
            }
            SkinFonts.resolveFamily(candidate)?.let {
                return EffectiveSource.Skin(it)
            }
        }
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
        /** 皮肤内嵌字体（审计 C 项）：声明名，高于同层系统字族名。 */
        data class SkinBundled(val name: String) : EffectiveSource()
        data class Global(val family: String) : EffectiveSource()
        data class GlobalFile(val fileId: String) : EffectiveSource()
        data object Default : EffectiveSource()
    }
}
