package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/**
 * 图像描述机制（审计 D 项）：对端 fileImage / assetImage 的 Jime 模型。
 *
 * 本文件只做纯模型与纯解析（无 Android 依赖，可直接单测）：
 * - 精灵图（sprite sheet）：一张位图 + 一张子图名 → 矩形／9-patch
 *   内边距映射表。对端资源文件即此形（`<sheet>.yaml`：名 →
 *   `{rect: {x, y, width, height}, insets: {top, bottom, left, right}}`，
 *   单位皆源图像素）；Jime 皮肤 JSON 顶层 `sprites` 节点承载同表
 *   （sheet 资产逻辑 id → 子图名 → 帧）。
 * - 样式描述：`{buttonStyleType: "fileImage", normalImage: {file, image},
 *   highlightImage?, contentMode?, insets?}`；`file` = 精灵图资产
 *   逻辑 id，`image` = 子图名。`insets`（样式层）存在时优先于
 *   精灵表层帧 insets（实测：工具栏圆图的拉伸边 19 由样式层给，
 *   其精灵表无 insets；浮层图的 63 由精灵表给，样式层无 insets）。
 * - 9-patch 内边距语义：四边为不拉伸的边框宽（源图像素）；中间
 *   与四边中段拉伸，角部保持源尺寸。样式层 insets 与帧 insets
 *   皆按此解；单边超边长时解析期记 issue 并钳制到边长。
 * - contentMode（对端同名）：scaleToFill 拉伸铺满、scaleAspectFit
 *   等比适应（留空）、scaleAspectFill 等比铺满（裁剪）、center
 *   原尺寸居中；缺失默认 scaleToFill（背景图语义：无模式即铺满）。
 *   [ImageContentMode.scaleTypeName] 为对应的 Android ScaleType
 *   名（字符串，避免纯层引入 Android 类型；渲染层按名转 ScaleType）。
 * - assetImage（对端同名）：App 内置图（非包内资产），名由调用
 *   方提供的内置图解析回调映射；无映射时渲染回退（不画背景），
 *   解析层不判定名是否有效。
 * - 变体选择规则（渲染层执行，此处定口径）：按压态优先用
 *   highlight（子图／资产）；highlight 缺失或解析失败时回退
 *   normal；normal 缺失或失败时整描述失效（不画，不崩溃）。
 * - 子图查无的回退：帧查无 = 整张精灵图（rect = 全图，insets = 0），
 *   而非失效——与旧整图口径一致；样式层 insets 若存在仍生效。
 */

/** 精灵图子图矩形（源图像素；x/y 为左上角）。 */
data class SpriteRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** 9-patch 不拉伸边框（源图像素；left/right 钳制到宽，top/bottom 钳制到高）。 */
data class SpriteInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val isZero: Boolean get() = left == 0 && top == 0 && right == 0 && bottom == 0

    /** 按给定边长钳制各边（负值归 0，超边长钳到边长）。 */
    fun clampedTo(width: Int, height: Int): SpriteInsets = SpriteInsets(
        left = left.coerceIn(0, width.coerceAtLeast(0)),
        top = top.coerceIn(0, height.coerceAtLeast(0)),
        right = right.coerceIn(0, width.coerceAtLeast(0)),
        bottom = bottom.coerceIn(0, height.coerceAtLeast(0)),
    )

    companion object {
        val ZERO = SpriteInsets()
    }
}

/** 精灵图一帧：子图矩形 + 该帧的 9-patch 边框（缺失为 0）。 */
data class SpriteFrame(
    val rect: SpriteRect,
    val insets: SpriteInsets = SpriteInsets.ZERO,
)

/** 精灵图引用：sheet 资产逻辑 id + 子图名。 */
data class SkinImageRef(
    val file: String,
    val image: String,
)

/** 内容模式（对端同名）与对应的 Android ScaleType 名。 */
enum class ImageContentMode(val jsonName: String, val scaleTypeName: String) {
    SCALE_TO_FILL("scaleToFill", "FIT_XY"),
    SCALE_ASPECT_FIT("scaleAspectFit", "FIT_CENTER"),
    SCALE_ASPECT_FILL("scaleAspectFill", "CENTER_CROP"),
    CENTER("center", "CENTER"),
    ;

