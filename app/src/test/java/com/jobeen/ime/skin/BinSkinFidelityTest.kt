package com.jobeen.ime.skin

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.skin.layout.LayoutKeyDefBuilder
import com.jobeen.ime.skin.layout.LayoutValidation
import com.jobeen.ime.skin.layout.LayoutValidator
import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.ResolvedForegroundLayer
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/**
 * Bin 皮肤保真度 harness（2026-11-16）：用 Jobeen 发来的真实 .jime
 *（bin-phone-pad.jime，随测试资源交付）跑 校验 → 快照 → 布局装配 全管线，
 *逐项断言 demo.png（Bin v1.9）应有的值。
 *
 * 定位逻辑：失败项即装配层真相——若装配值已对、真机仍错，根因在渲染层
 *（KeyView）；若装配值已错，根因在装配/解析层。真机渲染层另行核对。
 *
 * 哨兵默认方案：keyBackground 用 0xFF101010（与皮肤白 #FFFFFFFF、
 * 压下色 #FFE6E6E6 均不同），装配值若回落到哨兵即样式解析失效。
 */
class BinSkinFidelityTest {

    // ---------- 夹具：真实 .jime ----------

    private fun binJimeEntries(): Map<String, String> {
        val stream = requireNotNull(javaClass.classLoader)
            .getResourceAsStream("bin-skin/bin-phone-pad.jime")
            ?: error("missing test resource bin-skin/bin-phone-pad.jime")
        val out = mutableMapOf<String, String>()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        return out
    }

