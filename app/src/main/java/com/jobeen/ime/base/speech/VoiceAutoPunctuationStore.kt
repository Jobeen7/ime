// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech

import java.io.File

/**
 * 语音自动加标点开关：默认开；关闭后本地识别的最终上屏文本不再做
 * Jime 的标点处理（只做间距规整，与实时预览同口径），模型自带的
 * 标点原样保留。
 *
 * 存储口径与语音总开关同款：filesDir 下单行文件（`1`＝开、`0`＝关），
 * 缺失／损坏一律默认开（不断现有行为）；写盘走 tmp 文件＋rename
 * 原子替换；任何 IO 异常吞掉不向上抛。
 *
 * 与总开关不同的一点：读值每次都从盘上现读、不缓存——识别服务
 * 跑在独立 `:speech` 进程，与设置页不同进程；若像总开关那样在
 * 内存里缓存，设置页改了开关、服务进程不重启就看不到。最终文本
 * 一会话只归一化一次，现读成本可忽略。
 */
class VoiceAutoPunctuationStore(private val file: File) {

    /** 每次现读磁盘，不缓存（:speech 进程与设置页不同进程）。 */
    val enabled: Boolean
        get() = runCatching { file.readText().trim() }.getOrNull()?.let { it != "0" } ?: true

    fun setEnabled(value: Boolean) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(if (value) "1" else "0")
            if (!tmp.renameTo(file)) {
                file.writeText(if (value) "1" else "0")
            }
        }
    }

    companion object {
        private val sharedByPath = HashMap<String, VoiceAutoPunctuationStore>()

        fun shared(file: File): VoiceAutoPunctuationStore = synchronized(sharedByPath) {
            sharedByPath.getOrPut(file.absolutePath) { VoiceAutoPunctuationStore(file) }
        }

        /** filesDir 下文件名。 */
        const val FILE_NAME = "voice_auto_punctuation"
    }
}
