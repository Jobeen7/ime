# 皮肤格式 v3 规格（skin.json）

本文档是皮肤 v3 格式的规格说明。**权威来源是 `app/src/main/java/com/jobeen/ime/skin/model/SkinValidator.kt`**（校验器即规格）；本文档是对它的摘要，凡与代码不一致处以代码为准。不确定的条目已注明“以 SkinValidator.kt 为准”。

皮肤以 `.jime` 包导入，包内 `skin.json` 为皮肤描述文件。导入时 `JimeImporter` 调 `SkinValidator.validate` 校验；运行时 `SkinSnapshotFactory` 调同一校验器构建快照，校验拒绝则快照为 null（皮肤无法应用）。

---

## 1. formatVersion：只接受 3

- `SUPPORTED_FORMAT_VERSION = 3`（`SkinValidator.kt:42`）。
- 校验条件为 `formatVersion != SUPPORTED_FORMAT_VERSION` 即拒绝（`SkinValidator.kt:138-139`），拒绝串为 `format_version_unsupported:<n>`。
- **v1/v2 已终止支持**：v1、v2、v4 及其他任何非 3 的版本一律拒绝导入与应用，无迁移路径。旧版皮肤需按 v3 重写后重新导入。
- 生成端（`SkinTreeBuilder.kt:33`、`LegacySkinSaver.kt:56`）只产出 v3。

## 2. 顶层键（65 个）

校验器用 `readTopLevel(name)` 逐个读取顶层键并记入 consumed 集；未被任何解析器消费的键原样保留在 `unknownNodes` 里（只透传、不解读）。以下 65 个键为校验器实际消费的顶层键（63 个经 `readTopLevel`，另 `fontFaces`／`fontRanges` 由 `SkinFontParser` 直接读树）：

| 分组 | 顶层键 |
|---|---|
| 身份与版本 | `formatVersion`、`minAppVersion`、`meta`、`layoutId`、`layoutIdLandscape` |
| 外观与样式 | `appearance`、`stylesDark`、`derivationDark`、`derivationFormulas`、`geometry`、`fontFamily`、`fontFaces`、`fontRanges`、`roleFonts`、`textSizes`、`keyStyles`、`styleSets`、`keyShadow`、`keyAnimation`、`spaceAnimation`、`keyRipple`、`keyAnimations`、`hintSymbolsStyles`、`hintSymbolsGridStyles`、`hintSymbolNodes` |
| 键盘与候选 | `keyboards`、`keyMappings`、`notifications`、`conditionStyles`、`swipeThresholdDp`、`bubbleOverflowCheck`、`candidateRadiusDp`、`candidateIndexSizeDp`、`candidateCommentSizeDp`、`preeditHeight`、`preeditStyle`、`toolbar`、`toolbarHeight`、`toolbarStyle`、`toolbarLayout`、`toolbarButtonBackgroundStyle`、`menuAction`、`sprites`、`horizontalCandidates`、`horizontalCandidatesLayout`、`horizontalCandidatesStyle`、`horizontalCandidateStyle`、`verticalCandidates`、`verticalCandidatesLayout`、`verticalCandidatesStyle`、`verticalCandidateStyle`、`verticalBottomRow`、`candidateContextMenu`、`predictionCloseBg`、`predictionCloseRadiusDp`、`audio`、`keySound` |
| 符号 | `symbolBar`、`subClassifiedSymbols`、`t9Symbols`、`t9HorizontalSymbols`、`numericSymbols`、`horizontalSymbols`、`horizontalSymbolsRtl`、`symbolBadge` |

各键的值类型与取值范围以 `SkinValidator.kt` 为准（本文档不逐字段复述；校验器对非法值一般记 issue 并回落，不拒包——除 `formatVersion` 与布局动作白名单外，见下）。

- `meta`：对象，含 `id`（皮肤唯一标识，校验器不强制）、`name`、`author`、`version`（缺省 `"1.0"`）。
- `layoutId`／`layoutIdLandscape`：字符串。`layoutId` 取值见 §3.1 的 `switch_layout` 条目（出厂六布局名或 `custom:<id>`）；校验器此处只验字符串形态，引用解析与回退在快照工厂层。

