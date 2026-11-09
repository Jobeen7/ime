// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import android.content.Context
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 26 键字母键角标符号（施工单 10 H2-14，CyIME D25/H3「按键符号设置页重做」）：
 *
 * - 出厂 26 键每个字母键右上角一个标点符号（AltText 角标），长按提交它；
 *   本页让用户逐键改这个角标符号（CyIME 的 QwertySymbolEditor 同款：
 *   键盘式预览、点键改、改完即保存、无保存按钮、恢复默认收在页内）。
 * - 只存与出厂不同的位置（CyIME 同口径）；改回出厂值即删覆盖。
 * - 生效：QwertyKeyboard.effectiveLayout 在皮肤覆写装配前套用覆盖
 *   （copyWith 无皮肤写入时保留 AltText 与 LongPress 槽，故用户覆盖
 *   优先于皮肤文件、劣后于皮肤显式 subLabel/longPress）；服务侧偏好
 *   监听到改动后走显示刷新重建键盘，与外观轮同口径，下次打开即生效。
 * - 范围：只做 26 键字母键。九宫格数字键角标是键身份（数字本身）、
 *   数字／符号／自创布局键盘不在此页。
 * - 本文件纯逻辑＋偏好读写，不碰 View；单测直接覆盖。
 */
object KeySymbolOps {

