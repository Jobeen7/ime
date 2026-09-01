package com.ninthsoft.ime.base.ngram

import java.io.ByteArrayOutputStream

object GramEncoding {

    const val MAX_ENCODED_UNICODE = 8

    fun encode(utf8: String): ByteArray {
        val buf = ByteArrayOutputStream(utf8.length * 2)
        val iter = utf8.codePoints().iterator()
        while (iter.hasNext()) {
            val cp = iter.nextInt()
            when {
                cp < 0x80 -> {
                    if (cp == 0) {
                        buf.write(0xE0.toInt())
                    } else {
                        buf.write(cp)
                    }
                }

                cp in 0x4000 until 0xA000 -> {
                    if ((cp and 0xFF) == 0) {
                        buf.write(0xE1.toInt())
                        buf.write((cp shr 8) + 0x40)
                    } else {
                        buf.write((cp shr 8) + 0x40)
                        buf.write(cp and 0xFF)
                    }
                }

                else -> {
                    var u = cp
                    var bits = 32
                    while (bits > 0 && (u and 0xFE000000.toInt()) == 0) {
                        bits -= 7
                        u = u shl 7
                    }
                    val bytesToEncode = (bits + 6) / 7
                    buf.write(0xE0 or bytesToEncode)
                    repeat(bytesToEncode) {
                        buf.write(((u ushr 25) and 0x7F) or 0x80)
                        u = u shl 7
                    }
                }
            }
        }
        return buf.toByteArray()
    }

    fun decode(encoded: ByteArray): String = decode(encoded, 0, encoded.size)

    fun decode(encoded: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder()
        var p = offset
        val end = offset + length
        while (p < end) {
            val ch = (encoded[p++].toInt() and 0xFF)

            val cp: Int = when {
                ch < 0x80 -> ch

                ch == 0xE0 -> 0

                ch == 0xE1 -> {
                    if (p >= end) break
                    ((encoded[p++].toInt() and 0xFF) - 0x40) shl 8
                }

                ch < 0xE0 -> {
                    if (p >= end) break
                    ((ch - 0x40) shl 8) or (encoded[p++].toInt() and 0xFF)
                }

                (ch and 0xF0) == 0xE0 -> {
                    val n = ch and 0x0F
                    if (n <= 0 || n > 4 || p + n > end) break
                    var cpVal = 0
                    repeat(n) {
                        cpVal = (cpVal shl 7) or ((encoded[p++].toInt() and 0xFF) and 0x7F)
                    }
                    cpVal
                }

                else -> break
            }
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    fun nextUnicode(encoded: ByteArray, offset: Int): Int {
        val ch = (encoded[offset].toInt() and 0xFF)
        return when {
            ch < 0x80 -> offset + 1
            (ch and 0xF0) == 0xE0 -> offset + (ch and 0x0F) + 1
            else -> offset + 2
        }
    }

    fun unicodeLength(encoded: ByteArray, offset: Int, byteLen: Int): Int {
        var p = offset
        val end = offset + byteLen
        var len = 0
        while (p < end) {
            p = nextUnicode(encoded, p)
            len++
        }
        return len
    }
}