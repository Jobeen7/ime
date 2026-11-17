package com.jobeen.ime.skin.model

/**
 * 皮肤动作白名单与强类型动作（自 SkinKeyModel.kt 迁出的活声明；
 * 工具栏／符号栏／动作编辑等活代码共用）。
 */

/** 命令动作白名单（封闭集；定稿四.1–四.3 + delete_forward +
 * 确认记录九冻结的补充格式名）。新增条目须先改定稿/裁定。
 */
enum class WhitelistAction(
    val formatName: String,
    val valueRule: ValueRule,
) {
    COMMIT_DIRECT("commit_direct", ValueRule.REQUIRED),
    LITERAL_NEWLINE("literal_newline", ValueRule.FORBIDDEN),
    ENTER("enter", ValueRule.FORBIDDEN),
    DELETE("delete", ValueRule.FORBIDDEN),
    DELETE_FORWARD("delete_forward", ValueRule.FORBIDDEN),
    SPACE("space", ValueRule.FORBIDDEN),
    CURSOR_LEFT("cursor_left", ValueRule.FORBIDDEN),
    CURSOR_RIGHT("cursor_right", ValueRule.FORBIDDEN),
    ROTATE_SCHEMA("rotate_schema", ValueRule.FORBIDDEN),
    OPEN_SCHEMA_PICKER("open_schema_picker", ValueRule.FORBIDDEN),
    /**
     * 命名方案直切换（2026-10-28 重点候选 8-3）：value = 方案 ID
     *（优先）或方案名；解析期只验非空字符串，ID/名解析在运行
     * 期（SchemaTargetResolver：ID 精确命中优先于名）。
     */
    SWITCH_SCHEMA("switch_schema", ValueRule.REQUIRED),
    SWITCH_SYSTEM_IME("switch_system_ime", ValueRule.FORBIDDEN),
    OPEN_SYSTEM_IME_PICKER("open_system_ime_picker", ValueRule.FORBIDDEN),
    HIDE_KEYBOARD("hide_keyboard", ValueRule.FORBIDDEN),
    CAPS("caps", ValueRule.FORBIDDEN),
    SWITCH_LAYOUT("switch_layout", ValueRule.REQUIRED),
    RESUME("resume", ValueRule.FORBIDDEN),
    CLEAR("clear", ValueRule.FORBIDDEN),
    UNDO("undo", ValueRule.FORBIDDEN),
    REDO("redo", ValueRule.FORBIDDEN),
    VOICE_INPUT("voice_input", ValueRule.FORBIDDEN),
    /**
     * 中英切换（纠正 2，2026-11-01 定）：转换目标 enzh 键用——
     * #中英切换 = RIME ascii_mode 翻转，Jime 侧以此白名单动作
     * 接菜单现成的 ascii 开关路径（只翻偏好，不切类型；类型切
     * 换走独立的 keyboardType 动作，见 SkinAction.KeyboardType）。
     */
    TOGGLE_ASCII_MODE("toggle_ascii_mode", ValueRule.FORBIDDEN),
    /**
     * 审计 E 新增命令（Jime 格式名均为 snake_case，对端名由
     * 转换器映射，不直接进格式）：
     * - tab/escape/home/end：直送应用的按键（绕过引擎）；
     * - settings/system_settings：应用内设置／系统设置意图；
     * - none：刻意空操作；dismiss_keyboard：收起键盘别名；
     * - open_url：打开链接（仅 http/https 由分发层放行）；
     * - return_primary_keyboard/return_last_keyboard：经类型
     *   状态回主键盘／上一键盘；
     * - shift：瞬时上档（仅 tap 槽，按住生效、抬手复原）；
     * - symbol：符号通道（字面提交，不经组字与标点转换）。
     */
    TAB("tab", ValueRule.FORBIDDEN),
    ESCAPE("escape", ValueRule.FORBIDDEN),
    HOME("home", ValueRule.FORBIDDEN),
    END("end", ValueRule.FORBIDDEN),
    /**
     * 修饰键单按（WO52 8.3：va2 注册表补齐——Jime 原只有组合键
     * 里的修饰位，无独立修饰键动作）：直送应用按键事件
     * （AppKeyEventAction），键码与 SkinKeyCodes 键名表一致
     * （command=117 META、control=113 CTRL、option=57 ALT）。
     * function 不补：SkinKeyCodes 已定 Android 侧无可靠等价键码。
     */
    COMMAND("command", ValueRule.FORBIDDEN),
    CONTROL("control", ValueRule.FORBIDDEN),
    OPTION("option", ValueRule.FORBIDDEN),
    SETTINGS("settings", ValueRule.FORBIDDEN),
    SYSTEM_SETTINGS("system_settings", ValueRule.FORBIDDEN),
    NONE("none", ValueRule.FORBIDDEN),
    DISMISS_KEYBOARD("dismiss_keyboard", ValueRule.FORBIDDEN),
    OPEN_URL("open_url", ValueRule.REQUIRED),
    RETURN_PRIMARY_KEYBOARD("return_primary_keyboard", ValueRule.FORBIDDEN),
    RETURN_LAST_KEYBOARD("return_last_keyboard", ValueRule.FORBIDDEN),
    MOMENTARY_SHIFT("shift", ValueRule.FORBIDDEN),
    SYMBOL("symbol", ValueRule.REQUIRED),
    /**
     * 审计 E # 快捷指令集（对端 §6.3 原名，含中文名；格式名
     * 即指令本身，写在 command 节点的 action 字段）：
     * - 未列入者（#rimeDeploy / #rimeSync：无键动作直达口）
     *   解析期按未知动作剔除，不静默空操作。
     * （WO52 3.1a：两项已挂白名单，见 HASH_RIME_DEPLOY／HASH_RIME_SYNC。）
     */
    HASH_TRADITIONAL("#简繁切换", ValueRule.FORBIDDEN),
    HASH_RIME_SWITCHER("#RimeSwitcher", ValueRule.FORBIDDEN),
    HASH_SECOND_CANDIDATE("#次选上屏", ValueRule.FORBIDDEN),
    HASH_THIRD_CANDIDATE("#三选上屏", ValueRule.FORBIDDEN),
    HASH_SWITCH_SCHEME("#方案切换", ValueRule.FORBIDDEN),
    HASH_LINE_START("#行首", ValueRule.FORBIDDEN),
    HASH_LINE_END("#行尾", ValueRule.FORBIDDEN),
    HASH_NEWLINE("#换行", ValueRule.FORBIDDEN),
    HASH_ENTER("#Enter", ValueRule.FORBIDDEN),
    HASH_RETYPE("#重输", ValueRule.FORBIDDEN),
    HASH_CUT("#cut", ValueRule.FORBIDDEN),
    HASH_COPY("#copy", ValueRule.FORBIDDEN),
    /**
     * 全选（P0-2）：#selectText = 宿主文本全选（经输入连接
     * performContextMenuAction(selectAll)，与面板 SelectAll 同款）。
     */
    HASH_SELECT_TEXT("#selectText", ValueRule.FORBIDDEN),
    HASH_PASTE("#paste", ValueRule.FORBIDDEN),
    /**
     * 剪贴板面板直达（P1-4）：#showPasteboardView = 打开面板
     * 剪贴板态（与工具栏／菜单剪贴板入口同目的地）。
     */
    HASH_SHOW_PASTEBOARD_VIEW("#showPasteboardView", ValueRule.FORBIDDEN),
    /**
     * 二级集合翻页（P1-5）：#subCollectionPageUp/Down = 二级
     * 符号板当前子类翻页（页内无更多时空操作，不换父类）。
     */
    HASH_SUB_COLLECTION_PAGE_UP("#subCollectionPageUp", ValueRule.FORBIDDEN),
    HASH_SUB_COLLECTION_PAGE_DOWN("#subCollectionPageDown", ValueRule.FORBIDDEN),
    HASH_DELETE_TEXT("#deleteText", ValueRule.FORBIDDEN),
    HASH_UNDO("#undo", ValueRule.FORBIDDEN),
    HASH_REDO("#redo", ValueRule.FORBIDDEN),
    HASH_VERTICAL_PAGE_UP("#verticalCandidatesPageUp", ValueRule.FORBIDDEN),
    HASH_VERTICAL_PAGE_DOWN("#verticalCandidatesPageDown", ValueRule.FORBIDDEN),
    HASH_SHOW_PHRASE_VIEW("#showPhraseView", ValueRule.FORBIDDEN),
    HASH_CANDIDATES_BAR_TOGGLE("#candidatesBarStateToggle", ValueRule.FORBIDDEN),
    HASH_RIME_PREV_PAGE("#rimePreviousPage", ValueRule.FORBIDDEN),
    HASH_RIME_NEXT_PAGE("#rimeNextPage", ValueRule.FORBIDDEN),
    /**
     * 部署／同步（WO52 3.1a）：挂白名单，走现有部署／同步路径
     * （#rimeDeploy → FabActions.deployAndAwait 系部署核；
     * #rimeSync → WebDavSync.syncAllDicts）。无值。
     */
    HASH_RIME_DEPLOY("#rimeDeploy", ValueRule.FORBIDDEN),
    HASH_RIME_SYNC("#rimeSync", ValueRule.FORBIDDEN),
    HASH_TOGGLE_EMBEDDED_INPUT("#toggleEmbeddedInputMode", ValueRule.FORBIDDEN),
    HASH_CLEAR_CLIPBOARD("#clearSystemPasteboard", ValueRule.FORBIDDEN),
    /**
     * 新增 # 快捷指令（多主类型补充）：
     * - #keyboardMenu：键盘菜单开关（真实目标：面板菜单态切换）。
     * - #toggleSymbolBar / #toggleCommandView：解析＋映射＋分发
     *   均已接通，但目标 UI 不在本轮（完整符号条／命令面板均
     *   已排除）：分发为显式记日志的空操作，不伪造界面。
     */
    HASH_TOGGLE_SYMBOL_BAR("#toggleSymbolBar", ValueRule.FORBIDDEN),
    HASH_KEYBOARD_MENU("#keyboardMenu", ValueRule.FORBIDDEN),
    HASH_TOGGLE_COMMAND_VIEW("#toggleCommandView", ValueRule.FORBIDDEN),
    /**
     * 性能面板（P2-10）：#keyboardPerformance = 打开面板性能态
     * （与菜单性能入口同目的地；命令面板仍排除，见下）。
     */
    HASH_KEYBOARD_PERFORMANCE("#keyboardPerformance", ValueRule.FORBIDDEN),
    /**
     * 通知动作补齐（1:1 差距 P3；Jime 格式名均为 snake_case，
     * 对端名由转换器映射）：
     * - character：送 Rime 组字（非字面提交），value＝字符，必填；
     * - send_keys：直送应用组合键，value＝逗号分隔组合（见
     *   NotificationActionNames.parseSendKeysValue），必填；
     * - send_key_event：直送应用单键事件，value＝组合（键名／
     *   键码＋修饰键），必填；
     * - rime_option：翻转 rime 选项，value＝选项名，必填；
     * - delay：子动作间停顿，value＝毫秒数（0–1000），必填；
     * - snippet：片段展开，value＝模板，必填。
     * combine 未入白名单：通知动作格式为单值，组合动作需
     * 动作列表，格式无法承载；按键组合走既有 SkinAction.Combine。
     */
    CHARACTER("character", ValueRule.REQUIRED),
    SEND_KEYS("send_keys", ValueRule.REQUIRED),
    SEND_KEY_EVENT("send_key_event", ValueRule.REQUIRED),
    RIME_OPTION("rime_option", ValueRule.REQUIRED),
    DELAY("delay", ValueRule.REQUIRED),
    SNIPPET("snippet", ValueRule.REQUIRED),
    ;

    enum class ValueRule { REQUIRED, FORBIDDEN }

    companion object {
        fun fromFormatName(name: String): WhitelistAction? =
            when (name) {
                // 板锁已删（.22）：旧皮肤里的锁切换名作兼容别名，映射到
                // 无操作 NONE（分发已是 NoOpAction），不再整布局拒绝
                // （WO45 P2-3；旧口径“未知动作剔除”会随皮肤更新而破坏）。
                "symbolic_lock_toggle", "emoji_lock_toggle" -> NONE
                else -> entries.firstOrNull { it.formatName == name }
            }

        /** switch_layout 的合法目标 = 出厂八布局 NAME（详设 §3.2）。 */
        val LAYOUT_TARGETS: Set<String> = setOf("Qwerty", "T9", "T14", "T15", "T18", "Number", "Symbol", "Emoji")

        /** 自创布局运行时名前缀（B3 §3.3）：switch_layout 目标 `custom:<layoutId>`。 */
        const val CUSTOM_LAYOUT_PREFIX = "custom:"

        /**
         * switch_layout 合法目标全集（B3 §3.3 待核 2 定死）：出厂六布局，
         * 或自创布局运行时名（前缀 + 非空 layoutId）。其余一律 VALUE_INVALID。
         */
        fun isLayoutTarget(value: String?): Boolean {
            if (value == null) return false
            if (value in LAYOUT_TARGETS) return true
            return value.startsWith(CUSTOM_LAYOUT_PREFIX) &&
                value.removePrefix(CUSTOM_LAYOUT_PREFIX).isNotBlank()
        }
    }
}

