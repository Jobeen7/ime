package com.jobeen.ime.input.keyboard.key

/**
 * 变换目标（工具项 14）：transform 作用的键内视图。
 *
 * - [WHOLE_KEY]：整键（appearanceView；目标缺省时的旧口径）。
 * - [FACE]：常态图片键面（无图片面时回落整键）。
 * - [PRESSED_FACE]：按压态图片键面（无此层时回落整键）。
 * - [LABEL]：主标签文本视图（无标签视图的键回落整键）。
 *
 * 前景层（attachForegroundLayers 建的匿名文本／图片层）无名
 * 称身份，无法按名寻址，故不在本词汇内；其逐层寻址须先给
 * 层模型加名（另案），未知目标一律回落整键。
 */
enum class TransformTarget {
    WHOLE_KEY,
    FACE,
    PRESSED_FACE,
    LABEL,
}

/**
 * 变换目标解析（纯函数，工具项 14）：目标串 → 目标；
 * null/空白 → 整键；未知串 → null（调用方回落整键并记一次 WARN）。
 * 匹配大小写敏感（与样式名口径一致）。
 */
fun resolveTransformTarget(target: String?): TransformTarget? = when (target?.takeIf { it.isNotBlank() }) {
    null -> TransformTarget.WHOLE_KEY
    "face" -> TransformTarget.FACE
    "pressedFace" -> TransformTarget.PRESSED_FACE
    "label" -> TransformTarget.LABEL
    else -> null
}
