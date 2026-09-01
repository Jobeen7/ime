package com.ninthsoft.ime.base.speech

import android.content.Context
import android.os.Build
import com.ninthsoft.ime.base.net.HttpUtil
import kotlinx.serialization.Serializable
import timber.log.Timber

/**
 * 语音识别模型信息接口返回的清单。
 * 注意：可序列化类需为顶层声明，嵌套在 object 内会导致运行时找不到 serializer。
 */
@Serializable
data class SpeechModelManifest(
    val type: String = "cpu",
    val link: String = "",
    val md5: String = "",
)

/**
 * 语音识别模型信息接口。
 *
 * 下载前先请求服务器 API（/speech/model），由服务端在信封 `data` 中返回本次需要下载的
 * 归档包地址 `link`（.tar.bz2），再下载并解包到 DataManager.speechModelDir。
 *
 * 请求参数：
 * - version：应用版本号（由 [com.ninthsoft.ime.base.net.HttpUtil] 统一追加）
 * - type：设备支持的运行时，`qnn`（支持 QNN）或 `cpu`
 * - soc：设备具体 SoC/CPU 型号
 */
object SpeechModelApi {
    suspend fun fetchManifest(context: Context): SpeechModelManifest {
        val type = if (SherpaSpeechClient.isQnnRuntimeSupported(context)) "qnn" else "cpu"
        val query = mapOf(
            "type" to type,
            "soc" to socModel(),
        ).entries.joinToString("&") { (k, v) -> "$k=$v" }
        return runCatching {
            HttpUtil.get<SpeechModelManifest>("speech/model?$query")
        }.getOrElse { e ->
            Timber.e(e, "Failed to fetch speech model manifest")
            if (e !is kotlinx.coroutines.CancellationException &&
                e !is HttpUtil.ApiException
            ) {
                HttpUtil.showToast("语音模型下载失败：${e.message ?: "网络错误"}")
            }
            SpeechModelManifest()
        }
    }

    private fun socModel(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            Build.BOARD
        }
        return soc.ifBlank { Build.BOARD }
    }
}