## 3. 动作白名单（76 个）

布局键的动作（`keyMappings` 与布局定义中的动作名）必须精确命中白名单，否则**整布局拒绝**（`fromFormatName` 精确匹配返 null → `tap_invalid:unknown_action` → `INVALID_LAYOUT`）。白名单即 `SkinKeyModel.kt` 的 `WhitelistAction` 枚举，共 **76 个**（不是 82 个）。

### 3.1 基础动作（37 个）

`commit_direct`（须带值）、`literal_newline`、`enter`、`delete`、`delete_forward`、`space`、`cursor_left`、`cursor_right`、`rotate_schema`、`open_schema_picker`、`switch_schema`（须带值：方案 ID 优先或方案名）、`switch_system_ime`、`open_system_ime_picker`、`hide_keyboard`、`caps`、`switch_layout`（须带值）、`resume`、`clear`、`undo`、`redo`、`voice_input`、`toggle_ascii_mode`、`tab`、`escape`、`home`、`end`、`settings`、`system_settings`、`none`、`dismiss_keyboard`、`open_url`（须带值）、`return_primary_keyboard`、`return_last_keyboard`、`shift`、`symbol`（须带值）、`run_script`（须带值）、`open_script`。

- `switch_layout` 的合法目标：出厂六布局 `Qwerty`、`T9`、`T15`、`Number`、`Symbol`、`Emoji`，或自创布局 `custom:<layoutId>`。
- `symbolic_lock_toggle`／`emoji_lock_toggle` **不在白名单内**（板锁功能已于 v1.1.16.22 删除，动作随之移除）；但为兼容旧皮肤，`fromFormatName` 把这两个名字作别名映射到 `NONE`（无操作，不拒包；WO45 P2-3）。

### 3.2 `#` 快捷指令（33 个）

`#简繁切换`、`#RimeSwitcher`、`#次选上屏`、`#三选上屏`、`#方案切换`、`#行首`、`#行尾`、`#换行`、`#Enter`、`#重输`、`#cut`、`#copy`、`#selectText`、`#paste`、`#showPasteboardView`、`#subCollectionPageUp`、`#subCollectionPageDown`、`#deleteText`、`#undo`、`#redo`、`#verticalCandidatesPageUp`、`#verticalCandidatesPageDown`、`#showPhraseView`、`#candidatesBarStateToggle`、`#rimePreviousPage`、`#rimeNextPage`、`#toggleEmbeddedInputMode`、`#toggleScriptView`、`#clearSystemPasteboard`、`#toggleSymbolBar`、`#keyboardMenu`、`#toggleCommandView`、`#keyboardPerformance`。

### 3.3 序列／脚本动作（6 个，须带值）

`character`、`send_keys`、`send_key_event`、`rime_option`、`delay`、`snippet`。

## 4. 布局键：spanCol / spanColEnd

布局键（`LayoutModel.kt`）可用 `spanCol`／`spanColEnd` 做精确列位（跨行键不再只能贴两端）：

- `spanCol`：该键左侧的行内键数（0 = 行首）。
- `spanColEnd`：预留列区间的右端，**仅冲突判定用**；几何定位只用 `spanCol` + 自身宽度。
- 缺省：沿用行内顺序（不行文时按行内键数递推）。
- 校验：整数，且 `0 ≤ spanCol < spanColEnd`；越界记 issue。

## 5. insets（四边内边距）

`insets` 为对象 `{left, top, right, bottom}`，每边为数字（dp，0..64）：

- 节点缺失的边按 0 计（只列非零边即可）。
- 非数字的边记 issue 并按 0 计。
- 以 `AreaStyles.kt` 的解析为准。

## 6. 符号系列（t9Symbols 等）

符号类顶层键由各专用解析器解析，数据项形态见 `app/src/main/java/com/jobeen/ime/skin/symbol/`：

