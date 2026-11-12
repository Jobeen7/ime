// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 并发忙计数（U-M8）：多个任务各持一份忙态，先完成的任务不再把后完成
 * 的任务的忙态提前清零——旧代码用单个 Boolean，置 true→跑块→finally
 * 置 false，并发时先完成者清零，后完成者仍在跑。
 * count 是 Compose 态，在组合内读 isBusy 即订阅；acquire／release
 * 必须成对调用（finally 内释放），多余的 release 不会跌破 0。
 */
class BusyCounter {
    var count by mutableIntStateOf(0)
        private set

    val isBusy: Boolean get() = count > 0

    fun acquire() {
        count++
    }

    fun release() {
        if (count > 0) count--
    }
}
