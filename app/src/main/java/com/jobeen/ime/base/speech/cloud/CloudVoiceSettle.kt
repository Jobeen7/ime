package com.jobeen.ime.base.speech.cloud

/**
 * 云端会话文本收尾机（纯逻辑，可单测）：
 * partial → 预览（不提交）；final → 正常提交；正常结束无 final
 * 时提交最新 partial；自结束／取消／失败清预览。日志只记长度，
 * 绝不记文本——本机不记任何日志，调用方只在会话层记元数据。
 *
 * 按句记账（上轮修）：settled 是“当前句”态，不是会话态——
 * 一句 final 落屏后，下一句的 partial／final 仍可落屏（NLS
 * 每句 SentenceEnd 发一次 final、腾讯每片 slice_type=2 发一次
 * final，单 settled 会把第二句起全丢掉）。
 * 调用方无按句复位信号（PluginHost 桥只传 partial／final／
 * error／ended 文本，无句子 id），句边界在本机内认：final 之后
 * 到达的 partial，若与已提交句互为前缀即同一句的迟到尾巴（B-S1，
 * 丢弃）；否则是新句开头，当前句 settled 清零、按新句预览。
 * final 之后直接到达的 final 同口径：互为前缀是同一句迟到重发
 * （丢弃），否则是新句的 final、开新句落屏（1.10）。
 * 会话级终态另有 ended／failed：onEnded／stop 之后一切结果丢弃，
 * 失败后后续 partial／final 一律丢弃。
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
    /** 当前句是否已有 final 落屏（按句记账，不跨句共用）。 */
    private var settled = false
    /** 最近一次落屏的 final 文本（供迟到 partial 判同一句）。 */
    private var settledText: String? = null
    /** 会话已正常结束（onEnded／stop 之后一切结果丢弃）。 */
    private var ended = false
    private var failed = false

    fun onPartial(text: String) {
        if (failed || ended) return
        if (settled) {
            // final 之后的新句开头（与已提交句不互为前缀）才开新句；
            // 同一句迟到尾巴仍丢弃（B-S1）。
            val last = settledText
            if (last == null || text.startsWith(last) || last.startsWith(text)) return
            settled = false
            settledText = null
        }
        latestPartial = text
        hasPartial = true
        sink.setPreview(display(text))
    }

    fun onFinal(text: String) {
        if (failed || ended) return
        if (text.isEmpty()) return
        if (settled) {
            // 与 onPartial 同口径的新句检测（1.10）：final 之后直接到达
            // 的 final，若与已提交句互为前缀即同一句的迟到重发（丢弃）；
            // 否则是新句的 final（NLS 每句 SentenceEnd、腾讯每片发一次），
            // 开新句、落屏。旧口径 settled 即返，把第二句起全丢掉。
            val last = settledText
            if (last == null || text.startsWith(last) || last.startsWith(text)) return
            settled = false
            settledText = null
        }
        // final 即收尾当前句：此后迟到的 partial 一律丢弃，不再覆写
        // 已提交文本，onEnded 也不再把陈旧 partial 二次提交（B-S1）；
        // 但 settled 只管当前句，下一句的 final 仍可落屏。
        settled = true
        settledText = text
        latestPartial = null
        hasPartial = false
        sink.commit(display(text))
    }

    /** 正常结束：当前句无 final 时提交最新 partial（空 partial 不提交）。 */
    fun onEnded() {
        if (failed || ended) return
        ended = true
        val pending = if (settled) null else latestPartial?.takeIf { it.isNotEmpty() }
        latestPartial = null
        hasPartial = false
        if (pending != null) sink.commit(display(pending))
    }

    /** 失败收尾：清预览，之后一切结果丢弃。 */
    fun onError() {
        if (failed || ended) return
        failed = true
        // 当前句已落屏时旧口径即 no-op（无预览可清）：只记失败拦住
        // 后续新句结果，不多调一次 clearPreview。
        val wasSettled = settled
        latestPartial = null
        hasPartial = false
        if (!wasSettled) sink.clearPreview()
    }

    /** 显式停止：丢弃清预览，否则按正常结束结算。 */
    fun stop(discard: Boolean) {
        if (failed || ended) return
        ended = true
        // 当前句已落屏时旧口径即 no-op：只记会话结束，不多调 sink。
        val wasSettled = settled
        val pending = if (discard || wasSettled) null else latestPartial?.takeIf { it.isNotEmpty() }
        latestPartial = null
        hasPartial = false
        if (wasSettled) return
        if (discard) {
            sink.clearPreview()
        } else if (pending != null) {
            sink.commit(display(pending))
        }
    }
}
