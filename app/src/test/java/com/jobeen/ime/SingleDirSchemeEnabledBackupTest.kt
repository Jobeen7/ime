package com.jobeen.ime

import android.content.SharedPreferences
import androidx.core.content.edit
import com.jobeen.ime.data.backup.BackupManager
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.data.manager.SingleSchemeEnabled
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SingleDirSchemeEnabledBackupTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.walkBottomUp().forEach { f -> f.delete() } }
        tempDirs.clear()
    }

    private fun createTempDir(): File {
        val dir = Files.createTempDirectory("single-dir-test").toFile()
        tempDirs.add(dir)
        return dir
    }

    private class FakePrefs : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = data.toMap()
        override fun getString(key: String, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): Set<String>? = (data[key] as? Set<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor { data[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { data[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { data[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { data[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { data[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { data[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { data.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { data.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    @Test
    fun patchFresh_nullAndBlank() {
        val fresh = listOf(
            "# Written by Jime scheme settings.",
            "patch:",
            "  schema_list:",
            "    - a",
            "    - b",
            ""
        )
        listOfNullable(null, "   ").forEach { existing ->
            val res = SingleSchemeEnabled.patchSchemaListYaml(existing, listOf("a", "b"))
            assertArrayEquals(fresh.toTypedArray(), res.lines().toTypedArray())
        }
    }

    @Test
    fun patchReplace_keepsOtherLines() {
        val existing = "other: 1\n# comment\npatch:\n  schema_list:\n    - old1\n    - old2\n"
        val res = SingleSchemeEnabled.patchSchemaListYaml(existing, listOf("a", "b"))
        assertTrue(res.contains("other: 1"))
        assertTrue(res.contains("# comment"))
        assertTrue(res.contains("    - a"))
        assertTrue(res.contains("    - b"))
        assertFalse(res.contains("old1"))
        assertFalse(res.contains("old2"))
        assertTrue(res.endsWith("\n"))
    }

    @Test
    fun patchNoSchemaList_insertsAfterPatch() {
        val existing = "patch:\n  other: 1\n"
        val res = SingleSchemeEnabled.patchSchemaListYaml(existing, listOf("a", "b"))
        val lines = res.lines()
        val pIdx = lines.indexOf("patch:")
        val sIdx = lines.indexOf("  schema_list:")
        val oIdx = lines.indexOf("  other: 1")
        assertTrue(pIdx >= 0 && sIdx == pIdx + 1 && oIdx == sIdx + 3)
    }

    @Test
    fun patchNoPatch_appends() {
        val existing = "foo: bar\n"
        val res = SingleSchemeEnabled.patchSchemaListYaml(existing, listOf("a", "b"))
        val lines = res.lines()
        assertTrue(lines.contains("foo: bar"))
        val pIdx = lines.indexOf("patch:")
        assertTrue(pIdx > 0 && lines[pIdx + 1] == "  schema_list:")
    }

    @Test
    fun patchFlowStyle_dropped() {
        val existing = "patch:\n  schema_list: [a, b]\n"
        val res = SingleSchemeEnabled.patchSchemaListYaml(existing, listOf("c"))
        val lines = res.lines()
        assertTrue(lines.contains("  schema_list:"))
        assertTrue(lines.contains("    - c"))
        assertFalse(res.contains("[a, b]"))
    }

    @Test
    fun patchInvalidIds_skipped() {
        val res = SingleSchemeEnabled.patchSchemaListYaml(
            null, listOf("a b", "a/b", "", ".", "..", "valid")
        )
        assertEquals(
            listOf(
                "# Written by Jime scheme settings.",
                "patch:",
                "  schema_list:",
                "    - valid",
                ""
            ),
            res.lines()
        )
    }

    @Test
    fun patchCommentTruncates_keepsTailItems() {
        val existing = "patch:\n  schema_list:\n    - old1\n    # note\n    - old2\n"
        val res = SingleSchemeEnabled.patchSchemaListYaml(existing, listOf("new1"))
        val lines = res.lines()
        assertTrue(lines.contains("    # note"))
        assertTrue(lines.contains("    - old2"))
        assertFalse(res.contains("old1"))
        assertTrue(res.contains("    - new1"))
        val noteIdx = lines.indexOf("    # note")
        val old2Idx = lines.indexOf("    - old2")
        val new1Idx = lines.indexOf("    - new1")
        assertTrue(new1Idx < noteIdx && old2Idx > noteIdx)
    }

    @Test
    fun writeEnabledList_dualWrite() {
        val prefs = FakePrefs()
        val dir = createTempDir()
        val file = File(dir, "schemes.yaml")
        file.writeText("foo: bar\n")
        SingleSchemeEnabled.writeEnabledList(prefs, file, listOf("b", "a", "invalid id"))
        assertEquals("b,a", prefs.getString(SchemaManager.KEY_ENABLED_IDS, ""))
        val content = file.readText()
        assertTrue(content.contains("foo: bar"))
        assertTrue(content.contains("    - b"))
        assertTrue(content.contains("    - a"))
    }

    @Test
    fun writeEnabledList_missingFile_fresh() {
        val prefs = FakePrefs()
        val dir = createTempDir()
        val file = File(dir, "schemes.yaml")
        SingleSchemeEnabled.writeEnabledList(prefs, file, listOf("a"))
        val content = file.readText()
        assertTrue(content.startsWith("# Written by Jime scheme settings."))
        assertEquals("a", prefs.getString(SchemaManager.KEY_ENABLED_IDS, ""))
    }

    @Test
    fun legacyPref_schemeDirsAlwaysTrue() {
        assertTrue(BackupManager.isLegacyMultiDirPref("scheme_dirs", "enabled_schema_ids_foo"))
        assertTrue(BackupManager.isLegacyMultiDirPref("scheme_dirs", ""))
    }

    @Test
    fun legacyPref_perDirKeyTrue() {
        assertTrue(BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids_foo"))
        assertTrue(BackupManager.isLegacyMultiDirPref("other_file", "enabled_schema_ids_x"))
    }

    @Test
    fun legacyPref_globalAndEmptySuffixFalse() {
        assertFalse(BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids"))
        assertFalse(BackupManager.isLegacyMultiDirPref("schema_settings", "enabled_schema_ids_"))
        assertFalse(BackupManager.isLegacyMultiDirPref("schema_settings", "other_key"))
    }

    private fun <T> listOfNullable(vararg elements: T?): List<T?> = elements.toList()
}
