// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.backup

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * SharedPreferences 磁盘文件解析（WO33 第 1 项，D-M1 残余窗口）：
 * 把 shared_prefs/<name>.xml 的字节解析为键 → 类型值，与
 * [BackupManager] 的 encodePrefValue 接受的类型一一对应
 * （String／Int／Long／Float／Boolean／Set<String>）。
 *
 * 为什么不直接读 `SharedPreferences.all`：`all` 读的是内存，守卫
 * 验的是磁盘文件——一次 apply() 若在读时已改内存、落盘却在重验
 * 之后，守卫看不出，包里却是“未来的值”。从磁盘读，值与守卫验
 * 的就是同一份字节，内存超前于磁盘的窗口即关闭；剩下的残余只
 * 是“配对写里 prefs 的 flush 跨过结算点”（见 BackupManager）。
 *
 * 口径：
 * - 只认框架写出的形状：根 `<map>`，子元素 `<string>/<int>/
 *   <long>/<float>/<boolean>/<set>`（set 内只含 `<string>`）。
 *   框架写出的文件恒为此形；任何偏离（根不是 map、未知元素、
 *   缺 name／value 属性、数值解析失败、XML 本身损坏）都抛
 *   [IllegalArgumentException]，调用方回落到 `all` 内存读
 *   （旧行为），不因此让备份失败。
 * - 纯 javax.xml，不碰 Android，可主机单测。解析禁 DTD／外部
 *   实体（文件是本应用自己写出的，防御性收紧）。
 * - 空字符串（`<string name="k"></string>`）与空 set 正常返回，
 *   不丢键；XML 里没有 null 值（与 `all` 里跳 null 的口径一致，
 *   见调用方）。
 */
internal object PrefsXml {

    fun parse(bytes: ByteArray): Map<String, Any> {
        val factory = DocumentBuilderFactory.newInstance()
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (e: Exception) {
            // 本机解析器不支持该开关时不阻塞：文件是本应用自己写出的。
        }
        factory.isNamespaceAware = false
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val doc = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw IllegalArgumentException("prefs xml parse failed: ${e.message}", e)
        }
        val root = doc.documentElement
            ?: throw IllegalArgumentException("prefs xml has no root element")
        if (root.tagName != "map") {
            throw IllegalArgumentException("prefs xml root is <${root.tagName}>, expected <map>")
        }
        val out = LinkedHashMap<String, Any>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node !is Element) continue
            val name = node.getAttribute("name").takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("prefs <${node.tagName}> without name")
            out[name] = when (node.tagName) {
                "string" -> node.textContent ?: ""
                "int" -> node.valueAttr(name).toIntOrNull()
                    ?: throw IllegalArgumentException("prefs <$name> bad int")
                "long" -> node.valueAttr(name).toLongOrNull()
                    ?: throw IllegalArgumentException("prefs <$name> bad long")
                "float" -> node.valueAttr(name).toFloatOrNull()
                    ?: throw IllegalArgumentException("prefs <$name> bad float")
                "boolean" -> node.valueAttr(name).toBooleanStrictOrNull()
                    ?: throw IllegalArgumentException("prefs <$name> bad boolean")
                "set" -> {
                    val set = LinkedHashSet<String>()
                    val items = node.childNodes
                    for (j in 0 until items.length) {
                        val item = items.item(j)
                        if (item !is Element) continue
                        if (item.tagName != "string") {
                            throw IllegalArgumentException("prefs <$name> set holds <${item.tagName}>")
                        }
                        set += item.textContent ?: ""
                    }
                    set
                }
                else -> throw IllegalArgumentException("prefs unknown element <${node.tagName}>")
            }
        }
        return out
    }

    private fun Element.valueAttr(name: String): String =
        getAttribute("value").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("prefs <$name> without value")
}
