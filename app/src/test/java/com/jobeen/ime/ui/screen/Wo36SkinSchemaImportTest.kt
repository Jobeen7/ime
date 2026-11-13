// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO36 SchemaSettingsScreen:629：SAF 复制失败的空间不足判定不再内联
 * 关键词匹配（"No space left"／"ENOSPC"），改走 DeepErrorMapper
 * 既有分类口径（[isImportCopyNoSpace]）。
 *
 * 原行为保留：ENOSPC → schema_import_error_io，其余 IOException →
 * schema_import_error_copy（两串不动）；分类面按 DeepErrorMapper
 * 口径拓宽一档——"disk full" 类消息此前误判为复制失败，现判空间不足。
 */
class Wo36SkinSchemaImportTest {

    @Test
    fun noSpaceLeftIsNoSpace() {
        assertTrue(isImportCopyNoSpace(IOException("No space left on device")))
    }

    @Test
    fun enospcIsNoSpace() {
        assertTrue(isImportCopyNoSpace(IOException("write failed: ENOSPC")))
    }

    @Test
    fun diskFullIsNoSpace() {
        // DeepErrorMapper S-L7 口径：SQLite／写盘的 "disk full" 也是空间不足；
        // 旧内联检查只认 "No space left"／"ENOSPC"，会误判为复制失败。
        assertTrue(isImportCopyNoSpace(IOException("disk full")))
        assertTrue(isImportCopyNoSpace(IOException("database or disk is full")))
    }

    @Test
    fun genericIoIsNotNoSpace() {
        assertFalse(isImportCopyNoSpace(IOException("boom")))
        assertFalse(isImportCopyNoSpace(IOException("No such file or directory")))
    }

    @Test
    fun timeoutIsNotNoSpace() {
        assertFalse(isImportCopyNoSpace(IOException("Connection timed out")))
    }
}
