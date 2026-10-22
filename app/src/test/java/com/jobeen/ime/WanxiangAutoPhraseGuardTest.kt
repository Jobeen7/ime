package com.jobeen.ime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 万象内置包 lite / english 方案的 auto_phrase 摘除守卫（第十六
 * 批，换包回归防线）：官方 18.1.7 起 lite 与 english 注册了
 * `lua_filter@*wanxiang.auto_phrase`（无感造词）。该过滤器挂提
 *交通知后经 lua Memory 直写用户词典，不经过 Jime 禁学习门控
 * 唯一管辖的 Memory::OnCommit，密码/无痕框内会绕过门控造词落
 * 盘——Jime 适配将其从这两个方案的过滤器列表中摘除。整替官方
 * 包或手工编辑把注册行带回来时本测试必须失败。本测试读的是
 * tools/wanxiang-builtin 的适配源文件，不是生成后的方案文件；
 * t9 方案官方本就未注册，不在本守卫范围。
 */
class WanxiangAutoPhraseGuardTest {

    private fun schemaFile(name: String): File {
        val relative = "tools/wanxiang-builtin/$name"
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

    private fun assertNoActiveAutoPhrase(schemaName: String) {
        val lines = schemaFile(schemaName).readLines()
        val active = lines.filter {
            it.contains("auto_phrase") && !it.trimStart().startsWith("#")
        }
        assertTrue(
            "$schemaName 不得启用 auto_phrase（禁学习门控绕过，见第十六批），命中行：$active",
            active.isEmpty(),
        )
    }

    @Test
    fun liteSchemaHasNoActiveAutoPhrase() {
        assertNoActiveAutoPhrase("wanxiang_lite.schema.yaml")
    }

    @Test
    fun englishSchemaHasNoActiveAutoPhrase() {
        assertNoActiveAutoPhrase("wanxiang_english.schema.yaml")
    }
}
