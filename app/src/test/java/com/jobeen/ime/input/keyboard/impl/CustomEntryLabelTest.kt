// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 左竖栏“自定义”入口文案（I-L5）：竖屏两行“⊕\n自定义”；横屏
 * 面板矮、两行溢出约 8–11dp，改单行“⊕ 自定义”（12sp 不变）。
 * 字号／字色／行为一律不动，键宽与行和不动。
 *
 * 条目级裁剪（SidePanelRender.drawItems 每条目 clipRect）是绘制
 * 行为，JVM 测不了，需真机／截图验收（横屏入口不叠到相邻条目、
 * 竖屏两行入口不受影响）。
 */
class CustomEntryLabelTest {

    @Test
    fun landscapeIsSingleLine() {
        assertEquals("⊕ 自定义", customEntryLabel(landscape = true))
    }

    @Test
    fun portraitIsTwoLines() {
        assertEquals("⊕\n自定义", customEntryLabel(landscape = false))
    }
}
