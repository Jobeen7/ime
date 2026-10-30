// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech.cloud

/** JDK Base64（单测替身；生产用 AndroidBase64Codec）。 */
object JdkBase64Codec : Base64Codec {
    private val decoder = java.util.Base64.getDecoder()
    private val encoder = java.util.Base64.getEncoder().withoutPadding()

    override fun decode(input: String): ByteArray = decoder.decode(input)
    override fun encodeToString(bytes: ByteArray): String = encoder.encodeToString(bytes)
}
