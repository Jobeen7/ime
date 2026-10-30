package com.jobeen.ime.skin.qr

import android.content.Context
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.pkg.JimeImporter
import com.jobeen.ime.skin.pkg.SkinPackages
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 皮肤二维码导入（2026-10-27 新增分流）。
 *
 * 链路：扫码文本 → [SkinQrPayload.parse]（前缀分流/格式门）→
 * 内联型就地组包 / URL 型下载验签 → 既有
 * [JimeImporter.importZip] 全量校验 → 与文件导入相同的
 * Outcome（Imported / ConflictPending / Rejected）。
 *
 * 关键口径：
 * - 绝不绕过校验：内联包同样走 manifest + 校验器 + 配额 +
 *   冲突判定；非法包由导入管线拒绝并记 import_rejected，本层
 *   再记一条 qr_import_rejected（reason + kind，不记原文）。
 * - URL 只记 host（绝不记完整 URL：query 可能含 token）；
 *   异常只记类名（OkHttp 异常文案可能含 URL）。
 * - 冲突转副本复用库页既有流程：本层返回的 packageFile 即
 *   可复投的 .jime 临时文件，UI 的“以副本导入”按钮直接复用。
 * - 临时文件落在 cacheDir，导入后由 UI 侧沿既有习惯处理
 *   （文件导入的 temp 同样由调用方持有；本层不擅自删除，
 *   避免冲突待决时副本入口丢文件）。
 */
object SkinQrImporter {

    data class Result(
        val outcome: JimeImporter.Outcome,
        /** 可复投的 .jime 临时文件（解析失败时为 null）。 */
        val packageFile: File?,
    )

    private fun rejected(
        reason: String,
        kind: String,
        skinId: String? = null,
    ): Result {
        DiagLog.w(
            "qr", "qr_import_rejected",
            buildMap {
                put("reason", reason)
                put("kind", kind)
                if (skinId != null) put("skinId", skinId)
            },
        )
        return Result(JimeImporter.Outcome.Rejected(reason, emptyList()), null)
    }

    /**
     * 调用方（库页 runBusy）在主线程调本函数：解析是轻量纯计算
     * 可在调用线程做，下载/写盘/导入一律切 Dispatchers.IO——
     * OkHttp 同步 execute() 在主线程会抛 NetworkOnMainThread，
     * 不能依赖调用方包 IO 上下文。
     */
    suspend fun importFromQrText(
        context: Context,
        rawText: String?,
        mode: JimeImporter.ConflictMode = JimeImporter.ConflictMode.FAIL,
        client: OkHttpClient = defaultClient(),
    ): Result {
        val parsed = SkinQrPayload.parse(rawText)
        return withContext(Dispatchers.IO) {
            importParsed(context, parsed, rawText, mode, client)
        }
    }

    private suspend fun importParsed(
        context: Context,
        parsed: SkinQrPayload.ParseResult,
        rawText: String?,
        mode: JimeImporter.ConflictMode,
        client: OkHttpClient,
    ): Result {
        return when (parsed) {
            is SkinQrPayload.ParseResult.Rejected -> {
                val kind = when {
                    rawText?.trim().orEmpty().startsWith(SkinQrPayload.LEGACY_PREFIX) -> "legacy"
                    rawText?.trim().orEmpty().startsWith(SkinQrPayload.URL_PREFIX) -> "url"
                    rawText?.trim().orEmpty().startsWith(SkinQrPayload.INLINE_PREFIX) -> "inline"
                    else -> "unknown"
                }
                rejected(parsed.reason, kind)
            }
            is SkinQrPayload.ParseResult.Ok -> when (val payload = parsed.parsed) {
                is SkinQrPayload.Parsed.Inline -> {
                    val skinId = payload.metaId ?: SkinQrPayload.newQrSkinId()
                    val bytes = runCatching {
                        SkinQrPayload.buildPackageZip(payload.skinJson, skinId)
                    }.getOrNull() ?: return rejected("qr_package_build_failed", "inline", skinId)
                    val file = writeTemp(context, bytes, "skin-qr-inline")
                        ?: return rejected("qr_temp_write_failed", "inline", skinId)
                    importFile(context, file, mode, "inline", skinId)
                }
                is SkinQrPayload.Parsed.Url -> {
                    val host = runCatching {
                        payload.url.removePrefix("https://").substringBefore('/').substringBefore('?')
                    }.getOrNull() ?: ""
                    when (val downloaded = download(client, payload.url, payload.sha256, host)) {
                        is DownloadResult.Failed -> rejected(downloaded.reason, "url")
                        is DownloadResult.Bytes -> {
                            val file = writeTemp(context, downloaded.bytes, "skin-qr-url")
                                ?: return rejected("qr_temp_write_failed", "url")
                            importFile(context, file, mode, "url", null)
                        }
                    }
                }
            }
        }
    }

