// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.fontWeightMatches
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.notify.ConditionCurrent
import com.jobeen.ime.skin.notify.ConditionEvaluator
import com.jobeen.ime.skin.notify.ConditionStyleParser
import com.jobeen.ime.skin.notify.ConditionValue
import com.jobeen.ime.skin.notify.NotificationActionNames
import com.jobeen.ime.skin.notify.NotificationEvent
import com.jobeen.ime.skin.notify.NotificationSource
import com.jobeen.ime.skin.notify.NotificationState
import com.jobeen.ime.skin.notify.SkinNotification
import com.jobeen.ime.skin.notify.SkinNotificationParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.16.13 新增纯逻辑：通知恢复／匹配、条件样式、
 * 阴影缺省、边界高度、逐样式字号／内边距、numberPad 别名、
 * 字体字重、几何内边距、样式集／类型区域／深色布局。
 */
class Preview13AdditionsTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(
            tree, 170, fallbacks, assetKind = { id ->
                when (id) {
                    "bg_main" -> AssetKind.IMAGE
                    else -> null
                }
            },
        )
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun base(extra: String): String =
        """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
            "stylesDark": {"keyBackground": "#FF222222"},
            $extra}"""

    // ---- A3：失配恢复与锁定 ----

    private fun schemeRule(
        matchKey: String?,
        target: String,
        locked: Boolean = false,
    ) = SkinNotification(
        source = NotificationSource.SCHEME,
        matchKey = matchKey,
        matchValue = null,
        targetStyle = target,
        backgroundStyle = null,
        foregroundStyle = null,
        action = null,
        swipeUpAction = null,
        swipeDownAction = null,
        locked = locked,
    )

    @Test
    fun notificationMismatchRecoversUnlessLocked() {
        val state = NotificationState(listOf(schemeRule("wanxiang", "s1")))
        assertTrue(
            state.onEvent(
                NotificationEvent(NotificationSource.SCHEME, "wanxiang", null),
            ),
        )
        assertNotNull(state.latchedFor("s1"))
        // 失配事件清除锁定（默认 unlocked）。
        assertTrue(
            state.onEvent(
                NotificationEvent(NotificationSource.SCHEME, "other", null),
            ),
        )
        assertNull(state.latchedFor("s1"))
        // 无命中又无清除时返回 false。
        assertFalse(
            state.onEvent(
                NotificationEvent(NotificationSource.SCHEME, "other", null),
            ),
        )
    }

    @Test
    fun notificationLockedSurvivesMismatch() {
        val state = NotificationState(listOf(schemeRule("wanxiang", "s1", locked = true)))
        state.onEvent(NotificationEvent(NotificationSource.SCHEME, "wanxiang", null))
        state.onEvent(NotificationEvent(NotificationSource.SCHEME, "other", null))
        assertNotNull(state.latchedFor("s1"))
    }

    @Test
    fun notificationRimeOptionClearsOnlySameKey() {
        fun optionRule(key: String, target: String) = SkinNotification(
            source = NotificationSource.RIME_OPTION,
            matchKey = key,
            matchValue = "true",
            targetStyle = target,
            backgroundStyle = null,
            foregroundStyle = null,
            action = null,
            swipeUpAction = null,
            swipeDownAction = null,
        )
        val state = NotificationState(
            listOf(optionRule("ascii", "a"), optionRule("trad", "b")),
        )
        state.onEvent(NotificationEvent(NotificationSource.RIME_OPTION, "ascii", "true"))
        state.onEvent(NotificationEvent(NotificationSource.RIME_OPTION, "trad", "true"))
        // ascii 事件失配只清 ascii 规则，trad 规则不受影响。
        state.onEvent(NotificationEvent(NotificationSource.RIME_OPTION, "ascii", "false"))
        assertNull(state.latchedFor("a"))
        assertNotNull(state.latchedFor("b"))
    }

    @Test
    fun notificationKeyboardActionNeverUnlatches() {
        val state = NotificationState(listOf(schemeRule("wanxiang", "s1")))
        state.onEvent(NotificationEvent(NotificationSource.SCHEME, "wanxiang", null))
        state.onEvent(
            NotificationEvent(NotificationSource.KEYBOARD_ACTION, "space", null),
        )
        assertNotNull(state.latchedFor("s1"))
    }

    // ---- P1：方案 ID 或名匹配 ----

    @Test
    fun schemeMatchesByIdOrName() {
        val rule = schemeRule("wanxiang_t9", "s1")
        assertTrue(rule.matches(NotificationEvent(NotificationSource.SCHEME, "wanxiang_t9", null)))
        assertTrue(
            rule.matches(
                NotificationEvent(NotificationSource.SCHEME, "some_id", "wanxiang_t9"),
            ),
        )
        assertFalse(
            rule.matches(
                NotificationEvent(NotificationSource.SCHEME, "some_id", "other_name"),
            ),
        )
    }

    // ---- A4：PREEDIT 空／非空信号解析 ----

    @Test
    fun preeditEmptySignalParsedAndTextDropped() {
        val node = Json.parseToJsonElement(
            """[{"source": "preedit", "matchValue": "empty", "targetStyle": "a"},
                {"source": "preedit", "matchValue": "nonempty", "targetStyle": "b"},
                {"source": "preedit", "matchValue": "hello", "targetStyle": "c"},
                {"source": "preedit", "matchKey": "x", "matchValue": "empty", "targetStyle": "d"},
                {"source": "preedit", "matchValue": "empty", "targetStyle": "e", "locked": true}]""",
        ) as JsonArray
        val (parsed, dropped) = SkinNotificationParser.parse(node)
        assertEquals(3, parsed.size)
        assertEquals(2, dropped)
        assertEquals("empty", parsed[0].matchValue)
        assertEquals("nonempty", parsed[1].matchValue)
        assertTrue(parsed[2].locked)
    }

    // ---- A5：键盘动作值 ----

    @Test
    fun keyboardActionValueOf() {
        assertEquals(
            "numeric",
            NotificationActionNames.valueOf(
                KeyboardAction.SwitchKeyboardTypeAction("numeric"),
            ),
        )
        assertEquals(
            "ascii",
            NotificationActionNames.valueOf(
                KeyboardAction.ToggleRimeOptionAction("ascii"),
            ),
        )
        assertNull(NotificationActionNames.valueOf(KeyboardAction.SpaceAction))
    }

    @Test
    fun parseKeyComboAndSendKeys() {
        val combo = NotificationActionNames.parseKeyCombo("ctrl+shift+enter")
        assertNotNull(combo)
        assertEquals(66, combo!!.keyCode)
        assertTrue(combo.ctrl)
        assertTrue(combo.shift)
        assertFalse(combo.alt)
        assertFalse(combo.meta)
        val combos = NotificationActionNames.parseSendKeysValue("ctrl+a,f1")
        assertNotNull(combos)
        assertEquals(2, combos!!.size)
        assertEquals(29, combos[0].keyCode)
        assertEquals(131, combos[1].keyCode)
        assertNull(NotificationActionNames.parseKeyCombo("ctrl+zzz"))
        assertNull(NotificationActionNames.parseSendKeysValue("ctrl+a,zzz"))
    }

    // ---- A6：returnKeyType 条件 ----

    @Test
    fun conditionReturnKeyTypeStrings() {
        val node = Json.parseToJsonElement(
            """[{"conditionKey": "returnKeyType", "conditionValue": "go",
                "styleName": "s1"},
               {"conditionKey": "returnKeyType", "conditionValue": ["go", "search"],
                "styleName": "s2"},
               {"conditionKey": "returnKeyType", "conditionValue": [1, 2],
                "styleName": "s3"}]""",
        ) as JsonArray
        val (parsed, dropped) = ConditionStyleParser.parse(node)
        assertEquals(3, parsed.size)
        assertEquals(0, dropped)
        assertTrue(parsed[0].conditionValue is ConditionValue.Strings)
        assertTrue(parsed[1].conditionValue is ConditionValue.Strings)
        assertTrue(parsed[2].conditionValue is ConditionValue.Ints)
        assertTrue(
            ConditionEvaluator.matches(
                parsed[0], ConditionCurrent.StringValue("go"),
            ),
        )
        assertFalse(
            ConditionEvaluator.matches(
                parsed[0], ConditionCurrent.StringValue("search"),
            ),
        )
        assertFalse(
            ConditionEvaluator.matches(
                parsed[0], ConditionCurrent.IntValue(1),
            ),
        )
    }

    // ---- A7／S7／S1／S2：样式几何 ----

    private fun geometry(json: String): KeyStyleGeometry? {
        val entry = Json.parseToJsonElement(json) as JsonObject
        return KeyStyleGeometryParser.parseEntry(entry).first
    }

    @Test
    fun shadowDefaultsAreRadius3Offset03() {
        val parsed = geometry(
            """{"normalShadowColor": "#80000000"}""",
        )
        assertNotNull(parsed)
        val layer = parsed!!.shadow?.normal
        assertNotNull(layer)
        assertEquals(3f, layer!!.radiusDp)
        assertEquals(0f, layer.offsetXDp)
        assertEquals(3f, layer.offsetYDp)
    }

    @Test
    fun boundsHeightAndVerticalApplied() {
        val parsed = geometry(
            """{"bounds": {"width": 0.5, "height": 0.6, "alignment": "bottom"}}""",
        )
        assertNotNull(parsed)
        assertEquals(0.5f, parsed!!.bounds?.widthFraction)
        assertEquals(0.6f, parsed.bounds?.heightFraction)
        assertEquals(KeyBoundsVertical.BOTTOM, parsed.bounds?.vertical)
    }

    @Test
    fun perStyleFontAndInsetsParsed() {
        val parsed = geometry(
            """{"fontSize": 22.5, "altFontSize": 16, "iconFontSize": 20,
                "hInsetDp": 3, "vInsetDp": 2}""",
        )
        assertNotNull(parsed)
        assertEquals(22.5f, parsed!!.fontSizeDp)
        assertEquals(16f, parsed.altFontSizeDp)
        assertEquals(20f, parsed.iconSizeDp)
        assertEquals(3f, parsed.hInsetDp)
        assertEquals(2f, parsed.vInsetDp)
    }

    // ---- G3／G1：keyboards 表 ----

    @Test
    fun numberPadAliasAndStyleSetParsed() {
        val tree = Json.parseToJsonElement(
            """{"keyboards": {
                "numeric": {"layoutId": "num", "styleSet": "dark_keys"},
                "symbolic": {"layoutId": "sym"}}}""",
        ) as JsonObject
        val table = SkinJson.parseKeyboards(tree)
        assertEquals("num", table.getValue("numberPad").layoutId)
        assertEquals("dark_keys", table.getValue("numeric").styleSet)
        assertNull(table.getValue("symbolic").styleSet)
    }

    // ---- P5：字体字重 ----

    @Test
    fun fontFaceWeightDedupByNameAndWeight() {
        val tree = Json.parseToJsonElement(
            """{"fontFaces": [
                {"name": "MyFont", "url": "f1.ttf", "weight": 700},
                {"name": "myfont", "url": "f2.ttf", "weight": 400},
                {"name": "MyFont", "url": "f3.ttf", "weight": 700},
                {"name": "MyFont", "url": "f4.ttf", "weight": 950}]}""",
        ) as JsonObject
        val parsed = SkinFontParser.parse(tree, assetKind = { null })
        assertEquals(3, parsed.faces.size)
        assertEquals(700, parsed.faces[0].weight)
        assertEquals(400, parsed.faces[1].weight)
        // 非法字重按未声明处理（仍保留，去重键为 null）。
        assertNull(parsed.faces[2].weight)
        assertTrue(parsed.issues.any { it.detail == "font_face_weight_invalid" })
        assertTrue(parsed.issues.any { it.detail == "font_face_duplicate_name" })
    }

    @Test
    fun fontWeightMatchesPredicate() {
        assertTrue(fontWeightMatches(null, true))
        assertTrue(fontWeightMatches(null, false))
        assertTrue(fontWeightMatches(700, true))
        assertFalse(fontWeightMatches(700, false))
        assertTrue(fontWeightMatches(400, false))
        assertFalse(fontWeightMatches(400, true))
        assertTrue(fontWeightMatches(600, true))
        assertFalse(fontWeightMatches(599, true))
    }

    // ---- S5／S11／S12／G1／G2：校验层 ----

    @Test
    fun appearanceInsetsAndSymbolsParsed() {
        val valid = validate(
            base(
                """"appearance": {"topInsetDp": 8, "leftInsetDp": 4, "rightInsetDp": 6,
                    "background": {"type": "asset", "asset": "bg_main"}},
                "horizontalSymbolsRtl": true,
                "symbolBadge": {"fontSizeDp": 11, "normalColor": "#FF112233",
                  "highlightColor": "#FF445566"}""",
            ),
        )
        val appearance = valid.skin.appearance
        assertTrue(GeometryField.TOP_INSET in appearance.presence)
        assertTrue(GeometryField.LEFT_INSET in appearance.presence)
        assertTrue(GeometryField.RIGHT_INSET in appearance.presence)
        assertEquals(8f, appearance.effectiveGeometry.getValue(GeometryField.TOP_INSET))
        assertEquals(4f, appearance.effectiveGeometry.getValue(GeometryField.LEFT_INSET))
        assertEquals(6f, appearance.effectiveGeometry.getValue(GeometryField.RIGHT_INSET))
        assertTrue(valid.skin.horizontalSymbolsRtl)
        assertEquals(11f, valid.skin.symbolBadgeFontSizeDp)
        assertEquals(0xFF112233.toInt(), valid.skin.symbolBadgeNormalColor)
        assertEquals(0xFF445566.toInt(), valid.skin.symbolBadgeHighlightColor)
    }

    @Test
    fun styleSetsAndTypeAreasParsed() {
        val valid = validate(
            base(
                """"appearance": {"background": {"type": "asset", "asset": "bg_main"}},
                "styleSets": {"dark_keys": {"styles": {"keyBackground": "#FF000000"},
                    "keyStyles": {"k1": {"face": "#FFFFFFFF"}}}},
                "keyboards": {"numeric": {"layoutId": "num",
                    "areas": {"toolbarHeight": 56,
                      "horizontalCandidatesStyle": {"dividerVisible": false}}}}""",
            ),
        )
        val set = valid.skin.styleSets.getValue("dark_keys")
        assertEquals(0xFF000000.toInt(), set.styles.colors.getValue(StyleField.KEY_BACKGROUND))
        assertTrue(set.keyStyles.containsKey("k1"))
        val areas = valid.skin.typeAreas.getValue("numeric")
        assertEquals(56f, areas.toolbarHeight?.resolveDp(1000f, 48f))
        assertEquals(false, areas.horizontalCandidatesStyle?.dividerVisible)
    }

    // ---- G4：深色布局回落链 ----

    private fun layout(id: String) = ResolvedLayout(
        layoutId = id,
        generation = 1,
        name = id,
        baseLayout = emptyList(),
    )

    @Test
    fun darkLayoutFallbackChain() {
        val resolved = KeyboardTypeResolved(
            portrait = layout("light"),
            landscape = layout("light_land"),
            portraitDark = layout("dark"),
            landscapeDark = null,
        )
        assertEquals("dark", resolved.resolvedFor(landscape = false, isDark = true)?.layoutId)
        // 深色横屏缺失 → 深色竖屏。
        assertEquals("dark", resolved.resolvedFor(landscape = true, isDark = true)?.layoutId)
        // 浅色不受深色绑定影响。
        assertEquals("light_land", resolved.resolvedFor(landscape = true)?.layoutId)
        val noDark = KeyboardTypeResolved(portrait = layout("light"))
        assertEquals("light", noDark.resolvedFor(landscape = false, isDark = true)?.layoutId)
        assertEquals("light", noDark.resolvedFor(landscape = true, isDark = true)?.layoutId)
    }
}
