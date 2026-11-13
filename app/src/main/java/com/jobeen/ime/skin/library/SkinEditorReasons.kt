// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import androidx.annotation.StringRes
import com.jobeen.ime.R

/**
 * 皮肤编辑器 reason 码 → 用户可读文案的唯一映射（WO33 第 2(b)／4(b) 项）：
 *
 * - 入口 toast（[SkinEditorEntry]）与编辑页（SkinEditorScreen 的
 *   resolveReasonText 与保存结果展示）共用这一份映射，不各造一套；
 *   文案只改这里，两处自动一致。
 * - 覆盖的码＝生产代码实际能产出的全部 reason（见 KDoc 末尾的来源
 *   清单）：resolve 码、保存裁决码、保存失败码、应用事务内码。
 *   复合码只按冒号前的 base 码匹配（`manifest_invalid:<子码>`、
 *   `apply_failed:<内码>:<尾巴>`、`write_failed:<异常消息>` 等），
 *   其中 manifest_invalid 与 apply_failed 会递归进子码映射。
 * - 未知码回落具体提示（WO36 改回 WO33 前口径：防御分支“无生效皮肤”，
 *   不抛、不露原始码）；当前生产者无遗漏，回落只作防御——新增 reason
 *   码时须同步补映射与单测
 *   （[SkinEditorReasonsTest] 按来源清单逐码断言）。
 *
 * 来源清单（码 → 生产者；括号内为去重 base 码数，2026-11-13 逐码数过，
 * 与 [reasonResId] 的映射分支一一对应）：
 * - resolve（13 码）：no_active_skin／skin_json_missing／skin_json_unreadable／
 *   skin_json_unparseable／layout_unreadable／active_hash_mismatch／layout_hash_mismatch
 *   （SkinEditorSaver.resolveActiveFiles），manifest_* 六码
 *   （SkinEditorSave.validateManifestForEdit）。
 *   （S-L3 起 layout_hash_mismatch 不再作 resolve 失败码返回——失配布局
 *   改为排除＋入口提示，见 SkinEditorEntry.toastUnavailableLayouts；
 *   映射仍保留，供该提示与入口 toast 复用；13 码按映射分支计。）
 * - Blocked（14 码）：skin_unparseable／skin_json_limits_exceeded／
 *   layout_unparseable／layout_json_limits_exceeded／layout_invalid
 *   （SkinEditorSave.decideSkinJson／decideLayout），
 *   json_depth_exceeded／json_string_too_long／
 *   format_version_missing_or_invalid／format_version_unsupported／
 *   min_app_version_invalid／app_version_too_low／key_mappings_too_many／
 *   no_color_scheme／skin_missing_dark_mode
 *   （SkinValidator.Result.Rejected）。
 * - Failed（9 码）：backup_missing／manifest_invalid／
 *   new_generation_copy_failed／write_failed／
 *   manifest_skin_entry_missing／new_generation_incomplete／
 *   layout_index_entry_missing／index_changed_concurrently／
 *   apply_failed（SkinEditorSaver.saveSkinJsonLocked／saveLayoutLocked）。
 * - apply 内码（9 码，apply_failed 的子码，SkinEditorSaver.applyResultReason）：
 *   snapshot_rebuild_failed／target_invalid／
 *   switch_failed_rolled_back／first_render_failed_rolled_back／
 *   rollback_failed／superseded／rollback_unavailable／
 *   rolled_back_to_last_good／reverted_to_default。
 * - Stale（1 码）：active_changed（CAS 未命中）。
 * - applied／applied_pending 不进 UI（成功路径无 reason 展示），不在映射内。
 */
object SkinEditorReasons {

