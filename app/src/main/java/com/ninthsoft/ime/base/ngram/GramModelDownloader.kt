package com.ninthsoft.ime.base.ngram

import com.ninthsoft.ime.base.net.HttpUtil
import com.ninthsoft.ime.engine.rime.data.DataManager
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
)

object GramModelDownloader {
    private const val BUFFER_SIZE = 32 * 1024
    private const val REPORT_STEP = 256 * 1024L
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
        if (!downloadFile(link, partial, onProgress)) {
            partial.delete()
            return@withContext false
        }

        target.delete()
        check(partial.renameTo(target)) { "Failed to finalize ngram model: ${target.absolutePath}" }
        target.isFile && target.length() > 0L
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
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
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - reported >= REPORT_STEP ||
                                (total in 1 downTo downloaded)
                            ) {
                                onProgress(downloaded, total)
                                reported = downloaded
                            }
                        }
                        onProgress(downloaded, total)
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