- `t9Symbols`：对象，含 `dataSource`（符号项列表）；九宫格按 9 格分页，每页按 3 列成行（末行空位补空）。
- `t9HorizontalSymbols`、`numericSymbols`、`horizontalSymbols`：横排／数字符号列表（各自由专用解析器解析）。
- `horizontalSymbolsRtl`：布尔，横排符号是否从右向左。
- `subClassifiedSymbols`：子分类符号（父分类＋子分类两级）。
- `symbolBar`：符号栏规格（栏内键定义）。
- `symbolBadge`：符号角标对象。

符号项的三种形态（`SymbolItems.kt`）：

- `Plain`：纯文本项，点按上屏该文本。
- `LabelValue`：`{label, value}`，点按上屏 `value`。
- `LabelActionStyle`：`{label, action, styleName?}`，点按分发 `action` 而非提交文本；`styleName` 可选，缺省为空（空 = 取条目所在格的默认文字样式）。

## 7. keyRipple（按压涟漪）

顶层键 `keyRipple` 为对象 `{"color": "#B4B4E6FF", "durationMs": 650}`（`KeyRipple.kt`）：

- `color`：ARGB 十六进制，缺省 `#B4B4E6FF`（即 `argb(180,180,230,255)`）。
- `durationMs`：单程时长毫秒，范围 100..2000，缺省 650。
- 非法字段逐项回落缺省值并记 issue，**不拒包**。
- 涟漪是否显示由用户全局开关决定（`KeyboardManager.RippleEffect`，缺省关）；皮肤只提供参数，不替用户开。

## 8. 符号项动作（分发规则）

`LabelActionStyle` 的 `action` 按 `SymbolItemActionResolver.resolve` 分发（`SymbolItems.kt`），要点如下（完整映射以该文件为准）：

| action | 效果 |
|---|---|
| `resume`、`return` | 恢复／返回 |
| `#cut`、`#copy`、`#paste`、`#clearSystemPasteboard` | 剪贴板剪切／复制／粘贴／清空 |
| `#selectText`、`#deleteText` | 全选／删文本 |
| `#undo`、`#redo` | 宿主撤销／重做 |
| `#showPasteboardView`、`#showPhraseView`、`#keyboardMenu`、`#keyboardPerformance`、`#toggleSymbolBar`、`#toggleCommandView`、`#toggleScriptView`、`#toggleEmbeddedInputMode` | 打开剪贴板／常用语／键盘菜单／性能面板／符号栏／命令视图／脚本视图／切换内嵌输入 |
| `#简繁切换`、`#中英切换` | 繁简切换／中英切换 |
| `#次选上屏`、`#三选上屏` | 上屏第 2／第 3 候选 |
| `#方案切换`、`#RimeSwitcher` | 轮换方案／打开输入法选择器 |
| `#行首`、`#行尾` | 光标到行首／行尾 |
| `#换行` | 上屏换行符 |
| `#Enter` | 回车动作 |
| `#重输` | 清空重输 |
| `#candidatesBarStateToggle` | 候选栏状态切换 |
| `#rimePreviousPage`、`#verticalCandidatesPageUp` | 上一页 |
| `#rimeNextPage`、`#verticalCandidatesPageDown` | 下一页 |
| `#subCollectionPageUp`、`#subCollectionPageDown` | 子分类上一页／下一页 |
| 未知 `#` 开头指令 | 安全默认：无操作（不提交 label 文本） |
| 非 `#` 开头的其他 action | 上屏 `label` 文本 |

`Plain` 项点按上屏其文本；`LabelValue` 项点按上屏其 `value`。

## 9. 校验与拒绝（总则）

- 校验器对大多数非法字段：记 issue、回落缺省值、**不拒包**。
- 以下两类**拒包／拒布局**（无回落）：
  - `formatVersion != 3`：整包拒绝（`format_version_unsupported:<n>`）。
  - 布局动作名不在白名单：整布局拒绝（`tap_invalid:unknown_action`）。
- 未知顶层键：保留在 `unknownNodes` 里透传，不解读、不告警。
- 其余未尽事宜以 `SkinValidator.kt` 为准；校验器行为变更时本文档应同步更新。
