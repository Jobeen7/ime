// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.R

/**
 * 主设置页分组：固定顺序即主页呈现顺序；搜索后空组自动隐藏。
 * 只做导航／呈现层模型：不含点击动作、不含偏好键、不碰键盘服务。
 */
enum class MainSettingGroup(val key: String) {
    INPUT_CANDIDATE("input_candidate"),
    KEYBOARD("keyboard"),
    APPEARANCE("appearance"),
    CONVENIENCE("convenience"),
    DATA_BACKUP("data_backup"),
    ADVANCED_ABOUT("advanced_about"),
}

/** 主设置页一行：标题＋副标题＋所属组；点击动作由调用方按键映射，不进模型。 */
data class MainSettingEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val group: MainSettingGroup,
)

/**
 * 搜索过滤：空查询返回全部；否则标题或副标题含查询
 * （去首尾空白、不区分大小写）。只匹配行，不匹配组名：
 * 组是否出现，取决于组内是否有行命中。
 */
fun filterMainSettings(query: String, entries: List<MainSettingEntry>): List<MainSettingEntry> {
    val q = query.trim()
    if (q.isEmpty()) return entries
    val folded = q.lowercase()
    return entries.filter {
        it.title.lowercase().contains(folded) || it.subtitle.lowercase().contains(folded)
    }
}

/**
 * 按组归拢：组顺序固定为空组剔除。
 * 主页只渲染非空组卡片；子页只渲染本组过滤后的行。
 */
fun groupMainSettings(
    entries: List<MainSettingEntry>,
): List<Pair<MainSettingGroup, List<MainSettingEntry>>> {
    return MainSettingGroup.entries.mapNotNull { group ->
        val rows = entries.filter { it.group == group }
        if (rows.isEmpty()) null else group to rows
    }
}

/**
 * 单页组直达判定：组内仅 1 页时返回该页，否则返回 null（走组页）。
 * 组行点按用此判定：单页组直接进页，不经过只剩自己一行的组页；
 * 多页组仍进组页。当前单页组：外观组（appearance）、关于组（about）。
 * 搜索结果开页本就不经组页（直接调行点击映射），不受此判定影响。
 */
fun directPageForGroup(
    entries: List<MainSettingEntry>,
    group: MainSettingGroup,
): MainSettingEntry? {
    val rows = entries.filter { it.group == group }
    return if (rows.size == 1) rows[0] else null
}

// ---- 设置目录整树（通用渲染器＋搜索页用） ----

/**
 * 目录页：主页 14 个入口页。标题／副标题以字符串资源记，
 * 调用方（可组合函数内）解析后供搜索与渲染用。
 */
data class CatalogPage(
    val key: String,
    val titleRes: Int,
    val subtitleRes: Int,
    val group: MainSettingGroup,
)

/**
 * 目录行：页内静态偏好行。动态标题的行（字体角色、已选词典、
 * 带版本号的更新行）不进索引：标题不稳定，搜不到也定不了位。
 * sectionRes＝行所在分段（键盘分类、候选分组等），用于结果路径；
 * focusTitleRes＝点结果后定位的标题：缺省为行标题本身；键盘行
 * 定位到所在分类行（分类页内行不在主页层，定分类行才是可达目标）。
 */
data class CatalogRow(
    val titleRes: Int,
    val pageKey: String,
    val sectionRes: Int? = null,
    val focusTitleRes: Int? = null,
)