    /** reason 码（复合码只看冒号前的 base）→ 文案 string 资源。 */
    @StringRes
    fun reasonResId(reason: String): Int {
        val base = reason.substringBefore(":")
        return when (base) {
            // ---- resolve ----
            "no_active_skin" -> R.string.skin_editor_no_active_skin
            "skin_json_missing" -> R.string.skin_editor_reason_skin_json_missing
            "skin_json_unreadable" -> R.string.skin_editor_reason_skin_json_unreadable
            "skin_json_unparseable" -> R.string.skin_editor_reason_skin_json_unparseable
            "manifest_missing" -> R.string.skin_editor_reason_manifest_missing
            "manifest_unparseable" -> R.string.skin_editor_reason_manifest_unparseable
            "manifest_version_unsupported" -> R.string.skin_editor_reason_manifest_version
            "manifest_skin_entry_missing" -> R.string.skin_editor_reason_manifest_entry_missing
            "manifest_duplicate_path" -> R.string.skin_editor_reason_manifest_dup_path
            "manifest_skin_hash_mismatch" -> R.string.skin_editor_reason_manifest_hash_mismatch
            "active_hash_mismatch" -> R.string.skin_editor_reason_active_hash_mismatch
            "layout_hash_mismatch" -> R.string.skin_editor_reason_layout_hash_mismatch
            "layout_unreadable" -> R.string.skin_editor_reason_layout_unreadable
            // ---- Blocked：保存裁决 ----
            "skin_unparseable" -> R.string.skin_editor_reason_skin_unparseable
            "skin_json_limits_exceeded" -> R.string.skin_editor_reason_skin_json_limits_exceeded
            "json_depth_exceeded" -> R.string.skin_editor_reason_json_depth_exceeded
            "json_string_too_long" -> R.string.skin_editor_reason_json_string_too_long
            "format_version_missing_or_invalid" ->
                R.string.skin_editor_reason_format_version_missing_or_invalid
            "format_version_unsupported" -> R.string.skin_editor_reason_format_version_unsupported
            "min_app_version_invalid" -> R.string.skin_editor_reason_min_app_version_invalid
            "app_version_too_low" -> R.string.skin_editor_reason_app_version_too_low
            "key_mappings_too_many" -> R.string.skin_editor_reason_key_mappings_too_many
            "no_color_scheme" -> R.string.skin_editor_reason_no_color_scheme
            "skin_missing_dark_mode" -> R.string.skin_editor_reason_skin_missing_dark_mode
            "layout_unparseable" -> R.string.skin_editor_reason_layout_unparseable
            "layout_json_limits_exceeded" ->
                R.string.skin_editor_reason_layout_json_limits_exceeded
            "layout_invalid" -> R.string.skin_editor_reason_layout_invalid
            // ---- Failed：保存失败 ----
            "backup_missing" -> R.string.skin_editor_reason_backup_missing
            // manifest_invalid:<子码>：递归进子码映射；无子码时给通用清单文案。
            "manifest_invalid" -> manifestInvalidResId(reason)
            "new_generation_copy_failed" -> R.string.skin_editor_reason_new_generation_copy_failed
            "write_failed" -> R.string.skin_editor_reason_write_failed
            "new_generation_incomplete" -> R.string.skin_editor_reason_new_generation_incomplete
            "layout_index_entry_missing" -> R.string.skin_editor_reason_layout_index_entry_missing
            "index_changed_concurrently" -> R.string.skin_editor_reason_index_changed_concurrently
            // apply_failed:<内码>:<尾巴>：内码已知时用内码文案，否则通用应用失败文案。
            "apply_failed" -> applyFailedResId(reason)
            // ---- apply 内码（也可直接作为 reason 出现时复用）----
            "snapshot_rebuild_failed" -> R.string.skin_editor_reason_snapshot_rebuild_failed
            "target_invalid" -> R.string.skin_editor_reason_target_invalid
            "switch_failed_rolled_back" -> R.string.skin_editor_reason_switch_failed_rolled_back
            "first_render_failed_rolled_back" ->
                R.string.skin_editor_reason_first_render_failed_rolled_back
            "rollback_failed" -> R.string.skin_editor_reason_rollback_failed
            "superseded" -> R.string.skin_editor_reason_superseded
            "rollback_unavailable" -> R.string.skin_editor_reason_rollback_unavailable
            "rolled_back_to_last_good" -> R.string.skin_editor_reason_rolled_back_to_last_good
            "reverted_to_default" -> R.string.skin_editor_reason_reverted_to_default
            // ---- Stale ----
            "active_changed" -> R.string.skin_editor_reason_active_changed
            // 未知码回落（WO36）：改回具体提示“无生效皮肤”（WO33 前口径），
            // 不用通用“操作失败，请重试”。当前无生产者产未知码，走不到；
            // skin_editor_reason_unknown 串随之闲置（删串须直接改
            // strings.xml，不在本批动）。
            else -> R.string.skin_editor_no_active_skin
        }
    }

    /**
     * 编辑器入口 resolve 失败时的 toast 文案（WO33 第 2(b) 项；S-L1）：
     * 皮肤实际生效、只是活动记录／布局索引过期时给具体提示（恢复动作
     * 同为重新导入皮肤），其余沿用通用 toast
     * （skin_editor_no_active_skin）。按冒号前 base 码匹配（与
     * [reasonResId] 同款做法），`layout_hash_mismatch:<布局 id>` 这类
     * 复合码也能拿到具体提示，不再落通用分支。
     */
    @StringRes
    fun entryToastResId(reason: String): Int {
        val base = reason.substringBefore(":")
        return if (base == "active_hash_mismatch" || base == "layout_hash_mismatch") {
            reasonResId(reason)
        } else {
            R.string.skin_editor_no_active_skin
        }
    }

    private fun manifestInvalidResId(reason: String): Int {
        val sub = reason.substringAfter("manifest_invalid:", "")
        return if (sub.isEmpty()) {
            R.string.skin_editor_reason_manifest_invalid
        } else {
            reasonResId(sub)
        }
    }

    private fun applyFailedResId(reason: String): Int {
        val rest = reason.substringAfter("apply_failed:", "")
        // 内码在前、尾巴在后（如 snapshot_rebuild_failed:old_gen_intact、
        // rollback_failed:<异常消息>:restored_ok）：按前缀匹配内码。
        val inner = APPLY_INNER_CODES.firstOrNull { rest.startsWith(it) }
        return if (inner != null) reasonResId(inner) else R.string.skin_editor_reason_apply_failed
    }

    private val APPLY_INNER_CODES = listOf(
        "snapshot_rebuild_failed",
        "target_invalid",
        "switch_failed_rolled_back",
        "first_render_failed_rolled_back",
        "rollback_failed",
        "superseded",
        "rollback_unavailable",
        "rolled_back_to_last_good",
        "reverted_to_default",
    )
}
