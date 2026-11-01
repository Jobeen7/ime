package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 按键动效定义（审计 H 项）：对端四类动效的 Jime 模型。
 *
 * - scale：按压缩放（对端唯一在参考皮肤中实际出现的类型）；
 * - cartoon：帧序列（图片资产 id 列表 + 帧率 + 目标缩放 + 层级）；
 * - physics：粒子（图片/随机图 + 起止位置 + 随机偏移 + 透明/
 *   旋转开关 + 角度）；位置单位见 [KeyAnimationPositionUnit]；
 * - transform：变换（目标层/触发/时长/延迟/缓动/重复/自动回弹/
 *   完成恢复/按住保持/抬起时长/锚点/位置单位/脱离 + Jime 终态）。
 *
 * 终态说明：审计列出的 transform 字段未含终态属性（动画到
 * 哪里）；Jime 终态字段（endScale / endTranslation / endAlpha /
 * endRotationDeg）为本模型补齐的必填语义——无终态的 transform
 * 只记时序、不产生可见变化，渲染层按终态插值。
 * 回退口径：scale/cartoon/physics 缺关键字段（缩放值/图片）
 * 时整条丢弃；transform 其余字段非法时逐项回退默认值。
 * 纯解析，可直接单测；渲染见 KeyView（仅新字段触发时生效）。
 */
sealed interface KeyAnimationDef {

    /** 按压缩放：压下缩、抬起回弹（autoReverse=false 时抬起保持缩放）。 */
    data class Scale(
        val scale: Float,
        val pressDurationMs: Int,
        val releaseDurationMs: Int,
        val autoReverse: Boolean,
    ) : KeyAnimationDef

    /** 帧序列：按压时循环播放图片帧，抬起停止并隐藏。 */
    data class Cartoon(
        /** 图片资产逻辑 id（装配期过滤为已接纳资产）。 */
        val images: List<String>,
        val fps: Int,
        val targetScale: Float?,
        val zPosition: Float?,
    ) : KeyAnimationDef

    /** 粒子：按压时从起点向终点抛图片（随机图时每粒随机取图）。 */
    data class Physics(
        val images: List<String>,
        val randomImage: Boolean,
        val startX: Float?,
        val startY: Float?,
        val endX: Float?,
        val endY: Float?,
        val randomOffsetX: Float?,
        val randomOffsetY: Float?,
        val useOpacity: Boolean,
        val useRotation: Boolean,
        val startAngleDeg: Float?,
        val endAngleDeg: Float?,
        val durationMs: Int,
        val positionUnit: KeyAnimationPositionUnit,
    ) : KeyAnimationDef

    /** 变换：触发时对键面做终态插值动画。 */
    data class Transform(
        /**
         * 目标（工具项 14 渲染口径，见 resolveTransformTarget）：
         * null/空白 = 整键；face = 常态图片面；pressedFace = 按压
         * 图片面；label = 主标签；未知串回落整键并记一次 WARN。
         * 前景层无名称身份，不在本词汇内。
         */
        val target: String?,
        val trigger: KeyAnimationTrigger,
        val durationMs: Int,
        val delayMs: Int,
        /** 三次贝塞尔控制点 [x1, y1, x2, y2]；null = 用 easing。 */
        val bezier: List<Float>?,
        val easing: KeyAnimationEasing,
        val repeatCount: Int,
        val autoReverse: Boolean,
        val restoreOnFinish: Boolean,
        val holdUntilRelease: Boolean,
        val releaseDurationMs: Int,
        val anchorX: Float?,
        val anchorY: Float?,
        val positionUnit: KeyAnimationPositionUnit,
        val detached: Boolean,
        /** 终态（null = 该属性不变）。 */
        val endScale: Float?,
        val endTranslationX: Float?,
        val endTranslationY: Float?,
        val endAlpha: Float?,
        val endRotationDeg: Float?,
    ) : KeyAnimationDef
}

enum class KeyAnimationTrigger {
    PRESS,
    RELEASE,
}

enum class KeyAnimationPositionUnit {
    DP,
    FRACTION,
}

object KeyAnimationDefParser {

