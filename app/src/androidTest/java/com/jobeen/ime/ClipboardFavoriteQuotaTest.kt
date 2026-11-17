// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jobeen.ime.data.database.ClipboardDatabase
import com.jobeen.ime.data.database.ClipboardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 剪贴板 100 条配额计数（1.11）：收藏行不计入 [ClipboardDao.countUnpinned]，
 * 与淘汰口径（deleteOldest／deleteOlderThan 只删未置顶未收藏行）一致。
 * Room SQL 在 JVM 单测里跑不了（见 ClipboardDedupeTest 的说明），故放
 * 仪器测试：本机未跑（需真机/模拟器），与 DatabaseMigrationTest 同口径。
 * 使用测试专用库名，不触碰生产数据。
 */
@RunWith(AndroidJUnit4::class)
class ClipboardFavoriteQuotaTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun countUnpinned_excludesFavoritesAndPinned() = runBlocking {
        val db = ClipboardDatabase.createWithName(context, "clipboard_database_test_quota")
        try {
            val dao = db.clipboardDao()
            dao.insertAll(
                listOf(
                    ClipboardRecord(text = "普通1", timestamp = 100),
                    ClipboardRecord(text = "普通2", timestamp = 200),
                    ClipboardRecord(text = "收藏", timestamp = 300, isFavorite = true),
                    ClipboardRecord(text = "置顶", timestamp = 400, pinned = true),
                    ClipboardRecord(text = "收藏且置顶", timestamp = 500, pinned = true, isFavorite = true),
                    ClipboardRecord(text = "软删", timestamp = 600, deleted = true),
                ),
            )
            // 只数未置顶、未收藏的有效行：普通 2 条。
            assertEquals(2, dao.countUnpinned())
            // 对照：总量口径仍是全部有效行。
            assertEquals(5, dao.count())
        } finally {
            db.close()
            context.deleteDatabase("clipboard_database_test_quota")
        }
    }
}
