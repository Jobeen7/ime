// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.PrefsXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SharedPreferences 磁盘文件解析（WO33 第 1 项）：框架写出的
 * shared_prefs XML → 键／类型值，与 encodePrefValue 接受的类型
 * 一一对应。纯 javax.xml，可主机单测。
 */
class PrefsXmlTest {

    @Test
    fun parsesAllValueTypes() {
        val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="s">hello</string>
    <int name="i" value="42" />
    <long name="l" value="-7" />
    <float name="f" value="1.5" />
    <boolean name="b" value="true" />
    <boolean name="b2" value="false" />
    <set name="ss">
        <string>one</string>
        <string>two</string>
    </set>
</map>
""".trimIndent()
        val out = PrefsXml.parse(xml.toByteArray(Charsets.UTF_8))
        assertEquals("hello", out["s"])
        assertEquals(42, out["i"])
        assertEquals(-7L, out["l"])
        assertEquals(1.5f, out["f"])
        assertEquals(true, out["b"])
        assertEquals(false, out["b2"])
        assertEquals(linkedSetOf("one", "two"), out["ss"])
    }

    @Test
    fun parsesSpecialCharsAndEmptyValues() {
        val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="esc">a &amp; b &lt;c&gt; &quot;q&quot;</string>
    <string name="empty"></string>
    <string name="unicode">中文😀</string>
    <set name="emptySet"></set>
</map>
""".trimIndent()
        val out = PrefsXml.parse(xml.toByteArray(Charsets.UTF_8))
        assertEquals("a & b <c> \"q\"", out["esc"])
        assertEquals("", out["empty"])
        assertEquals("中文😀", out["unicode"])
        assertEquals(emptySet<String>(), out["emptySet"])
    }

    @Test
    fun parsesEmptyMap() {
        val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map />
""".trimIndent()
        assertTrue(PrefsXml.parse(xml.toByteArray(Charsets.UTF_8)).isEmpty())
    }

    @Test
    fun malformedXml_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefsXml.parse("<map><string name=\"x\">oops".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun emptyInput_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefsXml.parse(ByteArray(0))
        }
    }

    @Test
    fun nonMapRoot_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefsXml.parse("<foo />".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun unknownElement_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefsXml.parse("<map><double name=\"d\" value=\"1.0\" /></map>".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun missingName_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefsXml.parse("<map><string>no-name</string></map>".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun missingValue_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefsXml.parse("<map><int name=\"i\" /></map>".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun badNumber_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefsXml.parse("<map><int name=\"i\" value=\"not-a-number\" /></map>".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun setWithNonString_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            PrefsXml.parse(
                "<map><set name=\"s\"><int value=\"1\" /></set></map>".toByteArray(Charsets.UTF_8),
            )
        }
    }
}
