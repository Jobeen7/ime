package com.jobeen.ime.base.speech

import java.io.File

/** 识别模型文件集：tokens + encoder/decoder/joiner 三件套（.onnx） */
internal data class SpeechModelFiles(
    val tokens: File,
    val encoder: File,
    val decoder: File,
    val joiner: File,
)

/**
 * 在 [dir] 中定位语音模型文件：tokens.txt 必须存在；encoder/decoder/
 * joiner 取文件名含对应组件名、扩展名为 onnx 的文件，缺一即返回 null。
 *
 * 服务端（构建识别器）与客户端（就绪预检）共用这一份定位规则：此前
 * 两边各有一份实现且签名已经漂移（服务返文件集、客户端返 Boolean），
 * 规则再分叉会出现「客户端判就绪、服务端找不到文件」的口径不一致。
 */
internal fun findSpeechModelFiles(dir: File): SpeechModelFiles? {
    val tokens = File(dir, "tokens.txt")
    if (!tokens.isFile) return null
    val files = dir.listFiles().orEmpty().filter { it.isFile }
    fun pick(prefix: String) = files.firstOrNull {
        it.extension.equals("onnx", ignoreCase = true) && it.nameWithoutExtension.contains(
            prefix, ignoreCase = true
        )
    }
    val encoder = pick("encoder") ?: return null
    val decoder = pick("decoder") ?: return null
    val joiner = pick("joiner") ?: return null
    return SpeechModelFiles(tokens, encoder, decoder, joiner)
}
