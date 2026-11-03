package com.jobeen.ime

import com.jobeen.ime.base.speech.cloud.PluginHost
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.input.InputFieldKey
import com.jobeen.ime.input.OpenUrlPolicy
import com.jobeen.ime.input.ScriptRunSession
import com.jobeen.ime.skin.script.SkinScriptFailure
import com.jobeen.ime.skin.script.SkinScriptRunOutcome
import com.jobeen.ime.skin.script.SkinScriptSandbox
import com.jobeen.ime.skin.store.AtomicFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 第 4 批（非皮肤 P2/P3）纯逻辑单测：不碰 Android 框架方法，
 * 不用 Robolectric。设备行为（键盘确认框、脚本管理页后台化、
 * 部署超时真机时长）只标 device-verify，不在此断言。
 */
class Batch4NonSkinFixesTest {

    private val fieldA = InputFieldKey(
        packageName = "com.example.app",
        fieldId = 1001,
        inputType = 0x00000001,
        imeOptions = 0x40000000,
    )
    private val fieldB = fieldA.copy(fieldId = 1002)

    // ---- 第 1 项：脚本会话快照 ----

    @Test
    fun scriptSession_sameFieldAndEpoch_isCurrent() {
        val captured = ScriptRunSession(fieldA, 7L)
        assertTrue(ScriptRunSession.isCurrent(captured, fieldA, 7L))
    }

    @Test
    fun scriptSession_fieldChanged_isStale() {
        val captured = ScriptRunSession(fieldA, 7L)
        assertFalse(ScriptRunSession.isCurrent(captured, fieldB, 7L))
    }

    @Test
    fun scriptSession_epochAdvanced_isStale() {
        val captured = ScriptRunSession(fieldA, 7L)
        assertFalse(ScriptRunSession.isCurrent(captured, fieldA, 8L))
    }

    @Test
    fun scriptSession_nullField_sameEpoch_isCurrent() {
        val captured = ScriptRunSession(null, 3L)
        val noField: InputFieldKey? = null
        assertTrue(ScriptRunSession.isCurrent(captured, noField, 3L))
    }

    @Test
    fun scriptSession_nullVsField_isStale() {
        val captured = ScriptRunSession(null, 3L)
        assertFalse(ScriptRunSession.isCurrent(captured, fieldA, 3L))
    }

    // ---- 第 2 项：WebDAV 重读决策补齐（无 ETag/If-Match） ----

    @Test
    fun putWithRereadRetry_alwaysChanging_fetchesBoundedByAttempts() = runBlocking {
        var fetches = 0
        var merges = 0
        var puts = 0
        WebDavSync.putWithRereadRetry(
            initialHash = "A",
            fetchHash = { "R${++fetches}" },
            mergeAndReexport = { merges++; "R$fetches" },
            put = { puts++ },
            maxAttempts = 3,
        )
        assertEquals(3, fetches)
        assertEquals(3, merges)
        assertEquals(1, puts)
    }

    @Test
    fun putWithRereadRetry_mergeFails_abortsWithoutPut() = runBlocking {
        var puts = 0
        try {
            WebDavSync.putWithRereadRetry(
                initialHash = "A",
                fetchHash = { "B" },
                mergeAndReexport = { throw IllegalStateException("合并失败") },
                put = { puts++ },
            )
            fail("合并失败应直接抛错，不应 PUT")
        } catch (e: IllegalStateException) {
            assertEquals("合并失败", e.message)
        }
        assertEquals(0, puts)
    }

    // ---- 第 3 项：GZIP 按块限流 ----

