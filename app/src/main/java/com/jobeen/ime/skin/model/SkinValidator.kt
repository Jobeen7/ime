package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 *   剔该项）；layoutId 仍保留不应用（Batch 3）。
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
    )
    private val BLUR_RANGE = 0f..25f
    private val ALPHA_RANGE = 0f..1f
    private const val MAX_META_NAME_LENGTH = 32

    sealed interface Result {
        data class Valid(val skin: ValidatedSkin, val issues: List<SkinIssue>) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * @param currentVersionCode 当前 App versionCode（minAppVersion 门）。
     * @param fallbacks 全局偏好现值（几何回填链第二级）。
     * @param assetKind 资产逻辑 id → 类别；查无此 id 返回 null。
     */
    fun validate(
        tree: JsonObject,
        currentVersionCode: Int,
        fallbacks: GeometryFallbacks,
        assetKind: (String) -> AssetKind?,
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

        // ---- layoutId（B1 保留不应用） ----
        var layoutId: String? = null
        val layoutPrimitive = tree["layoutId"] as? JsonPrimitive
        if (layoutPrimitive != null) {
            val raw = layoutPrimitive.contentOrNull
            if (raw == null) {
                issues += SkinIssue("layoutId", SkinIssue.Kind.FIELD_FALLBACK, "layout_id_not_string")
            } else {
                layoutId = raw
                issues += SkinIssue("layoutId", SkinIssue.Kind.RETAINED_INACTIVE, "layout_not_applied_in_batch1")
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

        // ---- styles ----
        val styles = tree["styles"] as? JsonObject
        if (tree["styles"] != null && styles == null) {
            issues += SkinIssue("styles", SkinIssue.Kind.FIELD_FALLBACK, "styles_not_object")
        }
        val colors = mutableMapOf<StyleField, Int>()
        for (field in StyleField.entries) {
            val node = nodeAtPath(styles, field.path) ?: continue
            val raw = (node as? JsonPrimitive)?.contentOrNull
            val parsed = raw?.let { SkinJson.parseColor(it) }
            if (parsed == null) {
                issues += SkinIssue(
                    "styles.${field.path}",
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
                    "styles.surfaceStyle", SkinIssue.Kind.FIELD_FALLBACK, "invalid_surface_style",
                )
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
                    isOverridable = KeyIds::isOverridable,
                    isAnchor = KeyIds::isAnchor,
                )
                keyOverrides = parsed.overrides
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

        return Result.Valid(
            skin = ValidatedSkin(
                tree = tree,
                formatVersion = formatVersion,
                metaId = metaId,
                metaName = metaName,
                metaAuthor = metaAuthor,
                metaVersion = metaVersion,
                layoutId = layoutId,
                appearance = AppearanceSpec(
                    presence = presence,
                    effectiveGeometry = effective,
                    background = background,
                ),
                styles = StylesSpec(colors = colors, surfaceStyle = surfaceStyle),
                keyMappingsPresent = tree["keyMappings"] != null,
                keyMappingsCount = keyMappingsCount,
                keyOverrides = keyOverrides,
                keyRejections = keyRejections,
                sequenceBindingCount = sequenceBindingCount,
                audioRefs = audioRefs,
            ),
            issues = issues,
        )
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
        if (type != "asset" || assetId == null) {
            issues += SkinIssue(
                "appearance.background", SkinIssue.Kind.RESOURCE_FALLBACK, "background_ref_invalid",
            )
            return null
        }
        when (assetKind(assetId)) {
            AssetKind.IMAGE -> Unit
            AssetKind.AUDIO, AssetKind.OTHER -> {
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
        return BackgroundSpec(assetId = assetId, blurRadius = blur, overlayAlpha = overlay)
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
