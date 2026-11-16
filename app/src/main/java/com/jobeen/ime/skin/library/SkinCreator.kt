// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import android.content.Context
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.keyboard.theme.KeyboardThemePresets
import com.jobeen.ime.data.keyboard.theme.MonetPalette
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.model.StyleField
import com.jobeen.ime.skin.pkg.SkinPackages
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibrary
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 库内新建皮肤（2026-11-16 皮肤编辑器修法 1(a)＋2）：
 *
 * 摆设的根因是编辑器只能编已生效的 v3 库皮肤，而手机上从来没有过
 * v3 皮肤——内置主题根本不是 v3 皮肤，第一个皮肤也生不出来。
 * 本类打通 bootstrap：把当前内置主题复制为新皮肤（a），或新建
 * 空白皮肤（b），合成 formatVersion: 3 的 skin.json＋manifest.json，
 * 经 [ImportTransaction] 入库（原子代次＋索引，与导入同口径），
 * 调用方再用 [com.jobeen.ime.skin.runtime.SkinApplier] 激活，
 * 编辑器随即能打开。
 *
 * 合成口径：
 * - styles / stylesDark：按 [StyleField] 注册表逐字段取
 *   [StyleField.rawValueIn]（null 字段不写，保留“未指定”语义），
 *   颜色写 #AARRGGBB（[SkinJson.formatColor]）；无布局绑定
 *   （不写 layoutId / keyboards，运行期回退出厂布局）。
 * - meta.id 须与清单 skin 条目 id 一致（导入期 skin_meta_id_mismatch
 *   门）；meta.name 为库内显示名。
 * - 入库前先跑 [SkinValidator.validate]（与导入同口径），拒绝即
 *   早失败，不把坏皮肤写进库。
 */
object SkinCreator {

    sealed interface Result {
        data class Created(val skinId: String, val name: String, val ref: SkinGenRef) : Result
        data class Failed(val reason: String) : Result
    }

    /** 双色板：浅色 styles / 深色 stylesDark 的取值来源。 */
    data class Boards(val light: KeyboardColors.ColorScheme, val dark: KeyboardColors.ColorScheme)

    /**
     * 内置预设双色板（Android 侧解析：读 [KeyboardThemePresets]／
     * [MonetPalette]，其静态初始化调 android.graphics.Color，JVM
     * 单测不可用——纯函数一律经此参数传入，不直接读全局预设）。
     */
    data class PresetBoards(
        val defaultLight: KeyboardColors.ColorScheme,
        val defaultDark: KeyboardColors.ColorScheme,
        val monetLight: KeyboardColors.ColorScheme,
        val monetDark: KeyboardColors.ColorScheme,
    ) {
        companion object {
            fun current(): PresetBoards = PresetBoards(
                defaultLight = KeyboardThemePresets.Light.colors,
                defaultDark = KeyboardThemePresets.Amoled.colors,
                monetLight = MonetPalette.LightFallback,
                monetDark = MonetPalette.DarkFallback,
            )
        }
    }

    /**
     * 当前内置主题 id 对应的双色板。
     * - 默认家族（素白 light／暗夜 amoled）：双槽互补，浅＝素白、深＝暗夜；
     * - 莫奈：取自身深浅回退；
     * - 其余：两板取自身配色（深色板缺失时复制浅色板，与转换器同口径）。
     */
    fun boardsFor(theme: KeyboardTheme, presets: PresetBoards): Boards = when (theme.id) {
        KeyboardTheme.LIGHT_DEFAULT_ID, KeyboardTheme.DARK_DEFAULT_ID -> Boards(
            light = presets.defaultLight,
            dark = presets.defaultDark,
        )
        MonetPalette.ID -> Boards(
            light = presets.monetLight,
            dark = presets.monetDark,
        )
        else -> Boards(light = theme.colors, dark = theme.colors)
    }

    /** 复制所得皮肤的 id 家族：默认家族统称 default，莫奈 monet，其余取主题 id。 */
    fun copyIdFamily(theme: KeyboardTheme): String = when (theme.id) {
        KeyboardTheme.LIGHT_DEFAULT_ID, KeyboardTheme.DARK_DEFAULT_ID -> "default"
        MonetPalette.ID -> "monet"
        else -> theme.id
    }

    /**
     * 合成 skin.json 原文（formatVersion 3；无布局绑定）。纯函数。
     *
     * styles / stylesDark 按 [StyleField] 逐字段展开：顶层字段平铺，
     * `panel.*` / `pinner.*` 嵌套进同名对象。
     */
    fun buildSkinJson(skinId: String, name: String, boards: Boards): String {
        val tree = buildJsonObject {
            put("formatVersion", SkinValidator.SUPPORTED_FORMAT_VERSION)
            put("meta", buildJsonObject {
                put("id", skinId)
                put("name", name)
                put("author", "BIN")
                put("version", "1.0")
            })
            put("styles", stylesObject(boards.light))
            put("stylesDark", stylesObject(boards.dark))
        }
        return prettyJson.encodeToString(JsonObject.serializer(), tree)
    }

