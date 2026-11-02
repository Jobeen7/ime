package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** G1 面板颜色 + G2 面板区域按类型取用（2026-11-02 修复批）：纯逻辑。 */
class PanelTypeParamsTest {

    private fun scheme(keyBackground: Int, accentKeyBackground: Int) =
        KeyboardColors.ColorScheme(
            keyBackground = keyBackground, keyPressed = keyBackground + 1,
            keyBorderStroke = keyBackground + 2,
            specialKeyBackground = keyBackground + 3,
            specialKeyPressed = keyBackground + 4,
            specialKeyBorderStroke = keyBackground + 5,
            accentKeyBackground = accentKeyBackground,
            accentKeyPressed = accentKeyBackground + 1,
            accentKeyBorderStroke = accentKeyBackground + 2,
            keyText = keyBackground + 10, specialKeyText = keyBackground + 11,
            accentKeyText = accentKeyBackground + 12, altText = keyBackground + 13,
            background = keyBackground + 14,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = 20, toolbarText = 21, toolbarActived = 22,
                toolbarIcon = 23, candidateBackground = 24, candidateText = 25,
                candidateIndex = 26, candidateDivider = 27, toolbarPressed = 28,
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = 30, textColor = 31, secondaryTextColor = 32,
            ),
        )

    private val pkgLight = scheme(1, 101)
    private val pkgDark = scheme(2, 201)
    private val typeLight = scheme(3, 301)
    private val typeDark = scheme(4, 401)

    private fun snapshot() = SkinSnapshot(
        ref = SkinGenRef("s1", "h", 1),
        name = "test",
        colorScheme = pkgLight,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        darkColorScheme = pkgDark,
        toolbarHeight = VhLengthSpec(dp = 48f, vhPercent = null),
        preeditHeight = VhLengthSpec(dp = 30f, vhPercent = null),
        horizontalCandidatesStyle = CandidateAreaStyleSpec(background = 1000),
        verticalCandidatesStyle = CandidateAreaStyleSpec(background = 2000),
        horizontalCandidateStyle = CandidateCellStyleSpec(textSizeDp = 18f),
        verticalCandidateStyle = CandidateCellStyleSpec(textSizeDp = 17f),
        typeStyles = mapOf(
            "pinyin" to TypeStyleSet(
                colorScheme = typeLight, darkColorScheme = typeDark,
            ),
            "alphabetic" to TypeStyleSet(
                colorScheme = typeLight, darkColorScheme = null,
            ),
        ),
        typeAreas = mapOf(
            "pinyin" to TypeAreaSet(
                toolbarHeight = VhLengthSpec(dp = 56f, vhPercent = null),
                horizontalCandidatesStyle = CandidateAreaStyleSpec(background = 1001),
            ),
        ),
    )

    @Test
    fun typeStyleSetGovernsPanelSchemeInBothModes() {
        val snap = snapshot()
        assertSame(typeLight, snap.effectiveColorSchemeForType("pinyin", false))
        assertSame(typeDark, snap.effectiveColorSchemeForType("pinyin", true))
    }

    @Test
    fun typeStyleSetWithoutDarkFallsBackToItsOwnLightInDarkMode() {
        // 类型集无深色板时用该类型浅色板，不混用包级深色板。
        val snap = snapshot()
        assertSame(typeLight, snap.effectiveColorSchemeForType("alphabetic", true))
    }

    @Test
    fun unknownOrNullTypeFallsBackToPackageScheme() {
        val snap = snapshot()
        assertSame(pkgLight, snap.effectiveColorSchemeForType("numeric", false))
        assertSame(pkgDark, snap.effectiveColorSchemeForType("numeric", true))
        assertSame(pkgLight, snap.effectiveColorSchemeForType(null, false))
        assertSame(pkgDark, snap.effectiveColorSchemeForType(null, true))
    }

    @Test
    fun typeAreaOverridesOnlyDeclaredFields() {
        val snap = snapshot()
        assertEquals(56f, snap.toolbarHeightFor("pinyin")?.dp)
        assertEquals(1001, snap.horizontalCandidatesStyleFor("pinyin")?.background)
        // pinyin 区域集未写其余项：逐项回落包级。
        assertEquals(30f, snap.preeditHeightFor("pinyin")?.dp)
        assertEquals(2000, snap.verticalCandidatesStyleFor("pinyin")?.background)
        assertEquals(18f, snap.horizontalCandidateStyleFor("pinyin")?.textSizeDp)
        assertEquals(17f, snap.verticalCandidateStyleFor("pinyin")?.textSizeDp)
    }

    @Test
    fun typeWithoutAreaSetFallsBackToPackageAreas() {
        val snap = snapshot()
        assertEquals(48f, snap.toolbarHeightFor("numeric")?.dp)
        assertEquals(1000, snap.horizontalCandidatesStyleFor("numeric")?.background)
        assertEquals(48f, snap.toolbarHeightFor(null)?.dp)
        assertNull(snap.toolbarHeightFor(null)?.vhPercent)
    }
}
