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
    /** 键盘背景渐变（S8）：纯渐变背景时取此值（无位图背景时生效）。 */
    val backgroundGradient: KeyGradient? = null,
    val degradations: Set<Degradation>,
    val layoutId: String?,
    /** 键盘级几何（2026-10-27 新增分流）：顶层 geometry 有效值；消费点按 presence 逐项回退。 */
    val keyboardGeometry: KeyboardGeometrySpec = KeyboardGeometrySpec(),
    /** 字族（2026-10-27 新增分流）：规范化后的系统字族名；null = 默认字体。 */
    val fontFamily: String? = null,
    /** 分角色字体（2026-10-29 item-1）：皮肤内逐角色字族（仅有效项）。 */
    val roleFonts: Map<com.jobeen.ime.data.keyboard.theme.FontRole, String> = emptyMap(),
    /** 字体优先级表（审计 C 项）：校验结果直传（首项即 fontFamily）。 */
    val fontFamilies: List<String> = emptyList(),
    /** 分角色字体优先级表（审计 C 项）：校验结果直传。 */
    val roleFontFamilies: Map<com.jobeen.ime.data.keyboard.theme.FontRole, List<String>> =
        emptyMap(),
    /** 皮肤内嵌字体（审计 C 项）：校验结果直传（名 → 资产逻辑 id）。 */
    val skinFontFaces: List<SkinFontFace> = emptyList(),
    /**
     * 已接纳内嵌字体资产：资产逻辑 id → 实体 sha256（内容寻址，
     * 与 imageAssets 同款口径）。接纳 = 校验期声明且清单在册；
     * 查无者不入表，渲染回落下一级字体。
     */
    val skinFontAssets: Map<String, String> = emptyMap(),
    /** 按码位区间指派字体（审计 C 项）：校验结果直传。 */
    val fontRanges: List<SkinFontRange> = emptyList(),
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
    /** 样式几何表（审计 A 项）：样式名 → 非颜色几何面；装配器按样式名取用。 */
    val keyGeometries: Map<String, KeyStyleGeometry> = emptyMap(),
    /** 动效表（审计 H 项）：动效名 → 动效定义；装配器按样式动效名取用。 */
    val keyAnimations: Map<String, KeyAnimationDef> = emptyMap(),
    /** 长按符号样式表（现行 form）：样式名 → 长按符号样式；面板挂载时按键名取用。 */
    val hintSymbolsStyles: Map<String, HintSymbolsStyle> = emptyMap(),
    /** 长按符号网格样式表（网格版）：样式名 → 网格样式；非空时优先于旧版单行。 */
    val hintSymbolsGridStyles: Map<String, HintSymbolsGridStyle> = emptyMap(),
    /** 长按符号全节点注册表（见 ValidatedSkin 同名字段）；面板挂载时按名取用。 */
    val hintSymbolNodes: Map<String, HintSymbolNode> = emptyMap(),
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
    /** 工具栏高度（审计 B 项）：null = 未定义（窗口走旧 48dp）。 */
    val toolbarHeight: VhLengthSpec? = null,
    /** 工具栏样式（审计 B 项）：null = 未定义（渲染走旧口径）。 */
    val toolbarStyle: ToolbarStyleSpec? = null,
    /** 区域压制（区域门控）：true = 该区域缺席（不是透明）；缺省 false。 */
    val suppressToolbar: Boolean = false,
    val suppressKeyboard: Boolean = false,
    /** 工具栏布局（审计 B 项）：null = 未定义（出厂排列）。 */
    val toolbarLayout: ToolbarLayoutSpec? = null,
    /**
     * 精灵图映射表（审计 D 项）：快照直传校验结果（sheet 资产
     * 逻辑 id → 子图名 → 帧）；空表 = 未定义（描述按整图计）。
     */
    val spriteMaps: Map<String, Map<String, SpriteFrame>> = emptyMap(),
    /** 预编辑高度（审计 B 项）：null = 未定义（走旧 30dp）。 */
    val preeditHeight: VhLengthSpec? = null,
    /** 预编辑样式（审计 B 项）：null = 未定义。 */
    val preeditStyle: PreeditStyleSpec? = null,
    /** 横条候选区样式（审计 B 项）：null = 未定义。 */
    val horizontalCandidatesStyle: CandidateAreaStyleSpec? = null,
    /** 候选序号／注释字号（审计 I 项）：null = 未定义（走 Jime 默认 16 / 16）。 */
    val candidateIndexSizeDp: Float? = null,
    val candidateCommentSizeDp: Float? = null,
    /** 横条候选单元格样式全字段：null = 未定义。 */
    val horizontalCandidateStyle: CandidateCellStyleSpec? = null,
    /** 横条候选上限（审计 I 项）：null = 不限。 */
    val horizontalMaxColumns: Int? = null,
    /** 横条展开钮在左（审计 I 项）：false = 右（默认）。 */
    val horizontalExpandLeft: Boolean = false,
    /** 横向符号 RTL（S11）：true = 横向符号行从右向左排，默认 LTR。 */
    val horizontalSymbolsRtl: Boolean = false,
    /** 竖网格行列上限（审计 I 项）：null = 未定义。 */
    val verticalMaxRows: Int? = null,
    val verticalMaxColumns: Int? = null,
    /** 竖网格侧边栏显示（审计 I 项）：true = 显示（默认）。 */
    val verticalSidePanel: Boolean = true,
    /** 候选上下文菜单（审计 I 项）：空表 = 未定义。 */
    val candidateContextMenu: List<CandidateMenuItem> = emptyList(),
    /** 竖网格候选区样式（审计 B 项）：null = 未定义。 */
    val verticalCandidatesStyle: CandidateAreaStyleSpec? = null,
    /** 竖网格候选单元格样式全字段：null = 未定义。 */
    val verticalCandidateStyle: CandidateCellStyleSpec? = null,
    /** 竖网格底部钮行：null = 未声明（旧网格无钮行）。 */
    val verticalBottomRow: VerticalBottomRowSpec? = null,
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
    /** 二级分类符号：皮肤定义时板用二级面板，否则走既有一级表。 */
    val subClassifiedSymbols: com.jobeen.ime.skin.symbol.SubClassifiedSymbols? = null,
    /**
     * 分类符号角标（S12，symbolBadge 节）：缺省字号 9、颜色
     * specialKeyText；highlight 未写时选中沿用 normal 色。
     */
    val symbolBadgeFontSizeDp: Float? = null,
    val symbolBadgeNormalColor: Int? = null,
    val symbolBadgeHighlightColor: Int? = null,
    /** 未知顶层节点原样透传（见 ValidatedSkin.unknownNodes；快照不解读）。 */
    val unknownNodes: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    /**
     * 覆盖层版本（2026-10-29 item-1 D1）：构建本快照时叠加的
     * 覆盖层文件版本；0 = 无覆盖层。进程内覆盖层变化后重建的
     * 快照版本号不同，调用方以此判过期（见
     * SkinSnapshotProvider.refreshIfOverlayChanged）。
     */
    val overlayVersion: Long = 0L,
    /**
     * 多主槽一期：类型 → 方向已解析布局表（快照工厂按
     * `keyboards` 表逐项解析后注入；无表皮肤为空表）。
     * - [layoutId] 的含义不变：仍是顶层 layoutId 绑定（兼容
     *   旧调用方），绝不复用为“当前类型”——当前类型是运行
     *   态（KeyboardStateManager）的可变状态，不属于不可变
     *   快照；类型切换只改状态管理器，不重建快照。
     * - 旧单布局字段（resolvedLayout/resolvedLayoutLandscape）
     *   照常保留：无表皮肤走旧路径；有表皮肤且未设顶层绑定
     *   时，工厂以 pinyin 项桥接旧字段（见 SkinSnapshotFactory），
     *   使旧生效布局路径仍能命中主键盘布局。
     */
    val resolvedKeyboards: Map<String, KeyboardTypeResolved> = emptyMap(),
    /**
     * 类型样式集（G1）：类型 → 解析后的样式集；无条目 = 该类型
     * 沿用包级样式。键路径（effectiveLayoutForType）按此换样式，
     * 面板颜色按类型换样式属后续轮（面板共享、需类型感知刷新）。
     */
    val typeStyles: Map<String, TypeStyleSet> = emptyMap(),
    /** 类型区域集（G2）：类型 → 区域参数；面板接线属后续轮。 */
    val typeAreas: Map<String, TypeAreaSet> = emptyMap(),
    /**
     * 皮肤包演示资料（审计 K 余项）：包根演示图与说明的校验
     * 结果；仅代次目录构建路径注入（工厂 buildFromFiles），
     * 其余构造路径为空。演示图本体不进快照，UI 以
     * [SkinPackageDocsParser.demoImageFile] 按代次目录取文件。
     */
    val packageDocs: SkinPackageDocs = SkinPackageDocs(),
) {
    /** 是否携带深色板（旧单色板皮肤为 false，恒取浅色板）。 */
    val hasDarkPalette: Boolean get() = darkColorScheme != null

    /**
     * 按模式取板（经四变体回退链调色轴决策，结果与旧三元表达式
     * 逐字节一致：深色且有深色板 → 深色板，其余 → 浅色板）。
     */
    /** 按类型取样式集（G1）：无集或类型未引用时返回 null（调用方回落包级）。 */
    fun styleSetFor(type: String): TypeStyleSet? = typeStyles[type]

    /** 按类型取区域集（G2）：未声明时返回 null（调用方回落包级）。 */
    fun areaSetFor(type: String): TypeAreaSet? = typeAreas[type]

    /**
     * 面板配色按类型取（G1 面板颜色）：该类型有样式集时用其色板
     * （深色板口径与 effectiveColorScheme 一致：深色且有深色板 →
     * 深色板，否则浅色板）；无集时回落包级 effectiveColorScheme。
     * 面板共享、键盘按类型切换：面板刷新时以当前类型调本函数。
     */
    fun effectiveColorSchemeForType(type: String?, isDark: Boolean): KeyboardColors.ColorScheme {
        val set = type?.let { typeStyles[it] }
        if (set != null) {
            return if (isDark && set.darkColorScheme != null) set.darkColorScheme else set.colorScheme
        }
        return effectiveColorScheme(isDark)
    }

    /** 面板区域按类型取（G2）：类型区域集写了该项时优先，否则回落包级。 */
    fun toolbarHeightFor(type: String?): VhLengthSpec? =
        type?.let { typeAreas[it]?.toolbarHeight } ?: toolbarHeight

    fun preeditHeightFor(type: String?): VhLengthSpec? =
        type?.let { typeAreas[it]?.preeditHeight } ?: preeditHeight

    fun horizontalCandidatesStyleFor(type: String?): CandidateAreaStyleSpec? =
        type?.let { typeAreas[it]?.horizontalCandidatesStyle } ?: horizontalCandidatesStyle

    fun verticalCandidatesStyleFor(type: String?): CandidateAreaStyleSpec? =
        type?.let { typeAreas[it]?.verticalCandidatesStyle } ?: verticalCandidatesStyle

    fun horizontalCandidateStyleFor(type: String?): CandidateCellStyleSpec? =
        type?.let { typeAreas[it]?.horizontalCandidateStyle } ?: horizontalCandidateStyle

    fun verticalCandidateStyleFor(type: String?): CandidateCellStyleSpec? =
        type?.let { typeAreas[it]?.verticalCandidateStyle } ?: verticalCandidateStyle

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

/**
 * 单类型已解析结果（多主槽一期）：竖/横屏两个方向独立绑定，
 * 各自独立解析结论；高度取自 `keyboards` 表项本身（与布局
 * 文件顶层 heightPercent 是两级：表项高度优先于布局高度，
 * 见 KeyboardWindowView.contentHeight 的优先级注释）。
 */
data class KeyboardTypeResolved(
    /** 竖屏方向已解析布局；null = 该方向无有效绑定。 */
    val portrait: ResolvedLayout? = null,
    /** 横屏方向已解析布局；null = 该方向无有效绑定。 */
    val landscape: ResolvedLayout? = null,
    val portraitResolution: LayoutResolution = LayoutResolution.NOT_SET,
    val landscapeResolution: LayoutResolution = LayoutResolution.NOT_SET,
    /** 深色竖屏已解析布局（G4）；null = 深色沿用浅色竖屏。 */
    val portraitDark: ResolvedLayout? = null,
    /** 深色横屏已解析布局（G4）；null = 深色横屏沿用深色竖屏／浅色横屏。 */
    val landscapeDark: ResolvedLayout? = null,
    val portraitDarkResolution: LayoutResolution = LayoutResolution.NOT_SET,
    val landscapeDarkResolution: LayoutResolution = LayoutResolution.NOT_SET,
    /** 表项竖屏高度百分比（50–200 已在解析期过滤）；null = 未指定。 */
    val heightPercent: Double? = null,
    /** 表项横屏专属高度；null = 横屏沿用竖屏值。 */
    val heightPercentLandscape: Double? = null,
) {
    /**
     * 按方向取已解析布局：横屏无绑定时回落竖屏布局（其
     * baseLayoutLandscape 即横屏权重变体，与旧横屏绑定
     * 缺失时回落竖屏布局同口径）；竖屏无绑定时不取横屏
     * 布局（横屏专属布局不进竖屏），调用方走内置回退。
     * G4：深色优先取深色绑定，缺失时按方向回落浅色
     * （深色横屏 → 深色竖屏 → 浅色横屏 → 浅色竖屏）。
     */
    fun resolvedFor(landscape: Boolean, isDark: Boolean = false): ResolvedLayout? {
        // 注意：参数 landscape（Boolean）遮蔽同名属性，属性须用 this. 限定。
        if (!isDark) {
            return if (landscape) this.landscape ?: portrait else portrait
        }
        return if (landscape) {
            landscapeDark ?: portraitDark ?: this.landscape ?: portrait
        } else {
            portraitDark ?: portrait
        }
    }

    /** 按方向取解析结论（横屏回落竖屏布局时结论仍为竖屏项的结论）。 */
    fun resolutionFor(landscape: Boolean, isDark: Boolean = false): LayoutResolution {
        if (!isDark) {
            return if (landscape && this.landscape != null) landscapeResolution
            else portraitResolution
        }
        return if (landscape) {
            when {
                landscapeDark != null -> landscapeDarkResolution
                portraitDark != null -> portraitDarkResolution
                landscape != null -> landscapeResolution
                else -> portraitResolution
            }
        } else {
            if (portraitDark != null) portraitDarkResolution else portraitResolution
        }
    }

    /** 按方向取高度：横屏无专属值时回落竖屏值。 */
    fun heightPercentFor(landscape: Boolean): Double? =
        if (landscape) heightPercentLandscape ?: heightPercent else heightPercent
}

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
