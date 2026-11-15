package com.jobeen.ime.base.webserver

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** WO52 4.4：路径规范化＋根约束＋受保护路径＋皮肤符号链接。 */
class WebServerPathsTest {

    private lateinit var root: File
    private lateinit var skinRoot: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("paths-test").toFile()
        skinRoot = File(root, "skin_library").also { it.mkdirs() }
        File(root, "user").mkdirs()
        File(root, "shared").mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `empty path resolves to root`() {
        assertEquals(root, WebServerPaths.resolveWithin(root, ""))
        assertEquals(root, WebServerPaths.resolveWithin(root, "/"))
    }

    @Test
    fun `normal relative path resolves inside root`() {
        assertEquals(
            File(root, "sub/a.txt"),
            WebServerPaths.resolveWithin(root, "sub/a.txt"),
        )
        assertEquals(
            File(root, "sub/a.txt"),
            WebServerPaths.resolveWithin(root, "/sub//a.txt"),
        )
    }

    @Test
    fun `dotdot escaping root is rejected`() {
        assertNull(WebServerPaths.resolveWithin(root, "../etc/passwd"))
        assertNull(WebServerPaths.resolveWithin(root, "sub/../../etc"))
        assertNull(WebServerPaths.resolveWithin(root, ".."))
    }

    @Test
    fun `dotdot staying inside root folds`() {
        assertEquals(
            File(root, "a.txt"),
            WebServerPaths.resolveWithin(root, "sub/../a.txt"),
        )
    }

    @Test
    fun `protected covers root and scheme skin shared subtrees`() {
        assertTrue(WebServerPaths.isProtected(root, root))
        assertTrue(WebServerPaths.isProtected(root, File(root, "user")))
        assertTrue(WebServerPaths.isProtected(root, File(root, "user/default/a.yaml")))
        assertTrue(WebServerPaths.isProtected(root, File(root, "skin_library/bin")))
        assertTrue(WebServerPaths.isProtected(root, File(root, "shared/build")))
        assertFalse(WebServerPaths.isProtected(root, File(root, "hello.txt")))
        assertFalse(WebServerPaths.isProtected(root, File(root, "sub")))
        // 前缀欺骗：user2 不是 user 的子目录。
        assertFalse(WebServerPaths.isProtected(root, File(root, "user2/x")))
    }

    @Test
    fun `symlinked skin dir is detected`() {
        val real = File(root, "real-skins").also { it.mkdirs() }
        val link = File(skinRoot, "linked")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        assertTrue(WebServerPaths.isSkinSymlink(skinRoot, File(link, "skin.json")))
        assertTrue(WebServerPaths.isSkinSymlink(skinRoot, link))
        val normal = File(skinRoot, "plain").also { it.mkdirs() }
        assertFalse(WebServerPaths.isSkinSymlink(skinRoot, File(normal, "skin.json")))
    }

    @Test
    fun `symlinked skin file itself is detected`() {
        val realFile = File(root, "real-skin.json").also { it.writeText("{}") }
        val linkFile = File(skinRoot, "linked.json")
        Files.createSymbolicLink(linkFile.toPath(), realFile.toPath())
        assertTrue(WebServerPaths.isSkinSymlink(skinRoot, linkFile))
        val plainFile = File(skinRoot, "plain.json").also { it.writeText("{}") }
        assertFalse(WebServerPaths.isSkinSymlink(skinRoot, plainFile))
    }
}
