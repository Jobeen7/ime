package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
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
    /** 逐键覆写（B2）：键 ID → 强类型覆写；装配器在构建期消费（详设 §四）。 */
    val keyOverrides: Map<String, KeyOverride> = emptyMap(),
    /** 音效接纳结果（B2）：播放层只读接纳表（详设 §7）。 */
    val soundBindings: SoundBindings = SoundBindings.EMPTY,
    /**
     * 已解析布局（B3 §3.4）：快照构建期由 layoutId 引用解析 + 校验
     * + 构建得到的基础 KeyDef 结构（未过 keyMappings 覆写——覆写
     * 在键盘构建期由装配器施加，与出厂路径一致）。解析失败时为
     * null，结论看 [layoutResolution]。
     */
    val resolvedLayout: ResolvedLayout? = null,
    /** 布局解析结论（B3 §3.5 回退链）：失败按未设 layoutId 处理，快照照常成立。 */
    val layoutResolution: LayoutResolution = LayoutResolution.NOT_SET,
)

/** 布局引用解析结论（B3 详设 §3.4/§3.5）。 */
enum class LayoutResolution {
    /** 皮肤未设 layoutId。 */
    NOT_SET,

    /** 已解析并构建基础结构。 */
    RESOLVED,

    /** layoutId 指向不存在的布局（缺失/代次文件损坏）：按未设置处理。 */
    MISSING,

    /** 布局校验整布局无效：按未设置处理，问题清单已逐项记录。 */
    INVALID,
}

/** 已解析布局（随快照持有，当前皮肤 1 份，与位图上限同一纪律，B3 §3.6）。 */
data class ResolvedLayout(
    val layoutId: String,
    /** 入库代次（索引当前代次；新代次提交后下一次快照构建自动指向）。 */
    val generation: Int,
    val name: String,
    /** 基础键结构（构建完成、未过 keyMappings 覆写）。 */
    val baseLayout: List<List<KeyDef>>,
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
