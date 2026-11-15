// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel

import com.jobeen.ime.R
import com.jobeen.ime.data.manager.VerificationCodeExtractor

/**
 * 剪贴板长按菜单（WO52 5.3，`l22.T` 口径，纯模型）：
 *
 * 7 项＋保留的 Jime 现有项：
 * 1. Open link（网址正则找到 URL 才出现）
 * 2. Extract verification code（提取到验证码才出现，依赖 5.2）
 * 3. Split words（文本非空时出现，依赖 1.9）
 * 4. Save as phrase（写入常用语）
 * 5. Favorite／Remove from favorites（按已收藏切换；收藏态独立于置顶）
 * 6. Edit（与现有“编辑”合并为一项，不重复）
 * 7. Delete（标红／警示样式）
 * 保留现有 6 项不动（保原有行为）：置顶／取消置顶、移入分组、编辑、
 * 多选、排序、删除；Edit 与现有“编辑”是同一项。
 *
 * 图标名按单子记在模型里（SF Symbols 名：safari／numbers／
 * square.grid.3x3／bag.badge.plus／star.fill／star.slash.fill／
 * square.and.pencil／trash）；Jime 的动作卡菜单（ConfirmOverlay）
 * 无图标槽，图标暂不渲染（见已知差距）。
 *
 * 搜索结果长按：仅一项 Delete result。
 * 图片条目：Jime 剪贴板只有文本条目，Split 隐藏分支以 [isImage]
 * 参数预留，当前恒 false、不可达。
 */
object ClipboardItemMenu {

    enum class Id {
        OPEN_LINK,
        EXTRACT_CODE,
        SPLIT_WORDS,
        SAVE_AS_PHRASE,
        FAVORITE,
        PIN,
        MOVE_TO_GROUP,
        EDIT,
        MULTI_SELECT,
        REORDER,
        DELETE,
        DELETE_RESULT,
    }

    data class Item(
        val id: Id,
        val labelRes: Int,
        val icon: String,
        val destructive: Boolean = false,
    )

    fun itemsFor(
        text: String,
        isFavorite: Boolean = false,
        isSearchResult: Boolean = false,
        isImage: Boolean = false,
        isPinned: Boolean = false,
    ): List<Item> {
        if (isSearchResult) {
            return listOf(
                Item(Id.DELETE_RESULT, R.string.clipboard_delete_result, "trash", destructive = true),
            )
        }
        val items = ArrayList<Item>()
        if (VerificationCodeExtractor.findUrl(text) != null) {
            items += Item(Id.OPEN_LINK, R.string.clipboard_open_link, "safari")
        }
        if (VerificationCodeExtractor.extract(text) != null) {
            items += Item(Id.EXTRACT_CODE, R.string.clipboard_extract_code, "numbers")
        }
        if (text.isNotEmpty() && !isImage) {
            items += Item(Id.SPLIT_WORDS, R.string.clipboard_split_words, "square.grid.3x3")
        }
        items += Item(Id.SAVE_AS_PHRASE, R.string.clipboard_save_as_phrase, "bag.badge.plus")
        items += if (isFavorite) {
            Item(Id.FAVORITE, R.string.clipboard_remove_favorite, "star.slash.fill")
        } else {
            Item(Id.FAVORITE, R.string.clipboard_favorite, "star.fill")
        }
        items += Item(
            Id.PIN,
            if (isPinned) R.string.clipboard_unpin else R.string.clipboard_pin,
            "pin",
        )
        items += Item(Id.MOVE_TO_GROUP, R.string.clipboard_move_to_group, "folder")
        items += Item(Id.EDIT, R.string.clipboard_edit, "square.and.pencil")
        items += Item(Id.MULTI_SELECT, R.string.clipboard_multi_select, "checkmark.circle")
        items += Item(Id.REORDER, R.string.clipboard_reorder, "arrow.up.arrow.down")
        items += Item(Id.DELETE, R.string.clipboard_delete, "trash", destructive = true)
        return items
    }
}
