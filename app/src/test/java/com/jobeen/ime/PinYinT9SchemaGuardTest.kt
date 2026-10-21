package com.jobeen.ime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 万象内置包 T9 方案的 Jime 适配守卫（换包回归防线）：官方 lite
 * 包把 speller/algebra 的单字母缩写拼写注释掉了，Jime 适配要求
 * 小写 abbrev 行启用、且位于 derive 全量大写行之后（后置才不会
 * 对纯数字串派生数字缩写、且点选回写所需的小写单字母拼写仍在），
 * 大写 abbrev 行保持注释。整替官方包丢掉这处适配时本测试必须
 * 失败——它读的是 tools/wanxiang-builtin 的适配源文件，不是生
 * 成后的方案文件。
 */
class PinYinT9SchemaGuardTest {

    private fun schemaFile(): File {
        val relative = "tools/wanxiang-builtin/wanxiang_t9.schema.yaml"
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

    @Test
    fun lowercaseAbbrevEnabledAfterDeriveUpper() {
        val lines = schemaFile().readLines()
        val activeLower = lines.indexOfFirst {
            it.contains("abbrev/^([a-z]).*/\$1/") && !it.trimStart().startsWith("#")
        }
        assertTrue("小写 abbrev 行必须存在且启用", activeLower >= 0)
        val deriveUpper = lines.indexOfFirst {
            it.contains("derive/") && it.contains("\\U")
        }
        assertTrue("derive \\U 行必须存在", deriveUpper >= 0)
        assertTrue(
            "小写 abbrev 必须位于 derive \\U 行之后（当前 abbrev=$activeLower, derive=$deriveUpper）",
            activeLower > deriveUpper,
        )
    }

    @Test
    fun uppercaseAbbrevStaysCommented() {
        val lines = schemaFile().readLines()
        val upperLines = lines.filter { it.contains("abbrev/^([A-Z]).*/\$1/") }
        assertTrue(
            "大写 abbrev 行必须保持注释，不得启用",
            upperLines.all { it.trimStart().startsWith("#") },
        )
    }
}
