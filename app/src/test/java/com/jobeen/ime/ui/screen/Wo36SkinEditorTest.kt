// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO36 S-L3：被排除布局的页内提示文案（纯函数 [unavailableLayoutsHint]）。
 * Compose 页本身无主机单测，文案组装逻辑抽纯函数钉住；模板串值
 * 与 strings.xml 的一致性同款做法比对源码钉住。
 */
class Wo36SkinEditorTest {

    @Test
    fun unavailableHint_emptyReturnsNull() {
        assertNull(unavailableLayoutsHint("未显示：%1\$s", emptyList()))
    }

    @Test
    fun unavailableHint_singleId() {
        assertEquals(
            "未显示：lay1，请重新导入皮肤",
            unavailableLayoutsHint("未显示：%1\$s，请重新导入皮肤", listOf("lay1")),
        )
    }

    @Test
    fun unavailableHint_joinsMultipleWithIdeographicComma() {
        assertEquals(
            "未显示：lay1、lay2，请重新导入皮肤",
            unavailableLayoutsHint("未显示：%1\$s，请重新导入皮肤", listOf("lay1", "lay2")),
        )
    }

    @Test
    fun stringsXml_unavailableLayoutsTemplate() {
        // 与 PerfPanelLabelsTest 同款做法：单测工作目录即模块目录，
        // 直接读 strings.xml 源码比对，防止资源与文案口径静默漂移。
        val stringsXml = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml not found at ${stringsXml.absolutePath}", stringsXml.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsXml)
        val values = HashMap<String, String>()
        val nodes = doc.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            values[name] = node.textContent
        }
        val template = values["skin_editor_unavailable_layouts"]
        assertTrue("skin_editor_unavailable_layouts 缺失", template != null)
        assertTrue("模板须含 %1\$s 占位", template!!.contains("%1\$s"))
        assertTrue("模板须说明恢复办法（重新导入皮肤）", template.contains("重新导入皮肤"))
    }
}
