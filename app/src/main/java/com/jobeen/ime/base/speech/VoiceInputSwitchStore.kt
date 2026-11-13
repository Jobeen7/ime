// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech

import java.io.File

/**
 * 语音输入总开关：默认开；关闭后空格长按、工具栏语音键、自定义语音按钮
 * 均不启动语音（门控见 KeyboardWindowView 两个语音启动入口）。
 *
 * 存储口径为故障隔离模式简化版：filesDir 下单行
 * 文件（`1`＝开、`0`＝关），缺失／损坏一律默认开（不断现有行为）；
 * 写盘走 tmp 文件＋rename 原子替换；任何 IO 异常吞掉不向上抛；
 * 正式取用一律走 [shared] 单实例（设置页与输入法服务同进程共用同一份）。
 *
 * 写盘失败口径（B-L11）：内存值只在写盘成功后才翻转——写盘失败
 * （tmp rename 与回落写盘双败）时内存保持旧值，与磁盘一致，进程
 * 重启后开关不回跳；失败的 tmp 残留清掉，下次 setEnabled 可重试。
 *
 * 返回值口径（V-低1）：setEnabled 返回最终值是否等于请求值——
 * 写盘成功／请求值与当前值相同（无需写盘）时 true，写盘失败时
 * false。调用方不得无条件按拨动值改 UI：须按返回值或重读
 * [enabled] 回写，否则写盘失败时 UI 与 store 分叉。
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

    /**
     * 请求值写入成功（或已是当前值、无需写盘）返回 true；
     * 写盘失败返回 false，内存保持旧值（B-L11）。
     * 返回值即“最终值是否等于请求值”，调用方据此回写 UI。
     */
    fun setEnabled(value: Boolean): Boolean {
        synchronized(lock) {
            ensureLoadedLocked()
            if (enabledField == value) return true
            if (persistLocked(value)) {
                enabledField = value
                return true
            }
            return false
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        enabledField = runCatching { file.readText().trim() }.getOrNull()?.let { it != "0" } ?: true
    }

    /** 写盘新值，成功返回 true；失败返回 false（不改内存值，见类注释）。 */
    private fun persistLocked(value: Boolean): Boolean {
        val parent = file.parentFile ?: return false
        val text = if (value) "1" else "0"
        val tmp = File(parent, file.name + ".tmp")
        return runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
            true
        }.onFailure {
            runCatching { tmp.delete() }
        }.getOrDefault(false)
    }

    companion object {
        private val sharedByPath = HashMap<String, VoiceInputSwitchStore>()

        fun shared(file: File): VoiceInputSwitchStore = synchronized(sharedByPath) {
            sharedByPath.getOrPut(file.absolutePath) { VoiceInputSwitchStore(file) }
        }

        /** filesDir 下文件名。 */
        const val FILE_NAME = "voice_input_enabled"
    }
}
