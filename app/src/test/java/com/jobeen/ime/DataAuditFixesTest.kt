// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.jobeen.ime.data.manager.DeletedWordEntry
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.RimeSyncPrefs
import com.jobeen.ime.data.manager.decodeDeletedWordEntry
import com.jobeen.ime.data.manager.encodeDeletedWordEntry
import com.jobeen.ime.data.manager.mergeDeletedWordEntries
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * data 审计修复（2026-11-12 全量审 D 系）中 JVM 可测的部分：
 * 跨包通知队列＋UUID 竞态、删词转义／合并平局、部署位抢占与等待。
 * 需 Room／真机 Context 的修复（备份回滚、剪贴板事务、主题落盘、
 * 重置）本地 JVM 单测跑不通（见 ClipboardGroupTest 同款说明），
 * 由全量单测与真机验收覆盖，不在此文件硬凑。
 */
class DataAuditFixesTest {

    // ---- 内存版 SharedPreferences（JUnit-only、无 Robolectric/Mockito） ----

    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? Set<String> ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long =
            values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float =
            values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var cleared = false
            override fun putString(k: String, v: String?) = apply { pending[k] = v }
            override fun putStringSet(k: String, v: Set<String>?) = apply { pending[k] = v }
            override fun putInt(k: String, v: Int) = apply { pending[k] = v }
            override fun putLong(k: String, v: Long) = apply { pending[k] = v }
            override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
            override fun remove(k: String) = apply { pending[k] = null }
            override fun clear() = apply { cleared = true; pending.clear() }
            override fun commit(): Boolean {
                if (cleared) values.clear()
                for ((k, v) in pending) {
                    if (v == null) values.remove(k) else values[k] = v
                }
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    private class FakeContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    // ---- 跨包：待发通知队列（旧单值槽后一条覆盖前一条） ----

    @Test
    fun pendingNotices_deliveredFifoOneAtATime() {
        val ctx = FakeContext(FakePrefs())
        RimeSyncPrefs.postNotice(ctx, "first")
        RimeSyncPrefs.postNotice(ctx, "second")
        RimeSyncPrefs.postNotice(ctx, "third")
        assertEquals("first", RimeSyncPrefs.drainNotice(ctx))
        assertEquals("second", RimeSyncPrefs.drainNotice(ctx))
        assertEquals("third", RimeSyncPrefs.drainNotice(ctx))
        assertNull(RimeSyncPrefs.drainNotice(ctx))
    }

    @Test
    fun pendingNotices_legacySingleValueMigratesFirst() {
        val prefs = FakePrefs()
        val ctx = FakeContext(prefs)
        // 旧单值键遗留一条通知（字面键 pending_notice，见 RimeSyncPrefs）。
        prefs.edit().putString("pending_notice", "legacy").commit()
        RimeSyncPrefs.postNotice(ctx, "new")
        assertEquals("legacy", RimeSyncPrefs.drainNotice(ctx))
        assertEquals("new", RimeSyncPrefs.drainNotice(ctx))
        assertNull(RimeSyncPrefs.drainNotice(ctx))
        // 迁移后旧键已清，不会重复投递。
        assertFalse(prefs.contains("pending_notice"))
    }

    @Test
    fun pendingNotices_disabledDropsOnePerDrain() {
        val ctx = FakeContext(FakePrefs())
        RimeSyncPrefs.setNoticeEnabled(ctx, false)
        RimeSyncPrefs.postNotice(ctx, "first")
        RimeSyncPrefs.postNotice(ctx, "second")
        assertNull(RimeSyncPrefs.drainNotice(ctx))
        assertNull(RimeSyncPrefs.drainNotice(ctx))
        assertNull(RimeSyncPrefs.drainNotice(ctx))
    }

    @Test
    fun pendingNotices_blankNeverQueued() {
        val ctx = FakeContext(FakePrefs())
        RimeSyncPrefs.postNotice(ctx, "   ")
        RimeSyncPrefs.postNotice(ctx, "")
        assertNull(RimeSyncPrefs.drainNotice(ctx))
    }

    @Test
    fun pendingNotices_overflowDropsOldest() {
        val ctx = FakeContext(FakePrefs())
        for (i in 0 until 12) RimeSyncPrefs.postNotice(ctx, "n$i")
        // 上限 10 条：最旧两条（n0、n1）丢掉，n2 先出。
        assertEquals("n2", RimeSyncPrefs.drainNotice(ctx))
        var last = ""
        for (i in 3..11) last = RimeSyncPrefs.drainNotice(ctx) ?: ""
        assertEquals("n11", last)
        assertNull(RimeSyncPrefs.drainNotice(ctx))
    }

    // ---- D-M5：同步文件夹 UUID 生成竞态 ----

    @Test
    fun syncFolderUuid_stableAcrossCalls() {
        val ctx = FakeContext(FakePrefs())
        val first = RimeSyncPrefs.syncFolderUuid(ctx)
        assertTrue(first.isNotBlank())
        assertEquals(first, RimeSyncPrefs.syncFolderUuid(ctx))
        assertEquals(first, RimeSyncPrefs.syncFolderUuid(ctx))
    }

    @Test
    fun syncFolderUuid_concurrentFirstReadsReturnOneValue() {
        val prefs = FakePrefs()
        val ctx = FakeContext(prefs)
        val gate = CountDownLatch(1)
        val results = List(2) { AtomicReference<String>() }
        val threads = results.map { ref ->
            thread {
                gate.await()
                ref.set(RimeSyncPrefs.syncFolderUuid(ctx))
            }
        }
        gate.countDown()
        threads.forEach { it.join(10_000) }
        // 两路并发首次读取不得各生成一个 UUID：返回值一致，且与落盘值一致。
        assertEquals(results[0].get(), results[1].get())
        assertEquals(results[0].get(), prefs.getString("sync_folder_uuid", null))
    }

    // ---- D-M10：删词转义与合并平局 ----

    @Test
    fun deletedWord_tabNewlineBackslashWordsRoundTrip() {
        val words = listOf(
            "a\tb",
            "a\nb",
            "a\rb",
            "a\\b",
            "a\\tb",
            "词\t词\n词",
        )
        for (word in words) {
            val entry = DeletedWordEntry(deleted = true, updatedAt = 123L)
            val line = encodeDeletedWordEntry(word, entry)
            val (decodedWord, decodedEntry) = decodeDeletedWordEntry(line)!!
            assertEquals(word, decodedWord)
            assertEquals(entry, decodedEntry)
        }
    }

    @Test
    fun deletedWord_oldFormatStillDecodes() {
        val (word, entry) = decodeDeletedWordEntry("词")!!
        assertEquals("词", word)
        assertEquals(DeletedWordEntry(deleted = true, updatedAt = 0L), entry)
    }

    @Test
    fun deletedWord_mergeTiePrefersRestored() {
        // 同毫秒平局：恢复赢——恢复在因果上必然晚于删除。
        val local = mapOf("词" to DeletedWordEntry(deleted = true, updatedAt = 100L))
        val remote = mapOf("词" to DeletedWordEntry(deleted = false, updatedAt = 100L))
        assertEquals(false, mergeDeletedWordEntries(local, remote)["词"]?.deleted)
        // 方向反过来同样恢复赢。
        assertEquals(false, mergeDeletedWordEntries(remote, local)["词"]?.deleted)
        // 双方都已删时状态不变。
        val bothDeleted = mapOf("词" to DeletedWordEntry(deleted = true, updatedAt = 100L))
        assertEquals(true, mergeDeletedWordEntries(bothDeleted, bothDeleted)["词"]?.deleted)
        // 时间戳仍是主判据：新删除赢过旧恢复。
        val newerDelete = mapOf("词" to DeletedWordEntry(deleted = true, updatedAt = 200L))
        val olderRestore = mapOf("词" to DeletedWordEntry(deleted = false, updatedAt = 100L))
        assertEquals(true, mergeDeletedWordEntries(olderRestore, newerDelete)["词"]?.deleted)
    }

    // ---- D-L6：部署位抢占与等待 ----

    @Before
    fun resetDeployState() {
        EngineDeployState.releaseRunning()
        EngineDeployState.releaseOrphanRunning()
    }

    @Test
    fun tryAcquireRunning_secondAcquireFailsUntilReleased() {
        assertTrue(EngineDeployState.tryAcquireRunning())
        assertFalse(EngineDeployState.tryAcquireRunning())
        assertTrue(EngineDeployState.isRunning())
        EngineDeployState.releaseRunning()
        assertFalse(EngineDeployState.isRunning())
        assertTrue(EngineDeployState.tryAcquireRunning())
        EngineDeployState.releaseRunning()
    }

    @Test
    fun tryAcquireOrphanRunning_secondAcquireFailsUntilReleased() {
        assertTrue(EngineDeployState.tryAcquireOrphanRunning())
        assertFalse(EngineDeployState.tryAcquireOrphanRunning())
        assertTrue(EngineDeployState.isOrphanRunning())
        EngineDeployState.releaseOrphanRunning()
        assertFalse(EngineDeployState.isOrphanRunning())
    }

    // awaitSchemasReady 的行为（立即就绪／轮询后就绪／超时／异常计未就绪）
    // 已由 EngineDeployStateTest 全覆盖，此处不重复。
}
