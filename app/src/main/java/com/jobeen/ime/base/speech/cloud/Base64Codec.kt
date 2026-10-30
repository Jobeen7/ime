package com.jobeen.ime.base.speech.cloud

/**
 * Base64 编解码抽象：生产用 Android 实现（minSdk 24 全版本可用），
 * 单测用 JDK 实现（JVM 单测里 android.util.Base64 只是返回默认值的桩，
 * 不能用来验证编解码逻辑）。宿主内所有 Base64 操作必经此口。
 */
interface Base64Codec {
    /** 解码失败抛 IllegalArgumentException（与 Android 实现同口径）。 */
    fun decode(input: String): ByteArray
    fun encodeToString(bytes: ByteArray): String
}

object AndroidBase64Codec : Base64Codec {
    override fun decode(input: String): ByteArray =
        android.util.Base64.decode(input, android.util.Base64.DEFAULT)

    override fun encodeToString(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}