    companion object {
        fun byJsonName(name: String?): ImageContentMode? =
            entries.firstOrNull { it.jsonName == name }
    }
}

/** 图像描述：包内精灵图（fileImage）或 App 内置图（assetImage）。 */
sealed interface SkinImageDescriptor {
    /** 包内精灵图描述：常态子图必备，高亮子图可选，样式层 insets 可选。 */
    data class File(
        val normal: SkinImageRef,
        val highlight: SkinImageRef?,
        val contentMode: ImageContentMode,
        /** 样式层 9-patch 边框；null = 用帧 insets。 */
        val insets: SpriteInsets?,
    ) : SkinImageDescriptor

    /** App 内置图描述：名由渲染层回调解析，解析层不校验。 */
    data class Asset(val name: String) : SkinImageDescriptor

    /** 本描述引用的精灵图资产逻辑 id（去重保序；内置图为空表）。 */
    fun sheetFiles(): List<String> = when (this) {
        is File -> listOfNotNull(normal.file, highlight?.file).distinct()
        is Asset -> emptyList()
    }
}

/** 快照中的精灵图资产引用（内容寻址；与工具栏图标引用同口径）。 */
data class SkinImageSheetRef(
    val assetId: String,
    val sha256: String,
    /** 清单 path 扩展名（小写，无点）；空串 = 清单 path 无扩展名。 */
    val ext: String,
)

/**
 * 快照中的图像描述：解析形描述 + 各 sheet 的内容寻址引用。
 * sheet 缺失（清单查无）时构建期已处理：常态 sheet 缺失 → 整描述
 * 丢弃；高亮 sheet 缺失 → 描述的 highlight 置空（渲染回退常态）。
 */
data class SkinImageDescriptorSnapshot(
    val descriptor: SkinImageDescriptor,
    val sheets: Map<String, SkinImageSheetRef> = emptyMap(),
)

/** 解析一帧：rect 必备（非法则整帧丢弃）；insets 缺失为 0，超边钳制。 */
fun parseSpriteFrame(
    node: kotlinx.serialization.json.JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): SpriteFrame? {
    val obj = node as? JsonObject
    if (obj == null) {
        if (node != null) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "sprite_frame_not_object")
        }
        return null
    }
    val rectNode = obj["rect"] as? JsonObject
    if (rectNode == null) {
        issues += SkinIssue("$path.rect", SkinIssue.Kind.FIELD_FALLBACK, "sprite_rect_missing")
        return null
    }
    fun rectField(name: String): Int? {
        val raw = (rectNode[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (raw == null) {
            issues += SkinIssue("$path.rect.$name", SkinIssue.Kind.FIELD_FALLBACK, "sprite_value_invalid")
        }
        return raw
    }
    val x = rectField("x") ?: return null
    val y = rectField("y") ?: return null
    val width = rectField("width") ?: return null
    val height = rectField("height") ?: return null
    if (x < 0 || y < 0 || width <= 0 || height <= 0) {
        issues += SkinIssue("$path.rect", SkinIssue.Kind.FIELD_FALLBACK, "sprite_rect_invalid")
        return null
    }
    val insets = parseSpriteInsets(obj["insets"], "$path.insets", issues)
        ?.clampedTo(width, height)
        ?: SpriteInsets.ZERO
    return SpriteFrame(SpriteRect(x, y, width, height), insets)
}

/** 解析 9-patch 边框对象：每边为数字（像素，允许小数→四舍五入）；非法边记 issue 并按 0 计。 */
fun parseSpriteInsets(
    node: kotlinx.serialization.json.JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): SpriteInsets? {
    if (node == null) return null
    val obj = node as? JsonObject
    if (obj == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "sprite_insets_not_object")
        return null
    }
    fun side(name: String): Int {
        val raw = (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (obj.containsKey(name) && (raw == null || !raw.isFinite() || raw < 0)) {
            issues += SkinIssue("$path.$name", SkinIssue.Kind.FIELD_FALLBACK, "sprite_inset_invalid")
            return 0
        }
        return raw?.roundToInt() ?: 0
    }
    return SpriteInsets(
        left = side("left"),
        top = side("top"),
        right = side("right"),
        bottom = side("bottom"),
    )
}

/** 解析单张精灵图的映射表：子图名 → 帧；非法帧逐项丢弃并记 issue。 */
fun parseSpriteMap(
    node: kotlinx.serialization.json.JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): Map<String, SpriteFrame> {
    val obj = node as? JsonObject
    if (obj == null) {
        if (node != null) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "sprite_map_not_object")
        }
        return emptyMap()
    }
    val frames = LinkedHashMap<String, SpriteFrame>()
    for ((name, frameNode) in obj) {
        if (name.isEmpty()) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "sprite_name_empty")
            continue
        }
        parseSpriteFrame(frameNode, "$path.$name", issues)?.let { frames[name] = it }
    }
    return frames
}

