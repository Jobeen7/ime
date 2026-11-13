// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import com.jobeen.ime.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S-L1：入口 toast 按冒号前 base 码匹配——active／layout 两类 hash 失配
 * 给具体提示，其余沿用通用 toast；S-L2：新增 resolve 码
 * layout_unreadable 有专属映射（复合码按 base 匹配）。
 */
class SkinEditorReasonsEntryToastTest {

    @Test
    fun entryToast_activeHashMismatchIsSpecific() {
        assertEquals(
            R.string.skin_editor_reason_active_hash_mismatch,
            SkinEditorReasons.entryToastResId("active_hash_mismatch"),
        )
    }

    @Test
    fun entryToast_layoutHashMismatchWithIdIsSpecific() {
        // S-L1：复合码只按冒号前 base 匹配，带布局 id 后缀也不落通用分支。
        assertEquals(
            R.string.skin_editor_reason_layout_hash_mismatch,
            SkinEditorReasons.entryToastResId("layout_hash_mismatch:lay1"),
        )
        assertEquals(
            R.string.skin_editor_reason_layout_hash_mismatch,
            SkinEditorReasons.entryToastResId("layout_hash_mismatch"),
        )
    }

    @Test
    fun entryToast_otherResolveCodesStayGeneric() {
        // 除两类 hash 失配外仍沿用通用 toast（WO33 口径不变）。
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.entryToastResId("no_active_skin"),
        )
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.entryToastResId("skin_json_missing"),
        )
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.entryToastResId("skin_json_unreadable"),
        )
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.entryToastResId("layout_unreadable:lay1"),
        )
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.entryToastResId("bogus_code_xyz"),
        )
    }

    @Test
    fun layoutUnreadableHasSpecificMapping() {
        // S-L2：新增 resolve 码须同步补映射与单测（KDoc 来源清单约定）。
        assertEquals(
            R.string.skin_editor_reason_layout_unreadable,
            SkinEditorReasons.reasonResId("layout_unreadable"),
        )
        assertEquals(
            R.string.skin_editor_reason_layout_unreadable,
            SkinEditorReasons.reasonResId("layout_unreadable:lay1"),
        )
    }
}
