package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.input.keyboard.key.SwipeThresholds
import com.jobeen.ime.input.panel.toolbar.ToolbarConfig
import com.jobeen.ime.skin.notify.ConditionStyle
import com.jobeen.ime.skin.notify.ConditionStyleParser
import com.jobeen.ime.skin.notify.SkinNotification
import com.jobeen.ime.skin.notify.SkinNotificationParser
import com.jobeen.ime.skin.symbol.HorizontalSymbolsParser
import com.jobeen.ime.skin.symbol.NumericSymbols
import com.jobeen.ime.skin.symbol.SubClassifiedSymbolsParser
import com.jobeen.ime.skin.symbol.SymbolBarParser
import com.jobeen.ime.skin.symbol.SymbolBarSpec
import com.jobeen.ime.skin.symbol.T9HorizontalSymbolsParser
import com.jobeen.ime.skin.symbol.T9SymbolsParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * v3 校验器（定稿九章，独立实现，不引用旧解析器）：
 *
 * - 整包拒绝（[Result.Rejected]）：结构性损坏、formatVersion /
 *   minAppVersion 不满足、第十一章 JSON 上限超限（九.3/十一.5）。
 * - 逐项回填（[SkinIssue]）：已知字段类型错/越界/格式错 → 按
 *   优先级链回填下一级并逐项报告，绝不静默丢弃整包（九.2）。
 * - keyMappings / audio 自 B2 起强类型解析并应用（单项无效只
 *   剔该项）；layoutId 自 B3 起由快照工厂解析生效（本类只做
 *   字符串形态校验，引用解析在工厂层，见详设 §3.4）。
 *
 * 纯函数：全局偏好现值与资产清单经参数注入，可直接单测。
 */
object SkinValidator {

    const val SUPPORTED_FORMAT_VERSION = 3

    /** 第十一章 JSON 上限（十一.5）。 */
    const val MAX_JSON_DEPTH = 8
    const val MAX_STRING_LENGTH = 4096
    const val MAX_KEY_MAPPINGS = 512

    /** 第九章数值区间（九.1 冻结表）。 */
    private val GEOMETRY_RANGES: Map<GeometryField, ClosedFloatingPointRange<Float>> = mapOf(
        GeometryField.CORNER_RADIUS to 0f..40f,
        GeometryField.KEY_H_MARGIN to 0f..20f,
        GeometryField.KEY_V_MARGIN to 0f..20f,
        GeometryField.BOTTOM_INSET to 0f..48f,
        GeometryField.TOP_INSET to 0f..48f,
        GeometryField.LEFT_INSET to 0f..48f,
        GeometryField.RIGHT_INSET to 0f..48f,
    )
    private val BLUR_RANGE = 0f..25f
    private val ALPHA_RANGE = 0f..1f
    private const val MAX_META_NAME_LENGTH = 32

    /** 键盘级几何区间（2026-10-27 新增分流）：圆角/间距沿用 appearance 口径；高度取竖横屏滑条并集。 */
    private val KEYBOARD_GEOMETRY_RANGES: Map<KeyboardGeometryField, ClosedFloatingPointRange<Float>> = mapOf(
        KeyboardGeometryField.KEY_RADIUS to 0f..40f,
        KeyboardGeometryField.KEY_H_GAP to 0f..20f,
        KeyboardGeometryField.KEY_V_GAP to 0f..20f,
        KeyboardGeometryField.HEIGHT_PERCENT to 20f..70f,
        KeyboardGeometryField.HEIGHT_PERCENT_LANDSCAPE to 20f..70f,
    )

    /**
     * 系统字族白名单（2026-10-27 新增分流）：Typeface.create 对
     * 未知族名静默回落默认字体、无法与“指定了默认字体”区分，故
     * 校验期只接受 Android 系统字族；其它名字一律按非法值回退并
     * 记 issue，不进快照。`default` 为显式默认别名（等价于未指定）。
     */
    val SYSTEM_FONT_FAMILIES: Set<String> = setOf(
        "sans-serif",
        "sans-serif-light",
        "sans-serif-thin",
        "sans-serif-condensed",
        "sans-serif-medium",
        "sans-serif-black",
        "sans-serif-smallcaps",
        "serif",
        "monospace",
        "serif-monospace",
    )

    sealed interface Result {
        data class Valid(val skin: ValidatedSkin, val issues: List<SkinIssue>) : Result
        data class Rejected(val reason: String) : Result
    }

    /** G1 单键图片资产单体上限（超限按字段级回退颜色键面）。 */
    const val MAX_KEY_IMAGE_BYTES = 1024L * 1024L

    /**
     * 校验消费的顶层键集合（未知节点透传的补集口径）：
     * 校验器从树上读取并落入类型化字段的全部顶层键；
     * 树上除此之外的键即“未知节点”，原样透传到快照
     * （不做任何语义解读；未来校验新增键时须同步补进本表，
     * 否则新键会被误归为未知节点）。
     */
    private val KNOWN_TOP_LEVEL_KEYS = setOf(
        "appearance", "audio", "bubbleOverflowCheck",
        "candidateCommentSizeDp", "candidateContextMenu", "candidateIndexSizeDp",
        "candidateRadiusDp", "conditionStyles", "derivation", "derivationDark",
        "derivationFormulas", "fontFamily", "formatVersion", "geometry",
        "hintSymbolNodes", "hintSymbolsGridStyles", "hintSymbolsStyles",
        "horizontalCandidateStyle", "horizontalCandidates", "horizontalCandidatesLayout",
        "horizontalCandidatesStyle", "horizontalSymbolsRtl", "keyAnimation", "keyAnimations",
        "keyMappings", "keyShadow", "keySound", "keyStyles", "keyboards", "layoutId",
        "layoutIdLandscape",
        "meta", "minAppVersion", "notifications", "predictionCloseBg",
        "predictionCloseRadiusDp", "preeditHeight", "preeditStyle", "roleFonts",
        "spaceAnimation", "sprites", "styleSets", "styles", "stylesDark",
        "subClassifiedSymbols", "suppressKeyboard", "suppressToolbar", "swipeThresholdDp",
        "symbolBadge", "symbolBar",
        "t9HorizontalSymbols", "t9Symbols", "numericSymbols", "horizontalSymbols",
        "textSizes", "toolbar", "toolbarButtonBackgroundStyle", "toolbarHeight",
        "toolbarLayout", "toolbarStyle", "verticalBottomRow", "verticalCandidateStyle",
        "verticalCandidates", "verticalCandidatesLayout", "verticalCandidatesStyle",
    )