/** 14 个入口页：与主页行一一对应，键与主页行键一致。 */
fun catalogPages(): List<CatalogPage> = listOf(
    CatalogPage("schema", R.string.schema_settings, R.string.row_sub_schema_settings, MainSettingGroup.INPUT_CANDIDATE),
    CatalogPage("scheme_update", R.string.schema_update_title, R.string.row_sub_scheme_update, MainSettingGroup.INPUT_CANDIDATE),
    CatalogPage("rime", R.string.rime_title, R.string.row_sub_rime, MainSettingGroup.INPUT_CANDIDATE),
    CatalogPage("candidates", R.string.prediction_candidates, R.string.row_sub_candidates, MainSettingGroup.INPUT_CANDIDATE),
    CatalogPage("keyboard", R.string.keyboard_settings, R.string.row_sub_keyboard, MainSettingGroup.KEYBOARD),
    CatalogPage("skin", R.string.keyboard_theme, R.string.row_sub_skin, MainSettingGroup.KEYBOARD),
    CatalogPage("toolbar", R.string.toolbar_title, R.string.toolbar_settings_desc, MainSettingGroup.KEYBOARD),
    CatalogPage("appearance", R.string.appearance_title, R.string.row_sub_appearance, MainSettingGroup.APPEARANCE),
    CatalogPage("clipboard", R.string.clipboard_manager, R.string.row_sub_clipboard, MainSettingGroup.CONVENIENCE),
    CatalogPage("voice", R.string.voice_settings, R.string.row_sub_voice, MainSettingGroup.CONVENIENCE),
    CatalogPage("stats", R.string.input_stats_title, R.string.row_sub_stats, MainSettingGroup.CONVENIENCE),
    CatalogPage("userdict", R.string.user_dict, R.string.row_sub_userdict, MainSettingGroup.DATA_BACKUP),
    CatalogPage("backup", R.string.backup_restore, R.string.row_sub_backup, MainSettingGroup.DATA_BACKUP),
    CatalogPage("files", R.string.schema_files, R.string.row_sub_files, MainSettingGroup.DATA_BACKUP),
    CatalogPage("about", R.string.about_us, R.string.row_sub_about, MainSettingGroup.ADVANCED_ABOUT),
)

