// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import com.jobeen.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * SkinEditorReasons 映射单测（WO33 第 2(b)／4(b) 项）：
 * 按 SkinEditorReasons KDoc 的来源清单逐码断言——生产代码实际能
 * 产出的每个 reason 码都有专属文案（不是通用回落），复合码按
 * base／子码映射，无遗漏码。新增 reason 码时须同步补这里。
 */
class SkinEditorReasonsTest {

    private val unknown = R.string.skin_editor_reason_unknown

    /** 每个码都映射到专属文案（不等于通用回落），且等于预期的串。 */
    private fun assertMapped(reason: String, expected: Int) {
        val actual = SkinEditorReasons.reasonResId(reason)
        assertEquals("reason=$reason", expected, actual)
        assertNotEquals("reason=$reason must not fall back to unknown", unknown, actual)
    }

    @Test
    fun resolveCodesHaveSpecificText() {
        assertMapped("no_active_skin", R.string.skin_editor_no_active_skin)
        assertMapped("skin_json_missing", R.string.skin_editor_reason_skin_json_missing)
        assertMapped("skin_json_unreadable", R.string.skin_editor_reason_skin_json_unreadable)
        assertMapped("skin_json_unparseable", R.string.skin_editor_reason_skin_json_unparseable)
        assertMapped("manifest_missing", R.string.skin_editor_reason_manifest_missing)
        assertMapped("manifest_unparseable", R.string.skin_editor_reason_manifest_unparseable)
        assertMapped(
            "manifest_version_unsupported",
            R.string.skin_editor_reason_manifest_version,
        )
        assertMapped(
            "manifest_skin_entry_missing",
            R.string.skin_editor_reason_manifest_entry_missing,
        )
        assertMapped("manifest_duplicate_path", R.string.skin_editor_reason_manifest_dup_path)
        assertMapped(
            "manifest_skin_hash_mismatch",
            R.string.skin_editor_reason_manifest_hash_mismatch,
        )
        assertMapped("active_hash_mismatch", R.string.skin_editor_reason_active_hash_mismatch)
        assertMapped("layout_hash_mismatch", R.string.skin_editor_reason_layout_hash_mismatch)
        // 复合码：布局 id 后缀不影响映射。
        assertMapped(
            "layout_hash_mismatch:lay1",
            R.string.skin_editor_reason_layout_hash_mismatch,
        )
    }

    @Test
    fun blockedCodesHaveSpecificText() {
        assertMapped("skin_unparseable", R.string.skin_editor_reason_skin_unparseable)
        assertMapped(
            "skin_json_limits_exceeded",
            R.string.skin_editor_reason_skin_json_limits_exceeded,
        )
        assertMapped("json_depth_exceeded", R.string.skin_editor_reason_json_depth_exceeded)
        assertMapped("json_string_too_long", R.string.skin_editor_reason_json_string_too_long)
        assertMapped(
            "format_version_missing_or_invalid",
            R.string.skin_editor_reason_format_version_missing_or_invalid,
        )
        assertMapped(
            "format_version_unsupported",
            R.string.skin_editor_reason_format_version_unsupported,
        )
        assertMapped(
            "format_version_unsupported:99",
            R.string.skin_editor_reason_format_version_unsupported,
        )
        assertMapped(
            "min_app_version_invalid",
            R.string.skin_editor_reason_min_app_version_invalid,
        )
        assertMapped("app_version_too_low", R.string.skin_editor_reason_app_version_too_low)
        assertMapped(
            "app_version_too_low:5>3",
            R.string.skin_editor_reason_app_version_too_low,
        )
        assertMapped("key_mappings_too_many", R.string.skin_editor_reason_key_mappings_too_many)
        assertMapped(
            "key_mappings_too_many:123",
            R.string.skin_editor_reason_key_mappings_too_many,
        )
        assertMapped("no_color_scheme", R.string.skin_editor_reason_no_color_scheme)
        assertMapped("skin_missing_dark_mode", R.string.skin_editor_reason_skin_missing_dark_mode)
        assertMapped("layout_unparseable", R.string.skin_editor_reason_layout_unparseable)
        assertMapped(
            "layout_json_limits_exceeded",
            R.string.skin_editor_reason_layout_json_limits_exceeded,
        )
        assertMapped("layout_invalid", R.string.skin_editor_reason_layout_invalid)
        assertMapped("layout_invalid:lay1", R.string.skin_editor_reason_layout_invalid)
    }

