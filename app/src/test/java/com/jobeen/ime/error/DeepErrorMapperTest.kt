// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.error

import android.database.sqlite.SQLiteException
import com.jobeen.ime.R
import com.jobeen.ime.error.DeepErrorMapper.DeepErrorCode
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.util.zip.ZipException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WO33 第 4(c) 项：深层异常 → 错误码映射的纯函数测试。
 * [DeepErrorMapper.userMessage]／[DeepErrorMapper.userMessageForMessage]
 * 的 Context 层（getString）不在 JVM 单测覆盖内，覆盖的是归类与
 * 尾巴替换——调用点传什么码、显示什么文案由这两层决定。
 */
class DeepErrorMapperTest {

    // ---------- classify：按类型 ----------

    @Test
    fun classify_unknownHostIsNetwork() {
        assertEquals(DeepErrorCode.NETWORK, DeepErrorMapper.classify(UnknownHostException("x")))
    }

    @Test
    fun classify_socketTimeoutIsTimeoutNotNetwork() {
        // SocketTimeoutException 是 SocketException 的子类，顺序错了
        // 就会被判成 NETWORK——此用例钉住分支顺序。
        assertEquals(DeepErrorCode.TIMEOUT, DeepErrorMapper.classify(SocketTimeoutException("timed out")))
        assertEquals(DeepErrorCode.NETWORK, DeepErrorMapper.classify(SocketException("Connection reset")))
    }

    @Test
    fun classify_connectIsNetwork() {
        assertEquals(DeepErrorCode.NETWORK, DeepErrorMapper.classify(ConnectException("Connection refused")))
    }

    @Test
    fun classify_fileNotFoundAndZipAndSerialization() {
        assertEquals(DeepErrorCode.NOT_FOUND, DeepErrorMapper.classify(FileNotFoundException("x")))
        assertEquals(DeepErrorCode.CORRUPT, DeepErrorMapper.classify(ZipException("bad zip")))
        assertEquals(DeepErrorCode.CORRUPT, DeepErrorMapper.classify(SerializationException("bad json")))
    }

    // android.jar 桩：SQLiteException(String) 构造不把文案转发给
    // Throwable，JVM 单测里 message 恒为 null，映射的 malformed 分支
    // 永远走不到；用匿名子类覆写 message 才能带上文案（真机上构造
    // 器正常转发，映射分支本身无问题）。
    private fun sqliteException(message: String) = object : SQLiteException() {
        override val message: String = message
    }

    @Test
    fun classify_sqliteMalformedIsCorruptOthersAreIo() {
        assertEquals(
            DeepErrorCode.CORRUPT,
            DeepErrorMapper.classify(sqliteException("database disk image is malformed")),
        )
        assertEquals(
            DeepErrorCode.IO,
            DeepErrorMapper.classify(sqliteException("unable to open database file")),
        )
    }

    @Test
    fun classify_permissionTypes() {
        assertEquals(DeepErrorCode.PERMISSION, DeepErrorMapper.classify(SecurityException("x")))
        assertEquals(DeepErrorCode.PERMISSION, DeepErrorMapper.classify(AccessDeniedException("x")))
    }

    @Test
    fun classify_genericIoByMessageThenFallbackIo() {
        assertEquals(
            DeepErrorCode.NO_SPACE,
            DeepErrorMapper.classify(IOException("No space left on device")),
        )
        assertEquals(DeepErrorCode.IO, DeepErrorMapper.classify(IOException("generic failure")))
    }

    @Test
    fun classify_walksCauseChainAndReturnsNullWhenUnmatched() {
        assertEquals(
            DeepErrorCode.NETWORK,
            DeepErrorMapper.classify(RuntimeException("wrap", UnknownHostException("x"))),
        )
        assertNull(DeepErrorMapper.classify(RuntimeException("boom")))
        assertNull(DeepErrorMapper.classify(null))
    }

    // ---------- classifyMessage：按消息特征 ----------

    @Test
    fun classifyMessage_networkKeywords() {
        assertEquals(DeepErrorCode.NETWORK, DeepErrorMapper.classifyMessage("Unable to resolve host \"example.com\""))
        assertEquals(DeepErrorCode.NETWORK, DeepErrorMapper.classifyMessage("Connection refused"))
        assertEquals(DeepErrorCode.NETWORK, DeepErrorMapper.classifyMessage("Network is unreachable"))
    }

    @Test
    fun classifyMessage_httpCodes() {
        assertEquals(DeepErrorCode.PERMISSION, DeepErrorMapper.classifyMessage("HTTP 403"))
        assertEquals(DeepErrorCode.PERMISSION, DeepErrorMapper.classifyMessage("列出服务器文件失败：HTTP 401"))
        assertEquals(DeepErrorCode.NOT_FOUND, DeepErrorMapper.classifyMessage("HTTP 404"))
        assertEquals(DeepErrorCode.TIMEOUT, DeepErrorMapper.classifyMessage("HTTP 408"))
        assertEquals(DeepErrorCode.TIMEOUT, DeepErrorMapper.classifyMessage("HTTP 504"))
        assertEquals(DeepErrorCode.SERVER, DeepErrorMapper.classifyMessage("HTTP 500"))
        assertEquals(DeepErrorCode.SERVER, DeepErrorMapper.classifyMessage("HTTP 503 Service Unavailable"))
        assertNull(DeepErrorMapper.classifyMessage("HTTP 400"))
    }