/** 强类型动作（详设 §3.1）。 */
sealed interface SkinAction {
    /** 喂 Rime 组字（参与 Caps，不经标点转换；详设 §3.5）。 */
    data class Text(val value: String) : SkinAction

    /** 白名单命令；value 仅 REQUIRED 类动作携带。 */
    data class Command(
        val action: WhitelistAction,
        val value: String? = null,
    ) : SkinAction

    /**
     * 组合按键序列（审计 E：sendKeys）：有序的（键码，修饰键）
     * 组合，运行时逐个经引擎按键通道下发。
     */
    data class SendKeys(
        val keys: List<com.jobeen.ime.input.keyboard.key.KeyboardAction.SendKeyCombo>,
    ) : SkinAction

    /**
     * 直送应用的按键事件（审计 E：sendKeyEvent）：绕过引擎，
     * 经输入连接直发应用；修饰键以布尔字段携带。
     */
    data class SendKeyEvent(
        val keyCode: Int,
        val ctrl: Boolean = false,
        val shift: Boolean = false,
        val alt: Boolean = false,
        val meta: Boolean = false,
    ) : SkinAction

    /**
     * 组合动作（审计 E：combine）：有序子动作列表，运行时按序
     * 分发；子动作同样经本解析器解析（含嵌套 combine）。
     */
    data class Combine(val actions: List<SkinAction>) : SkinAction