    /** manifest contents 里 type=asset 的 id → sha256（接纳口径的输入）。 */
    private fun assetShaMap(entries: Map<String, String>): Map<String, String> {
        val manifest = Json.parseToJsonElement(entries.getValue("manifest.json")).jsonObject
        return manifest.getValue("contents").jsonArray.mapNotNull { item ->
            val obj = item.jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "asset") return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val sha = obj["sha256"]?.jsonPrimitive?.content ?: return@mapNotNull null
            id to sha
        }.toMap()
    }

    private val defaultScheme = KeyboardColors.ColorScheme(
        keyBackground = 0xFF101010.toInt(), keyPressed = 0xFF202020.toInt(),
        keyBorderStroke = 0xFF303030.toInt(),
        specialKeyBackground = 0xFF101010.toInt(),
        specialKeyPressed = 0xFF202020.toInt(),
        specialKeyBorderStroke = 0xFF303030.toInt(),
        accentKeyBackground = 0xFF101010.toInt(),
        accentKeyPressed = 0xFF202020.toInt(),
        accentKeyBorderStroke = 0xFF303030.toInt(),
        keyText = 0xFFEEEEEE.toInt(), specialKeyText = 0xFFEEEEEE.toInt(),
        accentKeyText = 0xFFEEEEEE.toInt(), altText = 0xFFAAAAAA.toInt(),
        background = 0xFF000000.toInt(), cornerRadius = 5f, keyHMargin = 3f,
        keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 0xFF000000.toInt(), toolbarText = 0xFFEEEEEE.toInt(),
            toolbarActived = 0xFFEEEEEE.toInt(), toolbarIcon = 0xFFEEEEEE.toInt(),
            candidateBackground = 0xFF000000.toInt(),
            candidateText = 0xFFEEEEEE.toInt(), candidateIndex = 0xFFAAAAAA.toInt(),
            candidateDivider = 0xFF303030.toInt(), toolbarPressed = 0xFF202020.toInt(),
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 0xFF000000.toInt(), textColor = 0xFFEEEEEE.toInt(),
            secondaryTextColor = 0xFFAAAAAA.toInt(),
        ),
        toastBackground = 0xFF202020.toInt(), toastText = 0xFFEEEEEE.toInt(),
    )

    private fun buildSnapshot(entries: Map<String, String>): SkinSnapshot {
        val tree = Json.parseToJsonElement(entries.getValue("skin.json")) as JsonObject
        val assets = assetShaMap(entries)
        val result = SkinValidator.validate(
            tree, 216, GeometryFallbacks(12f, 6f, 5f, 10f),
            assetKind = { id -> if (id in assets) AssetKind.IMAGE else null },
        )
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        val validated = (result as SkinValidator.Result.Valid).skin
        return SkinSnapshotBuilder.build(
            validated, SkinGenRef("bin-phone-pad", "b".repeat(64), 1),
            defaultScheme, MemoryTier.NORMAL,
            assetSha256 = { id -> assets[id] },
            targetWidthPx = 1080, targetHeightPx = 2400,
        )
    }

    private fun assembled(
        entries: Map<String, String>,
        snapshot: SkinSnapshot,
        layoutFile: String,
    ): List<List<KeyDef>> {
        val tree = Json.parseToJsonElement(entries.getValue(layoutFile)) as JsonObject
        val layoutId = tree["meta"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val result = LayoutValidator.validate(tree, layoutId)
        assertTrue("expected Valid layout, got $result", result is LayoutValidation.Valid)
        val factory = LayoutKeyDefBuilder.build(
            (result as LayoutValidation.Valid).layout, emptyMap(),
        )
        return KeyOverrideApplier.apply(
            "custom:$layoutId", factory, snapshot, emptyMap(),
            scheme = snapshot.effectiveColorScheme(isDark = false),
        )
    }

    private fun findKey(
        rows: List<List<KeyDef>>,
        logicalId: String,
    ): KeyDef = rows.flatten().firstOrNull { it.id == logicalId }
        ?: error("key $logicalId not found")

    private fun textOf(def: KeyDef): String =
        (def.appearance as KeyDef.Appearance.Text).displayText

    private fun layersOf(def: KeyDef): List<ResolvedForegroundLayer> =
        def.appearance.skinForegroundLayers.orEmpty()

    // ---------- ② 空格键 imageRef ----------

    @Test
    fun spaceImageRef_acceptedIntoImageAssets() {
        val entries = binJimeEntries()
        val snapshot = buildSnapshot(entries)
        // 预修复失败：SkinSnapshotBuilder 只收字符串形态 image，
        // 从不收 imageRef?.file（arsenal-cannon 从没进 imageAssets）。
        assertTrue(
            "imageAssets should contain arsenal-cannon, got ${snapshot.imageAssets.keys}",
            "arsenal-cannon" in snapshot.imageAssets,
        )
        assertTrue(
            "imageAssets should contain kaomoji-key",
            "kaomoji-key" in snapshot.imageAssets,
        )
    }

    @Test
    fun assembledSpaceKey_hasSpriteRef() {
        val entries = binJimeEntries()
        val snapshot = buildSnapshot(entries)
        for (layoutId in listOf("bin-pinyin", "bin-alphabetic")) {
            val rows = assembled(entries, snapshot, "layouts/$layoutId.json")
            val space = findKey(rows, "$layoutId:K-space")
            val face = space.appearance.skinFaceSpriteRef
            assertNotNull("$layoutId space face spriteRef", face)
            assertEquals("arsenal-cannon", face!!.file)
            assertEquals("cannon", face.image)
            val pressed = space.appearance.skinPressedFaceSpriteRef
            assertNotNull("$layoutId space pressed spriteRef", pressed)
            assertEquals("arsenal-cannon", pressed!!.file)
        }
    }

    // ---------- ③ 配色 ----------

    @Test
    fun schemeKeyBackground_isSkinWhite() {
        val snapshot = buildSnapshot(binJimeEntries())
        // skin.json styles.keyBackground = #FFFFFFFF（demo 浅色键面白）。
        assertEquals(0xFFFFFFFF.toInt(), snapshot.colorScheme.keyBackground)
    }

    @Test
    fun keyStyleS1Face_isWhite() {
        val snapshot = buildSnapshot(binJimeEntries())
        // keyStyles.s1.face = #FFFFFFFF；pressed = #FFE6E6E6（实拍 #E6E6E6）。
        assertEquals("#FFFFFFFF", snapshot.keyStyles["s1"]?.get(KeyStyleRole.FACE))
        assertEquals("#FFE6E6E6", snapshot.keyStyles["s1"]?.get(KeyStyleRole.PRESSED))
    }

    @Test
    fun assembledT91Face_isWhite() {
        val entries = binJimeEntries()
        val snapshot = buildSnapshot(entries)
        val rows = assembled(entries, snapshot, "layouts/bin-pinyin.json")
        val t91 = findKey(rows, "bin-pinyin:K-t91")
        // 装配后键面色应为皮肤白；若为 0xFFE6E6E6 = 面/压互换，
        // 若为哨兵 0xFF101010 = 样式解析回落。
        assertEquals(0xFFFFFFFF.toInt(), t91.appearance.skinFaceColor)
    }

    @Test
    fun assembledDigitHint_isAboveMainLabel() {
        val entries = binJimeEntries()
        val snapshot = buildSnapshot(entries)
        val rows = assembled(entries, snapshot, "layouts/bin-pinyin.json")
        val t91 = findKey(rows, "bin-pinyin:K-t91")
        // 对照组：数字提示层 biasY 0.22（实拍中 1–9 正常显示在主标签上方）。
        val hint = layersOf(t91).firstOrNull { it.text == "1" }
        assertNotNull("t91 digit hint layer", hint)
        assertEquals(0.22f, hint!!.biasY, 0.001f)
    }

    // ---------- ④ 方案切换键 ----------

    @Test
    fun assembledSchemeSwitch_foregroundBelowMainLabel() {
        val entries = binJimeEntries()
        val snapshot = buildSnapshot(entries)
        // T9 布局：主标签“中”，前景层“/En”应偏下（biasY 0.67）；
        // 英文布局：主标签“En”，前景层“/中”同样偏下。
        val t9 = assembled(entries, snapshot, "layouts/bin-pinyin.json")
        val toEn = layersOf(findKey(t9, "bin-pinyin:K-alphabetic"))
            .firstOrNull { it.text == "/En" }
        assertNotNull("bin-pinyin /En layer", toEn)
        assertEquals(0.5f, toEn!!.biasX, 0.001f)
        assertEquals(0.67f, toEn.biasY, 0.001f)

        val en = assembled(entries, snapshot, "layouts/bin-alphabetic.json")
        val toZh = layersOf(findKey(en, "bin-alphabetic:K-pinyin"))
            .firstOrNull { it.text == "/中" }
        assertNotNull("bin-alphabetic /中 layer", toZh)
        assertEquals(0.67f, toZh!!.biasY, 0.001f)
    }

    @Test
    fun assembledSchemeSwitch_mainLabelMirroredAbove() {
        val entries = binJimeEntries()
        val snapshot = buildSnapshot(entries)
        // ④ 双行避让：前景层在下（0.67）时主标签镜像到上半（1-0.67=0.33），
        // 与前景层拉开、不重叠；只有上半数字提示层的键不动（仍居中）。
        val t9 = assembled(entries, snapshot, "layouts/bin-pinyin.json")
        assertEquals(
            0.33f,
            findKey(t9, "bin-pinyin:K-alphabetic").appearance.skinContentBiasY!!,
            0.001f,
        )
        assertNull(findKey(t9, "bin-pinyin:K-t91").appearance.skinContentBiasY)
        val en = assembled(entries, snapshot, "layouts/bin-alphabetic.json")
        assertEquals(
            0.33f,
            findKey(en, "bin-alphabetic:K-pinyin").appearance.skinContentBiasY!!,
            0.001f,
        )
    }

    // ---------- ⑤ 英文键盘（装配层标签真相） ----------

    @Test
    fun assembledCommaLabels_matchLayout() {
        val entries = binJimeEntries()
        val snapshot = buildSnapshot(entries)
        // 装配层标签：T9 逗号 ","、英文句号 "."。
        // 实拍英文句号显 "。" 是显示层全角模式转换（.→。，
        // PunctuationMode.half2full），与皮肤数据无关，不在此断言。
        val t9 = assembled(entries, snapshot, "layouts/bin-pinyin.json")
        assertEquals(",", textOf(findKey(t9, "bin-pinyin:K-comma")))
        val en = assembled(entries, snapshot, "layouts/bin-alphabetic.json")
        assertEquals(".", textOf(findKey(en, "bin-alphabetic:K-comma")))
    }

    // ---------- ① 左侧符号栏（demo：tall 键四符号 `,.?!` 纵向排布） ----------

    @Test
    fun t9SymbolsCollection_rendersFourSymbolsWithZones() {
        val entries = binJimeEntries()
        val snapshot = buildSnapshot(entries)
        val rows = assembled(entries, snapshot, "layouts/bin-pinyin.json")
        val tall = findKey(rows, "bin-pinyin:K-t9SymbolsCollection")
        // 布局键本身仍是空键（label ""、tap none），符号由装配期按 demo
        // 合成：四前景文字层纵向四等分居中 + 分区标记（渲染层按抬手 Y 落区提交）。
        assertEquals("", textOf(tall))
        val layers = layersOf(tall)
        assertEquals(listOf(",", ".", "?", "!"), layers.map { it.text })
        assertEquals(listOf(0.125f, 0.375f, 0.625f, 0.875f), layers.map { it.biasY })
        assertEquals(
            listOf(",", ".", "?", "!"),
            tall.appearance.symbolZoneSymbols,
        )
        assertNull(snapshot.t9Symbols)
    }

    // ---------- 错位 ② Q 键 "⇥"（2026-11-16） ----------

    @Test
    fun assembledQKey_isPlainTextWithoutTabHint() {
        // TAB 下滑提示已删（施工单 5 第 10 项反转）：下滑 tab 的 Q 键
        // 不再派生 "⇥" 副显、不再转 AltText，主字形不再上移约 7px。
        // 删派生前本断言失败（Q 为 AltText、altText 为 "⇥"）。
        val entries = binJimeEntries()
        val snapshot = buildSnapshot(entries)
        val rows = assembled(entries, snapshot, "layouts/bin-alphabetic.json")
        val q = findKey(rows, "bin-alphabetic:K-q")
        assertTrue(
            "Q must stay plain Text, got ${q.appearance.javaClass.simpleName}",
            q.appearance is KeyDef.Appearance.Text &&
                q.appearance !is KeyDef.Appearance.AltText,
        )
        assertEquals("Q", textOf(q))
    }
}
