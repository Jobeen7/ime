// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 部署输入指纹：目录名＋启用镜像串＋两棵树（用户目录、共享目录）
 * 的文件清单（相对路径|长度|mtime，顶层 yaml 再加内容 SHA）。
 * 排除项（部署产物与运行期写入）变化时指纹必须不变，否则跳过永不命中；
 * 任何真实输入变化时指纹必须变，否则会跳过一次必需的部署。
 * 判定方向宁可误 miss（多编一次）不可误 hit（漏编一次）。
 */
class DeployFingerprintTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    private fun write(file: File, text: String): File {
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun inputs(userDir: File, sharedDir: File): List<DeployInputFile> =
        collectDeployInputs(userDir, sharedDir)!!

    private fun fingerprint(
        dirName: String,
        mirror: String,
        userDir: File,
        sharedDir: File,
    ): String? = computeDeployFingerprint(dirName, mirror, inputs(userDir, sharedDir))

    @Test
    fun fingerprint_stableWhenNothingChanges() {
        val user = tempDir("fp-user")
        val shared = tempDir("fp-shared")
        write(File(user, "default.custom.yaml"), "schema_list: [a]\n")
        write(File(shared, "default.yaml"), "schema_list: [a]\n")
        val first = fingerprint("dirA", "a,b", user, shared)
        val second = fingerprint("dirA", "a,b", user, shared)
        assertNotNull(first)
        assertEquals(first, second)
    }

    @Test
    fun fingerprint_changesOnTopYamlContentChange_sameMtime() {
        val user = tempDir("fp-user")
        val shared = tempDir("fp-shared")
        val yaml = write(File(user, "default.custom.yaml"), "schema_list: [a]\n")
        val mtime = yaml.lastModified()
        val before = fingerprint("dirA", "a", user, shared)
        // 内容变、长度与 mtime 都不变：没有内容 SHA 就会误 hit。
        yaml.writeText("schema_list: [b]\n")
        yaml.setLastModified(mtime)
        val after = fingerprint("dirA", "a", user, shared)
        assertNotEquals(before, after)
    }

    @Test
    fun fingerprint_changesOnNestedFileChange() {
        val user = tempDir("fp-user")
        val shared = tempDir("fp-shared")
        val dict = write(File(shared, "dicts/base.dict.yaml"), "a\t1\n")
        val before = fingerprint("dirA", "a", user, shared)
        dict.writeText("a\t1\nb\t2\n")
        val after = fingerprint("dirA", "a", user, shared)
        assertNotEquals(before, after)
    }

    @Test
    fun fingerprint_changesOnMirrorChange() {
        val user = tempDir("fp-user")
        val shared = tempDir("fp-shared")
        write(File(user, "default.custom.yaml"), "schema_list: [a]\n")
        assertNotEquals(
            fingerprint("dirA", "a", user, shared),
            fingerprint("dirA", "a,b", user, shared),
        )
    }

    @Test
    fun fingerprint_changesOnDirSwitch() {
        val user = tempDir("fp-user")
        val shared = tempDir("fp-shared")
        write(File(user, "default.custom.yaml"), "schema_list: [a]\n")
        assertNotEquals(
            fingerprint("dirA", "a", user, shared),
            fingerprint("dirB", "a", user, shared),
        )
    }

    @Test
    fun fingerprint_ignoresUserYamlChange() {
        val user = tempDir("fp-user")
        val shared = tempDir("fp-shared")
        write(File(user, "default.custom.yaml"), "schema_list: [a]\n")
        val userYaml = write(File(user, "user.yaml"), "last_build_time: 111\n")
        val before = fingerprint("dirA", "a", user, shared)
        // librime 每次部署都重写 user.yaml 的 last_build_time：若计入，
        // 指纹永远变化、跳过永不命中。
        userYaml.writeText("last_build_time: 222\n")
        assertEquals(before, fingerprint("dirA", "a", user, shared))
    }