    private fun stylesObject(scheme: KeyboardColors.ColorScheme): JsonObject {
        val flat = mutableMapOf<String, String>()
        val panel = mutableMapOf<String, String>()
        val pinner = mutableMapOf<String, String>()
        for (field in StyleField.entries) {
            val raw = field.rawValueIn(scheme) ?: continue
            val color = SkinJson.formatColor(raw)
            val parts = field.path.split(".")
            when {
                parts.size == 2 && parts[0] == "panel" -> panel[parts[1]] = color
                parts.size == 2 && parts[0] == "pinner" -> pinner[parts[1]] = color
                parts.size == 1 -> flat[parts[0]] = color
                else -> flat[field.path] = color
            }
        }
        return buildJsonObject {
            for ((k, v) in flat) put(k, v)
            if (panel.isNotEmpty()) {
                put("panel", buildJsonObject {
                    for ((k, v) in panel) put(k, v)
                })
            }
            if (pinner.isNotEmpty()) {
                put("pinner", buildJsonObject {
                    for ((k, v) in pinner) put(k, v)
                })
            }
        }
    }

    /** 合成单皮肤清单原文（skin.json 一项，sha256 对最终字节现算）。纯函数。 */
    fun buildManifestJson(skinId: String, skinBytes: ByteArray): String {
        val manifest = PackageManifest(
            formatVersion = SkinValidator.SUPPORTED_FORMAT_VERSION,
            contents = listOf(
                PackageManifest.Content(
                    type = PackageManifest.TYPE_SKIN,
                    id = skinId,
                    path = "skin.json",
                    sha256 = DigestUtil.sha256Hex(skinBytes),
                    sizeBytes = skinBytes.size.toLong(),
                ),
            ),
        )
        return PackageManifest.toJson(manifest)
    }

    /**
     * 复制当前内置主题为新皮肤（入库，不激活）。
     * [displayName] 为库内显示的主题名（如“默认”／“莫奈色”），
     * 新皮肤名为“<displayName> 副本”。
     */
    suspend fun copyBuiltinTheme(
        context: Context,
        theme: KeyboardTheme,
        displayName: String,
        presets: PresetBoards,
    ): Result {
        val family = copyIdFamily(theme)
        val nameBase = "$displayName 副本"
        return create(
            context = context,
            idBase = "builtin-$family",
            nameBase = nameBase,
            boards = boardsFor(theme, presets),
        )
    }

    /** 新建空白皮肤（默认双色板：浅＝素白、深＝暗夜；无布局绑定；入库，不激活）。 */
    suspend fun createBlankSkin(context: Context, presets: PresetBoards): Result = create(
        context = context,
        idBase = "blank-skin",
        nameBase = "新建皮肤",
        boards = Boards(
            light = presets.defaultLight,
            dark = presets.defaultDark,
        ),
    )

    private suspend fun create(
        context: Context,
        idBase: String,
        nameBase: String,
        boards: Boards,
    ): Result {
        val store = SkinLibrary.store
        val layout = SkinLibrary.layout
        val existing = when (val read = store.readIndex()) {
            is SkinLibraryStore.ReadResult.Ok -> read.value.skins
            else -> emptyList()
        }
        val existingIds = existing.map { it.id }.toSet()
        val existingNames = existing.map { it.name }.toSet()
        val skinId = uniqueId(idBase, existingIds)
        val name = uniqueName(nameBase, existingNames)
        val skinBytes = buildSkinJson(skinId, name, boards).encodeToByteArray()
        val skinTree = SkinJson.parseObject(skinBytes.decodeToString())
            ?: return Result.Failed("skin_unparseable")
        // 入库前校验（与导入同口径）：拒绝即早失败，不写坏皮肤进库。
        val importCtx = SkinPackages.importContext(context)
        when (
            val validation = SkinValidator.validate(
                tree = skinTree,
                currentVersionCode = importCtx.currentVersionCode,
                fallbacks = importCtx.fallbacks,
                assetKind = { null },
            )
        ) {
            is SkinValidator.Result.Rejected -> return Result.Failed(validation.reason)
            is SkinValidator.Result.Valid -> Unit
        }
        val manifestJson = buildManifestJson(skinId, skinBytes)
        val hash = DigestUtil.sha256Hex(skinBytes)
        val tx = ImportTransaction(layout, store)
        return when (
            val result = tx.importPackage { staging ->
                val skinDir = File(staging, "skins/$skinId").apply { mkdirs() }
                File(skinDir, "skin.json").writeBytes(skinBytes)
                File(skinDir, "manifest.json").writeText(manifestJson)
                ImportTransaction.BuiltPackage(
                    skins = listOf(
                        ImportTransaction.StagedSkin(
                            id = skinId, name = name, dir = skinDir, hash = hash,
                        ),
                    ),
                )
            }
        ) {
            is ImportTransaction.Result.Imported -> {
                val entry = result.skins.firstOrNull()
                    ?: return Result.Failed("skin_create_failed")
                Result.Created(skinId, name, SkinGenRef(entry.id, entry.hash, entry.generation))
            }
            is ImportTransaction.Result.Failed ->
                Result.Failed("transaction:${result.stage}:${result.reason}")
        }
    }

    /** id 去重：base、base-2、base-3…（同 id 同 hash 会走更新代次，复制须新 id）。 */
    internal fun uniqueId(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        for (n in 2..99) {
            val candidate = "$base-$n"
            if (candidate !in existing) return candidate
        }
        return "$base-${System.currentTimeMillis()}"
    }

    /** 显示名去重：base、base 2、base 3… */
    internal fun uniqueName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        for (n in 2..99) {
            val candidate = "$base $n"
            if (candidate !in existing) return candidate
        }
        return "$base ${System.currentTimeMillis()}"
    }

    private val prettyJson = Json { prettyPrint = true }
}