    private suspend fun importFile(
        context: Context,
        file: File,
        mode: JimeImporter.ConflictMode,
        kind: String,
        skinId: String?,
    ): Result {
        val importer = SkinPackages.importer(context)
        val ctx = SkinPackages.importContext(context)
        val outcome = importer.importZip(file, ctx, mode)
        when (outcome) {
            is JimeImporter.Outcome.Imported -> DiagLog.i(
                "qr", "qr_import_done",
                buildMap {
                    put("kind", kind)
                    put("importedSkinCount", outcome.report.importedSkinIds.size)
                    if (skinId != null) put("skinId", skinId)
                },
            )
            is JimeImporter.Outcome.ConflictPending -> DiagLog.i(
                "qr", "qr_import_conflict",
                mapOf("kind" to kind, "conflictCount" to outcome.conflicts.size),
            )
            is JimeImporter.Outcome.Rejected -> DiagLog.w(
                "qr", "qr_import_rejected",
                buildMap {
                    put("reason", outcome.reason)
                    put("kind", kind)
                    if (skinId != null) put("skinId", skinId)
                },
            )
        }
        return Result(outcome, file)
    }

    private fun writeTemp(context: Context, bytes: ByteArray, prefix: String): File? {
        return runCatching {
            val file = File(context.cacheDir, "$prefix-${System.currentTimeMillis()}.jime")
            file.writeBytes(bytes)
            file
        }.getOrNull()
    }

    /** 下载结果：失败原因只带 reason 码，不带异常文案（可能含 URL）。 */
    private sealed interface DownloadResult {
        data class Bytes(val bytes: ByteArray) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    /** 下载并（可选）验整包 sha256；失败已记 qr_download_rejected。 */
    private fun download(
        client: OkHttpClient,
        url: String,
        expectedSha256: String?,
        host: String,
    ): DownloadResult {
        fun fail(reason: String, errorClass: String? = null): DownloadResult {
            DiagLog.w(
                "qr", "qr_download_rejected",
                buildMap {
                    put("reason", reason)
                    put("host", host)
                    if (errorClass != null) put("errorClass", errorClass)
                },
            )
            return DownloadResult.Failed(reason)
        }
        return runCatching {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return fail("qr_download_http:${response.code}")
                }
                val body = response.body ?: return fail("qr_download_empty_body")
                val buffer = ByteArrayOutputStreamCapped(SkinQrPayload.MAX_DOWNLOAD_BYTES)
                body.byteStream().use { input ->
                    val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        if (!buffer.writeCapped(chunk, read)) {
                            return fail("qr_download_too_large")
                        }
                    }
                }
                val bytes = buffer.bytes()
                if (expectedSha256 != null && DigestUtil.sha256Hex(bytes) != expectedSha256) {
                    return fail("qr_download_sha_mismatch")
                }
                DownloadResult.Bytes(bytes)
            }
        }.getOrElse { error ->
            fail("qr_download_failed", error::class.simpleName ?: "unknown")
        }
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 带上限的字节累积器：超限即停，不把整包读进内存。 */
    private class ByteArrayOutputStreamCapped(private val cap: Long) {
        private val chunks = ArrayList<ByteArray>()
        private var total = 0L

        fun writeCapped(chunk: ByteArray, length: Int): Boolean {
            if (total + length > cap) return false
            chunks += chunk.copyOf(length)
            total += length
            return true
        }

        fun bytes(): ByteArray {
            val out = ByteArray(total.toInt())
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(out, offset)
                offset += chunk.size
            }
            return out
        }
    }
}
