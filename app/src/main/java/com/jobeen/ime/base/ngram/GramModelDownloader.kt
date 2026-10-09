package com.jobeen.ime.base.ngram

import com.jobeen.ime.base.net.HttpUtil
import com.jobeen.ime.engine.rime.data.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class GramModelManifest(
    val link: String = "",
    val sha256: String = "",
)

object GramModelDownloader {
    private const val BUFFER_SIZE = 32 * 1024
    private const val REPORT_STEP = 256 * 1024L
    // Grammar models are currently about 400 MB. Keep headroom for upstream
    // growth while refusing unbounded or obviously invalid responses.
    private const val MAX_MODEL_BYTES = 600L * 1024 * 1024
    private const val PART_SUFFIX = ".part"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        language: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val manifest = runCatching {
            GramModelApi.fetchManifest(language)
        }.onFailure {
            Timber.e(it, "Failed to fetch ngram model manifest")
            if (it !is kotlinx.coroutines.CancellationException &&
                it !is HttpUtil.ApiException
            ) {
                HttpUtil.showToast("模型增强下载失败：${it.message ?: "网络错误"}")
            }
        }.getOrNull()
            ?: return@withContext false
        val link = manifest.link.trim()
        if (link.isEmpty()) {
            Timber.w("Ngram model manifest has no download link: %s", language)
            return@withContext false
        }

        val target = File(DataManager.sharedDataDir, "$language.gram")
        val partial = File(target.parentFile, target.name + PART_SUFFIX)
        partial.delete()
        if (!downloadFile(link, partial, manifest.sha256, onProgress)) {
            partial.delete()
            return@withContext false
        }

        // 不先删 target 再 rename：.part 与 target 同目录，rename 本身即
        // 原子覆盖，先删会打开「旧模型已删、新模型未就位」的空窗，中途
        // 被杀将无模型可用（与 WanxiangUpdateManager 语法模型落盘同款规避）
        check(partial.renameTo(target)) { "Failed to finalize ngram model: ${target.absolutePath}" }
        target.isFile && target.length() > 0L
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        expectedSha256: String,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Ngram model download failed: HTTP %d", response.code)
                    HttpUtil.showToast("模型增强下载失败：HTTP ${response.code}")
                    return false
                }
                val body = response.body ?: return false
                val total = body.contentLength()
                val expected = expectedSha256.trim().lowercase()
                if (expected.isNotEmpty() && !expected.matches(Regex("[0-9a-f]{64}"))) {
                    Timber.w("Ngram model manifest contains an invalid SHA-256")
                    return false
                }
                if (total > MAX_MODEL_BYTES) {
                    Timber.w("Ngram model exceeds size limit: %d > %d", total, MAX_MODEL_BYTES)
                    return false
                }
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var reported = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) return false
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (downloaded + count > MAX_MODEL_BYTES) {
                                Timber.w("Ngram model exceeded size limit while streaming")
                                return false
                            }
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - reported >= REPORT_STEP ||
                                (total > 0 && downloaded >= total)
                            ) {
                                onProgress(downloaded, total)
                                reported = downloaded
                            }
                        }
                        if (downloaded == 0L || (total >= 0L && downloaded != total)) {
                            Timber.w("Ngram model length mismatch: expected=%d actual=%d", total, downloaded)
                            return false
                        }
                        onProgress(downloaded, total)
                    }
                }
                if (expected.isNotEmpty()) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    target.inputStream().use { input ->
                        val digestBuffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(digestBuffer)
                            if (count < 0) break
                            digest.update(digestBuffer, 0, count)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actual != expected) {
                        Timber.w("Ngram model SHA-256 mismatch")
                        return false
                    }
                }
                true
            }
        }.onFailure {
            Timber.e(it, "Ngram model download failed")
            if (it !is kotlinx.coroutines.CancellationException) {
                HttpUtil.showToast("模型增强下载失败：${it.message ?: "网络错误"}")
            }
        }.getOrDefault(false)
    }

}

private object GramModelApi {
    suspend fun fetchManifest(language: String): GramModelManifest {
        val encoded = java.net.URLEncoder.encode(language, Charsets.UTF_8.name())
        return HttpUtil.get("model/grammar?language=$encoded")
    }
}
