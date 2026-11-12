package com.jobeen.ime.base.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-L9：.tar.bz2 解压不处理符号链接／硬链接条目，不跟随既有链接写出。 */
class TarBz2ExtractorTest {

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("tar-bz2-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private sealed interface TarEntry {
        data class File(val name: String, val content: ByteArray) : TarEntry
        data class Symlink(val name: String, val target: String) : TarEntry
        data class Hardlink(val name: String, val target: String) : TarEntry
    }

    private fun buildArchive(file: File, entries: List<TarEntry>) {
        BZip2CompressorOutputStream(FileOutputStream(file)).use { bzOut ->
            TarArchiveOutputStream(bzOut).use { tarOut ->
                for (entry in entries) {
                    when (entry) {
                        is TarEntry.File -> {
                            val tarEntry = TarArchiveEntry(entry.name)
                            tarEntry.size = entry.content.size.toLong()
                            tarOut.putArchiveEntry(tarEntry)
                            tarOut.write(entry.content)
                            tarOut.closeArchiveEntry()
                        }
                        is TarEntry.Symlink -> {
                            val tarEntry =
                                TarArchiveEntry(entry.name, TarArchiveEntry.LF_SYMLINK)
                            tarEntry.linkName = entry.target
                            tarOut.putArchiveEntry(tarEntry)
                            tarOut.closeArchiveEntry()
                        }
                        is TarEntry.Hardlink -> {
                            val tarEntry =
                                TarArchiveEntry(entry.name, TarArchiveEntry.LF_LINK)
                            tarEntry.linkName = entry.target
                            tarOut.putArchiveEntry(tarEntry)
                            tarOut.closeArchiveEntry()
                        }
                    }
                }
                tarOut.finish()
            }
        }
    }

    @Test
    fun symlinkEntry_skipped_noLinkCreated() {
        runWithTempDir { root ->
            val archive = File(root, "test.tar.bz2")
            buildArchive(
                archive,
                listOf(
                    TarEntry.File("victim.txt", "keep".toByteArray()),
                    TarEntry.Symlink("mylink", "victim.txt"),
                ),
            )
            val dest = File(root, "dest")
            TarBz2ExtractorUtil.extract(archive, dest)
            assertFalse(File(dest, "mylink").exists())
            assertEquals("keep", File(dest, "victim.txt").readText())
        }
    }

    @Test
    fun hardlinkEntry_skipped() {
        runWithTempDir { root ->
            val archive = File(root, "test.tar.bz2")
            buildArchive(
                archive,
                listOf(
                    TarEntry.File("victim.txt", "keep".toByteArray()),
                    TarEntry.Hardlink("hard", "victim.txt"),
                ),
            )
            val dest = File(root, "dest")
            TarBz2ExtractorUtil.extract(archive, dest)
            assertFalse(File(dest, "hard").exists())
            assertEquals("keep", File(dest, "victim.txt").readText())
        }
    }

    @Test
    fun fileEntry_doesNotFollowPreexistingSymlink() {
        runWithTempDir { root ->
            val dest = File(root, "dest").apply { mkdirs() }
            val victim = File(dest, "victim.txt").apply { writeText("keep") }
            // 解压前目标位置已是符号链接（指向 dest 内的受害文件）
            Files.createSymbolicLink(File(dest, "link").toPath(), victim.toPath())
            val archive = File(root, "test.tar.bz2")
            buildArchive(archive, listOf(TarEntry.File("link", "pwned".toByteArray())))
            TarBz2ExtractorUtil.extract(archive, dest)
            // 链接被删、写成真实文件；受害文件原样
            assertFalse(Files.isSymbolicLink(File(dest, "link").toPath()))
            assertEquals("pwned", File(dest, "link").readText())
            assertEquals("keep", victim.readText())
        }
    }

    @Test
    fun normalFiles_stillExtract() {
        runWithTempDir { root ->
            val archive = File(root, "test.tar.bz2")
            buildArchive(
                archive,
                listOf(
                    TarEntry.File("a.txt", "a".toByteArray()),
                    TarEntry.File("sub/b.txt", "b".toByteArray()),
                ),
            )
            val dest = File(root, "dest")
            TarBz2ExtractorUtil.extract(archive, dest)
            assertEquals("a", File(dest, "a.txt").readText())
            assertEquals("b", File(dest, "sub/b.txt").readText())
        }
    }
}
