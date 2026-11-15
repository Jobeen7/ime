// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import com.jobeen.ime.data.database.ScriptSeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 脚本种子单测（WO52 8.1）：4 条种子脚本＋默认新建脚本。 */
class ScriptSeedsTest {

    @Test
    fun seeds_fourScriptsInOrder() {
        assertEquals(
            listOf("Unicode 查询", "Base64 编码", "Base64 解码", "Roll"),
            ScriptSeeds.scripts.map { it.name },
        )
    }

    @Test
    fun seeds_allDefineAsyncOutput() {
        for (script in ScriptSeeds.scripts) {
            assertTrue("${script.name} 须定义 async function output()", script.content.contains("async function output()"))
        }
    }

    @Test
    fun seeds_allUseInputPriorityComment() {
        for (script in ScriptSeeds.scripts) {
            if (script.name == "Roll") continue
            // 头注释写的是“脚本输入框 $searchText → 键盘选中文本
            // $selectedText → 剪贴板 $pasteboardContent”（中间有中文说明，
            // 不是三变量直接相连），故逐个变量断言。
            assertTrue("${script.name} 须注明输入优先级", script.content.contains("\$searchText"))
            assertTrue("${script.name} 须注明输入优先级", script.content.contains("\$selectedText"))
            assertTrue("${script.name} 须注明输入优先级", script.content.contains("\$pasteboardContent"))
        }
    }

    @Test
    fun seeds_uuidsAreUnique() {
        val uuids = ScriptSeeds.scripts.map { it.uuid }
        assertEquals(uuids.size, uuids.toSet().size)
    }

    @Test
    fun seeds_allowPasteboardTrue() {
        assertTrue(ScriptSeeds.scripts.all { it.allowPasteboard })
    }

    @Test
    fun seeds_noThirdPartyProductName() {
        // 仓库规矩：第三方输入法产品名不进仓（种子头注释已改品牌字样）。
        val joined = ScriptSeeds.scripts.joinToString("\n") { it.content }
        assertFalse(joined.contains("元书"))
        assertFalse(joined.contains("Hamster"))
        assertFalse(joined.contains("JavaScriptCore"))
    }

    @Test
    fun defaultNewScript_matchesSpec() {
        assertEquals(
            "async function output() { return [\"Hello, \" + \$searchText] }",
            ScriptSeeds.DEFAULT_NEW_SCRIPT_CONTENT,
        )
    }
}
