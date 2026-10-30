package com.jobeen.ime.base.speech.cloud

/**
 * 云端会话文本收尾机（纯逻辑，可单测）：
 * partial → 预览（不提交）；final → 正常提交；正常结束无 final
 * 时提交最新 partial；自结束／取消／失败清预览。收尾只发生一次，
 * 失败后后续 partial／final 一律丢弃。日志只记长度，绝不记文本
 * ——本机不记任何日志，调用方只在会话层记元数据。
 */
interface CloudTextSink {
    fun setPreview(text: String)
    fun commit(text: String)
    fun clearPreview()
}

class CloudVoiceSettle(
    private val sink: CloudTextSink,
    private val display: (String) -> String = { it },
) {
    /** 是否已出过可提交的 partial（窗口层空探针 discard 的判断依据）。 */
    var hasPartial: Boolean = false
        private set

    private var latestPartial: String? = null
    private var settled = false
    private var failed = false

    fun onPartial(text: String) {
        if (settled || failed) return
        latestPartial = text
        hasPartial = true
        sink.setPreview(display(text))
    }

    fun onFinal(text: String) {
        if (settled || failed) return
        if (text.isEmpty()) return
        latestPartial = null
        hasPartial = false
        sink.commit(display(text))
    }

    /** 正常结束：无 final 时提交最新 partial（空 partial 不提交）。 */
    fun onEnded() {
        if (settled || failed) return
        settled = true
        val pending = latestPartial?.takeIf { it.isNotEmpty() }
        latestPartial = null
        hasPartial = false
        if (pending != null) sink.commit(display(pending))
    }

    /** 失败收尾：清预览，之后一切结果丢弃。 */
    fun onError() {
        if (settled || failed) return
        failed = true
        latestPartial = null
        hasPartial = false
        sink.clearPreview()
    }

    /** 显式停止：丢弃清预览，否则按正常结束结算。 */
    fun stop(discard: Boolean) {
        if (settled || failed) return
        settled = true
        val pending = if (discard) null else latestPartial?.takeIf { it.isNotEmpty() }
        latestPartial = null
        hasPartial = false
        if (discard) {
            sink.clearPreview()
        } else if (pending != null) {
            sink.commit(display(pending))
        }
    }
}
