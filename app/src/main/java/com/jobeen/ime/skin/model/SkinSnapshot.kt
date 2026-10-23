package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef

/**
 * 不可变运行快照（定稿一.4：渲染只读快照，热路径不做解析/解码）。
 *
 * 由 [SkinSnapshotBuilder] 在冷路径（导入/应用/启动加载）构建；
 * 位图本体不入快照——快照只记预处理产物引用，位图由 runtime 层
 * 按「当前皮肤 1 份 + 编辑器预览 1 份」上限持有（定稿十一.3）。
 */
data class SkinSnapshot(
    val ref: SkinGenRef,
    val name: String,
    /**
     * 生效配色：styles 已合成到默认方案之上；几何字段在 presence 内
     * 为皮肤文件值、其余为默认方案值——resolve() 对非 presence 字段
     * 再以全局偏好覆盖（定稿三.4.2：皮肤值存在时完全不读偏好）。
     */
    val colorScheme: KeyboardColors.ColorScheme,
    val geometryPresence: Set<GeometryField>,
    /** appearance 有效值（含回填结果）：底距等不在 ColorScheme 内的消费点读此表。 */
    val effectiveGeometry: Map<GeometryField, Float>,
    val background: BackgroundSnapshot?,
    val degradations: Set<Degradation>,
    val layoutId: String?,
)

/** 快照中的背景状态：预处理产物引用 + 生效参数（已含降级处理）。 */
data class BackgroundSnapshot(
    val assetId: String,
    /** 资产实体 sha256（内容寻址，定稿七.5）。 */
    val assetSha256: String,
    /** 生效模糊半径（降级关模糊时为 0，皮肤文件原值不动）。 */
    val blurRadius: Float,
    val overlayAlpha: Float,
    /** 预处理产物键（详设 §2.6）：assetHash + 目标尺寸 + blur。 */
    val preparedKey: String,
)
