package com.jobeen.ime.skin.symbolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** YAML 子集解析器（符号栏配置专用）：指南形态与错误口径。 */
class SymbolBarYamlTest {

    private fun map(node: YamlNode?): YamlNode.Map = node as YamlNode.Map

    private fun scalar(node: YamlNode?): String? = (node as? YamlNode.Scalar)?.value

    private fun get(node: YamlNode.Map, key: String): YamlNode? = node.entries[key]

    @Test
    fun guideExampleParses() {
        val root = map(
            SymbolBarYaml.parse(
                """
                closeButtonOnLeft: true
                height: 44
                cellInsets: { left: 4, right: 4, top: 4, bottom: 4 }
                backgroundColor: { light: "#EEEEEE", dark: "#222222" }
                longPressHintColor: "#888888"
                type:
                  - MD
                  - 数学
                panels:
                  MD:
                    - center:
                        action: { symbol: "," }
                        text: ","
                      topLeft:
                        action: { symbol: "、" }
                """.trimIndent(),
            ),
        )
        assertEquals("true", scalar(get(root, "closeButtonOnLeft")))
        assertEquals("44", scalar(get(root, "height")))
        val insets = map(get(root, "cellInsets") as YamlNode.Map)
        assertEquals("4", scalar(get(insets, "left")))
        val bg = map(get(root, "backgroundColor") as YamlNode.Map)
        assertEquals("#EEEEEE", scalar(get(bg, "light")))
        assertEquals("#222222", scalar(get(bg, "dark")))
        val types = get(root, "type") as YamlNode.List
        assertEquals(listOf("MD", "数学"), types.items.map { scalar(it) })
        val panels = map(get(root, "panels") as YamlNode.Map)
        val md = get(panels, "MD") as YamlNode.List
        val first = map(md.items[0] as YamlNode.Map)
        val center = map(get(first, "center") as YamlNode.Map)
        val action = map(get(center, "action") as YamlNode.Map)
        assertEquals(",", scalar(get(action, "symbol")))
        assertEquals(",", scalar(get(center, "text")))
        val topLeft = map(get(first, "topLeft") as YamlNode.Map)
        assertEquals("、", scalar(get(map(get(topLeft, "action") as YamlNode.Map), "symbol")))
    }

    @Test
    fun singleQuotedLatexKeepsBackslashes() {
        val root = map(
            SymbolBarYaml.parse(
                """
                panels:
                  MD:
                    - center:
                        action: { snippet: '\frac{{{cursor}}}{}' }
                """.trimIndent(),
            ),
        )
        val panels = map(get(root, "panels") as YamlNode.Map)
        val md = get(panels, "MD") as YamlNode.List
        val center = map(get(map(md.items[0] as YamlNode.Map), "center") as YamlNode.Map)
        val action = map(get(center, "action") as YamlNode.Map)
        assertEquals("\\frac{{{cursor}}}{}", scalar(get(action, "snippet")))
    }

    @Test
    fun doubleQuotedEscapesAndComments() {
        val root = map(
            SymbolBarYaml.parse(
                "# 首行注释\n" +
                    "text: \"a\\tb\" # 行尾注释\n" +
                    "hash: \"#888888\"\n",
            ),
        )
        assertEquals("a\tb", scalar(get(root, "text")))
        assertEquals("#888888", scalar(get(root, "hash")))
    }

    @Test
    fun quotedKeysAndEmptyValues() {
        val root = map(
            SymbolBarYaml.parse(
                "\"My Type\":\n" +
                    "  - center:\n" +
                    "      action: { symbol: \"x\" }\n" +
                    "emptyKey:\n",
            ),
        )
        val list = get(root, "My Type") as YamlNode.List
        assertEquals(1, list.items.size)
        assertTrue(get(root, "emptyKey") is YamlNode.Null)
    }

    @Test
    fun syntaxErrorsReturnNull() {
        // 缩进错乱（同级映射后突然加深的孤行）。
        assertNull(
            SymbolBarYaml.parse(
                "a: 1\n" +
                    "   b: 2\n",
            ),
        )
        // 未闭合引号。
        assertNull(SymbolBarYaml.parse("a: \"unclosed\n"))
        // 未闭合流映射。
        assertNull(SymbolBarYaml.parse("a: { left: 4\n"))
        // 重复键。
        assertNull(SymbolBarYaml.parse("a: 1\na: 2\n"))
        // Tab 缩进。
        assertNull(SymbolBarYaml.parse("a:\n\tb: 1\n"))
        // 锚点。
        assertNull(SymbolBarYaml.parse("a: &anchor 1\n"))
        // 块标量。
        assertNull(SymbolBarYaml.parse("a: |\n  x\n"))
        // 双引号未知转义。
        assertNull(SymbolBarYaml.parse("a: \"\\q\"\n"))
        // 空文档。
        assertNull(SymbolBarYaml.parse("  \n# only a comment\n"))
    }
}