    /** 出厂角标表：与 QwertyKeyboard.buildLayout 的 alphabetKey 逐字一致。 */
    val DEFAULT_SYMBOLS: Map<Char, String> = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
        'a' to "~", 's' to "!", 'd' to "@", 'f' to "#", 'g' to "$",
        'h' to "%", 'j' to "^", 'k' to "&", 'l' to "*",
        'z' to "(", 'x' to ")", 'c' to ":", 'v' to ";", 'b' to ",",
        'n' to "?", 'm' to "/",
    )

    /** 键盘行：三行字母键（与出厂布局行一致），设置页迷你键盘与之一致。 */
    val ROWS: List<List<Char>> = listOf(
        "qwertyuiop".toList(),
        "asdfghjkl".toList(),
        "zxcvbnm".toList(),
    )

    const val PREFS_NAME = "key_symbols"
    const val KEY_CONFIG = "overrides"

    /** 生效符号：有自定义取自定义，否则出厂；未知字母返回 null。 */
    fun resolve(letter: Char, overrides: Map<Char, String>): String? {
        val lower = letter.lowercaseChar()
        if (lower !in DEFAULT_SYMBOLS) return null
        return overrides[lower] ?: DEFAULT_SYMBOLS.getValue(lower)
    }

    /**
     * 合法符号：去空白后恰好一个码点（codePointCount==1，与
     * RareCharPolicy.isRareChar 同口径）。emoji 与补充平面汉字
     * （UTF-16 代理对，length==2）可存；ZWJ 序列／组合附加符是
     * 多码点，仍不支持。角标格只容一字，多字会溢出裁切。
     */
    fun isValidSymbol(symbol: String): Boolean =
        isSingleCodePoint(symbol.trim())

    /** 单个码点判定（供 isValidSymbol／set／parse 共用）。 */
    private fun isSingleCodePoint(text: String): Boolean =
        text.codePointCount(0, text.length) == 1

    /**
     * 改一键：非法符号原样返回；改回出厂值即删覆盖（只存不同位置）；
     * 未知字母原样返回。
     */
    fun set(overrides: Map<Char, String>, letter: Char, symbol: String): Map<Char, String> {
        val lower = letter.lowercaseChar()
        if (lower !in DEFAULT_SYMBOLS) return overrides
        val clean = symbol.trim()
        if (!isSingleCodePoint(clean)) return overrides
        if (clean == DEFAULT_SYMBOLS.getValue(lower)) return overrides - lower
        return overrides + (lower to clean)
    }

    /** 恢复默认：清掉全部覆盖。 */
    fun reset(): Map<Char, String> = emptyMap()

    fun serialize(overrides: Map<Char, String>): String =
        Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                overrides.forEach { (letter, symbol) -> put(letter.toString(), symbol) }
            },
        )

    /**
     * 解析：损坏／非对象时回落空；逐项丢弃非法项（键非单个 a–z 字母、
     * 值非单个码点）。未知字段容忍。
     */
    fun parse(raw: String?): Map<Char, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            buildMap {
                for ((key, value) in root) {
                    if (key.length != 1) continue
                    val letter = key[0]
                    if (letter !in 'a'..'z') continue
                    val symbol = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?: continue
                    if (!isSingleCodePoint(symbol)) continue
                    put(letter, symbol)
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 布局套用：字母键（AltText、主显为单个小写字母）按覆盖改角标
     * 与长按提交动作；其余键原样返回。空覆盖时原样返回整布局。
     */
    fun applyToLayout(
        layout: List<List<KeyDef>>,
        overrides: Map<Char, String>,
    ): List<List<KeyDef>> {
        if (overrides.isEmpty()) return layout
        return layout.map { row -> row.map { def -> withSymbol(def, overrides) } }
    }

    private fun withSymbol(def: KeyDef, overrides: Map<Char, String>): KeyDef {
        val appearance = def.appearance as? KeyDef.Appearance.AltText ?: return def
        val text = appearance.displayText
        if (text.length != 1) return def
        val letter = text[0]
        if (letter !in 'a'..'z') return def
        val symbol = overrides[letter] ?: return def
        val newAppearance = KeyDef.Appearance.AltText(
            displayText = text,
            altText = symbol,
            altTextTranslationY = appearance.altTextTranslationY,
            mainTextTranslationY = appearance.mainTextTranslationY,
            altTextSize = appearance.altTextSize,
            textSize = appearance.textSize,
            textStyle = appearance.textStyle,
            percentWidth = appearance.percentWidth,
            variant = appearance.variant,
            border = appearance.border,
            margin = appearance.margin,
            viewId = appearance.viewId,
            visibility = appearance.visibility,
            rowSpan = appearance.rowSpan,
            alignSpanEnd = appearance.alignSpanEnd,
            spanCol = appearance.spanCol,
            spanColEnd = appearance.spanColEnd,
        )
        // 皮肤装配期写的槽此时尚未写入（装配在套用之后跑）；出厂 def
        // 的四个 var 槽恒为默认，照抄即可，不丢信息。
        val newDef = KeyDef(
            appearance = newAppearance,
            behaviors = def.behaviors.map { behavior ->
                if (behavior is KeyDef.Behavior.LongPress) {
                    KeyDef.Behavior.LongPress(KeyboardAction.CommitAction(symbol))
                } else {
                    behavior
                }
            }.toSet(),
            popups = def.popups,
            id = def.id,
        )
        newDef.skinSoundAssetId = def.skinSoundAssetId
        newDef.skinSoundMuted = def.skinSoundMuted
        newDef.skinStyleRef = def.skinStyleRef
        newDef.skinStyleOverrides = def.skinStyleOverrides
        return newDef
    }
}

/** 按键符号覆盖存储：加载失败即默认（空覆盖＝全部出厂）。 */
interface KeySymbolStore {
    fun load(): Map<Char, String>
    fun save(overrides: Map<Char, String>)
}

/** 内存实现（默认；单测用）。 */
class InMemoryKeySymbolStore(
    initial: Map<Char, String> = emptyMap(),
) : KeySymbolStore {
    private var stored = initial
    override fun load(): Map<Char, String> = stored
    override fun save(overrides: Map<Char, String>) {
        stored = overrides
    }
}

/** 偏好实现：独立 `key_symbols` 偏好文件，覆盖以 JSON 串存一键。 */
class SharedPrefsKeySymbolStore(context: Context) : KeySymbolStore {

    private val prefs = context.getSharedPreferences(
        KeySymbolOps.PREFS_NAME, Context.MODE_PRIVATE,
    )

    override fun load(): Map<Char, String> =
        KeySymbolOps.parse(prefs.getString(KeySymbolOps.KEY_CONFIG, null))

    override fun save(overrides: Map<Char, String>) {
        prefs.edit().putString(KeySymbolOps.KEY_CONFIG, KeySymbolOps.serialize(overrides)).apply()
    }
}
