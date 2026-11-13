// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.error

import android.database.sqlite.SQLiteException
import com.jobeen.ime.error.DeepErrorMapper.DeepErrorCode
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S-L7：NO_SPACE 漏一类——"disk full"／"database or disk is full" 此前
 * 被归为 IO。状态空间按消息来源分区：消息文本／IOException／SQLite
 * 类型分支；对照组钉住 malformed 仍为 CORRUPT、其余 SQLite 仍为 IO、
 * 旧 "no space left" 不变。
 */
class DeepErrorMapperNoSpaceTest {

    // 与 DeepErrorMapperTest 同款做法：android.jar 桩的 SQLiteException
    // 构造器不转发文案，JVM 单测里须用匿名子类覆写 message。
    private fun sqliteException(message: String) = object : SQLiteException() {
        override val message: String = message
    }

    @Test
    fun classifyMessage_diskFullVariantsAreNoSpace() {
        assertEquals(
            DeepErrorCode.NO_SPACE,
            DeepErrorMapper.classifyMessage("database or disk is full"),
        )
        assertEquals(DeepErrorCode.NO_SPACE, DeepErrorMapper.classifyMessage("disk full"))
        assertEquals(DeepErrorCode.NO_SPACE, DeepErrorMapper.classifyMessage("Disk Full"))
        assertEquals(
            DeepErrorCode.NO_SPACE,
            DeepErrorMapper.classifyMessage("write failed: database or disk is full"),
        )
    }

    @Test
    fun classify_ioExceptionWithDiskFullMessageIsNoSpace() {
        assertEquals(
            DeepErrorCode.NO_SPACE,
            DeepErrorMapper.classify(IOException("database or disk is full")),
        )
        assertEquals(
            DeepErrorCode.NO_SPACE,
            DeepErrorMapper.classify(IOException("No space left on device")),
        )
    }

    @Test
    fun classify_sqliteDiskFullIsNoSpaceNotIo() {
        // 类型分支先于消息分支：SQLiteException 的 "database or disk
        // is full" 若只走消息分支会被 classifyByType 抢先判成 IO。
        assertEquals(
            DeepErrorCode.NO_SPACE,
            DeepErrorMapper.classify(sqliteException("database or disk is full")),
        )
    }

    @Test
    fun classify_sqliteOtherBranchesUnchanged() {
        assertEquals(
            DeepErrorCode.CORRUPT,
            DeepErrorMapper.classify(sqliteException("database disk image is malformed")),
        )
        assertEquals(
            DeepErrorCode.IO,
            DeepErrorMapper.classify(sqliteException("unable to open database file")),
        )
    }
}
