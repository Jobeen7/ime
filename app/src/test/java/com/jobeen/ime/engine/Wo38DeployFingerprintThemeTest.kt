// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题切换强制重编译（WO38 E1-6）：主题本体 themes.json 住在外部根
 * 的 themes/，此前部署指纹只扫用户／共享两棵树，主题切换改 themes.json
 * 指纹不变、部署会被跳过——是真缺口。本组断言主题 mtime／内容变化
 * 一定让指纹变、不变时指纹一定不变、主题树缺失时行为与旧版一致。
 * 测试用真实生产布局：ext/user/＜目录名＞＋ext/shared＋ext/themes。
 */
class Wo38DeployFingerprintThemeTest {

    private class Layout(val ext: File) {
        val userDir = File(ext, "user/default")
        val sharedDir = File(ext, "shared")
        val themesDir = File(ext, "themes")

        init {
            userDir.mkdirs()
            sharedDir.mkdirs()
            themesDir.mkdirs()
        }
    }

    private fun layout(): Layout =
        Layout(Files.createTempDirectory("wo38-fp-theme").toFile().also { it.deleteOnExit() })

    private fun write(file: File, text: String): File {
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun fingerprint(layout: Layout): String? {
        val files = collectDeployInputs(layout.userDir, layout.sharedDir) ?: return null
        return computeDeployFingerprint("default", "a", files)
    }

    @Test
    fun themeMtimeChange_changesFingerprint() {
        val l = layout()
        write(File(l.userDir, "default.custom.yaml"), "schema_list: [a]\n")
        val themes = write(File(l.themesDir, "themes.json"), "[{\"id\": \"a\"}]\n")
        val before = fingerprint(l)
        assertNotNull(before)
        // 纯 mtime 变化（内容、长度都不动）：主题切换保存即改 mtime。
        themes.setLastModified(themes.lastModified() + 5000L)
        assertNotEquals(before, fingerprint(l))
    }

    @Test
    fun themeMtimeUnchanged_fingerprintStable() {
        val l = layout()
        write(File(l.userDir, "default.custom.yaml"), "schema_list: [a]\n")
        write(File(l.themesDir, "themes.json"), "[{\"id\": \"a\"}]\n")
        assertEquals(fingerprint(l), fingerprint(l))
    }

    @Test
    fun themeContentChangeSameMtime_changesFingerprint() {
        val l = layout()
        write(File(l.userDir, "default.custom.yaml"), "schema_list: [a]\n")
        val themes = write(File(l.themesDir, "themes.json"), "[{\"id\": \"a\"}]\n")
        val mtime = themes.lastModified()
        val before = fingerprint(l)
        // 内容变、长度与 mtime 都不变：没有内容 SHA 就会误 hit。
        themes.writeText("[{\"id\": \"b\"}]\n")
        themes.setLastModified(mtime)
        assertNotEquals(before, fingerprint(l))
    }

    @Test
    fun themeEntry_presentInCollectedInputs() {
        val l = layout()
        write(File(l.userDir, "default.custom.yaml"), "schema_list: [a]\n")
        write(File(l.themesDir, "themes.json"), "[{\"id\": \"a\"}]\n")
        val inputs = collectDeployInputs(l.userDir, l.sharedDir)!!
        val themeEntry = inputs.singleOrNull { it.relPath == "theme/themes.json" }
        assertNotNull(themeEntry)
        assertNotNull(themeEntry!!.contentSha)
    }

    @Test
    fun themesDirAbsent_inputsHaveNoThemeEntries() {
        val l = layout()
        write(File(l.userDir, "default.custom.yaml"), "schema_list: [a]\n")
        assertTrue(l.themesDir.deleteRecursively())
        val inputs = collectDeployInputs(l.userDir, l.sharedDir)!!
        // 主题树不存在：不计入、也不失败，与旧行为一致。
        assertTrue(inputs.none { it.relPath.startsWith("theme/") })
        assertEquals(fingerprint(l), fingerprint(l))
    }

    @Test
    fun themeTmpLeftover_doesNotAffectFingerprint() {
        val l = layout()
        write(File(l.userDir, "default.custom.yaml"), "schema_list: [a]\n")
        write(File(l.themesDir, "themes.json"), "[{\"id\": \"a\"}]\n")
        val before = fingerprint(l)
        // 保存中途的临时文件不是主题输入：残留 tmp 不得扰动指纹。
        write(File(l.themesDir, "themes.json.tmp"), "[{\"id\": \"half\"}]\n")
        assertEquals(before, fingerprint(l))
    }

    @Test
    fun resolveThemesDir_derivesFromUserDir() {
        val l = layout()
        assertEquals(l.themesDir, resolveThemesDir(l.userDir))
        // 布局外路径（无父级可上溯）返回 null，不抛错。
        assertNull(resolveThemesDir(File("lonely")))
    }
}
