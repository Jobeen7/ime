package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.skin.layout.LayoutModel
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
    /**
     * 深色板合成方案（2026-10-27 第一组）：与 [colorScheme]
     * 同几何、同背景，只换颜色叶子；null = 旧单色板皮肤。
     * 渲染层禁止直接读 [colorScheme] 做模式判断，一律经
     * [effectiveColorScheme] 取板，否则深色模式下令牌样式
     * 仍会解析到浅色板。
     */
    val darkColorScheme: KeyboardColors.ColorScheme? = null,
    val geometryPresence: Set<GeometryField>,
    /**
     * 样式显式字段集（2026-10-29 外观轮：全局样式分层用）：
     * 校验期 styles 显式给出的有效颜色字段；全局层只覆盖不在
     * 集内的字段。深色板缺失时与浅色板同集（null＝同浅色板）。
     */
    val stylePresence: Set<StyleField> = emptySet(),
    val darkStylePresence: Set<StyleField>? = null,
    /** appearance 有效值（含回填结果）：底距等不在 ColorScheme 内的消费点读此表。 */
    val effectiveGeometry: Map<GeometryField, Float>,
    val background: BackgroundSnapshot?,
    val degradations: Set<Degradation>,
    val layoutId: String?,
    /** 键盘级几何（2026-10-27 新增分流）：顶层 geometry 有效值；消费点按 presence 逐项回退。 */
    val keyboardGeometry: KeyboardGeometrySpec = KeyboardGeometrySpec(),
    /** 字族（2026-10-27 新增分流）：规范化后的系统字族名；null = 默认字体。 */
    val fontFamily: String? = null,
    /** 分角色字体（2026-10-29 item-1）：皮肤内逐角色字族（仅有效项）。 */
    val roleFonts: Map<com.jobeen.ime.data.keyboard.theme.FontRole, String> = emptyMap(),
    /** 拟态双阴影（2026-10-29 item-1）：皮肤级阴影规格；缺省关闭。 */
    val keyShadow: KeyShadowSpec = KeyShadowSpec(),
    /** 按键动效（2026-10-29 item-1）：全部键动效规格；缺省关闭。 */
    val keyAnimation: KeyAnimationSpec = KeyAnimationSpec(),
    /** 空格专属动效（2026-10-29 item-1）：null = 空格键跟随 keyAnimation。 */
    val spaceAnimation: KeyAnimationSpec? = null,
    /** 气泡溢出检测（2026-10-28 截图 6 项）：false = 关闭自动调位；默认开。 */
    val bubbleOverflowCheck: Boolean = true,
    /** 滑动阈值（2026-10-28 截图 6 项）：dp；null = 未指定。 */
    val swipeThresholdDp: Float? = null,
    /** 候选字背景圆角（2026-10-28 截图 6 项）：dp；默认 6。 */
    val candidateRadiusDp: Float = 6f,
    /** 预测态关闭键独立样式（2026-10-28 截图 6 项）：null = 复用候选背景。 */
    val predictionCloseBg: Int? = null,
    val predictionCloseRadiusDp: Float? = null,
    /** 逐键覆写（B2）：键 ID → 强类型覆写；装配器在构建期消费（详设 §四）。 */
    val keyOverrides: Map<String, KeyOverride> = emptyMap(),
    /** 单键样式表（2026-10-27 并入项）：样式名 → 角色 → 取值字符串；装配器按快照方案解析。 */
    val keyStyles: Map<String, Map<KeyStyleRole, String>> = emptyMap(),
    /** G1 皮肤级分档字号（校验通过值；装配器逐键与逐键覆写合并）。 */
    val textSizes: Map<KeyTextRole, Float> = emptyMap(),
    /**
     * G1 已接纳单键图片资产：资产逻辑 id → 实体 sha256（内容寻址）。
     * 接纳口径 = 清单在册（构建器以 assetSha256 查无为拒）；类别与
     * 尺寸已由校验器清单口径守过，未过校验的引用到不了此处。
     * 渲染层按 id 经 SkinKeyImageStore 取位图，未命中回退颜色键面。
     */
    val imageAssets: Map<String, String> = emptyMap(),
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
    /**
     * 横屏专属布局（2026-10-27 第一组 + 矩阵扩展）：layoutIdLandscape
     * 引用解析得到的已解析布局，与竖屏绑定独立解析；null 时
     * 横屏取竖屏布局的横屏权重变体（baseLayoutLandscape），
     * 若竖屏布局也不存在则回退方案默认键盘，永不空白。
     * 无竖屏绑定时横屏绑定仍可激活内置布局的横屏位（主键盘
     * 位映射见 CustomLayoutRuntime.resolveSwitchTarget）。
     */
    val resolvedLayoutLandscape: ResolvedLayout? = null,
    /** 横屏绑定解析结论：NOT_SET = 未绑定。 */
    val landscapeLayoutResolution: LayoutResolution = LayoutResolution.NOT_SET,
    /**
     * 内置布局覆盖（2026-10-28）：内置 id → 该皮肤代次目录内
     * layouts/<内置id>.json 的校验后模型；无文件/无效 = 无条目，
     * 键盘构建期回退出厂布局。键 stableId 为出厂语义键 ID，
     * tap 为 null 的键沿用出厂行为（见 BuiltinLayoutApplier）。
     */
    val builtinLayouts: Map<String, LayoutModel> = emptyMap(),
    /**
     * 工具栏构成（2026-10-27 工具栏进皮肤）：快照只承载构成与资产
     * 引用（id + sha256 + 扩展名），位图不在快照内——渲染构造期
     * 由 SkinToolbarIconResolver 按引用解码，失败回退内置图标。
     * `buttons == null` = 皮肤未定义中央区（走 App 配置）。
     */
    val toolbar: SkinToolbarSnapshot = SkinToolbarSnapshot(),
    /**
     * 状态驱动通知（重点候选 8-1，接线轮 R1）：校验结果透传；
     * 运行态按此建锁定表，快照切换时同步重置。
     */
    val notifications: List<com.jobeen.ime.skin.notify.SkinNotification> = emptyList(),
    /**
     * 条件样式（重点候选 8-1，接线轮 R2）：校验结果透传；
     * 运行态按此建门控集与激活集。
     */
    val conditionStyles: List<com.jobeen.ime.skin.notify.ConditionStyle> = emptyList(),
    /**
     * 符号栏（重点候选 8-6，接线轮 R5）：校验结果透传；
     * null = 无自定义栏（走现有符号键盘）。
     */
    val symbolBar: com.jobeen.ime.skin.symbol.SymbolBarSpec? = null,
    /**
     * 覆盖层版本（2026-10-29 item-1 D1）：构建本快照时叠加的
     * 覆盖层文件版本；0 = 无覆盖层。进程内覆盖层变化后重建的
     * 快照版本号不同，调用方以此判过期（见
     * SkinSnapshotProvider.refreshIfOverlayChanged）。
     */
    val overlayVersion: Long = 0L,
) {
    /** 是否携带深色板（旧单色板皮肤为 false，恒取浅色板）。 */
    val hasDarkPalette: Boolean get() = darkColorScheme != null

    /**
     * 按模式取板（经四变体回退链调色轴决策，结果与旧三元表达式
     * 逐字节一致：深色且有深色板 → 深色板，其余 → 浅色板）。
     */
    fun effectiveColorScheme(isDark: Boolean): KeyboardColors.ColorScheme {
        val (palette, _) = VariantResolver.resolvePalette(isDark, darkColorScheme != null)
        return if (palette == VariantPalette.DARK) darkColorScheme!! else colorScheme
    }
}

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
    /**
     * 横屏基础键结构（2026-10-27 并入项）：与 baseLayout 同键
     * 同序，仅宽度按横屏权重算；运行时按方向二选一，旋转不
     * 要求重建快照（快照构建期两份一次建成）。
     */
    val baseLayoutLandscape: List<List<KeyDef>> = baseLayout,
    /**
     * 布局专用高度百分比（2026-10-28）：文件顶层 heightPercent
     * 的校验后值；null = 未指定。键盘显示该布局时覆盖皮肤级
     * geometry 高度（见 KeyboardWindowView.contentHeight）。
     */
    val heightPercent: Double? = null,
)

