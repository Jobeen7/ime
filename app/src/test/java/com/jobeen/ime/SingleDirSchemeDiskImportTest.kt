package com.jobeen.ime

import com.jobeen.ime.data.manager.DiskSchema
import com.jobeen.ime.data.manager.discoverSchemes
import com.jobeen.ime.data.manager.extractZip
import com.jobeen.ime.data.manager.isProtectedEntry
import com.jobeen.ime.data.manager.isZipMagic
import com.jobeen.ime.data.manager.deployTimeoutMs
import com.jobeen.ime.data.manager.parseSchemaFile
import com.jobeen.ime.data.manager.resolveEnabledIds
import com.jobeen.ime.data.manager.stripTopDir
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SingleDirSchemeDiskImportTest {
    private val tmpDirs = mutableListOf<File>()

    private fun newTempDir(): File {
        val f = Files.createTempDirectory("single-dir-test").toFile()
        tmpDirs.add(f)
        return f
    }

    @After
    fun cleanup() {
        for (d in tmpDirs) {
            if (d.exists()) {
                d.walkBottomUp().forEach { it.delete() }
            }
        }
        tmpDirs.clear()
    }

    @Test
    fun rootSubdir_excluded() {
        val userDir = newTempDir()
        val sharedDir = newTempDir()
        File(userDir, "a.schema.yaml").writeText("schema:\n  schema_id: a\n")
        val oldDir = File(userDir, "old")
        oldDir.mkdirs()
        File(oldDir, "old.schema.yaml").writeText("schema:\n  schema_id: old\n")

        val result = discoverSchemes(userDir, sharedDir)

        assertEquals(1, result.size)
        assertEquals("a", result[0].id)
        assertFalse(result.any { it.id == "old" })
    }

    @Test
    fun userOverridesShared() {
        val userDir = newTempDir()
        val sharedDir = newTempDir()
        File(userDir, "b.schema.yaml").writeText("schema:\n  schema_id: b\n  name: user-b\n")
        File(userDir, "a.schema.yaml").writeText("schema:\n  schema_id: a\n  name: user-a\n")
        File(sharedDir, "b.schema.yaml").writeText("schema:\n  schema_id: b\n  name: shared-b\n")
        File(sharedDir, "a.schema.yaml").writeText("schema:\n  schema_id: a\n  name: shared-a\n")

        val result = discoverSchemes(userDir, sharedDir)

        assertEquals(2, result.size)
        assertEquals("a", result[0].id)
        assertEquals("user-a", result[0].name)
        assertEquals("b", result[1].id)
        assertEquals("user-b", result[1].name)
    }

    @Test
    fun parseMissingFields_defaultEmpty() {
        val dir = newTempDir()
        val f = File(dir, "x.schema.yaml")
        f.writeText("schema:\n  schema_id: x\n  name: X\n")

        val schema = parseSchemaFile(f)

        assertNotNull(schema)
        assertEquals("x", schema!!.id)
        assertEquals("X", schema.name)
        assertEquals("", schema.layout)
        assertEquals("", schema.punctuation)
        assertEquals("", schema.kind)

        val noSection = File(dir, "y.schema.yaml")
        noSection.writeText("schema_id: y\n")
        assertNull(parseSchemaFile(noSection))
    }

    @Test
    fun parseQuotedValues_unquoted() {
        val dir = newTempDir()

        val doubleQuoted = File(dir, "dq.schema.yaml")
        doubleQuoted.writeText("schema:\n  schema_id: dq\n  name: \"quoted name\"\n")
        val dq = parseSchemaFile(doubleQuoted)
        assertNotNull(dq)
        assertEquals("quoted name", dq!!.name)

        val singleQuoted = File(dir, "sq.schema.yaml")
        singleQuoted.writeText("schema:\n  schema_id: sq\n  name: 'quoted name'\n")
        val sq = parseSchemaFile(singleQuoted)
        assertNotNull(sq)
        assertEquals("quoted name", sq!!.name)

        val singleSide = File(dir, "ss.schema.yaml")
        singleSide.writeText("schema:\n  schema_id: ss\n  name: \"unclosed\n")
        val ss = parseSchemaFile(singleSide)
        assertNotNull(ss)
        assertEquals("\"unclosed", ss!!.name)
    }

    @Test
    fun resolveIntersection_keepsPrefsOrder() {
        val disk = listOf(DiskSchema("a", "", "", "", ""), DiskSchema("b", "", "", "", ""), DiskSchema("c", "", "", "", ""))

        assertEquals(listOf("c", "a"), resolveEnabledIds(listOf("c", "a", "x"), disk.map { it.id }))
        assertEquals(listOf("a", "b", "c"), resolveEnabledIds(emptyList(), disk.map { it.id }))
        assertEquals(listOf("a", "b", "c"), resolveEnabledIds(listOf("x", "y"), disk.map { it.id }))
    }

    @Test
    fun zipMagic_cases() {
        assertTrue(isZipMagic(byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())))
        assertFalse(isZipMagic(byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte())))
        assertFalse(isZipMagic(byteArrayOf(0x00.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())))
        assertFalse(isZipMagic(byteArrayOf()))
    }

    @Test
    fun stripTopDir_cases() {
        assertEquals("pkg", stripTopDir(listOf("pkg/a", "pkg/b")))
        assertNull(stripTopDir(listOf("pkg/a", "c")))
        assertNull(stripTopDir(emptyList()))
    }

    @Test
    fun protectedEntry_cases() {
        assertTrue(isProtectedEntry("pkg/scheme.userdb"))
        assertTrue(isProtectedEntry("default.yaml"))
        assertFalse(isProtectedEntry("pkg/default.yaml"))
        assertTrue(isProtectedEntry("pkg/wanxiang.custom.yaml"))
        assertTrue(isProtectedEntry("pkg/custom_phrase.txt"))
        assertTrue(isProtectedEntry("pkg/phrase.userdict.txt"))
        assertFalse(isProtectedEntry("pkg/a.schema.yaml"))
    }

    @Test
    fun extractZip_mixedPackage() {
        val zipFile = File(newTempDir(), "pkg.zip")
        val targetDir = newTempDir()
        val aContent = "AAAA"
        val bContent = "BBBBBB"

        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("pkg/a.schema.yaml"))
            zos.write(aContent.toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("pkg/b.schema.yaml"))
            zos.write(bContent.toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("pkg/wanxiang.custom.yaml"))
            zos.write("custom".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("pkg/../evil.txt"))
            zos.write("evil".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("pkg/subdir/"))
            zos.closeEntry()
        }

        val result = extractZip(zipFile, targetDir)

        assertEquals(2, result.installed)
        assertEquals(1, result.skippedProtected)
        assertEquals(1, result.skippedUnsafe)
        assertEquals(listOf("a", "b"), result.newSchemaIds)
        assertEquals(aContent.toByteArray().size.toLong() + bContent.toByteArray().size, result.totalBytes)
        assertEquals(aContent, File(targetDir, "a.schema.yaml").readText())
        assertEquals(bContent, File(targetDir, "b.schema.yaml").readText())
        assertFalse(File(targetDir, "wanxiang.custom.yaml").exists())
        assertFalse(File(targetDir.parentFile, "evil.txt").exists())
    }

    @Test
    fun extractZip_absolutePath_skipped() {
        val zipFile = File(newTempDir(), "abs.zip")
        val targetDir = newTempDir()
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("/abs.txt"))
            zos.write("abs".toByteArray())
            zos.closeEntry()
        }

        val result = extractZip(zipFile, targetDir)

        assertEquals(1, result.skippedUnsafe)
        assertEquals(0, result.installed)
        assertEquals(0, targetDir.listFiles()?.size ?: 0)
    }

    @Test
    fun extractZip_symlinkEscape_skipped() {
        val base = newTempDir()
        val targetDir = File(base, "target").apply { mkdirs() }
        val outside = File(base, "outside").apply { mkdirs() }
        File(targetDir, "linkdir").setSymbolicLinkTarget(outside.toPath())

        val zipFile = File(base, "symlink.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("x/linkdir/evil.txt"))
            zos.write("evil".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("x/ok.txt"))
            zos.write("OK".toByteArray())
            zos.closeEntry()
        }

        val result = extractZip(zipFile, targetDir)

        assertEquals(1, result.skippedUnsafe)
        assertEquals(1, result.installed)
        assertTrue(File(targetDir, "ok.txt").exists())
        assertFalse(File(outside, "evil.txt").exists())
    }

    @Test
    fun deployTimeout_cases() {
        assertEquals(180000L, deployTimeoutMs(0))
        assertEquals(210000L, deployTimeoutMs(8388608))
        assertEquals(420000L, deployTimeoutMs(69206016))
        assertEquals(180000L, deployTimeoutMs(-1))
        assertEquals(1800000L, deployTimeoutMs(10L * 1024 * 1024 * 1024))
    }

    private fun File.setSymbolicLinkTarget(target: java.nio.file.Path) {
        java.nio.file.Files.createSymbolicLink(this.toPath(), target)
    }
}
