package com.jobeen.ime.input.panel.component

import com.jobeen.ime.skin.model.SkinIssue
import com.jobeen.ime.skin.model.parseVerticalCandidates
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 竖网格行列上限（审计 I 项）：解析与皮肤优先于用户偏好的优先级。 */
class CandidateGridLimitsTest {

    @Test
    fun verticalCandidatesParse() {
        val issues = mutableListOf<SkinIssue>()
        val spec = parseVerticalCandidates(
            Json.parseToJsonElement("""{"maxRows": 4, "maxColumns": 5}"""),
            "verticalCandidates", issues,
        )
        assertEquals(4, spec?.maxRows)
        assertEquals(5, spec?.maxColumns)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun verticalCandidatesAbsentIsNull() {
        val issues = mutableListOf<SkinIssue>()
        assertNull(parseVerticalCandidates(null, "verticalCandidates", issues))
        assertTrue(issues.isEmpty())
    }

    @Test
    fun verticalCandidatesOutOfRangeIsDropped() {
        val issues = mutableListOf<SkinIssue>()
        val spec = parseVerticalCandidates(
            Json.parseToJsonElement("""{"maxRows": 0, "maxColumns": 99}"""),
            "verticalCandidates", issues,
        )
        assertNull(spec?.maxRows)
        assertNull(spec?.maxColumns)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun skinLimitsWinOverUserPreference() {
        assertEquals(3 to 2, effectiveGridLimits(3, 2, 5))
    }

    @Test
    fun absentSkinRowsFallBackToUserPreference() {
        assertEquals(5 to 5, effectiveGridLimits(null, null, 5))
    }

    @Test
    fun partialSkinLimitsFallBackPerField() {
        assertEquals(4 to 5, effectiveGridLimits(4, null, 9))
        assertEquals(9 to 3, effectiveGridLimits(null, 3, 9))
    }
}