/** 快照中的背景状态：预处理产物引用 + 生效参数（已含降级处理）。 */
data class BackgroundSnapshot(
    val assetId: String,
    /** 资产实体 sha256（内容寻址，定稿七.5）。 */
    val assetSha256: String,
    /** 生效模糊半径（降级关模糊时为 0，皮肤文件原值不动）。 */
    val blurRadius: Float,
    val overlayAlpha: Float,
    /** 预处理产物键（详设 §2.6）：assetHash + 目标尺寸 + blur + 调节后缀。 */
    val preparedKey: String,
    /**
     * 产物目标尺寸（2026-10-28 修复轮：运行期自愈重建用）。
     * 与 preparedKey 内嵌尺寸同一值；快照构建期按当时档位/尺寸算好，
     * 运行期重建直接复用，不再重算——避免档位/尺寸漂移导致键分裂。
     */
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    /**
     * 生效背景图调节（参考项）：已含降级处理（降级关模糊
     * 时其 blurRadiusDp 被剥除，产物键与产物本体一致）；null = 不调节。
     */
    val imageAdjust: ImageAdjust? = null,
    /**
     * 文件原值（2026-10-29 外观轮：全局模糊重建用，不受全局
     * 覆盖与降级改写影响；运行期重算生效模糊的输入）。
     */
    val specBlurRadius: Float = 0f,
    val specImageAdjust: ImageAdjust? = null,
    /** 构建期内存降级是否关模糊（同上，重算时降级优先于全局强制开）。 */
    val blurDisabled: Boolean = false,
    /**
     * 主题视频背景（2026-10-29 外观轮）：静态图资产的扩展名
     * （小写，无点；GIF 动图判定用）与视频资产逻辑 id／实体 sha。
     * 视频缺失（清单查无 sha）时 videoSha256 为 null＝无视频，
     * 绝不以坏引用占位；imageAdjust 只作用静态图，不作用视频帧。
     */
    val backgroundAssetExt: String = "",
    val videoAssetId: String? = null,
    val videoSha256: String? = null,
)