    @Test
    fun fingerprint_ignoresBuildDirChange() {
        val user = tempDir("fp-user")
        val shared = tempDir("fp-shared")
        write(File(user, "default.custom.yaml"), "schema_list: [a]\n")
        write(File(user, "build/a.schema.bin"), "bin1")
        val before = fingerprint("dirA", "a", user, shared)
        write(File(user, "build/a.schema.bin"), "bin2-longer")
        write(File(user, "build/new.bin"), "new")
        assertEquals(before, fingerprint("dirA", "a", user, shared))
    }

    @Test
    fun fingerprint_ignoresUserdbChange() {
        val user = tempDir("fp-user")
        val shared = tempDir("fp-shared")
        write(File(user, "default.custom.yaml"), "schema_list: [a]\n")
        write(File(user, "wanxiang.userdb/000001.ldb"), "ldb1")
        write(File(user, "wanxiang.userdb.txt"), "word\t1\n")
        val before = fingerprint("dirA", "a", user, shared)
        write(File(user, "wanxiang.userdb/000001.ldb"), "ldb2")
        write(File(user, "wanxiang.userdb.txt"), "word\t1\nword2\t1\n")
        assertEquals(before, fingerprint("dirA", "a", user, shared))
    }

    @Test
    fun fingerprint_mtimeTouch_misses() {
        val user = tempDir("fp-user")
        val shared = tempDir("fp-shared")
        val yaml = write(File(user, "default.custom.yaml"), "schema_list: [a]\n")
        val before = fingerprint("dirA", "a", user, shared)
        // 纯 mtime 变化也按 miss 处理（保守方向：多编一次无害）。
        yaml.setLastModified(yaml.lastModified() + 5000L)
        assertNotEquals(before, fingerprint("dirA", "a", user, shared))
    }

    @Test
    fun shouldSkip_truthTable() {
        assertTrue(shouldSkipDeploy(stored = "fp", computed = "fp", productsPresent = true, forceFull = false))
        assertFalse(shouldSkipDeploy(stored = "other", computed = "fp", productsPresent = true, forceFull = false))
        assertFalse(shouldSkipDeploy(stored = null, computed = "fp", productsPresent = true, forceFull = false))
        assertFalse(shouldSkipDeploy(stored = "fp", computed = null, productsPresent = true, forceFull = false))
        assertFalse(shouldSkipDeploy(stored = "fp", computed = "fp", productsPresent = false, forceFull = false))
        // 切换/导入即使误调 wrapper 也不得跳过：forceFull 硬约束。
        assertFalse(shouldSkipDeploy(stored = "fp", computed = "fp", productsPresent = true, forceFull = true))
    }

    @Test
    fun exclusion_predicate() {
        assertTrue(isDeployFingerprintExcluded("user/build/a.bin"))
        assertTrue(isDeployFingerprintExcluded("shared/build/a.bin"))
        assertTrue(isDeployFingerprintExcluded("user/user.yaml"))
        assertTrue(isDeployFingerprintExcluded("user/wanxiang.userdb/000001.ldb"))
        assertTrue(isDeployFingerprintExcluded("user/wanxiang.userdb.txt"))
        assertFalse(isDeployFingerprintExcluded("user/default.custom.yaml"))
        assertFalse(isDeployFingerprintExcluded("shared/dicts/base.dict.yaml"))
        assertFalse(isDeployFingerprintExcluded("shared/lua/helper.lua"))
    }

    @Test
    fun fingerprintKey_isPerDir() {
        assertNotEquals(deployFingerprintKey("dirA"), deployFingerprintKey("dirB"))
        assertEquals(deployFingerprintKey("dirA"), deployFingerprintKey("dirA"))
    }

    @Test
    fun collect_returnsNullOnMissingDir() {
        assertNull(collectDeployInputs(File("/nonexistent-dir-xyz"), tempDir("fp-shared")))
    }
}
