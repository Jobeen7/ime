package com.jobeen.ime.skin.image

import com.jobeen.ime.skin.model.ImageContentMode
import com.jobeen.ime.skin.model.SkinImageDescriptor
import com.jobeen.ime.skin.model.SkinImageRef
import com.jobeen.ime.skin.model.SpriteFrame
import com.jobeen.ime.skin.model.SpriteInsets
import com.jobeen.ime.skin.model.SpriteRect

/**
 * 对端 5 图的 Jime 等价形式一览（原图一律不进包；转换器按此表映射）：
 *
 * | 对端图 | Jime 等价 | 值 |
 * | float_back | FloatBackDrawable（描述驱动的圆角矩形＋投影） | 见下 |
 * | toolbar_circle | ic_skin_toolbar_circle（常态白／按压灰） | 白 #FFFFFF，按压 #D0D4DB |
 * | cnen | TextIconDrawable.cnen()（黑字双行） | 中／EN，#000000 |
 * | encn | TextIconDrawable.encn()（绿字） | 英，#0FBC7A |
 * | wxicon | ic_skin_wxicon（原创手写体 p 矢量，工具栏菜单钮前景用） | 绿 #1BC886，线宽 14/200 |
 *
 * float_back（审计 D 项 + E4：固定 16dp 近似已废止，改由描述驱动）：
 * - 精灵描述：file float_back／子图 IMG1（常态与高亮同帧，无按压变体），
 *   帧矩形 130×130、帧 insets 63（四边皆 63，拉伸段仅中间 4px）；
 * - 主体色：浅色 #D0D3DA、深色 #474747（皆为参考图实测纯色：浅色图
 *   最频色、深色图最频色）；
 * - 圆角：不再是固定 dp，取自原图实测圆弧——形体跨度 80px（25..104）、
 *   圆弧半径约 25px，即圆角 = 0.3125 × 绘制形 min 边（[floatBackRadiusPx]，
 *   上限 min 边一半）；投影沿用上一轮 XML 的几何（形体左右各缩 2dp、
 *   底部缩 4dp，投影层露边），颜色 #26000000。
 * XML 内的色值（toolbar_circle、wxicon）是此处常量的镜像（XML
 * 资源无法引用 Kotlin 常量），改动时两处同步；float_back 已无 XML，
 * 由 [FloatBackDrawable] 按本规格绘制。
 *
 * 注意：等价物是组件，默认 UI 不改线（菜单钮仍为现有图标、浮层背景
 * 仍走皮肤）；转换器在转换皮肤时引用这些等价物，各槽位接线待
 * 转换器落地时定（工具栏钮背景槽、语言键图标槽等目前无等价物挂点）。
 */
object SkinImageEquivalents {
    /** float_back 主体灰（浅色实测）。 */
    val floatBackGray: Int = 0xFFD0D3DA.toInt()

    /** float_back 主体灰（深色实测）。 */
    val floatBackDarkGray: Int = 0xFF474747.toInt()

    /** float_back 投影色（上一轮 XML 几何沿用）。 */
    val floatBackShadow: Int = 0x26000000.toInt()

    /** float_back 精灵描述（常态与高亮同帧；样式层 insets 缺失→用帧 insets）。 */
    val floatBackDescriptor: SkinImageDescriptor.File = SkinImageDescriptor.File(
        normal = SkinImageRef("float_back", "IMG1"),
        highlight = SkinImageRef("float_back", "IMG1"),
        contentMode = ImageContentMode.SCALE_TO_FILL,
        insets = null,
    )

    /** float_back 精灵帧（130×130 全图，9-patch 边框 63）。 */
    val floatBackFrame: SpriteFrame = SpriteFrame(
        rect = SpriteRect(0, 0, 130, 130),
        insets = SpriteInsets(left = 63, top = 63, right = 63, bottom = 63),
    )

    /**
     * float_back 圆角系数：实测圆弧半径 25px / 形体跨度 80px。
     * 固定 16dp 近似（上一轮）已废止：圆角现随绘制形 min 边缩放。
     */
    const val FLOAT_BACK_ARC_FRACTION: Float = 0.3125f

    /** float_back 圆角（px）：系数 × 形体 min 边，上限 min 边一半（不超椭圆）。 */
    fun floatBackRadiusPx(minSidePx: Float): Float {
        if (minSidePx <= 0f) return 0f
        return (minSidePx * FLOAT_BACK_ARC_FRACTION).coerceAtMost(minSidePx / 2f)
    }

    /** toolbar_circle 常态白（IMG1 实测）。 */
    val toolbarCircleNormal: Int = 0xFFFFFFFF.toInt()

    /** toolbar_circle 高亮灰（IMG2 实测）。 */
    val toolbarCircleHighlight: Int = 0xFFD0D4DB.toInt()

    /** cnen 字色（黑字形层实测）。 */
    val cnenText: Int = 0xFF000000.toInt()

    /** encn 字色（绿字实测）。 */
    val encnText: Int = 0xFF0FBC7A.toInt()

    /** wxicon 笔画绿（主色实测）。 */
    val wxiconGreen: Int = 0xFF1BC886.toInt()
}
