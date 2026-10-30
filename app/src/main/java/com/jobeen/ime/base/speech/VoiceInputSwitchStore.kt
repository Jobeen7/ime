// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech

import java.io.File

/**
 * 语音输入总开关：默认开；关闭后空格长按、工具栏语音键、自定义语音按钮
 * 均不启动语音（门控见 KeyboardWindowView 两个语音启动入口）。
 *
 * 存储口径照 VoiceCorrectionStore 的故障隔离模式简化版：filesDir 下单行
 * 文件（`1`＝开、`0`＝关），缺失／损坏一律默认开（不断现有行为）；
 * 写盘走 tmp 文件＋rename 原子替换；任何 IO 异常吞掉不向上抛；
 * 正式取用一律走 [shared] 单实例（设置页与输入法服务同进程共用同一份）。
 */
class VoiceInputSwitchStore(private val file: File) {

    private val lock = Any()
    private var loaded = false
    private var enabledField = true

    val enabled: Boolean
        get() = synchronized(lock) {
            ensureLoadedLocked()
            enabledField
        }

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            ensureLoadedLocked()
            if (enabledField == value) return
            enabledField = value
            persistLocked()
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        enabledField = runCatching { file.readText().trim() }.getOrNull()?.let { it != "0" } ?: true
    }

    private fun persistLocked() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(if (enabledField) "1" else "0")
            if (!tmp.renameTo(file)) {
                file.writeText(if (enabledField) "1" else "0")
            }
        }
    }

    companion object {
        private val sharedByPath = HashMap<String, VoiceInputSwitchStore>()

        fun shared(file: File): VoiceInputSwitchStore = synchronized(sharedByPath) {
            sharedByPath.getOrPut(file.absolutePath) { VoiceInputSwitchStore(file) }
        }

        /** filesDir 下文件名，与纠错词表文件同目录。 */
        const val FILE_NAME = "voice_input_enabled"
    }
}
