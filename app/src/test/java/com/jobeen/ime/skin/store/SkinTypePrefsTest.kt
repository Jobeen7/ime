package com.jobeen.ime.skin.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 按皮肤隔离的类型持久化语义：同皮肤恢复、换肤复位。 */
class SkinTypePrefsTest {

    @Test
    fun sameSkinRestoresStoredType() {
        assertEquals(
            "emojis",
            SkinTypePrefs.resolveStoredType("skin-a", "emojis", "skin-a"),
        )
    }

    @Test
    fun skinChangeResetsToNull() {
        assertNull(SkinTypePrefs.resolveStoredType("skin-a", "emojis", "skin-b"))
        assertNull(SkinTypePrefs.resolveStoredType("skin-a", "emojis", null))
        assertNull(SkinTypePrefs.resolveStoredType(null, "emojis", "skin-b"))
    }

    @Test
    fun blankStoredTypeIsNull() {
        assertNull(SkinTypePrefs.resolveStoredType("skin-a", "", "skin-a"))
        assertNull(SkinTypePrefs.resolveStoredType("skin-a", "   ", "skin-a"))
        assertNull(SkinTypePrefs.resolveStoredType("skin-a", null, "skin-a"))
    }
}
