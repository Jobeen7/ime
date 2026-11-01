// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.skin.model

/**
 * 精灵图描述文件形态（对端 structure.md：PNG 旁的
 * `resources/<name>.yaml`，子图名 → rect/insets 映射）：
 *
 * ```yaml
 * button_normal:
 *   rect: {x: 0, y: 0, width: 100, height: 50}
 *   insets: {top: 1, bottom: 2, left: 3, right: 4}
 * ```
 *
 * Jime 无 YAML 依赖，故此处为固定模式缩进解析器（纯 Kotlin，
 * 不接通用 YAML）：认两层块形态（rect/insets 行下挂缩进的
 * 标量字段）与同行流式形态（`rect: {x: 0, y: 0, ...}`）；行尾
 * `#` 注释与空行跳过；键名可单/双引号包裹；其余形态（锚点、
 * 多文档、非整数值）不解析，该条目丢弃并记 issue（不静默
 * 吞掉、不拒整包）。
 *
 * 校验口径：
 * - rect 必备：x/y ≥ 0，width/height > 0，否则整条目丢弃。
 * - insets 可选：缺省全 0；四边缺省 0，负值使整条目丢弃。
 * - 重名子图：后者覆盖前者并记 issue（YAML 重键本就歧义）。
 *
 * 与 JSON 精灵图映射表的冲突口径（见 [mergeSpriteMaps]）：
 * 同资产同子图两边都定义时，文件形态胜（sidecar 是资产自带
 * 描述，比皮肤级 JSON 更具体），且冲突记入结果供调用方
 * 诊断——不静默覆盖。
 *
 * 接线点：解析产物与 Wave-5 的 JSON 精灵图映射表同为
 * `Map<资产, Map<子图名, SpriteFrame>>`，直接由
 * [mergeSpriteMaps] 合并后走既有描述/渲染路径
 * （lookupSpriteFrame / drawableFor）。包内 sidecar 文件的
 * 读取（清单登记 + 资产读盘）为后续项：快照构建目前只认
 * JSON 表，sidecar 文本到达后调本合并函数即可接入。
 *
 * 纯函数，可直接单测。
 */
object YamlSpriteDescriptor {

    data class Issue(val entry: String?, val reason: String)
    data class ParseResult(val frames: Map<String, SpriteFrame>, val issues: List<Issue>)
    data class Conflict(val asset: String, val image: String, val winner: String = "file")
    data class Merged(val maps: Map<String, Map<String, SpriteFrame>>, val conflicts: List<Conflict>)