    /** 解析动效表（名 → 定义）：非对象/未知类型/缺关键字段的项整项丢弃并记警告。 */
    fun parseTable(
        node: JsonElement?,
    ): Pair<Map<String, KeyAnimationDef>, List<String>> {
        if (node == null) return emptyMap<String, KeyAnimationDef>() to emptyList<String>()
        val obj = node as? JsonObject
            ?: return emptyMap<String, KeyAnimationDef>() to listOf("key_animations_not_object")
        val table = LinkedHashMap<String, KeyAnimationDef>()
        val warnings = mutableListOf<String>()
        for ((name, defNode) in obj) {
            val (def, defWarnings) = parseOne(defNode)
            warnings += defWarnings.map { "$name:$it" }
            if (def != null) table[name] = def
        }
        return table to warnings
    }

    /** 解析单条动效：animationType 缺失/未知 → null + 警告。 */
    fun parseOne(node: JsonElement): Pair<KeyAnimationDef?, List<String>> {
        val obj = node as? JsonObject
            ?: return null to listOf("key_animation_not_object")
        val type = (obj["animationType"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.lowercase()
        return when (type) {
            "scale" -> parseScale(obj)
            "cartoon" -> parseCartoon(obj)
            "physics" -> parsePhysics(obj)
            "transform" -> parseTransform(obj)
            else -> null to listOf("key_animation_type_invalid")
        }
    }

    private fun number(obj: JsonObject, field: String): Float? {
        val primitive = obj[field] as? JsonPrimitive
        if (primitive != null && !primitive.isString) {
            return primitive.content.toFloatOrNull()?.takeIf { it.isFinite() }
        }
        return null
    }

    private fun intField(
        obj: JsonObject,
        field: String,
        min: Int,
        max: Int,
        warnings: MutableList<String>,
    ): Int? {
        val value = number(obj, field)?.toInt()
        if (value == null || value < min || value > max) {
            warnings += "key_animation_${field}_invalid"
            return null
        }
        return value
    }

    private fun floatField(
        obj: JsonObject,
        field: String,
        min: Float,
        max: Float,
        warnings: MutableList<String>,
    ): Float? {
        val value = number(obj, field)
        if (value == null || value < min || value > max) {
            warnings += "key_animation_${field}_invalid"
            return null
        }
        return value
    }

    private fun boolField(
        obj: JsonObject,
        field: String,
        default: Boolean,
        warnings: MutableList<String>,
    ): Boolean {
        val node = obj[field] ?: return default
        val primitive = node as? JsonPrimitive
        if (primitive != null && !primitive.isString) {
            try {
                return primitive.content.toBooleanStrict()
            } catch (e: IllegalArgumentException) {
                warnings += "key_animation_${field}_invalid"
                return default
            }
        }
        warnings += "key_animation_${field}_invalid"
        return default
    }

    private fun stringList(obj: JsonObject, field: String): List<String> {
        val array = obj[field] as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun point(obj: JsonObject, field: String): Pair<Float, Float>? {
        val pointObj = obj[field] as? JsonObject ?: return null
        val x = number(pointObj, "x")
        val y = number(pointObj, "y")
        if (x == null || y == null) return null
        return x to y
    }

    private fun positionUnit(obj: JsonObject): KeyAnimationPositionUnit {
        val raw = (obj["positionUnit"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.lowercase()
        return when (raw) {
            "fraction", "relative" -> KeyAnimationPositionUnit.FRACTION
            else -> KeyAnimationPositionUnit.DP
        }
    }

    private fun easingOf(obj: JsonObject): KeyAnimationEasing {
        val raw = (obj["easing"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.lowercase()
        return when (raw) {
            "linear" -> KeyAnimationEasing.LINEAR
            "easeinout", "ease_in_out" -> KeyAnimationEasing.EASE_IN_OUT
            "overshoot" -> KeyAnimationEasing.OVERSHOOT
            else -> KeyAnimationEasing.EASE_OUT
        }
    }

    private fun parseScale(
        obj: JsonObject,
    ): Pair<KeyAnimationDef?, List<String>> {
        val warnings = mutableListOf<String>()
        val scale = floatField(obj, "scale", 0.80f, 1f, warnings)
            ?: return null to warnings
        val press = intField(obj, "pressDuration", 50, 300, warnings) ?: 90
        val release = intField(obj, "releaseDuration", 50, 300, warnings) ?: 90
        val autoReverse = boolField(obj, "isAutoReverse", true, warnings)
        return KeyAnimationDef.Scale(scale, press, release, autoReverse) to warnings
    }

    private fun parseCartoon(
        obj: JsonObject,
    ): Pair<KeyAnimationDef?, List<String>> {
        val warnings = mutableListOf<String>()
        val images = stringList(obj, "images")
        if (images.isEmpty()) {
            return null to (warnings + "key_animation_images_invalid")
        }
        val fps = intField(obj, "fps", 1, 60, warnings) ?: 30
        val targetScale = floatField(obj, "targetScale", 0.1f, 3f, warnings)
        val zPosition = number(obj, "zPosition")
        return KeyAnimationDef.Cartoon(images, fps, targetScale, zPosition) to warnings
    }

    private fun parsePhysics(
        obj: JsonObject,
    ): Pair<KeyAnimationDef?, List<String>> {
        val warnings = mutableListOf<String>()
        val images = stringList(obj, "images")
        if (images.isEmpty()) {
            return null to (warnings + "key_animation_images_invalid")
        }
        val start = point(obj, "startPosition")
        val end = point(obj, "endPosition")
        val randomOffset = point(obj, "randomOffset")
        val duration = intField(obj, "durationMs", 50, 3000, warnings) ?: 400
        return KeyAnimationDef.Physics(
            images = images,
            randomImage = boolField(obj, "randomImage", false, warnings),
            startX = start?.first,
            startY = start?.second,
            endX = end?.first,
            endY = end?.second,
            randomOffsetX = randomOffset?.first,
            randomOffsetY = randomOffset?.second,
            useOpacity = boolField(obj, "useOpacity", true, warnings),
            useRotation = boolField(obj, "useRotation", false, warnings),
            startAngleDeg = number(obj, "startAngle"),
            endAngleDeg = number(obj, "endAngle"),
            durationMs = duration,
            positionUnit = positionUnit(obj),
        ) to warnings
    }

    private fun parseTransform(
        obj: JsonObject,
    ): Pair<KeyAnimationDef?, List<String>> {
        val warnings = mutableListOf<String>()
        val target = (obj["target"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val triggerRaw = (obj["trigger"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.lowercase()
        val trigger = when (triggerRaw) {
            "release" -> KeyAnimationTrigger.RELEASE
            "press", null -> KeyAnimationTrigger.PRESS
            else -> {
                warnings += "key_animation_trigger_invalid"
                KeyAnimationTrigger.PRESS
            }
        }
        val duration = intField(obj, "durationMs", 50, 3000, warnings) ?: 300
        val delay = intField(obj, "delayMs", 0, 5000, warnings) ?: 0
        val bezier = (obj["timing"] as? JsonArray)?.let { array ->
            val values = array.mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { !it.isString }
                    ?.content?.toFloatOrNull()?.takeIf { it.isFinite() }
            }
            if (values.size == 4 && values.all { it in 0f..1f }) {
                values
            } else {
                warnings += "key_animation_timing_invalid"
                null
            }
        }
        val repeatCount = intField(obj, "repeatCount", 0, 100, warnings) ?: 0
        val autoReverse = boolField(obj, "autoreverses", false, warnings)
        val restoreOnFinish = boolField(obj, "restoreOnFinish", true, warnings)
        val holdUntilRelease = boolField(obj, "holdUntilRelease", false, warnings)
        val releaseDuration = intField(obj, "releaseDuration", 50, 3000, warnings)
            ?: 300
        val anchor = point(obj, "anchorPoint")
        val translation = point(obj, "endTranslation")
        return KeyAnimationDef.Transform(
            target = target,
            trigger = trigger,
            durationMs = duration,
            delayMs = delay,
            bezier = bezier,
            easing = easingOf(obj),
            repeatCount = repeatCount,
            autoReverse = autoReverse,
            restoreOnFinish = restoreOnFinish,
            holdUntilRelease = holdUntilRelease,
            releaseDurationMs = releaseDuration,
            anchorX = anchor?.first,
            anchorY = anchor?.second,
            positionUnit = positionUnit(obj),
            detached = boolField(obj, "detached", false, warnings),
            endScale = floatField(obj, "endScale", 0.1f, 3f, warnings),
            endTranslationX = translation?.first,
            endTranslationY = translation?.second,
            endAlpha = floatField(obj, "endAlpha", 0f, 1f, warnings),
            endRotationDeg = number(obj, "endRotationDeg"),
        ) to warnings
    }
}