    /**
     * 键盘类型切换（纠正 2：与中英切换并存的独立机制）：
     * 对端 keyboardType 对象形式的 Jime 映射——value 为类型名
     * （含自定义名），运行时经类型状态入口切换；空值解析期剔除。
     */
    data class KeyboardType(val type: String) : SkinAction

    /**
     * RIME 选项开关（动作子集）：value 为选项名，运行时翻转
     * 该选项的布尔值。注意对端口径：三态以上选项不能经此路
     * 切换（只翻布尔）；经此路改动的状态 RIME 不记忆持久化
     * （需持久化走方案切换器）。
     */
    data class RimeOption(val name: String) : SkinAction

    /**
     * 模板文本（动作子集，对端 snippet）：value 为模板，上屏时
     * 经 SnippetExpander 展开 `{{date:格式}}` 后字面提交；
     * `{{cursor}}` 标光标位；农历变量暂不支持（原样保留）。
     */
    data class Snippet(val template: String) : SkinAction

    /**
     * 子动作间停顿（动作子集，combine 专用）：毫秒数；顶层
     * （非 combine 内）出现解析期剔除。上限见
     * [DELAY_MAX_MS]（主线程阻塞有界）。
     */
    data class Delay(val ms: Int) : SkinAction

    /**
     * sequence 占位（历史类型，保留仅为模型兼容）：Batch 3 裁定
     * 删除该占位（详设 §6.6）后，解析器对 type=sequence 一律以
     * SEQUENCE_REMOVED 拒绝，不再产生本类型；文件中已有的
     * sequence 节点按未知节点原样往返、不执行不剥除。装配器对
     * 本类型的既有处理（恒等/过滤）保留，防御以旧版构建的内存
     * 模型流入。
     */
    data object SequencePlaceholder : SkinAction
}
