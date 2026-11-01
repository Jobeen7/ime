package com.jobeen.ime.skin.image

/**
 * 对端 5 图的 Jime 等价形式一览（原图一律不进包；转换器按此表映射）：
 *
 * | 对端图 | Jime 等价 | 值 |
 * | float_back | ic_skin_float_back（圆角矩形＋投影） | 主体 #D0D3DA，圆角 16dp |
 * | toolbar_circle | ic_skin_toolbar_circle（常态白／按压灰） | 白 #FFFFFF，按压 #D0D4DB |
 * | cnen | TextIconDrawable.cnen()（黑字双行） | 中／EN，#000000 |
 * | encn | TextIconDrawable.encn()（绿字） | 英，#0FBC7A |
 * | wxicon | ic_skin_wxicon（原创手写体 p 矢量，工具栏菜单钮前景用） | 绿 #1BC886，线宽 14/200 |
 *
 * 颜色值均为浅色版实测（深色版待转换器取深色图样时再对）；XML 内的色值
 * 是此处常量的镜像（XML 资源无法引用 Kotlin 常量），改动时两处同步。
 *
 * 注意：等价物是组件，默认 UI 不改线（菜单钮仍为现有图标、浮层背景
 * 仍走皮肤）；转换器在转换皮肤时引用这些等价物，各槽位接线待
 * 转换器落地时定（工具栏钮背景槽、语言键图标槽等目前无等价物挂点）。
 */
object SkinImageEquivalents {
    /** float_back 主体灰（浅色实测）。 */
    val floatBackGray: Int = 0xFFD0D3DA.toInt()

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