    @Test
    fun failedCodesHaveSpecificText() {
        assertMapped("backup_missing", R.string.skin_editor_reason_backup_missing)
        assertMapped("manifest_invalid", R.string.skin_editor_reason_manifest_invalid)
        assertMapped(
            "new_generation_copy_failed",
            R.string.skin_editor_reason_new_generation_copy_failed,
        )
        assertMapped("write_failed", R.string.skin_editor_reason_write_failed)
        assertMapped("write_failed:disk full", R.string.skin_editor_reason_write_failed)
        assertMapped(
            "new_generation_incomplete",
            R.string.skin_editor_reason_new_generation_incomplete,
        )
        assertMapped(
            "layout_index_entry_missing",
            R.string.skin_editor_reason_layout_index_entry_missing,
        )
        assertMapped(
            "index_changed_concurrently",
            R.string.skin_editor_reason_index_changed_concurrently,
        )
        assertMapped("apply_failed", R.string.skin_editor_reason_apply_failed)
        assertMapped("active_changed", R.string.skin_editor_reason_active_changed)
    }

    @Test
    fun manifestInvalidRecursesIntoSubReason() {
        assertMapped(
            "manifest_invalid:manifest_duplicate_path",
            R.string.skin_editor_reason_manifest_dup_path,
        )
        assertMapped(
            "manifest_invalid:manifest_skin_hash_mismatch",
            R.string.skin_editor_reason_manifest_hash_mismatch,
        )
        assertMapped(
            "manifest_invalid:manifest_missing",
            R.string.skin_editor_reason_manifest_missing,
        )
    }

    @Test
    fun applyFailedUsesInnerReasonText() {
        assertMapped(
            "apply_failed:snapshot_rebuild_failed:old_gen_intact",
            R.string.skin_editor_reason_snapshot_rebuild_failed,
        )
        assertMapped(
            "apply_failed:target_invalid:old_gen_intact",
            R.string.skin_editor_reason_target_invalid,
        )
        assertMapped(
            "apply_failed:switch_failed_rolled_back:restored_ok",
            R.string.skin_editor_reason_switch_failed_rolled_back,
        )
        assertMapped(
            "apply_failed:first_render_failed_rolled_back:restored_ok",
            R.string.skin_editor_reason_first_render_failed_rolled_back,
        )
        assertMapped(
            "apply_failed:rollback_failed:boom:restored_partial",
            R.string.skin_editor_reason_rollback_failed,
        )
        assertMapped(
            "apply_failed:superseded:restored_ok",
            R.string.skin_editor_reason_superseded,
        )
        assertMapped(
            "apply_failed:rollback_unavailable:nope:restored_ok",
            R.string.skin_editor_reason_rollback_unavailable,
        )
        assertMapped(
            "apply_failed:rolled_back_to_last_good:old_gen_intact",
            R.string.skin_editor_reason_rolled_back_to_last_good,
        )
        assertMapped(
            "apply_failed:reverted_to_default:old_gen_intact",
            R.string.skin_editor_reason_reverted_to_default,
        )
        // 未知内码回落通用应用失败文案，不露原始码。
        assertEquals(
            R.string.skin_editor_reason_apply_failed,
            SkinEditorReasons.reasonResId("apply_failed:unknown_inner:old_gen_intact"),
        )
    }

    @Test
    fun applyInnerCodesMapDirectly() {
        assertMapped(
            "snapshot_rebuild_failed",
            R.string.skin_editor_reason_snapshot_rebuild_failed,
        )
        assertMapped("target_invalid", R.string.skin_editor_reason_target_invalid)
        assertMapped(
            "switch_failed_rolled_back",
            R.string.skin_editor_reason_switch_failed_rolled_back,
        )
        assertMapped(
            "first_render_failed_rolled_back",
            R.string.skin_editor_reason_first_render_failed_rolled_back,
        )
        assertMapped("rollback_failed", R.string.skin_editor_reason_rollback_failed)
        assertMapped("superseded", R.string.skin_editor_reason_superseded)
        assertMapped("rollback_unavailable", R.string.skin_editor_reason_rollback_unavailable)
        assertMapped(
            "rolled_back_to_last_good",
            R.string.skin_editor_reason_rolled_back_to_last_good,
        )
        assertMapped("reverted_to_default", R.string.skin_editor_reason_reverted_to_default)
    }

    @Test
    fun unknownCodeFallsBackToGenericText() {
        assertEquals(unknown, SkinEditorReasons.reasonResId("bogus_code_xyz"))
        assertEquals(unknown, SkinEditorReasons.reasonResId(""))
    }

    @Test
    fun entryToastHashMismatchIsSpecific() {
        // (b)：活动记录／布局索引两类 hash 失配给具体提示（S-L1），
        // 其余沿用通用 toast。复合码按冒号前 base 匹配。
        assertEquals(
            R.string.skin_editor_reason_active_hash_mismatch,
            SkinEditorReasons.entryToastResId("active_hash_mismatch"),
        )
        assertEquals(
            R.string.skin_editor_reason_layout_hash_mismatch,
            SkinEditorReasons.entryToastResId("layout_hash_mismatch:lay1"),
        )
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
            SkinEditorReasons.entryToastResId("bogus_code_xyz"),
        )
    }
}