/** 解析顶层 `sprites` 节点：sheet 资产逻辑 id → 子图映射表。 */
fun parseSpriteMaps(
    node: kotlinx.serialization.json.JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): Map<String, Map<String, SpriteFrame>> {
    val obj = node as? JsonObject
    if (obj == null) {
        if (node != null) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "sprite_maps_not_object")
        }
        return emptyMap()
    }
    val maps = LinkedHashMap<String, Map<String, SpriteFrame>>()
    for ((sheet, mapNode) in obj) {
        if (sheet.isEmpty()) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "sprite_sheet_empty")
            continue
        }
        maps[sheet] = parseSpriteMap(mapNode, "$path.$sheet", issues)
    }
    return maps
}

/** 解析精灵图引用 `{file, image}`：任一缺失/空白即整引用丢弃。 */
fun parseSkinImageRef(
    node: kotlinx.serialization.json.JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): SkinImageRef? {
    val obj = node as? JsonObject
    if (obj == null) {
        if (node != null) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "image_ref_not_object")
        }
        return null
    }
    val file = (obj["file"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    val image = (obj["image"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.takeIf { it.isNotBlank() }
    if (file == null || image == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "image_ref_invalid")
        return null
    }
    return SkinImageRef(file, image)
}

/**
 * 解析图像描述节点（对端 buttonStyleType 形）。
 * - fileImage：normalImage 必备；highlightImage 可选（非法则丢弃该
 *   变体并记 issue，不丢整描述）；contentMode 未知记 issue 并默认
 *   scaleToFill；insets 非法记 issue 并按缺失计（用帧 insets）。
 * - assetImage：名取 `name`，无则 `assetName`，再无则 `image`；
 *   皆缺失/空白则整描述丢弃。
 * - 其他 buttonStyleType / 非对象 / 缺 type：记 issue 并返回 null。
 */
fun parseImageDescriptor(
    node: kotlinx.serialization.json.JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): SkinImageDescriptor? {
    if (node == null) return null
    val obj = node as? JsonObject
    if (obj == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "image_descriptor_not_object")
        return null
    }
    val type = (obj["buttonStyleType"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return when (type) {
        "fileImage" -> {
            val normal = parseSkinImageRef(obj["normalImage"], "$path.normalImage", issues)
                ?: return null
            val highlight = if (obj.containsKey("highlightImage")) {
                parseSkinImageRef(obj["highlightImage"], "$path.highlightImage", issues)
            } else {
                null
            }
            val modeRaw = (obj["contentMode"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val mode = if (modeRaw == null) {
                ImageContentMode.SCALE_TO_FILL
            } else {
                ImageContentMode.byJsonName(modeRaw) ?: run {
                    issues += SkinIssue(
                        "$path.contentMode", SkinIssue.Kind.FIELD_FALLBACK, "content_mode_invalid",
                    )
                    ImageContentMode.SCALE_TO_FILL
                }
            }
            val insets = parseSpriteInsets(obj["insets"], "$path.insets", issues)
            SkinImageDescriptor.File(normal, highlight, mode, insets)
        }
        "assetImage" -> {
            val name = listOf("name", "assetName", "image").firstNotNullOfOrNull { key ->
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }
            if (name == null) {
                issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "asset_image_name_missing")
                null
            } else {
                SkinImageDescriptor.Asset(name)
            }
        }
        else -> {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "image_descriptor_type_invalid")
            null
        }
    }
}

/** 查帧：描述常态／高亮子图在映射表中的帧；查无返回 null（调用方按整图计）。 */
fun lookupSpriteFrame(
    descriptor: SkinImageDescriptor.File,
    spriteMaps: Map<String, Map<String, SpriteFrame>>,
    highlight: Boolean,
): SpriteFrame? {
    val ref = if (highlight) descriptor.highlight ?: descriptor.normal else descriptor.normal
    return spriteMaps[ref.file]?.get(ref.image)
}

/** 生效 9-patch 边框：样式层优先，次为帧，皆无为 0（按帧边长钳制）。 */
fun effectiveCapInsets(
    descriptor: SkinImageDescriptor.File,
    frame: SpriteFrame?,
): SpriteInsets {
    if (descriptor.insets != null) {
        val size = frame?.rect
        return if (size != null) {
            descriptor.insets.clampedTo(size.width, size.height)
        } else {
            descriptor.insets
        }
    }
    return frame?.insets ?: SpriteInsets.ZERO
}

/**
 * 帧裁剪矩形（按 sheet 尺寸钳制）：帧与 sheet 无交集返回 null
 * （调用方按整描述失效计）；否则返回交集矩形。
 */
fun SpriteFrame.cropRect(sheetWidth: Int, sheetHeight: Int): SpriteRect? {
    if (sheetWidth <= 0 || sheetHeight <= 0) return null
    val left = rect.x.coerceIn(0, sheetWidth)
    val top = rect.y.coerceIn(0, sheetHeight)
    val right = (rect.x + rect.width).coerceIn(0, sheetWidth)
    val bottom = (rect.y + rect.height).coerceIn(0, sheetHeight)
    if (right <= left || bottom <= top) return null
    return SpriteRect(left, top, right - left, bottom - top)
}

/** 查帧结果的裁剪口径：帧缺失 = 整图；帧存在但与 sheet 无交集 = null（脏数据，整描述失效）。 */
fun cropOrFull(frame: SpriteFrame?, sheetWidth: Int, sheetHeight: Int): SpriteRect? {
    if (frame == null) {
        if (sheetWidth <= 0 || sheetHeight <= 0) return null
        return SpriteRect(0, 0, sheetWidth, sheetHeight)
    }
    return frame.cropRect(sheetWidth, sheetHeight)
}

/**
 * 9-patch 切片几何（纯函数；渲染层按此切图绘制）。
 * 源空间为 sheet 像素，目标空间为绘制 bounds 像素；cap 边框在
 * 目标空间按源像素等量取（调用方在需要时先按 density 缩放）。
 * 边框为 0 的行／列不产生切片；拉伸区不存在（边框盖满）时
 * 返回空表（调用方回退整图 contentMode 绘制）。
 */
data class NinePatchSlice(
    val srcLeft: Int,
    val srcTop: Int,
    val srcRight: Int,
    val srcBottom: Int,
    val dstLeft: Float,
    val dstTop: Float,
    val dstRight: Float,
    val dstBottom: Float,
)

fun ninePatchSlices(
    sheetWidth: Int,
    sheetHeight: Int,
    frame: SpriteRect,
    insets: SpriteInsets,
    dstWidth: Float,
    dstHeight: Float,
): List<NinePatchSlice> {
    if (sheetWidth <= 0 || sheetHeight <= 0 || dstWidth <= 0f || dstHeight <= 0f) {
        return emptyList()
    }
    val caps = insets.clampedTo(frame.width, frame.height)
    val stretchW = frame.width - caps.left - caps.right
    val stretchH = frame.height - caps.top - caps.bottom
    if (stretchW <= 0 && stretchH <= 0) return emptyList()
    // 源切分点（帧内坐标 → sheet 绝对坐标）。
    val srcX = listOf(0, caps.left, caps.left + stretchW, frame.width)
    val srcY = listOf(0, caps.top, caps.top + stretchH, frame.height)
    // 目标切分点：边框段保持源像素宽，拉伸段吃剩余目标宽。
    fun dstCuts(total: Float, capA: Int, stretch: Int, capB: Int): List<Float> {
        val a = capA.toFloat()
        val b = capB.toFloat()
        return if (stretch > 0) {
            listOf(0f, a, (total - b).coerceAtLeast(a), total)
        } else {
            // 无拉伸段：两边框按比例压入目标宽（不溢出）。
            val sum = (capA + capB).toFloat()
            if (sum <= 0f) {
                listOf(0f, 0f, total, total)
            } else {
                val mid = total * capA / sum
                listOf(0f, mid, mid, total)
            }
        }
    }
    val dstX = dstCuts(dstWidth, caps.left, stretchW, caps.right)
    val dstY = dstCuts(dstHeight, caps.top, stretchH, caps.bottom)
    val slices = ArrayList<NinePatchSlice>()
    for (row in 0 until 3) {
        for (col in 0 until 3) {
            val sLeft = frame.x + srcX[col]
            val sTop = frame.y + srcY[row]
            val sRight = frame.x + srcX[col + 1]
            val sBottom = frame.y + srcY[row + 1]
            val dLeft = dstX[col]
            val dTop = dstY[row]
            val dRight = dstX[col + 1]
            val dBottom = dstY[row + 1]
            if (sRight <= sLeft || sBottom <= sTop) continue
            if (dRight <= dLeft || dBottom <= dTop) continue
            slices += NinePatchSlice(sLeft, sTop, sRight, sBottom, dLeft, dTop, dRight, dBottom)
        }
    }
    return slices
}

/** contentMode 绘制矩形（纯函数；渲染层按此在 bounds 内放位图）。 */
data class ContentModeRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** 按 contentMode 算位图在 bounds 内的目标矩形（center 不缩放）。 */
fun contentModeDstRect(
    mode: ImageContentMode,
    bitmapWidth: Int,
    bitmapHeight: Int,
    boundsWidth: Float,
    boundsHeight: Float,
): ContentModeRect {
    if (bitmapWidth <= 0 || bitmapHeight <= 0 || boundsWidth <= 0f || boundsHeight <= 0f) {
        return ContentModeRect(0f, 0f, boundsWidth.coerceAtLeast(0f), boundsHeight.coerceAtLeast(0f))
    }
    return when (mode) {
        ImageContentMode.SCALE_TO_FILL -> ContentModeRect(0f, 0f, boundsWidth, boundsHeight)
        ImageContentMode.CENTER -> {
            val w = bitmapWidth.toFloat()
            val h = bitmapHeight.toFloat()
            ContentModeRect(
                (boundsWidth - w) / 2f, (boundsHeight - h) / 2f,
                (boundsWidth + w) / 2f, (boundsHeight + h) / 2f,
            )
        }
        ImageContentMode.SCALE_ASPECT_FIT -> {
            val scale = minOf(boundsWidth / bitmapWidth, boundsHeight / bitmapHeight)
            val w = bitmapWidth * scale
            val h = bitmapHeight * scale
            ContentModeRect(
                (boundsWidth - w) / 2f, (boundsHeight - h) / 2f,
                (boundsWidth + w) / 2f, (boundsHeight + h) / 2f,
            )
        }
        ImageContentMode.SCALE_ASPECT_FILL -> {
            val scale = maxOf(boundsWidth / bitmapWidth, boundsHeight / bitmapHeight)
            val w = bitmapWidth * scale
            val h = bitmapHeight * scale
            ContentModeRect(
                (boundsWidth - w) / 2f, (boundsHeight - h) / 2f,
                (boundsWidth + w) / 2f, (boundsHeight + h) / 2f,
            )
        }
    }
}
