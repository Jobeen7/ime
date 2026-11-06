package com.jobeen.ime.skin.runtime

import com.jobeen.ime.skin.store.LayoutIndexEntry
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 #2：单次构建内索引只读一次、各 layout.json 只解析一次。
 * 断言方式：读出后删文件再读——缓存命中则仍返回同一实例
 *（删文件后仍能拿到同一树，证明文件只解析过一次）。
 */
class LayoutLookupParseOnceTest {

    private class Fixture {
        val root: File = Files.createTempDirectory("skin-lookup").toFile()
        val layout = SkinLibraryLayout(root).also { it.ensureDirs() }
        val store = SkinLibraryStore(layout)

        /** 在索引中登记布局并落盘 layout.json，返回该文件。 */
        suspend fun writeLayout(id: String, dir: String = "layouts/$id"): File {
            val file = File(layout.fileFromRelative(dir), "layout.json").also {
                it.parentFile.mkdirs()
                it.writeText("""{"id":"$id","rows":[]}""")
            }
            store.writeIndex(
                LibraryIndex(
                    layouts = listOf(
                        LayoutIndexEntry(
                            id = id,
                            hash = "hash-$id",
                            generation = 1,
                            dir = dir,
                            sizeBytes = file.length(),
                            updatedAt = 1L,
                        ),
                    ),
                ),
            )
            return file
        }
    }

    @Test
    fun layoutParsedOnceAndIndexReadOnce() = runBlocking {
        val f = Fixture()
        val layoutFile = f.writeLayout("l1")
        val lookup = SkinSnapshotFactory.LayoutLookup(f.layout)

        val tree1 = lookup.layoutTree("l1")
        assertNotNull(tree1)

        // 删掉索引与布局文件：缓存命中则仍返回同一实例。
        assertTrue(layoutFile.delete())
        assertTrue(f.layout.indexFile.delete())
        val tree2 = lookup.layoutTree("l1")
        assertSame("layout.json must be parsed once per build", tree1, tree2)
        assertNotNull("index must be read once per build", lookup.entry("l1"))
    }

    @Test
    fun missingAndUnreadableReturnNull() = runBlocking {
        val f = Fixture()
        val layoutFile = f.writeLayout("l1")
        val lookup = SkinSnapshotFactory.LayoutLookup(f.layout)

        assertNull(lookup.entry("no-such-layout"))
        assertNull(lookup.layoutTree("no-such-layout"))

        // 有条目无文件 = 文件不可读，返回 null（与改前口径一致）。
        assertTrue(layoutFile.delete())
        val stale = SkinSnapshotFactory.LayoutLookup(f.layout)
        assertNotNull(stale.entry("l1"))
        assertNull(stale.layoutTree("l1"))
    }

    @Test
    fun noLibraryReturnsNull() = runBlocking {
        val lookup = SkinSnapshotFactory.LayoutLookup(null)
        assertFalse(lookup.hasLibrary)
        assertNull(lookup.entry("l1"))
        assertNull(lookup.layoutTree("l1"))
    }

    @Test
    fun entrySurvivesFileDeletion() = runBlocking {
        val f = Fixture()
        f.writeLayout("l1")
        val lookup = SkinSnapshotFactory.LayoutLookup(f.layout)
        assertEquals("l1", lookup.entry("l1")?.id)
        assertTrue(f.layout.indexFile.delete())
        assertEquals("l1", lookup.entry("l1")?.id)
    }
}