    private fun gzipOf(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    @Test
    fun gunzipBytes_smallRoundtrip_ok() {
        val plain = "hello doubao".toByteArray(Charsets.UTF_8)
        assertEquals(
            "hello doubao",
            String(PluginHost.gunzipBytes(gzipOf(plain)), Charsets.UTF_8),
        )
    }

    @Test
    fun gunzipBytes_atCap_ok() {
        val plain = ByteArray(PluginHost.MAX_GUNZIP_BYTES) { 0 }
        assertEquals(PluginHost.MAX_GUNZIP_BYTES, PluginHost.gunzipBytes(gzipOf(plain)).size)
    }

    @Test
    fun gunzipBytes_overCap_throwsBeforeFullMaterialization() {
        val plain = ByteArray(PluginHost.MAX_GUNZIP_BYTES + 1) { 0 }
        try {
            PluginHost.gunzipBytes(gzipOf(plain))
            fail("超限解压应抛错，不应返回完整输出")
        } catch (e: IllegalStateException) {
            assertEquals("gunzip output exceeds limit", e.message)
        }
    }

    @Test
    fun gunzipBytes_corrupt_throws() {
        try {
            PluginHost.gunzipBytes("not gzip".toByteArray(Charsets.UTF_8))
            fail("损坏输入应抛错")
        } catch (e: Exception) {
            assertTrue(e is java.io.IOException || e is IllegalStateException)
        }
    }

    // ---- 第 4 项：脚本全流程单一预算 ----

    @Test
    fun scriptSandbox_evalPlusCall_shareSingleBudget() {
        // 顶层 350ms＋onEvent 350ms：每段单看皆 <500ms，旧
        // 分段预算下能过；共享 500ms 总预算下必超限。
        val source = """
            var t0 = Date.now();
            while (Date.now() - t0 < 350) {}
            function onEvent(ctx) {
                var t1 = Date.now();
                while (Date.now() - t1 < 350) {}
                return 'done';
            }
        """.trimIndent()
        val outcome = SkinScriptSandbox().run(source, "press", emptyMap())
        assertEquals(
            SkinScriptRunOutcome.Failed(SkinScriptFailure.BudgetExceeded),
            outcome,
        )
    }

    @Test
    fun scriptSandbox_shortScript_underTotalBudget_stillOk() {
        val outcome = SkinScriptSandbox().run(
            "function onEvent(ctx) { return 'ok'; }",
            "press",
            emptyMap(),
        )
        val ok = outcome as SkinScriptRunOutcome.Ok
        assertEquals("ok", ok.value)
    }

    // ---- 第 6 项 a：open_url 长度上限 ----

    @Test
    fun openUrlPolicy_overLongBlocked_atLimitAllowed() {
        assertTrue(OpenUrlPolicy.isAllowed("https://example.com/path"))
        val atLimit = "https://example.com/" + "a".repeat(
            OpenUrlPolicy.MAX_URL_LENGTH - "https://example.com/".length,
        )
        assertEquals(OpenUrlPolicy.MAX_URL_LENGTH, atLimit.length)
        assertTrue(OpenUrlPolicy.isAllowed(atLimit))
        assertFalse(OpenUrlPolicy.isAllowed(atLimit + "a"))
    }

    // ---- 第 6 项 e：共用原子写 ----

    @Test
    fun atomicFilesWrite_createsParentsAndReplacesFully() {
        val root = Files.createTempDirectory("batch4-atomic").toFile()
        try {
            val target = File(root, "sub/dir/file.txt")
            AtomicFiles.write(target, "first".toByteArray(Charsets.UTF_8))
            assertEquals("first", target.readText(Charsets.UTF_8))
            AtomicFiles.write(target, "second-longer".toByteArray(Charsets.UTF_8))
            assertEquals("second-longer", target.readText(Charsets.UTF_8))
            // 无 tmp 残留。
            val leftovers = root.walkTopDown()
                .filter { it.name.contains(".tmp-") }
                .toList()
            assertTrue(leftovers.isEmpty())
            // 目录＋文件名口径走同一实现。
            AtomicFiles.writeAtomically(root, "plain.txt", "plain")
            assertEquals("plain", File(root, "plain.txt").readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }
}