    /**
     * @param currentVersionCode 当前 App versionCode（minAppVersion 门）。
     * @param fallbacks 全局偏好现值（几何回填链第二级）。
     * @param assetKind 资产逻辑 id → 类别；查无此 id 返回 null。
     * @param isOverridable 键 ID 可覆写判定（缺省内置登记表；布局
     *   编辑器预览等场景可放宽为含当前布局坐标 ID 的判定）。
     * @param assetBytes 资产逻辑 id → 字节数；未知返回 null（此时
     *   只做类别校验，不做尺寸校验）。
     */
    fun validate(
        tree: JsonObject,
        currentVersionCode: Int,
        fallbacks: GeometryFallbacks,
        assetKind: (String) -> AssetKind?,
        isOverridable: (String) -> Boolean = KeyIds::isOverridable,
        assetBytes: (String) -> Long? = { null },
    ): Result {
        // ---- 结构性门（九.3 / 十一.5）：命中即整包拒绝 ----
        if (SkinJson.depth(tree) > MAX_JSON_DEPTH) {
            return Result.Rejected("json_depth_exceeded")
        }
        if (SkinJson.maxStringLength(tree) > MAX_STRING_LENGTH) {
            return Result.Rejected("json_string_too_long")
        }
        val formatVersion = (tree["formatVersion"] as? JsonPrimitive).strictIntOrNull()
            ?: return Result.Rejected("format_version_missing_or_invalid")
        if (formatVersion > SUPPORTED_FORMAT_VERSION) {
            return Result.Rejected("format_version_unsupported:$formatVersion")
        }
        val minAppVersion = tree["minAppVersion"]
        if (minAppVersion != null) {
            val required = (minAppVersion as? JsonPrimitive).strictIntOrNull()
                ?: return Result.Rejected("min_app_version_invalid")
            if (required > currentVersionCode) {
                return Result.Rejected("app_version_too_low:$required>$currentVersionCode")
            }
        }
        val keyMappings = tree["keyMappings"] as? JsonObject
        if (keyMappings != null && keyMappings.size > MAX_KEY_MAPPINGS) {
            return Result.Rejected("key_mappings_too_many:${keyMappings.size}")
        }

        val issues = mutableListOf<SkinIssue>()

        // ---- meta ----
        val meta = tree["meta"] as? JsonObject
        if (tree["meta"] != null && meta == null) {
            issues += SkinIssue("meta", SkinIssue.Kind.FIELD_FALLBACK, "meta_not_object")
        }
        val metaId = (meta?.get("id") as? JsonPrimitive)?.contentOrNull
        var metaName = ""
        val namePrimitive = meta?.get("name") as? JsonPrimitive
        if (namePrimitive != null) {
            val raw = namePrimitive.contentOrNull
            if (raw == null || raw.length > MAX_META_NAME_LENGTH) {
                issues += SkinIssue(
                    "meta.name", SkinIssue.Kind.FIELD_FALLBACK,
                    if (raw == null) "name_not_string" else "name_too_long",
                )
            } else {
                metaName = raw
            }
        }
        val metaAuthor = (meta?.get("author") as? JsonPrimitive)?.contentOrNull ?: ""
        val metaVersion = (meta?.get("version") as? JsonPrimitive)?.contentOrNull ?: "1.0"

        // ---- layoutId（B3 起由快照工厂解析生效；此处只验字符串形态） ----
        var layoutId: String? = null
        val layoutPrimitive = tree["layoutId"] as? JsonPrimitive
        if (layoutPrimitive != null) {
            val raw = layoutPrimitive.contentOrNull
            if (raw == null) {
                issues += SkinIssue("layoutId", SkinIssue.Kind.FIELD_FALLBACK, "layout_id_not_string")
            } else {
                layoutId = raw
            }
        }

        // ---- layoutIdLandscape（2026-10-27 第一组：横屏专属布局绑定，
        // 与 layoutId 同口径只验字符串形态；引用解析与回退在快照工厂层） ----
        var layoutIdLandscape: String? = null
        val layoutLandscapePrimitive = tree["layoutIdLandscape"] as? JsonPrimitive
        if (layoutLandscapePrimitive != null) {
            // 数字/布尔也是 JsonPrimitive，contentOrNull 会把 42
            // 读成 "42"——必须以 isString 为准，否则非字符串
            // 绑定会被当成合法布局 id 接受。
            if (layoutLandscapePrimitive.isString) {
                layoutIdLandscape = layoutLandscapePrimitive.content
            } else {
                issues += SkinIssue(
                    "layoutIdLandscape", SkinIssue.Kind.FIELD_FALLBACK,
                    "layout_id_not_string",
                )
            }
        }

        // ---- appearance ----
        val appearance = tree["appearance"] as? JsonObject
        if (tree["appearance"] != null && appearance == null) {
            issues += SkinIssue("appearance", SkinIssue.Kind.FIELD_FALLBACK, "appearance_not_object")
        }
        val presence = mutableSetOf<GeometryField>()
        val effective = mutableMapOf<GeometryField, Float>()
        for (field in GeometryField.entries) {
            val primitive = appearance?.get(field.jsonName) as? JsonPrimitive
            if (primitive == null) {
                if (appearance?.containsKey(field.jsonName) == true) {
                    issues += SkinIssue(
                        "appearance.${field.jsonName}",
                        SkinIssue.Kind.FIELD_FALLBACK, "not_a_number",
                    )
                }
                effective[field] = fallbacks.valueOf(field)
                continue
            }
            val value = primitive.strictDoubleOrNull()?.toFloat()
            val range = GEOMETRY_RANGES.getValue(field)
            if (value == null || !value.isFinite() || value !in range) {
                issues += SkinIssue(
                    "appearance.${field.jsonName}",
                    SkinIssue.Kind.FIELD_FALLBACK, "out_of_range_or_invalid",
                )
                effective[field] = fallbacks.valueOf(field)
            } else {
                presence += field
                effective[field] = value
            }
        }
        val background = validateBackground(appearance?.get("background"), assetKind, issues)


        // ---- 键盘级几何（2026-10-27 新增分流：顶层 geometry） ----
        // 逐项校验：类型错/非有限数/越界 → FIELD_FALLBACK + 该项缺席
        // （消费点回退 appearance/App 设置，绝不崩溃、不拒整包）。
        // 与 appearance 几何同义字段并存且值不同时，geometry 优先，
        // 记一条 INFO 明示，不静默覆盖。
        val keyboardPresence = mutableSetOf<KeyboardGeometryField>()
        val keyboardValues = mutableMapOf<KeyboardGeometryField, Float>()
        val geometryNode = tree["geometry"]
        if (geometryNode != null) {
            val geometryObj = geometryNode as? JsonObject
            if (geometryObj == null) {
                issues += SkinIssue(
                    "geometry", SkinIssue.Kind.FIELD_FALLBACK, "geometry_not_object",
                )
            } else {
                for (field in KeyboardGeometryField.entries) {
                    val primitive = geometryObj[field.jsonName] as? JsonPrimitive
                    if (primitive == null) {
                        if (geometryObj.containsKey(field.jsonName)) {
                            issues += SkinIssue(
                                "geometry.${field.jsonName}",
                                SkinIssue.Kind.FIELD_FALLBACK, "not_a_number",
                            )
                        }
                        continue
                    }
                    val value = primitive.strictDoubleOrNull()?.toFloat()
                    val range = KEYBOARD_GEOMETRY_RANGES.getValue(field)
                    if (value == null || !value.isFinite() || value !in range) {
                        issues += SkinIssue(
                            "geometry.${field.jsonName}",
                            SkinIssue.Kind.FIELD_FALLBACK, "out_of_range_or_invalid",
                        )
                    } else {
                        keyboardPresence += field
                        keyboardValues[field] = value
                    }
                }
            }
        }
        if (KeyboardGeometryField.KEY_RADIUS in keyboardPresence &&
            GeometryField.CORNER_RADIUS in presence &&
            keyboardValues.getValue(KeyboardGeometryField.KEY_RADIUS) !=
                effective.getValue(GeometryField.CORNER_RADIUS)
        ) {
            issues += SkinIssue(
                "geometry.keyRadiusDp", SkinIssue.Kind.INFO,
                "geometry_overrides_appearance:cornerRadius",
            )
        }
        if (KeyboardGeometryField.KEY_H_GAP in keyboardPresence &&
            GeometryField.KEY_H_MARGIN in presence &&
            keyboardValues.getValue(KeyboardGeometryField.KEY_H_GAP) !=
                effective.getValue(GeometryField.KEY_H_MARGIN)
        ) {
            issues += SkinIssue(
                "geometry.keyHGapDp", SkinIssue.Kind.INFO,
                "geometry_overrides_appearance:keyHMargin",
            )
        }
        if (KeyboardGeometryField.KEY_V_GAP in keyboardPresence &&
            GeometryField.KEY_V_MARGIN in presence &&
            keyboardValues.getValue(KeyboardGeometryField.KEY_V_GAP) !=
                effective.getValue(GeometryField.KEY_V_MARGIN)
        ) {
            issues += SkinIssue(
                "geometry.keyVGapDp", SkinIssue.Kind.INFO,
                "geometry_overrides_appearance:keyVMargin",
            )
        }

        // ---- 皮肤内嵌字体（审计 C 项：顶层 fontFaces） ----
        // 在 fontFamily / roleFonts 之前解析：两者取值可引用
        // 此处声明的内嵌字体名（大小写不敏感，存声明原文）。
        val parsedFontFaces = SkinFontParser.parse(tree, assetKind, assetBytes)
        issues += parsedFontFaces.issues
        val skinFontFaces = parsedFontFaces.faces
        val faceByLower: Map<String, String> =
            skinFontFaces.associate { it.name.lowercase() to it.name }

        // ---- 字族（2026-10-27 新增分流 + 审计 C 项：顶层 fontFamily） ----
        // 单值或数组：数组顺序即优先级，首项即旧单值 fontFamily；
        // "default" = 该项跟随（不记 issue），未知／非字符串项
        // 逐项丢弃并记 issue，绝不拒包。
        val parsedFontFamily = SkinFontParser.parseCandidates(
            tree["fontFamily"], "fontFamily", faceByLower, SYSTEM_FONT_FAMILIES,
            "font_family_empty", "font_family_unknown", "font_family_not_string",
        )
        issues += parsedFontFamily.issues
        val fontFamilies = parsedFontFamily.candidates
        val fontFamily = fontFamilies.firstOrNull()

        // ---- 分角色字体（2026-10-29 item-1：顶层 roleFonts） ----
        // 角色名取全局字体层的 8 角色（stored 名）；值与 fontFamily
        // 同口径（白名单族名／内嵌名；单值或数组，数组顺序即优
        // 先级；"default"/空白 = 该角色跟随，不记 issue；未知族
        // 名/非字符串逐项丢弃并记 issue，绝不拒包）。
        val roleFonts = mutableMapOf<com.jobeen.ime.data.keyboard.theme.FontRole, String>()
        val roleFontFamilies =
            mutableMapOf<com.jobeen.ime.data.keyboard.theme.FontRole, List<String>>()
        val roleFontsNode = tree["roleFonts"]
        if (roleFontsNode != null) {
            val roleFontsObj = roleFontsNode as? JsonObject
            if (roleFontsObj == null) {
                issues += SkinIssue(
                    "roleFonts", SkinIssue.Kind.FIELD_FALLBACK, "role_fonts_not_object",
                )
            } else {
                for ((name, valueNode) in roleFontsObj) {
                    val role = com.jobeen.ime.data.keyboard.theme.FontRole.entries
                        .firstOrNull { it.stored == name }
                    if (role == null) {
                        issues += SkinIssue(
                            "roleFonts.$name", SkinIssue.Kind.FIELD_FALLBACK,
                            "role_fonts_unknown_role",
                        )
                        continue
                    }
                    val parsed = SkinFontParser.parseCandidates(
                        valueNode, "roleFonts.$name", faceByLower, SYSTEM_FONT_FAMILIES,
                        null, "role_fonts_unknown_family", "role_fonts_not_string",
                    )
                    issues += parsed.issues
                    if (parsed.candidates.isNotEmpty()) {
                        roleFonts[role] = parsed.candidates.first()
                        roleFontFamilies[role] = parsed.candidates
                    }
                }
            }
        }

        // ---- 按码位区间指派字体（审计 C 项：顶层 fontRanges） ----
        // family 与 fontFamily 同口径：系统白名单或已声明内嵌
        // 字体名；单项非法只剔该项。声明顺序即优先级（先胜出）。
        val parsedFontRanges = SkinFontParser.parseRanges(tree) { raw ->
            val lower = raw.lowercase()
            when {
                lower in SYSTEM_FONT_FAMILIES -> lower
                else -> faceByLower[lower]
            }
        }
        issues += parsedFontRanges.issues
        val fontRanges = parsedFontRanges.ranges

        // ---- 气泡溢出检测（2026-10-28 截图 6 项：默认开） ----
        // 严格布尔：非布尔值记 FIELD_FALLBACK 并保持默认开，不拒包。
        var bubbleOverflowCheck = true
        val bubblePrimitive = tree["bubbleOverflowCheck"] as? JsonPrimitive
        if (bubblePrimitive != null) {
            val parsed = bubblePrimitive.booleanOrNull
            if (parsed != null) {
                bubbleOverflowCheck = parsed
            } else {
                issues += SkinIssue(
                    "bubbleOverflowCheck", SkinIssue.Kind.FIELD_FALLBACK,
                    "bubble_overflow_check_not_boolean",
                )
            }
        }

        // ---- 滑动阈值（2026-10-28 截图 6 项） ----
        // 严格数值 + 区间校验，非法记 FIELD_FALLBACK 并视为未指定。
        var swipeThresholdDp: Float? = null
        val swipePrimitive = tree["swipeThresholdDp"] as? JsonPrimitive
        if (swipePrimitive != null) {
            val value = swipePrimitive.strictDoubleOrNull()?.toFloat()
            if (value != null && value.isFinite() &&
                value >= SwipeThresholds.MIN_DP && value <= SwipeThresholds.MAX_DP
            ) {
                swipeThresholdDp = value
            } else {
                issues += SkinIssue(
                    "swipeThresholdDp", SkinIssue.Kind.FIELD_FALLBACK,
                    "swipe_threshold_invalid",
                )
            }
        }

        // ---- 候选栏圆角 + 预测关闭键样式（2026-10-28 截图 6 项） ----
        // 圆角区间 0..32dp，非法记 FIELD_FALLBACK 并取默认/复用值；
        // 关闭键背景只认 #RRGGBB/#AARRGGBB，非法同样回退不拒包。
        var candidateRadiusDp: Float = 6f
        val radiusPrimitive = tree["candidateRadiusDp"] as? JsonPrimitive
        if (radiusPrimitive != null) {
            val value = radiusPrimitive.strictDoubleOrNull()?.toFloat()
            if (value != null && value.isFinite() && value in 0f..32f) {
                candidateRadiusDp = value
            } else {
                issues += SkinIssue(
                    "candidateRadiusDp", SkinIssue.Kind.FIELD_FALLBACK,
                    "candidate_radius_invalid",
                )
            }
        }
        var predictionCloseBg: Int? = null
        val closeBgNode = tree["predictionCloseBg"] as? JsonPrimitive
        if (closeBgNode != null) {
            val parsed = if (closeBgNode.isString) {
                closeBgNode.contentOrNull?.let { SkinJson.parseColor(it) }
            } else {
                null
            }
            if (parsed != null) {
                predictionCloseBg = parsed
            } else {
                issues += SkinIssue(
                    "predictionCloseBg", SkinIssue.Kind.FIELD_FALLBACK,
                    "prediction_close_bg_invalid",
                )
            }
        }
        var predictionCloseRadiusDp: Float? = null
        val closeRadiusPrimitive = tree["predictionCloseRadiusDp"] as? JsonPrimitive
        if (closeRadiusPrimitive != null) {
            val value = closeRadiusPrimitive.strictDoubleOrNull()?.toFloat()
            if (value != null && value.isFinite() && value in 0f..32f) {
                predictionCloseRadiusDp = value
            } else {
                issues += SkinIssue(
                    "predictionCloseRadiusDp", SkinIssue.Kind.FIELD_FALLBACK,
                    "prediction_close_radius_invalid",
                )
            }
        }

        // ---- 工具栏高度（审计 B 项） ----
        // 数字 = dp（16..120），字符串 "Nvh" = 视口高度百分比（0..50）；
        // 非法记 issue 并回落旧 48dp（不拒包）。
        val toolbarHeight = parseVhLength(
            tree["toolbarHeight"], "toolbarHeight", issues, 16f..120f,
        )

        // ---- 工具栏样式（审计 B 项） ----
        val toolbarStyle = parseToolbarStyle(tree["toolbarStyle"], "toolbarStyle", issues)

        // ---- 区域压制（区域门控）：非布尔值记 issue 并按不压制计。 ----
        fun suppressFlag(name: String): Boolean {
            val raw = tree[name] as? JsonPrimitive ?: return false
            return raw.booleanOrNull ?: run {
                issues += SkinIssue(name, SkinIssue.Kind.FIELD_FALLBACK, "suppress_not_bool")
                false
            }
        }
        val suppressToolbar = suppressFlag("suppressToolbar")
        val suppressKeyboard = suppressFlag("suppressKeyboard")

        // ---- 工具栏布局（审计 B 项） ----
        val toolbarLayout = parseToolbarLayout(tree["toolbarLayout"], "toolbarLayout", issues)

        // ---- 工具栏钮背景图（审计 D 项） + 精灵图映射表 ----
        // 顶层 `toolbarButtonBackgroundStyle`（对端同名）为全体工具栏
        // 钮的背景描述；`sprites` 为 sheet 资产 id → 子图映射表。
        // 两者缺失皆为 null/空表（旧口径：钮无背景图）；非法记 issue
        // 并丢弃对应节点（不拒包）。
        val toolbarButtonBackground = parseImageDescriptor(
            tree["toolbarButtonBackgroundStyle"], "toolbarButtonBackgroundStyle", issues,
        )
        val spriteMaps = parseSpriteMaps(tree["sprites"], "sprites", issues)

        // ---- 预编辑高度（审计 B 项） ----
        // 数字 = dp（12..64），字符串 "Nvh" = 视口高度百分比；
        // 非法记 issue 并回落旧 30dp（不拒包）。
        val preeditHeight = parseVhLength(
            tree["preeditHeight"], "preeditHeight", issues, 12f..64f,
        )

        // ---- 预编辑样式（审计 B 项） ----
        val preeditStyle = parsePreeditStyle(tree["preeditStyle"], "preeditStyle", issues)

        // ---- 候选序号／注释字号（审计 I 项） ----
        val candidateIndexSizeDp = parseDpField(
            tree["candidateIndexSizeDp"], "candidateIndexSizeDp", issues, 8f..32f,
        )
        val candidateCommentSizeDp = parseDpField(
            tree["candidateCommentSizeDp"], "candidateCommentSizeDp", issues, 8f..32f,
        )

        // ---- 横条候选上限（审计 I 项） ----
        val horizontalMaxColumns = parseHorizontalCandidates(
            tree["horizontalCandidates"], "horizontalCandidates", issues,
        )?.maxColumns
        val horizontalExpandLeft = parseHorizontalLayout(
            tree["horizontalCandidatesLayout"], "horizontalCandidatesLayout", issues,
        )?.expandLeft ?: false
        // S11：横向符号 RTL（未写 = LTR）。
        val horizontalSymbolsRtl =
            (tree["horizontalSymbolsRtl"] as? JsonPrimitive)?.booleanOrNull ?: false
        val verticalCandidates = parseVerticalCandidates(
            tree["verticalCandidates"], "verticalCandidates", issues,
        )
        val verticalSidePanel = parseVerticalLayout(
            tree["verticalCandidatesLayout"], "verticalCandidatesLayout", issues,
        )?.sidePanel ?: true
        val candidateContextMenu = parseCandidateContextMenu(
            tree["candidateContextMenu"], "candidateContextMenu", issues,
        )

        // ---- 候选区样式（审计 B 项） ----
        val horizontalCandidatesStyle = parseCandidateAreaStyle(
            tree["horizontalCandidatesStyle"], "horizontalCandidatesStyle", issues,
        )
        val verticalCandidatesStyle = parseCandidateAreaStyle(
            tree["verticalCandidatesStyle"], "verticalCandidatesStyle", issues,
        )

        // ---- 竖网格底部钮行（翻页／返回或收起／退格） ----
        val verticalBottomRow = parseVerticalBottomRow(
            tree["verticalBottomRow"], "verticalBottomRow", issues,
        )

        // ---- 候选单元格样式全字段（字号／字重／内边距／背景内边距／圆角） ----
        val horizontalCandidateStyle = parseCandidateCellStyle(
            tree["horizontalCandidateStyle"], "horizontalCandidateStyle", issues,
        )
        val verticalCandidateStyle = parseCandidateCellStyle(
            tree["verticalCandidateStyle"], "verticalCandidateStyle", issues,
        )

        // ---- styles（浅色板） ----
        // 显式 styles 逐字段优先于派生展开（2026-10-27 并入项口径）；
        // 派生失败只记 issue，不单独拒包（无配色拒绝由下方统一口径裁决）。
        val (colors, surfaceStyle) = parseColorSection(
            tree = tree,
            stylesKey = "styles",
            derivationKey = "derivation",
            issues = issues,
        )


        // ---- 校验硬化（2026-10-27 并入项）：无配色主题整包拒绝 ----
        // 显式 styles 与派生均未给出任何有效颜色时，旧行为是静默
        // 全量回落默认方案（用户看不出皮肤未生效）；现改为明确拒绝，
        // 导入/提交/预览各层按 Rejected 处理并向用户明示原因。
        // 浅色板是双色板的基础板：只有深色板而无浅色板同样拒包
        // （深色缺失叶子要按浅色板逐字段回填，无基础板无从回填）。
        if (colors.isEmpty()) {
            return Result.Rejected("no_color_scheme")
        }

        // ---- stylesDark / derivationDark（2026-10-27 第一组：深色板） ----
        // 合成优先级（高 → 低）：显式 stylesDark > 派生 derivationDark
        // > 浅色板同字段（逐字段回填，不回落默认方案）。深色段缺失
        // 叶子不记 issue（回填是设计行为）；显式/派生字段本身损坏
        // 才记 issue 并按本链回填。任一段为对象即视为声明深色板
        // （即使最终全等于浅色板，快照仍记 darkStyles，模式切换
        // 行为与未声明一致、无可见差异）。
        var darkStyles: StylesSpec? = null
        val darkStylesNode = tree["stylesDark"]
        val darkDerivationNode = tree["derivationDark"]
        val darkDeclared = darkStylesNode is JsonObject || darkDerivationNode is JsonObject
        // ---- 双模式硬要求（2026-10-29 批量第 6 项）：此后所有皮肤
        // 须同时声明深浅两板；深色段对象整体缺失即整包拒绝（不静默
        // 回填——回填只用于已声明深色段内的缺失叶子）。仅整段缺失
        // 拒包：已声明但部分损坏的深色段仍走现有逐字段回填。
        // 导入时校验，不重验已入库皮肤（已导入的单边皮肤继续可用）。
        if (!darkDeclared) {
            return Result.Rejected("skin_missing_dark_mode")
        }
        if (darkDeclared) {
            val (darkExplicit, darkSurface) = parseColorSection(
                tree = tree,
                stylesKey = "stylesDark",
                derivationKey = "derivationDark",
                issues = issues,
            )
            val darkColors = colors.toMutableMap()
            // parseColorSection 已按「显式 > 派生」合成深色段内值；
            // 此处再以深色段值覆盖浅色板：深色段内未给出的叶子
            // 保留浅色板值（逐字段回填），不碰默认方案。
            for ((field, value) in darkExplicit) {
                darkColors[field] = value
            }
            darkStyles = StylesSpec(
                colors = darkColors,
                surfaceStyle = darkSurface ?: surfaceStyle,
            )
        }


        // ---- G1 皮肤级分档字号（textSizes：角色 → 数值 8..40） ----
        // 非法角色/值逐项丢弃并记 issue，不拒整包；缺失角色回落出厂字号。
        val skinTextSizes = mutableMapOf<KeyTextRole, Float>()
        val textSizesNode = tree["textSizes"]
        if (textSizesNode != null) {
            val textSizesObj = textSizesNode as? JsonObject
            if (textSizesObj == null) {
                issues += SkinIssue(
                    "textSizes", SkinIssue.Kind.FIELD_FALLBACK, "text_sizes_not_object",
                )
            } else {
                for ((name, valueNode) in textSizesObj) {
                    val role = KeyTextRole.byJsonName(name)
                    if (role == null) {
                        issues += SkinIssue(
                            "textSizes.$name", SkinIssue.Kind.FIELD_FALLBACK,
                            "unknown_text_role",
                        )
                        continue
                    }
                    val primitive = valueNode as? JsonPrimitive
                    val value = if (primitive == null || primitive.isString) {
                        null
                    } else {
                        primitive.doubleOrNull?.toFloat()
                    }
                    if (value == null || !KeyTextSizeLimits.isValid(value)) {
                        issues += SkinIssue(
                            "textSizes.$name", SkinIssue.Kind.FIELD_FALLBACK,
                            "text_size_invalid",
                        )
                        continue
                    }
                    skinTextSizes[role] = value
                }
            }
        }

        // ---- keyStyles（2026-10-27 并入项：单键样式表） ----
        // 表本身只做形态校验（解析在快照应用期，目标 = 派生展开后
        // 的令牌快照）；无效项逐项丢弃并记 issue，不拒整包。
        // 几何面（审计 A 项）同表解析：样式项的 geometry 子对象
        // 或平铺对端字段 → keyGeometries，角色解析不受影响。
        // （抽取为 parseKeyStylesSection：主段与 G1 样式集共用，
        // 行为与抽取前一致，issue 路径由 path 参数决定。）
        val (keyStyles, keyGeometries) =
            parseKeyStylesSection(tree["keyStyles"], "keyStyles", issues)

        // ---- styleSets（G1）：顶层样式集，逐集与主段同口径解析 ----
        // 非对象整段丢弃记 issue；集内 styles 缺失时该集无效（只记
        // issue、不拒包，引用它的类型回落包级样式）；深色段未声明
        // 时该集无深色板（深色沿用浅色板，与主段 darkStyles 为 null
        // 同口径）；单键样式表复用主段解析（issue 路径带集名前缀）。
        val styleSets = LinkedHashMap<String, StyleSetRaw>()
        val styleSetsNode = tree["styleSets"]
        if (styleSetsNode != null) {
            val styleSetsObj = styleSetsNode as? JsonObject
            if (styleSetsObj == null) {
                issues += SkinIssue(
                    "styleSets", SkinIssue.Kind.FIELD_FALLBACK, "style_sets_not_object",
                )
            } else {
                for ((setName, setNode) in styleSetsObj) {
                    if (setName.isBlank()) continue
                    val setObj = setNode as? JsonObject
                    if (setObj == null) {
                        issues += SkinIssue(
                            "styleSets.$setName", SkinIssue.Kind.FIELD_FALLBACK,
                            "style_set_not_object",
                        )
                        continue
                    }
                    val (setColors, setSurface) = parseColorSection(
                        tree = setObj,
                        stylesKey = "styles",
                        derivationKey = "derivation",
                        issues = issues,
                    )
                    if (setObj["styles"] == null && setObj["derivation"] == null) {
                        issues += SkinIssue(
                            "styleSets.$setName", SkinIssue.Kind.FIELD_FALLBACK,
                            "style_set_no_styles",
                        )
                        continue
                    }
                    val setDark: StylesSpec? =
                        if (setObj["stylesDark"] is JsonObject ||
                            setObj["derivationDark"] is JsonObject
                        ) {
                            val (darkExplicit, darkSurface) = parseColorSection(
                                tree = setObj,
                                stylesKey = "stylesDark",
                                derivationKey = "derivationDark",
                                issues = issues,
                            )
                            val darkColors = setColors.toMutableMap()
                            for ((field, value) in darkExplicit) darkColors[field] = value
                            StylesSpec(
                                colors = darkColors,
                                surfaceStyle = darkSurface ?: setSurface,
                            )
                        } else {
                            null
                        }
                    val (setKeyStyles, setKeyGeometries) = parseKeyStylesSection(
                        setObj["keyStyles"], "styleSets.$setName.keyStyles", issues,
                    )
                    styleSets[setName] = StyleSetRaw(
                        styles = StylesSpec(colors = setColors, surfaceStyle = setSurface),
                        darkStyles = setDark,
                        keyStyles = setKeyStyles,
                        keyGeometries = setKeyGeometries,
                    )
                }
            }
        }

        // ---- keyAnimations（审计 H 项：动效表） ----
        // 非对象/未知类型/缺关键字段的项整项丢弃并记 issue，不拒整包。
        var keyAnimations: Map<String, KeyAnimationDef> = emptyMap()
        if (tree["keyAnimations"] != null) {
            val (table, tableWarnings) =
                KeyAnimationDefParser.parseTable(tree["keyAnimations"])
            keyAnimations = table
            for (warning in tableWarnings) {
                issues += SkinIssue(
                    "keyAnimations",
                    SkinIssue.Kind.FIELD_FALLBACK, warning,
                )
            }
        }

        // ---- hintSymbolsStyles（现行 form：长按符号样式表） ----
        // 非对象整段记 issue；单项无效（无 symbolStyles 等）逐项
        // 丢弃并记 issue，不拒整包；旧 form 节点在此整体无效。
        var hintSymbolsStyles: Map<String, HintSymbolsStyle> = emptyMap()
        val hintSymbolsStylesNode = tree["hintSymbolsStyles"]
        if (hintSymbolsStylesNode != null) {
            val hintSymbolsStylesObj = hintSymbolsStylesNode as? JsonObject
            if (hintSymbolsStylesObj == null) {
                issues += SkinIssue(
                    "hintSymbolsStyles", SkinIssue.Kind.FIELD_FALLBACK,
                    "hint_symbols_styles_not_object",
                )
            } else {
                val table = LinkedHashMap<String, HintSymbolsStyle>()
                for ((name, styleNode) in hintSymbolsStylesObj) {
                    val parsed = HintSymbolsParser.parseStyle(styleNode)
                    if (parsed == null) {
                        issues += SkinIssue(
                            "hintSymbolsStyles.$name", SkinIssue.Kind.FIELD_FALLBACK,
                            "hint_symbols_style_invalid",
                        )
                    } else {
                        table[name] = parsed
                    }
                }
                hintSymbolsStyles = table
            }
        }

        // ---- hintSymbolsGridStyles（网格版：长按符号网格样式表） ----
        // 口径与旧版表一致：非对象整段记 issue；单项无效逐项丢弃。
        var hintSymbolsGridStyles: Map<String, HintSymbolsGridStyle> = emptyMap()
        val hintSymbolsGridStylesNode = tree["hintSymbolsGridStyles"]
        if (hintSymbolsGridStylesNode != null) {
            val hintSymbolsGridStylesObj = hintSymbolsGridStylesNode as? JsonObject
            if (hintSymbolsGridStylesObj == null) {
                issues += SkinIssue(
                    "hintSymbolsGridStyles", SkinIssue.Kind.FIELD_FALLBACK,
                    "hint_symbols_grid_styles_not_object",
                )
            } else {
                val gridTable = LinkedHashMap<String, HintSymbolsGridStyle>()
                for ((name, styleNode) in hintSymbolsGridStylesObj) {
                    val parsed = HintSymbolsGridParser.parseStyle(styleNode)
                    if (parsed == null) {
                        issues += SkinIssue(
                            "hintSymbolsGridStyles.$name", SkinIssue.Kind.FIELD_FALLBACK,
                            "hint_symbols_grid_style_invalid",
                        )
                    } else {
                        gridTable[name] = parsed
                    }
                }
                hintSymbolsGridStyles = gridTable
            }
        }

        // ---- hintSymbolNodes（长按符号全节点注册表） ----
        // 非对象整段记 issue；单项无动作／动作非法逐项丢弃并记
        // issue，不拒整包；颜色／几何按同名查 keyStyles 表。
        var hintSymbolNodes: Map<String, HintSymbolNode> = emptyMap()
        val hintSymbolNodesNode = tree["hintSymbolNodes"]
        if (hintSymbolNodesNode != null) {
            val hintSymbolNodesObj = hintSymbolNodesNode as? JsonObject
            if (hintSymbolNodesObj == null) {
                issues += SkinIssue(
                    "hintSymbolNodes", SkinIssue.Kind.FIELD_FALLBACK,
                    "hint_symbol_nodes_not_object",
                )
            } else {
                val nodeTable = LinkedHashMap<String, HintSymbolNode>()
                for ((name, node) in hintSymbolNodesObj) {
                    val parsed = HintSymbolNodeParser.parse(node)
                    if (parsed == null) {
                        issues += SkinIssue(
                            "hintSymbolNodes.$name", SkinIssue.Kind.FIELD_FALLBACK,
                            "hint_symbol_node_invalid",
                        )
                    } else {
                        nodeTable[name] = parsed
                    }
                }
                hintSymbolNodes = nodeTable
            }
        }

        // ---- keyMappings（B2 强类型解析；单项无效只剔该项，逐项报告） ----
        var keyMappingsCount = 0
        var keyOverrides: Map<String, KeyOverride> = emptyMap()
        var keyRejections: List<KeyBindingRejection> = emptyList()
        var sequenceBindingCount = 0
        if (tree["keyMappings"] != null) {
            if (keyMappings == null) {
                issues += SkinIssue(
                    "keyMappings", SkinIssue.Kind.FIELD_FALLBACK, "key_mappings_not_object",
                )
            } else {
                keyMappingsCount = keyMappings.size
                val parsed = SkinKeyMappingsParser.parse(
                    keyMappings = keyMappings,
                    isOverridable = { id ->
                        isOverridable(id) ||
                            com.jobeen.ime.skin.layout.LayoutModel.parseLogicalId(id) != null
                    },
                    isAnchor = KeyIds::isAnchor,
                )
                keyOverrides = filterKeyImages(parsed.overrides, assetKind, assetBytes, issues)
                for (warning in parsed.fieldWarnings) {
                    issues += SkinIssue(
                        "keyMappings.${warning.keyId}.${warning.field}",
                        SkinIssue.Kind.FIELD_FALLBACK, warning.reason,
                    )
                }
                keyRejections = parsed.rejections
                sequenceBindingCount = parsed.sequenceSlots.size
                for (rejection in parsed.rejections) {
                    val path = buildString {
                        append("keyMappings.").append(rejection.keyId)
                        rejection.slot?.let { append('.').append(it.jsonName) }
                    }
                    issues += SkinIssue(
                        path,
                        if (rejection.reason == BindingRejectReason.ANCHOR_NOT_OVERRIDABLE) {
                            SkinIssue.Kind.INFO
                        } else {
                            SkinIssue.Kind.FIELD_FALLBACK
                        },
                        rejection.reason.name.lowercase(),
                    )
                }
                for ((keyId, slot) in parsed.sequenceSlots) {
                    issues += SkinIssue(
                        "keyMappings.$keyId.${slot.jsonName}",
                        SkinIssue.Kind.RETAINED_INACTIVE, "sequence_retained_inactive",
                    )
                }
                for (warning in parsed.styleWarnings) {
                    issues += SkinIssue(
                        "keyMappings.${warning.owner}" +
                            (warning.role?.let { ".${it.jsonName}" } ?: ""),
                        SkinIssue.Kind.FIELD_FALLBACK, warning.reason,
                    )
                }
            }
        }

        // ---- audio（B2 应用；此处只做引用存在性提示，接纳判定在快照构建期） ----
        val audioRefs = mutableMapOf<String, String>()
        val audio = tree["audio"] as? JsonObject
        if (tree["audio"] != null && audio == null) {
            issues += SkinIssue("audio", SkinIssue.Kind.FIELD_FALLBACK, "audio_not_object")
        }
        if (audio != null) {
            for ((slot, node) in audio) {
                val assetId = (node as? JsonPrimitive)?.contentOrNull
                if (assetId == null) {
                    issues += SkinIssue("audio.$slot", SkinIssue.Kind.FIELD_FALLBACK, "audio_ref_not_string")
                } else {
                    audioRefs[slot] = assetId
                    if (assetKind(assetId) == null) {
                        issues += SkinIssue(
                            "audio.$slot", SkinIssue.Kind.RESOURCE_FALLBACK, "audio_asset_missing",
                        )
                    }
                }
            }
        }

        // ---- keySound（按键音；与 audio 同口径：只做引用存在性提示，
        // 接纳判定在快照构建期；非法引用逐项丢弃，不影响其它槽） ----
        val keySoundActions = mutableMapOf<String, String>()
        val keySoundNode = tree["keySound"] as? JsonObject
        if (tree["keySound"] != null && keySoundNode == null) {
            issues += SkinIssue("keySound", SkinIssue.Kind.FIELD_FALLBACK, "keysound_not_object")
        }
        fun keySoundSlot(name: String): String? {
            val node = keySoundNode?.get(name) ?: return null
            // 资产 id 必须是 JSON 字符串：contentOrNull 对数字/布尔也会
            // 返回字面量，故须显式 isString，否则 123 会被当成合法引用。
            val assetId = (node as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (assetId == null) {
                issues += SkinIssue(
                    "keySound.$name", SkinIssue.Kind.FIELD_FALLBACK, "keysound_ref_not_string",
                )
                return null
            }
            if (assetKind(assetId) == null) {
                issues += SkinIssue(
                    "keySound.$name", SkinIssue.Kind.RESOURCE_FALLBACK, "keysound_asset_missing",
                )
            }
            return assetId
        }
        if (keySoundNode != null) {
            val actions = keySoundNode["actions"] as? JsonObject
            if (keySoundNode["actions"] != null && actions == null) {
                issues += SkinIssue(
                    "keySound.actions", SkinIssue.Kind.FIELD_FALLBACK, "keysound_actions_not_object",
                )
            }
            if (actions != null) {
                for ((char, node) in actions) {
                    val assetId = (node as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (assetId == null) {
                        issues += SkinIssue(
                            "keySound.actions.$char",
                            SkinIssue.Kind.FIELD_FALLBACK, "keysound_ref_not_string",
                        )
                    } else {
                        keySoundActions[char] = assetId
                        if (assetKind(assetId) == null) {
                            issues += SkinIssue(
                                "keySound.actions.$char",
                                SkinIssue.Kind.RESOURCE_FALLBACK, "keysound_asset_missing",
                            )
                        }
                    }
                }
            }
        }

        // ---- toolbar（2026-10-27 工具栏进皮肤：顶层节点，逐项丢弃不拒包） ----
        // 按钮池判定复用 ToolbarConfig（未知 id 即非法）；资产类别沿用
        // 传入的 manifest 口径（IMAGE 资产机制，与背景同口径）。
        val (toolbarSpec, toolbarIssues) = SkinToolbarParser.parse(
            node = tree["toolbar"],
            assetKind = assetKind,
            isKnownButton = { id -> ToolbarConfig.item(id) != null },
        )
        issues += toolbarIssues

        // ---- notifications / conditionStyles（重点候选 8-1） ----
        // 顶层数组，逐项丢弃不拒包（坏项只记 issue）；非数组记一次
        // issue 并视为空；未定义即空表（旧皮肤无行为变化）。
        var notifications: List<SkinNotification> = emptyList()
        val notificationsNode = tree["notifications"]
        if (notificationsNode != null) {
            val arr = notificationsNode as? JsonArray
            if (arr == null) {
                issues += SkinIssue(
                    "notifications", SkinIssue.Kind.FIELD_FALLBACK, "notifications_not_array",
                )
            } else {
                val (parsed, dropped) = SkinNotificationParser.parse(arr)
                notifications = parsed
                if (dropped > 0) {
                    issues += SkinIssue(
                        "notifications", SkinIssue.Kind.FIELD_FALLBACK,
                        "notifications_dropped:$dropped",
                    )
                }
            }
        }
        var conditionStyles: List<ConditionStyle> = emptyList()
        val conditionStylesNode = tree["conditionStyles"]
        if (conditionStylesNode != null) {
            val arr = conditionStylesNode as? JsonArray
            if (arr == null) {
                issues += SkinIssue(
                    "conditionStyles", SkinIssue.Kind.FIELD_FALLBACK,
                    "condition_styles_not_array",
                )
            } else {
                val (parsed, dropped) = ConditionStyleParser.parse(arr)
                conditionStyles = parsed
                if (dropped > 0) {
                    issues += SkinIssue(
                        "conditionStyles", SkinIssue.Kind.FIELD_FALLBACK,
                        "condition_styles_dropped:$dropped",
                    )
                }
            }
        }

        // ---- symbolBar（重点候选 8-6）：顶层对象，非法键项逐项丢弃不拒包 ----
        var symbolBar: SymbolBarSpec? = null
        val symbolBarNode = tree["symbolBar"]
        if (symbolBarNode != null) {
            if (symbolBarNode !is JsonObject) {
                issues += SkinIssue(
                    "symbolBar", SkinIssue.Kind.FIELD_FALLBACK, "symbol_bar_not_object",
                )
            } else {
                val (parsed, dropped) = SymbolBarParser.parse(symbolBarNode)
                symbolBar = parsed
                if (dropped > 0) {
                    issues += SkinIssue(
                        "symbolBar", SkinIssue.Kind.FIELD_FALLBACK,
                        "symbol_bar_dropped:$dropped",
                    )
                }
            }
        }

        // ---- subClassifiedSymbols：顶层数组或 {parents}，非法父／子类逐项丢弃不拒包 ----
        var subClassifiedSymbols: com.jobeen.ime.skin.symbol.SubClassifiedSymbols? = null
        val subClassifiedNode = tree["subClassifiedSymbols"]
        if (subClassifiedNode != null) {
            if (subClassifiedNode !is JsonObject && subClassifiedNode !is JsonArray) {
                issues += SkinIssue(
                    "subClassifiedSymbols", SkinIssue.Kind.FIELD_FALLBACK,
                    "sub_classified_symbols_not_array_or_object",
                )
            } else {
                val (parsed, dropped) = SubClassifiedSymbolsParser.parse(subClassifiedNode)
                subClassifiedSymbols = parsed
                if (dropped > 0) {
                    issues += SkinIssue(
                        "subClassifiedSymbols", SkinIssue.Kind.FIELD_FALLBACK,
                        "sub_classified_symbols_dropped:$dropped",
                    )
                }
            }
        }

        // ---- t9Symbols / t9HorizontalSymbols / numericSymbols /
        // horizontalSymbols（P1-7）：此前解析器写好但无生产调用
        // 点、顶层键也不在已知表内，节点被当未知透传；现解析进
        // 快照（非法项逐项丢弃、只记 issue，不拒包），渲染见
        // SymbolKeyboard（九宫格／数字作附加父类、横向条作横条）。
        var t9Symbols: com.jobeen.ime.skin.symbol.T9SymbolsSpec? = null
        val t9SymbolsNode = tree["t9Symbols"]
        if (t9SymbolsNode != null) {
            val (parsed, dropped) = T9SymbolsParser.parse(t9SymbolsNode)
            t9Symbols = parsed
            if (dropped > 0) {
                issues += SkinIssue(
                    "t9Symbols", SkinIssue.Kind.FIELD_FALLBACK,
                    "t9_symbols_dropped:$dropped",
                )
            }
        }
        var t9HorizontalSymbols: com.jobeen.ime.skin.symbol.HorizontalSymbolsSpec? = null
        val t9HorizontalNode = tree["t9HorizontalSymbols"]
        if (t9HorizontalNode != null) {
            val (parsed, dropped) = T9HorizontalSymbolsParser.parse(t9HorizontalNode)
            t9HorizontalSymbols = parsed
            if (dropped > 0) {
                issues += SkinIssue(
                    "t9HorizontalSymbols", SkinIssue.Kind.FIELD_FALLBACK,
                    "t9_horizontal_symbols_dropped:$dropped",
                )
            }
        }
        var numericSymbols: com.jobeen.ime.skin.symbol.NumericSymbolsCollection? = null
        val numericSymbolsNode = tree["numericSymbols"]
        if (numericSymbolsNode != null) {
            // 节点存在即解析（无 dataSource／空源时回落内置数字集，
            // 与 NumericSymbols.parse 的既有口径一致）。
            numericSymbols = NumericSymbols.parse(numericSymbolsNode)
        }
        var horizontalSymbols: com.jobeen.ime.skin.symbol.HorizontalSymbolsSpec? = null
        val horizontalSymbolsNode = tree["horizontalSymbols"]
        if (horizontalSymbolsNode != null) {
            val (parsed, dropped) = HorizontalSymbolsParser.parse(horizontalSymbolsNode)
            horizontalSymbols = parsed
            if (dropped > 0) {
                issues += SkinIssue(
                    "horizontalSymbols", SkinIssue.Kind.FIELD_FALLBACK,
                    "horizontal_symbols_dropped:$dropped",
                )
            }
        }

        // ---- typeAreas（G2）：keyboards.<类型>.areas，逐类型区域参数 ----
        // 与包级区域段同解析口径（同字段、同范围、同 issue 语义），
        // 非对象／空对象均视为未声明（不记 issue、不占位）；
        // 面板接线属后续轮，本批只落数据层与快照透传。
        val typeAreas = LinkedHashMap<String, TypeAreaSet>()
        val keyboardsNode = tree["keyboards"] as? JsonObject
        if (keyboardsNode != null) {
            for ((type, entryNode) in keyboardsNode) {
                if (type.isBlank()) continue
                val entryObj = entryNode as? JsonObject ?: continue
                val areasObj = entryObj["areas"] as? JsonObject ?: continue
                val areaPath = "keyboards.$type.areas"
                val areaSet = TypeAreaSet(
                    toolbarHeight = parseVhLength(
                        areasObj["toolbarHeight"], "$areaPath.toolbarHeight",
                        issues, 16f..120f,
                    ),
                    preeditHeight = parseVhLength(
                        areasObj["preeditHeight"], "$areaPath.preeditHeight",
                        issues, 12f..64f,
                    ),
                    horizontalCandidatesStyle = parseCandidateAreaStyle(
                        areasObj["horizontalCandidatesStyle"],
                        "$areaPath.horizontalCandidatesStyle", issues,
                    ),
                    verticalCandidatesStyle = parseCandidateAreaStyle(
                        areasObj["verticalCandidatesStyle"],
                        "$areaPath.verticalCandidatesStyle", issues,
                    ),
                    horizontalCandidateStyle = parseCandidateCellStyle(
                        areasObj["horizontalCandidateStyle"],
                        "$areaPath.horizontalCandidateStyle", issues,
                    ),
                    verticalCandidateStyle = parseCandidateCellStyle(
                        areasObj["verticalCandidateStyle"],
                        "$areaPath.verticalCandidateStyle", issues,
                    ),
                )
                if (areaSet != TypeAreaSet()) typeAreas[type] = areaSet
            }
        }

        // ---- symbolBadge（S12）：顶层对象，非法字段逐项回落渲染缺省 ----
        val badgeObj = tree["symbolBadge"] as? JsonObject
        val symbolBadgeFontSizeDp = parseDpField(
            badgeObj?.get("fontSizeDp"), "symbolBadge.fontSizeDp", issues, 6f..20f,
        )
        val symbolBadgeNormalColor = parseStyleColor(
            badgeObj?.get("normalColor"), "symbolBadge.normalColor", issues,
        )
        val symbolBadgeHighlightColor = parseStyleColor(
            badgeObj?.get("highlightColor"), "symbolBadge.highlightColor", issues,
        )

        // ---- keyShadow（2026-10-29 item-1 拟态）：顶层对象，非法字段逐项回退默认值 ----
        val (keyShadow, keyShadowIssues) = KeyShadowParser.parse(tree["keyShadow"])
        issues += keyShadowIssues

        // ---- keyAnimation / spaceAnimation（2026-10-29 item-1 动效）：同口径 ----
        val (keyAnimation, keyAnimationIssues) =
            KeyAnimationParser.parse(tree["keyAnimation"], "keyAnimation")
        issues += keyAnimationIssues
        val (spaceAnimation, spaceAnimationIssues) =
            KeyAnimationParser.parse(tree["spaceAnimation"], "spaceAnimation")
        issues += spaceAnimationIssues
        // spaceAnimation 未写节点 = 跟随 keyAnimation（null 哨兵）；
        // 写了节点（即使 enabled=false）= 空格专属规格（取代 keyAnimation）。
        val spaceAnimationOrNull = if (tree["spaceAnimation"] == null) null else spaceAnimation

        return Result.Valid(
            skin = ValidatedSkin(
                tree = tree,
                formatVersion = formatVersion,
                metaId = metaId,
                metaName = metaName,
                metaAuthor = metaAuthor,
                metaVersion = metaVersion,
                layoutId = layoutId,
                layoutIdLandscape = layoutIdLandscape,
                appearance = AppearanceSpec(
                    presence = presence,
                    effectiveGeometry = effective,
                    background = background,
                ),
                styles = StylesSpec(colors = colors, surfaceStyle = surfaceStyle),
                darkStyles = darkStyles,
                styleSets = styleSets,
                typeAreas = typeAreas,
                keyMappingsPresent = tree["keyMappings"] != null,
                keyMappingsCount = keyMappingsCount,
                keyOverrides = keyOverrides,
                keyRejections = keyRejections,
                sequenceBindingCount = sequenceBindingCount,
                audioRefs = audioRefs,
                keySound = KeySoundSpec(
                    actions = keySoundActions,
                    delete = keySoundSlot("delete"),
                    input = keySoundSlot("input"),
                    system = keySoundSlot("system"),
                ),
                keyStyles = keyStyles,
                keyGeometries = keyGeometries,
                keyAnimations = keyAnimations,
                hintSymbolsStyles = hintSymbolsStyles,
                hintSymbolsGridStyles = hintSymbolsGridStyles,
                hintSymbolNodes = hintSymbolNodes,
                keyboardGeometry = KeyboardGeometrySpec(
                    presence = keyboardPresence,
                    values = keyboardValues,
                ),
                fontFamily = fontFamily,
                fontFamilies = fontFamilies,
                roleFonts = roleFonts,
                roleFontFamilies = roleFontFamilies,
                skinFontFaces = skinFontFaces,
                fontRanges = fontRanges,
                bubbleOverflowCheck = bubbleOverflowCheck,
                swipeThresholdDp = swipeThresholdDp,
                candidateRadiusDp = candidateRadiusDp,
                predictionCloseBg = predictionCloseBg,
                predictionCloseRadiusDp = predictionCloseRadiusDp,
                toolbar = toolbarSpec,
                suppressToolbar = suppressToolbar,
                suppressKeyboard = suppressKeyboard,
                toolbarHeight = toolbarHeight,
                toolbarStyle = toolbarStyle,
                toolbarLayout = toolbarLayout,
                toolbarButtonBackground = toolbarButtonBackground,
                spriteMaps = spriteMaps,
                preeditHeight = preeditHeight,
                preeditStyle = preeditStyle,
                horizontalCandidatesStyle = horizontalCandidatesStyle,
                verticalCandidatesStyle = verticalCandidatesStyle,
                candidateIndexSizeDp = candidateIndexSizeDp,
                candidateCommentSizeDp = candidateCommentSizeDp,
                horizontalCandidateStyle = horizontalCandidateStyle,
                verticalCandidateStyle = verticalCandidateStyle,
                verticalBottomRow = verticalBottomRow,
                horizontalMaxColumns = horizontalMaxColumns,
                horizontalExpandLeft = horizontalExpandLeft,
                horizontalSymbolsRtl = horizontalSymbolsRtl,
                verticalMaxRows = verticalCandidates?.maxRows,
                verticalMaxColumns = verticalCandidates?.maxColumns,
                verticalSidePanel = verticalSidePanel,
                candidateContextMenu = candidateContextMenu,
                textSizes = skinTextSizes,
                notifications = notifications,
                conditionStyles = conditionStyles,
                symbolBar = symbolBar,
                subClassifiedSymbols = subClassifiedSymbols,
                t9Symbols = t9Symbols,
                t9HorizontalSymbols = t9HorizontalSymbols,
                numericSymbols = numericSymbols,
                horizontalSymbols = horizontalSymbols,
                symbolBadgeFontSizeDp = symbolBadgeFontSizeDp,
                symbolBadgeNormalColor = symbolBadgeNormalColor,
                symbolBadgeHighlightColor = symbolBadgeHighlightColor,
                keyShadow = keyShadow,
                keyAnimation = keyAnimation,
                spaceAnimation = spaceAnimationOrNull,
                // 未知节点原样透传（快照层；文件层本就逐字节保留，
                // 快照→skin.json 再生成器未来落地后才构成完整往返）。
                unknownNodes = tree.filterKeys { it !in KNOWN_TOP_LEVEL_KEYS },
            ),
            issues = issues,
        )
    }

    /**
     * 单键样式表解析（主段 keyStyles 与 G1 样式集内 keyStyles 共用）：
     * 返回（角色表，几何表）；节点缺失时两表皆空；非对象记 issue
     * 并返回空表。issue 路径由 [path] 决定（主段 "keyStyles"，
     * 样式集 "styleSets.<集名>.keyStyles"）。
     */
    private fun parseKeyStylesSection(
        node: kotlinx.serialization.json.JsonElement?,
        path: String,
        issues: MutableList<SkinIssue>,
    ): Pair<Map<String, Map<KeyStyleRole, String>>, Map<String, KeyStyleGeometry>> {
        if (node == null) return emptyMap<String, Map<KeyStyleRole, String>>() to
            emptyMap<String, KeyStyleGeometry>()
        val keyStylesObj = node as? JsonObject
        if (keyStylesObj == null) {
            issues += SkinIssue(
                path, SkinIssue.Kind.FIELD_FALLBACK, "key_styles_not_object",
            )
            return emptyMap<String, Map<KeyStyleRole, String>>() to
                emptyMap<String, KeyStyleGeometry>()
        }
        val (table, tableWarnings) = KeyStyleParser.parseTable(keyStylesObj)
        for (warning in tableWarnings) {
            issues += SkinIssue(
                "$path.${warning.owner}" +
                    (warning.role?.let { ".${it.jsonName}" } ?: ""),
                SkinIssue.Kind.FIELD_FALLBACK, warning.reason,
            )
        }
        val geometries = LinkedHashMap<String, KeyStyleGeometry>()
        for ((name, styleNode) in keyStylesObj) {
            val styleObj = styleNode as? JsonObject ?: continue
            val (geometry, geometryWarnings) =
                KeyStyleGeometryParser.parseEntry(styleObj)
            if (geometry != null) geometries[name] = geometry
            for (warning in geometryWarnings) {
                issues += SkinIssue(
                    "$path.$name.geometry",
                    SkinIssue.Kind.FIELD_FALLBACK, warning,
                )
            }
        }
        return table to geometries
    }

    /**
     * 一段配色（styles + derivation）的校验合成：显式逐字段
     * 优先，派生展开补缺失叶子；损坏字段逐项记 issue 并跳过
     * （调用方按回填链处理）。浅色段与深色段共用本实现，
     * 仅键名与 issue 路径前缀不同。
     * @return 段内有效颜色表（不含任何回填）与 surfaceStyle。
     */
    private fun parseColorSection(
        tree: JsonObject,
        stylesKey: String,
        derivationKey: String,
        issues: MutableList<SkinIssue>,
    ): Pair<Map<StyleField, Int>, KeyboardColors.SurfaceStyle?> {
        val styles = tree[stylesKey] as? JsonObject
        if (tree[stylesKey] != null && styles == null) {
            issues += SkinIssue(stylesKey, SkinIssue.Kind.FIELD_FALLBACK, "styles_not_object")
        }
        val colors = mutableMapOf<StyleField, Int>()
        for (field in StyleField.entries) {
            val node = nodeAtPath(styles, field.path) ?: continue
            val raw = (node as? JsonPrimitive)?.contentOrNull
            val parsed = raw?.let { SkinJson.parseColor(it) }
            if (parsed == null) {
                issues += SkinIssue(
                    "$stylesKey.${field.path}",
                    SkinIssue.Kind.FIELD_FALLBACK, "invalid_color",
                )
            } else {
                colors[field] = parsed
            }
        }
        var surfaceStyle: KeyboardColors.SurfaceStyle? = null
        val surfacePrimitive = styles?.get("surfaceStyle") as? JsonPrimitive
        if (surfacePrimitive != null) {
            when (surfacePrimitive.contentOrNull) {
                "Raised" -> surfaceStyle = KeyboardColors.SurfaceStyle.Raised
                "Flat" -> surfaceStyle = KeyboardColors.SurfaceStyle.Flat
                else -> issues += SkinIssue(
                    "$stylesKey.surfaceStyle", SkinIssue.Kind.FIELD_FALLBACK,
                    "invalid_surface_style",
                )
            }
        }
        val derivationNode = tree[derivationKey]
        if (derivationNode != null) {
            val derivation = derivationNode as? JsonObject
            if (derivation == null) {
                issues += SkinIssue(
                    derivationKey, SkinIssue.Kind.FIELD_FALLBACK, "derivation_not_object",
                )
            } else {
                var derivationOk = true
                for (name in ColorDerivation.BaseColors.FIELD_NAMES) {
                    if (ColorDerivation.parseField(derivation, name) == null) {
                        derivationOk = false
                        issues += SkinIssue(
                            "$derivationKey.$name",
                            SkinIssue.Kind.FIELD_FALLBACK, "derivation_field_invalid",
                        )
                    }
                }
                if (derivationOk) {
                    val base = ColorDerivation.parse(derivation)!!
                    val derived = ColorDerivation.expand(base).toMutableMap()
                    // G1 公式层（仅浅色段）：derivationFormulas 在固定
                    // 映射之上改写派生值，仍低于显式 styles；非法公式
                    // 整条回退固定映射并记 issue（并入 parseColorSection
                    // 是为与双色板共用同一合成口径，不再另起内联展开）。
                    if (stylesKey == "styles") {
                        val formulasNode = tree["derivationFormulas"] as? JsonObject
                        if (tree["derivationFormulas"] != null && formulasNode == null) {
                            issues += SkinIssue(
                                "derivationFormulas", SkinIssue.Kind.FIELD_FALLBACK,
                                "derivation_formulas_not_object",
                            )
                        }
                        if (formulasNode != null) {
                            val baseMap = mapOf(
                                "face" to base.face,
                                "pressed" to base.pressed,
                                "bed" to base.bed,
                                "ink" to base.ink,
                                "accent" to base.accent,
                                "hint" to base.hint,
                            )
                            for ((path, formulaNode) in formulasNode) {
                                val field = StyleField.entries.firstOrNull { it.path == path }
                                if (field == null) {
                                    issues += SkinIssue(
                                        "derivationFormulas.$path", SkinIssue.Kind.FIELD_FALLBACK,
                                        "derivation_formula_unknown_token",
                                    )
                                    continue
                                }
                                val formula = (formulaNode as? JsonPrimitive)?.contentOrNull
                                val value = formula?.let { DerivationFormulas.eval(it, baseMap) }
                                if (value == null) {
                                    issues += SkinIssue(
                                        "derivationFormulas.$path", SkinIssue.Kind.FIELD_FALLBACK,
                                        "derivation_formula_invalid",
                                    )
                                    continue
                                }
                                derived[field] = value
                            }
                        }
                    }
                    var derivedCount = 0
                    for ((field, value) in derived) {
                        if (field !in colors) {
                            colors[field] = value
                            derivedCount++
                        }
                    }
                    issues += SkinIssue(
                        derivationKey, SkinIssue.Kind.INFO,
                        "derivation_applied:$derivedCount",
                    )
                }
            }
        }
        // G1：有公式段但无有效 6 基色时，公式无求值基准，整段不生效并明示。
        if (stylesKey == "styles" && tree["derivationFormulas"] is JsonObject) {
            val derivationObj = tree[derivationKey] as? JsonObject
            val baseValid = derivationObj != null &&
                ColorDerivation.BaseColors.FIELD_NAMES.all {
                    ColorDerivation.parseField(derivationObj, it) != null
                }
            if (!baseValid) {
                issues += SkinIssue(
                    "derivationFormulas", SkinIssue.Kind.FIELD_FALLBACK,
                    "derivation_formula_no_base",
                )
            }
        }
        return colors to surfaceStyle
    }

    /**
     * G1 图片资产清单过滤（校验器口径）：逐键 image 与前景 image 层
     * 按清单判定——缺失/类型非法/超限时逐字段回退（image 置空、
     * 坏前景层整层丢弃），并逐项记 RESOURCE_FALLBACK issue；
     * 其他字段与动作绑定不受影响。快照接纳口径在构建器再守一道。
     */
    private fun filterKeyImages(
        overrides: Map<String, KeyOverride>,
        assetKind: (String) -> AssetKind?,
        assetBytes: (String) -> Long?,
        issues: MutableList<SkinIssue>,
    ): Map<String, KeyOverride> {
        if (overrides.isEmpty()) return overrides
        fun imageProblem(id: String): String? {
            return when (assetKind(id)) {
                null -> "key_image_missing"
                AssetKind.IMAGE -> {
                    val size = assetBytes(id)
                    if (size != null && size > MAX_KEY_IMAGE_BYTES) {
                        "key_image_too_large"
                    } else {
                        null
                    }
                }
                else -> "key_image_wrong_type"
            }
        }
        val filtered = LinkedHashMap<String, KeyOverride>()
        for ((keyId, override) in overrides) {
            var current = override
            override.image?.let { id ->
                imageProblem(id)?.let { problem ->
                    issues += SkinIssue(
                        "keyMappings.$keyId.image",
                        SkinIssue.Kind.RESOURCE_FALLBACK, problem,
                    )
                    current = current.copy(image = null)
                }
            }
            override.foregroundLayers?.let { layers ->
                val kept = layers.filter { layer ->
                    val image = layer.image ?: return@filter true
                    val problem = imageProblem(image)
                    if (problem != null) {
                        issues += SkinIssue(
                            "keyMappings.$keyId.foregroundLayers",
                            SkinIssue.Kind.RESOURCE_FALLBACK, problem,
                        )
                        false
                    } else {
                        true
                    }
                }
                if (kept.size != layers.size) {
                    current = current.copy(
                        foregroundLayers = kept.ifEmpty { null },
                    )
                }
            }
            filtered[keyId] = current
        }
        return filtered
    }

    /** 背景段校验：type=asset + 资产存在且为图片，否则整段回退为无背景（七.3 字段级）。 */
    private fun validateBackground(
        node: kotlinx.serialization.json.JsonElement?,
        assetKind: (String) -> AssetKind?,
        issues: MutableList<SkinIssue>,
    ): BackgroundSpec? {
        if (node == null) return null
        val obj = node as? JsonObject
        if (obj == null) {
            issues += SkinIssue(
                "appearance.background", SkinIssue.Kind.RESOURCE_FALLBACK, "background_not_object",
            )
            return null
        }
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        val assetId = (obj["asset"] as? JsonPrimitive)?.contentOrNull
        // S8：纯渐变背景（无资产）：与键面渐变同字段口径取 normal 支；
        // 不足两色时整段无效（回落纯色，不以单色占位）。
        if (type == "gradient") {
            val (gradient, _, gradientWarnings) = KeyGradientParser.parseFaces(obj)
            for (warning in gradientWarnings) {
                issues += SkinIssue(
                    "appearance.background", SkinIssue.Kind.FIELD_FALLBACK, warning,
                )
            }
            if (gradient == null) {
                issues += SkinIssue(
                    "appearance.background", SkinIssue.Kind.RESOURCE_FALLBACK,
                    "background_gradient_invalid",
                )
                return null
            }
            val overlay = validateRangedFloat(
                obj["overlayAlpha"], ALPHA_RANGE, 0f,
                "appearance.background.overlayAlpha", issues,
            )
            return BackgroundSpec(
                assetId = "",
                blurRadius = 0f,
                overlayAlpha = overlay,
                gradient = gradient,
            )
        }
        if (type != "asset" || assetId == null) {
            issues += SkinIssue(
                "appearance.background", SkinIssue.Kind.RESOURCE_FALLBACK, "background_ref_invalid",
            )
            return null
        }
        when (assetKind(assetId)) {
            AssetKind.IMAGE -> Unit
            AssetKind.AUDIO, AssetKind.VIDEO, AssetKind.FONT, AssetKind.OTHER -> {
                issues += SkinIssue(
                    "appearance.background", SkinIssue.Kind.RESOURCE_FALLBACK, "background_asset_wrong_type",
                )
                return null
            }
            null -> {
                issues += SkinIssue(
                    "appearance.background", SkinIssue.Kind.RESOURCE_FALLBACK, "background_asset_missing",
                )
                return null
            }
        }
        val blur = validateRangedFloat(
            obj["blurRadius"], BLUR_RANGE, 0f, "appearance.background.blurRadius", issues,
        )
        val overlay = validateRangedFloat(
            obj["overlayAlpha"], ALPHA_RANGE, 0f, "appearance.background.overlayAlpha", issues,
        )
        val imageAdjust = ImageAdjust.parse(
            obj["imageAdjust"], "appearance.background.imageAdjust", issues,
        )
        // S8：资产背景亦可附渐变（位图失效时回落渐变，仍在纯色之上）；
        // 无渐变字段时 parseFaces 返回空渐变，不记警告。
        val (assetGradient, _, assetGradientWarnings) = KeyGradientParser.parseFaces(obj)
        for (warning in assetGradientWarnings) {
            issues += SkinIssue(
                "appearance.background", SkinIssue.Kind.FIELD_FALLBACK, warning,
            )
        }
        // 主题视频背景（2026-10-29 外观轮）：只认 VIDEO 类资产；
        // 错类／缺失时只丢视频字段、静态段仍有效（字段级回退，
        // 与背景图错类整段回退不同——视频是静态之上的叠加层）。
        val videoId = (obj["video"] as? JsonPrimitive)?.contentOrNull
        val videoAssetId = when {
            videoId == null -> null
            assetKind(videoId) == AssetKind.VIDEO -> videoId
            assetKind(videoId) == null -> {
                issues += SkinIssue(
                    "appearance.background.video", SkinIssue.Kind.RESOURCE_FALLBACK, "background_video_missing",
                )
                null
            }
            else -> {
                issues += SkinIssue(
                    "appearance.background.video", SkinIssue.Kind.RESOURCE_FALLBACK, "background_video_wrong_type",
                )
                null
            }
        }
        return BackgroundSpec(
            assetId = assetId,
            blurRadius = blur,
            overlayAlpha = overlay,
            imageAdjust = imageAdjust,
            videoAssetId = videoAssetId,
            gradient = assetGradient,
        )
    }

    private fun validateRangedFloat(
        node: kotlinx.serialization.json.JsonElement?,
        range: ClosedFloatingPointRange<Float>,
        default: Float,
        path: String,
        issues: MutableList<SkinIssue>,
    ): Float {
        if (node == null) return default
        val value = (node as? JsonPrimitive).strictDoubleOrNull()?.toFloat()
        if (value == null || !value.isFinite() || value !in range) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "out_of_range_or_invalid")
            return default
        }
        return value
    }

    /**
     * 严格数值读取：JSON 字符串不算数值（kotlinx 的 intOrNull /
     * doubleOrNull 会把 "3" 这类字符串内容也解析成数，与九.1
     * 「类型错按坏字段」口径不符，故先以 isString 守卫）。
     */
    private fun JsonPrimitive?.strictIntOrNull(): Int? =
        if (this == null || isString) null else intOrNull

    private fun JsonPrimitive?.strictDoubleOrNull(): Double? =
        if (this == null || isString) null else doubleOrNull

    /** 按点分路径取节点（styles 的 panel.* / pinner.* 嵌套，定稿十章）。 */
    private fun nodeAtPath(root: JsonObject?, path: String): kotlinx.serialization.json.JsonElement? {
        var current: kotlinx.serialization.json.JsonElement = root ?: return null
        for (segment in path.split('.')) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }
}