/** 页内静态行：仅含行标题稳定的页；工具页（备份、方案文件等）只出页结果。 */
fun catalogRows(): List<CatalogRow> = listOf(
    // 键盘：按分类；定位目标＝分类行。
    CatalogRow(R.string.key_sound, "keyboard", R.string.kb_cat_sound, R.string.kb_cat_sound),
    CatalogRow(R.string.key_sound_volume, "keyboard", R.string.kb_cat_sound, R.string.kb_cat_sound),
    CatalogRow(R.string.key_custom_sound, "keyboard", R.string.kb_cat_sound, R.string.kb_cat_sound),
    CatalogRow(R.string.key_custom_sound_pick, "keyboard", R.string.kb_cat_sound, R.string.kb_cat_sound),
    CatalogRow(R.string.key_custom_sound_clear, "keyboard", R.string.kb_cat_sound, R.string.kb_cat_sound),
    CatalogRow(R.string.key_sound_preview, "keyboard", R.string.kb_cat_sound, R.string.kb_cat_sound),
    CatalogRow(R.string.key_vibration, "keyboard", R.string.kb_cat_haptics, R.string.kb_cat_haptics),
    CatalogRow(R.string.key_vibration_follow_system, "keyboard", R.string.kb_cat_haptics, R.string.kb_cat_haptics),
    CatalogRow(R.string.key_vibration_amplitude, "keyboard", R.string.kb_cat_haptics, R.string.kb_cat_haptics),
    CatalogRow(R.string.key_vibration_duration, "keyboard", R.string.kb_cat_haptics, R.string.kb_cat_haptics),
    CatalogRow(R.string.key_release_vibration, "keyboard", R.string.kb_cat_haptics, R.string.kb_cat_haptics),
    CatalogRow(R.string.swipe_trigger_distance, "keyboard", R.string.kb_cat_gesture, R.string.kb_cat_gesture),
    CatalogRow(R.string.gesture_swipe_velocity, "keyboard", R.string.kb_cat_gesture, R.string.kb_cat_gesture),
    CatalogRow(R.string.gesture_long_press_timeout, "keyboard", R.string.kb_cat_gesture, R.string.kb_cat_gesture),
    CatalogRow(R.string.gesture_repeat_interval, "keyboard", R.string.kb_cat_gesture, R.string.kb_cat_gesture),
    CatalogRow(R.string.gesture_double_tap_timeout, "keyboard", R.string.kb_cat_gesture, R.string.kb_cat_gesture),
    CatalogRow(R.string.gesture_slide_step, "keyboard", R.string.kb_cat_gesture, R.string.kb_cat_gesture),
    CatalogRow(R.string.mnemonic_hints, "keyboard", R.string.kb_cat_display, R.string.kb_cat_display),
    CatalogRow(R.string.decoration_effects, "keyboard", R.string.kb_cat_display, R.string.kb_cat_display),
    CatalogRow(R.string.key_ripple_effect, "keyboard", R.string.kb_cat_display, R.string.kb_cat_display),
    CatalogRow(R.string.key_border_show, "keyboard", R.string.kb_cat_display, R.string.kb_cat_display),
    CatalogRow(R.string.expand_border, "keyboard", R.string.kb_cat_display, R.string.kb_cat_display),
    CatalogRow(R.string.display_hide_symbol, "keyboard", R.string.kb_cat_display, R.string.kb_cat_display),
    CatalogRow(R.string.display_hide_hint, "keyboard", R.string.kb_cat_display, R.string.kb_cat_display),
    CatalogRow(R.string.key_corner_radius, "keyboard", R.string.kb_cat_display, R.string.kb_cat_display),
    CatalogRow(R.string.key_x_gap, "keyboard", R.string.keyboard_layout, R.string.keyboard_layout),
    CatalogRow(R.string.key_y_gap, "keyboard", R.string.keyboard_layout, R.string.keyboard_layout),
    CatalogRow(R.string.keyboard_height, "keyboard", R.string.keyboard_layout, R.string.keyboard_layout),
    CatalogRow(R.string.keyboard_height_landscape, "keyboard", R.string.keyboard_layout, R.string.keyboard_layout),
    CatalogRow(R.string.horizontal_padding, "keyboard", R.string.keyboard_layout, R.string.keyboard_layout),
    CatalogRow(R.string.bottom_padding, "keyboard", R.string.keyboard_layout, R.string.keyboard_layout),
    CatalogRow(R.string.ignore_system_insets, "keyboard", R.string.keyboard_layout, R.string.keyboard_layout),
    CatalogRow(R.string.symbol_pairing, "keyboard", R.string.kb_cat_behavior, R.string.kb_cat_behavior),
    // 候选：内容组／窗口组。
    CatalogRow(R.string.model_prediction_enable, "candidates", R.string.candidate_content),
    CatalogRow(R.string.model_rerank_enable, "candidates", R.string.candidate_content),
    CatalogRow(R.string.candidate_show_index, "candidates", R.string.candidate_window),
    CatalogRow(R.string.candidate_show_comment, "candidates", R.string.candidate_window),
    CatalogRow(R.string.candidate_border, "candidates", R.string.candidate_window),
    CatalogRow(R.string.candidate_line_limit, "candidates", R.string.candidate_window),
    CatalogRow(R.string.candidate_max_lines, "candidates", R.string.candidate_window),
    CatalogRow(R.string.candidate_auto_shrink, "candidates", R.string.candidate_window),
    CatalogRow(R.string.candidate_full_display, "candidates", R.string.candidate_window),
    CatalogRow(R.string.candidate_inline_suggestion, "candidates", R.string.candidate_window),
    // 剪切板：监听／历史／规则。
    CatalogRow(R.string.clipboard_listening, "clipboard", R.string.clipboard_listening),
    CatalogRow(R.string.clipboard_max_entries, "clipboard", R.string.clipboard_history),
    CatalogRow(R.string.clipboard_retention_days, "clipboard", R.string.clipboard_history),
    CatalogRow(R.string.clipboard_dedup, "clipboard", R.string.clipboard_rules),
    CatalogRow(R.string.clipboard_output_rule, "clipboard", R.string.clipboard_rules),
    CatalogRow(R.string.clipboard_return_after_paste, "clipboard", R.string.clipboard_rules),
    // 语音：模型组／插件组／纠错组。
    CatalogRow(R.string.voice_recognition_model, "voice", R.string.voice_model_group),
    CatalogRow(R.string.cloud_voice_plugins, "voice", R.string.cloud_voice_plugins),
    CatalogRow(R.string.voice_correction_enable, "voice", R.string.voice_correction_group),
    CatalogRow(R.string.voice_correction_clear_pairs, "voice", R.string.voice_correction_group),
    // 词典：造词／手动备份／定期同步／高级。
    CatalogRow(R.string.word_creation_title, "userdict", R.string.word_creation_title),
    CatalogRow(R.string.user_dict_export, "userdict", R.string.user_dict_manual_backup),
    CatalogRow(R.string.user_dict_import, "userdict", R.string.user_dict_manual_backup),
    CatalogRow(R.string.user_dict_periodic_sync, "userdict", R.string.user_dict_periodic_sync),
    CatalogRow(R.string.user_dict_periodic_interval, "userdict", R.string.user_dict_periodic_sync),
    CatalogRow(R.string.user_dict_allow_http, "userdict", R.string.user_dict_advanced),
    CatalogRow(R.string.user_dict_upload, "userdict", R.string.user_dict_advanced),
    CatalogRow(R.string.user_dict_download, "userdict", R.string.user_dict_advanced),
    CatalogRow(R.string.user_dict_upload_schemes, "userdict", R.string.user_dict_advanced),
    CatalogRow(R.string.user_dict_download_schemes, "userdict", R.string.user_dict_advanced),
    CatalogRow(R.string.user_dict_deleted_words, "userdict", R.string.user_dict_advanced),
    // 关于：无分段。
    CatalogRow(R.string.about_diag_export, "about"),
    CatalogRow(R.string.skin_diag_title, "about"),
    CatalogRow(R.string.about_reset, "about"),
    CatalogRow(R.string.licenses_index, "about"),
    // 外观：视频背景／全局样式／应用外观（皮肤行已去重，留键盘组入口）。
    CatalogRow(R.string.video_background, "appearance", R.string.video_background),
    CatalogRow(R.string.link_toolbar, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.key_radius, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.key_border_width, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.top_corner, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.key_background, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.key_border_color, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.toolbar_background, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.toolbar_border_color, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.background_blur, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.blur_radius, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.bar_background, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.bar_blur, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.shared_background, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.reset_default, "appearance", R.string.global_style, R.string.global_style),
    CatalogRow(R.string.app_appearance, "appearance", R.string.app_appearance),
)

