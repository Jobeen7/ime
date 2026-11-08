package com.jobeen.ime

import com.jobeen.ime.data.manager.parseDefaultYamlSchemaList
import com.jobeen.ime.data.manager.parseSchemaFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 中英混输 mixedcode 方案守卫（施工单 10 H1-1）：
 * 上游 lite 包自带 wanxiang_mixedcode.schema.yaml，但 Jime 的
 * schema_list 没启用它，且它自身的词典（dicts/mixed，1817 条）没有
 * 一条纯英文词条——只加 schema_list 会交出一个打不出 hello 的“混输”
 * 方案。本测试守三点：清单含 mixedcode、适配源带 Jime 字段与英文翻译
 * 器、重置内置清单与清单顺序一致。读的是 tools/wanxiang-builtin 的
 * 适配源文件与 AppReset.kt 源码，不是生成后的方案文件。
 */
class WanxiangMixedcodeGuardTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val current = dir ?: return@repeat
            val candidate = File(current, relative)
            if (candidate.isFile) return candidate
            dir = current.parentFile
        }
        fail("找不到 $relative（user.dir=${System.getProperty("user.dir")}）")
        error("unreachable")
    }

    private fun defaultSchemaList(): List<String> {
        val text = repoFile("tools/wanxiang-builtin/default.yaml").readText()
        return parseDefaultYamlSchemaList(text)
    }

    @Test
    fun defaultSchemaListEnablesMixedcodeInOrder() {
        assertEquals(
            listOf("wanxiang_t9", "wanxiang_lite", "wanxiang_english", "wanxiang_mixedcode"),
            defaultSchemaList(),
        )
    }

    @Test
    fun mixedcodeAdapterHasJimeSchemaFields() {
        val file = repoFile("tools/wanxiang-builtin/wanxiang_mixedcode.schema.yaml")
        val disk = parseSchemaFile(file)
        assertEquals("wanxiang_mixedcode", disk?.id)
        assertEquals("Qwerty", disk?.layout)
        assertEquals("HalfWidth", disk?.punctuation)
        assertEquals("PinYin", disk?.kind)
        // parseSchemaFile 不采集 candidateKind，文本直断。
        assertTrue(
            "mixedcode 适配源须写 candidateKind PinYin",
            file.readText().contains("  candidateKind: \"PinYin\"\n"),
        )
    }

    @Test
    fun mixedcodeAdapterRoutesEnglishToEnglishDict() {
        val text = repoFile("tools/wanxiang-builtin/wanxiang_mixedcode.schema.yaml").readText()
        // mixed 词典 0 条纯英文词条，纯英文候选只能来自 wanxiang_english。
        assertTrue(
            "mixedcode 引擎须挂 table_translator@wanxiang_english",
            text.contains("    - table_translator@wanxiang_english\n"),
        )
        assertTrue(
            "mixedcode 须有 wanxiang_english 翻译器段",
            text.contains("wanxiang_english:\n  dictionary: wanxiang_english\n"),
        )
    }

    @Test
    fun mixedcodeAdapterHasNoActiveAutoPhrase() {
        val lines = repoFile("tools/wanxiang-builtin/wanxiang_mixedcode.schema.yaml").readLines()
        val active = lines.filter {
            it.contains("auto_phrase") && !it.trimStart().startsWith("#")
        }
        assertTrue("mixedcode 不得启用 auto_phrase，命中行：$active", active.isEmpty())
    }

    @Test
    fun builtinLiteIdsMatchDefaultSchemaList() {
        val source = repoFile(
            "app/src/main/java/com/jobeen/ime/data/manager/AppReset.kt",
        ).readText()
        val marker = "BUILTIN_LITE_IDS = listOf("
        val start = source.indexOf(marker)
        assertTrue("AppReset.kt 须有 BUILTIN_LITE_IDS", start >= 0)
        val end = source.indexOf(")", start + marker.length)
        assertTrue("BUILTIN_LITE_IDS 须闭合", end > start)
        val ids = Regex("\"([^\"]+)\"")
            .findAll(source.substring(start, end))
            .map { it.groupValues[1] }
            .toList()
        assertEquals(defaultSchemaList(), ids)
    }
}
