// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import com.jobeen.ime.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WO36 未知码回落：改回具体提示“无生效皮肤”（WO33 前口径）。
 *
 * 未知码（无生产者产出，走不到的防御分支）不再回落通用
 * “操作失败，请重试”（skin_editor_reason_unknown），改回
 * skin_editor_no_active_skin；已知码映射与入口 toast 口径不变。
 * 既有 SkinEditorReasonsTest.unknownCodeFallsBackToGenericText
 * 须随之改期望（见汇报），本文件钉住新口径。
 */
class Wo36SkinReasonsTest {

    @Test
    fun unknownCodeFallsBackToNoActiveSkin() {
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.reasonResId("bogus_code_xyz"),
        )
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.reasonResId(""),
        )
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.reasonResId("bogus_code_xyz:tail"),
        )
    }

    @Test
    fun manifestInvalidUnknownSubcodeFallsBackToNoActiveSkin() {
        // manifest_invalid 递归进子码映射：子码未知时同样走未知码回落。
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.reasonResId("manifest_invalid:bogus_sub"),
        )
    }

    @Test
    fun knownCodesKeepSpecificText() {
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.reasonResId("no_active_skin"),
        )
        assertEquals(
            R.string.skin_editor_reason_layout_hash_mismatch,
            SkinEditorReasons.reasonResId("layout_hash_mismatch:lay1"),
        )
        assertEquals(
            R.string.skin_editor_reason_write_failed,
            SkinEditorReasons.reasonResId("write_failed:disk full"),
        )
        assertEquals(
            R.string.skin_editor_reason_active_changed,
            SkinEditorReasons.reasonResId("active_changed"),
        )
        // apply_failed 未知内码仍回落通用应用失败文案（本分支不动）。
        assertEquals(
            R.string.skin_editor_reason_apply_failed,
            SkinEditorReasons.reasonResId("apply_failed:unknown_inner:old_gen_intact"),
        )
    }

    @Test
    fun entryToastUnknownStillNoActiveSkin() {
        assertEquals(
            R.string.skin_editor_no_active_skin,
            SkinEditorReasons.entryToastResId("bogus_code_xyz"),
        )
        assertEquals(
            R.string.skin_editor_reason_layout_hash_mismatch,
            SkinEditorReasons.entryToastResId("layout_hash_mismatch:lay1"),
        )
    }
}