    @Test
    fun classifyMessage_storageAndCorrupt() {
        assertEquals(DeepErrorCode.NO_SPACE, DeepErrorMapper.classifyMessage("No space left on device"))
        assertEquals(DeepErrorCode.NOT_FOUND, DeepErrorMapper.classifyMessage("No such file or directory"))
        assertEquals(DeepErrorCode.PERMISSION, DeepErrorMapper.classifyMessage("Permission denied"))
        assertEquals(DeepErrorCode.CORRUPT, DeepErrorMapper.classifyMessage("database disk image is malformed"))
        assertEquals(DeepErrorCode.TIMEOUT, DeepErrorMapper.classifyMessage("Connection timed out"))
    }

    @Test
    fun classifyMessage_curatedChineseHasNoMarker() {
        // 应用层自写的中文提示本就是给用户看的：无特征 → null，
        // 调用方原样保留，不硬套通用文案。
        assertNull(DeepErrorMapper.classifyMessage("同步目录不存在，请检查同步路径是否填写正确"))
        assertNull(DeepErrorMapper.classifyMessage("同步目录下没有该备份文件"))
        assertNull(DeepErrorMapper.classifyMessage("词典名非法，拒绝上传"))
        assertNull(DeepErrorMapper.classifyMessage(null))
        assertNull(DeepErrorMapper.classifyMessage(""))
    }

    // ---------- sanitizeTail：组合消息只换尾巴 ----------

    @Test
    fun sanitizeTail_keepsOutcomePrefixReplacesRawTail() {
        assertEquals(
            "还原失败，已恢复到还原前状态：M",
            DeepErrorMapper.sanitizeTail("还原失败，已恢复到还原前状态：No space left on device", "M"),
        )
        assertEquals(
            "migration plan failed:M",
            DeepErrorMapper.sanitizeTail("migration plan failed: Unable to resolve host", "M"),
        )
    }

    @Test
    fun sanitizeTail_replacesParenthesisedTailOnly() {
        assertEquals(
            "还原准备失败：无法保存还原前快照（M）",
            DeepErrorMapper.sanitizeTail("还原准备失败：无法保存还原前快照（No space left on device）", "M"),
        )
    }

    @Test
    fun sanitizeTail_httpTailWithBodyIsReplacedWholeTail() {
        // 尾段含 HTTP 500＋服务端正文：整段换掉，正文与 URL 不上屏。
        assertEquals(
            "列出服务器文件失败：M",
            DeepErrorMapper.sanitizeTail("列出服务器文件失败：HTTP 500（服务端正文）", "M"),
        )
    }

    @Test
    fun sanitizeTail_noMarkerKeepsOriginal() {
        assertEquals(
            "move failed: skin1",
            DeepErrorMapper.sanitizeTail("move failed: skin1", "M"),
        )
        assertEquals(
            "还原前快照损坏，无法回滚：读取失败",
            DeepErrorMapper.sanitizeTail("还原前快照损坏，无法回滚：读取失败", "M"),
        )
    }

    @Test
    fun sanitizeTail_bareRawMessageIsReplacedWhole() {
        assertEquals("M", DeepErrorMapper.sanitizeTail("No space left on device", "M"))
    }

    // ---------- messageResId：码 → 串 ----------

    @Test
    fun messageResIds_matchDeepErrorStrings() {
        assertEquals(R.string.deep_error_network, DeepErrorMapper.messageResId(DeepErrorCode.NETWORK))
        assertEquals(R.string.deep_error_timeout, DeepErrorMapper.messageResId(DeepErrorCode.TIMEOUT))
        assertEquals(R.string.deep_error_server, DeepErrorMapper.messageResId(DeepErrorCode.SERVER))
        assertEquals(R.string.deep_error_no_space, DeepErrorMapper.messageResId(DeepErrorCode.NO_SPACE))
        assertEquals(R.string.deep_error_not_found, DeepErrorMapper.messageResId(DeepErrorCode.NOT_FOUND))
        assertEquals(R.string.deep_error_corrupt, DeepErrorMapper.messageResId(DeepErrorCode.CORRUPT))
        assertEquals(R.string.deep_error_permission, DeepErrorMapper.messageResId(DeepErrorCode.PERMISSION))
        assertEquals(R.string.deep_error_io, DeepErrorMapper.messageResId(DeepErrorCode.IO))
        assertEquals(R.string.deep_error_unknown, DeepErrorMapper.messageResId(DeepErrorCode.UNKNOWN))
    }
}