    /** 解析单个 sidecar YAML 文本 → 子图帧表（asset 维度由调用方定）。 */
    fun parse(yaml: String): ParseResult {
        val frames = LinkedHashMap<String, SpriteFrame>()
        val issues = ArrayList<Issue>()
        var current: String? = null
        var section: String? = null
        val fields = LinkedHashMap<String, Int>()
        fun flush() {
            val name = current ?: return
            val rect = fields["rect.x"] to fields["rect.y"]
            val w = fields["rect.width"]
            val h = fields["rect.height"]
            if (rect.first == null || rect.second == null || w == null || h == null) {
                issues += Issue(name, "yaml_sprite_rect_missing")
                return
            }
            if (rect.first!! < 0 || rect.second!! < 0 || w!! <= 0 || h!! <= 0) {
                issues += Issue(name, "yaml_sprite_rect_invalid")
                return
            }
            val top = fields["insets.top"] ?: 0
            val bottom = fields["insets.bottom"] ?: 0
            val left = fields["insets.left"] ?: 0
            val right = fields["insets.right"] ?: 0
            if (top < 0 || bottom < 0 || left < 0 || right < 0) {
                issues += Issue(name, "yaml_sprite_insets_invalid")
                return
            }
            if (frames.containsKey(name)) {
                issues += Issue(name, "yaml_sprite_duplicate")
            }
            frames[name] = SpriteFrame(
                rect = SpriteRect(rect.first!!, rect.second!!, w, h),
                insets = SpriteInsets(top = top, bottom = bottom, left = left, right = right),
            )
        }
        for (rawLine in yaml.lines()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            val stripped = line.trimStart()
            if (stripped.startsWith("#")) continue
            val indent = line.length - stripped.length
            val content = stripped.substringBefore(" #").trimEnd()
            if (content.endsWith(":")) {
                val key = content.dropLast(1).trim().unquote()
                if (indent == 0) {
                    flush()
                    current = key
                    section = null
                    fields.clear()
                } else if (current != null && (key == "rect" || key == "insets")) {
                    section = key
                } else {
                    issues += Issue(current, "yaml_sprite_section_unknown")
                }
                continue
            }
            val colon = content.indexOf(':')
            if (colon < 0) {
                issues += Issue(current, "yaml_sprite_line_invalid")
                continue
            }
            val key = content.substring(0, colon).trim().unquote()
            val valueRaw = content.substring(colon + 1).trim()
            // 流式形态：`rect: {x: 0, y: 0, width: 10, height: 10}`——
            // 内联键值对直接落入该段字段（与块形态同口径校验）。
            if ((key == "rect" || key == "insets") &&
                valueRaw.startsWith("{") && valueRaw.endsWith("}")
            ) {
                if (current == null) {
                    issues += Issue(null, "yaml_sprite_field_orphan")
                    continue
                }
                for (pair in valueRaw.removeSurrounding("{", "}").split(",")) {
                    val c = pair.indexOf(':')
                    if (c < 0) {
                        issues += Issue(current, "yaml_sprite_value_invalid")
                        continue
                    }
                    val fk = pair.substring(0, c).trim().unquote()
                    val fv = pair.substring(c + 1).trim().unquote().toIntOrNull()
                    if (fv == null) {
                        issues += Issue(current, "yaml_sprite_value_invalid")
                        continue
                    }
                    if (key == "rect" && fk in setOf("x", "y", "width", "height")) {
                        fields["rect.$fk"] = fv
                    } else if (key == "insets" && fk in setOf("top", "bottom", "left", "right")) {
                        fields["insets.$fk"] = fv
                    } else {
                        issues += Issue(current, "yaml_sprite_flow_key_unknown")
                    }
                }
                section = key
                continue
            }
            val value = valueRaw.unquote().toIntOrNull()
            if (value == null) {
                issues += Issue(current, "yaml_sprite_value_invalid")
                continue
            }
            val sec = section
            if (current == null || sec == null) {
                issues += Issue(current, "yaml_sprite_field_orphan")
                continue
            }
            when (sec) {
                "rect" -> if (key in setOf("x", "y", "width", "height")) {
                    fields["rect.$key"] = value
                } else {
                    issues += Issue(current, "yaml_sprite_rect_key_unknown")
                }
                "insets" -> if (key in setOf("top", "bottom", "left", "right")) {
                    fields["insets.$key"] = value
                } else {
                    issues += Issue(current, "yaml_sprite_insets_key_unknown")
                }
            }
        }
        flush()
        return ParseResult(frames, issues)
    }

    /**
     * 合并 JSON 表与文件形态表：文件形态胜；同资产同子图两边
     * 定义且帧不同时记一条冲突（帧相同不记——等值覆盖无歧义）。
     */
    fun mergeSpriteMaps(
        jsonMaps: Map<String, Map<String, SpriteFrame>>,
        fileMaps: Map<String, Map<String, SpriteFrame>>,
    ): Merged {
        val merged = LinkedHashMap<String, Map<String, SpriteFrame>>()
        val conflicts = ArrayList<Conflict>()
        for ((asset, jsonFrames) in jsonMaps) {
            merged[asset] = LinkedHashMap(jsonFrames)
        }
        for ((asset, fileFrames) in fileMaps) {
            val base = (merged[asset] as? LinkedHashMap)?.toMutableMap() ?: LinkedHashMap()
            for ((image, frame) in fileFrames) {
                val old = base[image]
                if (old != null && old != frame) {
                    conflicts += Conflict(asset, image)
                }
                base[image] = frame
            }
            merged[asset] = base
        }
        return Merged(merged, conflicts)
    }

    private fun String.unquote(): String {
        val t = trim()
        if (t.length >= 2 &&
            ((t.startsWith("'") && t.endsWith("'")) ||
                (t.startsWith("\"") && t.endsWith("\""))
            )
        ) {
            return t.substring(1, t.length - 1)
        }
        return t
    }
}