/** 解析后的页：搜索与渲染直接用字符串，不再碰资源。 */
data class ResolvedPage(
    val key: String,
    val title: String,
    val subtitle: String,
    val groupTitle: String,
)

/** 解析后的行：focusTitle 缺省为行标题本身。 */
data class ResolvedRow(
    val title: String,
    val pageKey: String,
    val pageTitle: String,
    val groupTitle: String,
    val sectionTitle: String?,
    val focusTitle: String?,
)

/** 搜索结果：标题＋面包屑路径＋目标页键＋定位标题（页结果无定位）。 */
data class SearchResult(
    val title: String,
    val path: String,
    val pageKey: String,
    val focusTitle: String?,
)

/**
 * 面包屑路径：组 › 页 › 分段；页名与组名相同时省页段，
 * 段名与页名／组名相同时省段（单页组不写重复名）。
 */
fun buildSearchPath(
    groupTitle: String,
    pageTitle: String,
    sectionTitle: String?,
): String {
    val segments = mutableListOf(groupTitle)
    if (pageTitle != groupTitle) segments += pageTitle
    if (sectionTitle != null && sectionTitle != groupTitle && sectionTitle != pageTitle) {
        segments += sectionTitle
    }
    return segments.joinToString(" › ")
}

/**
 * 跨目录搜索：空查询返回空（搜索页等输入）；否则页标题／副标题、
 * 行标题／页标题／分段标题含查询（去首尾空白、不区分大小写）。
 * 页结果在前、行结果在后，各按目录顺序；行结果带定位标题。
 */
fun searchCatalog(
    query: String,
    pages: List<ResolvedPage>,
    rows: List<ResolvedRow>,
): List<SearchResult> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val folded = q.lowercase()
    val pageResults = pages.filter {
        it.title.lowercase().contains(folded) || it.subtitle.lowercase().contains(folded)
    }.map {
        SearchResult(
            title = it.title,
            path = buildSearchPath(it.groupTitle, it.title, null),
            pageKey = it.key,
            focusTitle = null,
        )
    }
    val rowResults = rows.filter {
        it.title.lowercase().contains(folded) ||
            it.pageTitle.lowercase().contains(folded) ||
            (it.sectionTitle?.lowercase()?.contains(folded) == true)
    }.map {
        SearchResult(
            title = it.title,
            path = buildSearchPath(it.groupTitle, it.pageTitle, it.sectionTitle),
            pageKey = it.pageKey,
            focusTitle = it.focusTitle ?: it.title,
        )
    }
    return pageResults + rowResults
}

/**
 * 目录校验：页键唯一、行有所属页、同页内行标题不重复。
 * 返回问题描述串；空即校验通过。资源解析不在此做。
 */
fun validateCatalog(
    pages: List<CatalogPage>,
    rows: List<CatalogRow>,
): List<String> {
    val problems = mutableListOf<String>()
    val pageKeys = pages.map { it.key }
    if (pageKeys.size != pageKeys.toSet().size) {
        problems += "duplicate page key"
    }
    val pageKeySet = pageKeys.toSet()
    rows.forEach { row ->
        if (row.pageKey !in pageKeySet) {
            problems += "row without page: ${row.pageKey}"
        }
    }
    rows.groupBy { it.pageKey }.forEach { (pageKey, pageRows) ->
        val titles = pageRows.map { it.titleRes }
        if (titles.size != titles.toSet().size) {
            problems += "duplicate row in page: $pageKey"
        }
    }
    return problems
}
