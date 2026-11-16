// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import androidx.annotation.StringRes
import com.jobeen.ime.R
import com.jobeen.ime.skin.model.SkinIssue

/**
 * 导入拒绝 reason 码 → 用户可操作文案（2026-11-16 皮肤编辑器修法 3）。
 *
 * 皮肤库页导入被拒绝时不再原样显示 reason 码，改为“问题＋动作”：
 * 哪一个文件／布局／键出问题、用户该怎么修。
 *
 * 码表来源：[com.jobeen.ime.skin.pkg.JimeImporter]（manifest/skin/
 * layout/quota/video/transaction）、
 * [com.jobeen.ime.skin.pkg.ZipSafeExtractor]（zip: 子码）、
 * [com.jobeen.ime.skin.model.SkinValidator]（校验拒绝码）、
 * [com.jobeen.ime.error.DeepErrorMapper]（transaction 内层码）。
 * 纯函数：[resolve] 返回串 ID＋参数，调用方用
 * `context.getString(resId, *args.toTypedArray())` 取文案。
 * 未知码回落通用串（附原码，方便按原码排查）。
 */
object SkinImportReasons {

    data class Message(@StringRes val resId: Int, val args: List<String> = emptyList())

    fun resolve(reason: String, issues: List<SkinIssue> = emptyList()): Message {
        val base = reason.substringBefore(":")
        val tail = reason.substringAfter(":", "")
        return when (base) {
            // ---- 压缩包层：reason 形如 zip:<子码> ----
            "zip" -> resolveZip(tail)
            // ---- 清单层 ----
            "manifest_count" -> Message(R.string.skin_import_reason_manifest_count, listOf(tail))
            "manifest_unparseable" ->
                Message(R.string.skin_import_reason_manifest_unparseable)
            "manifest_format_version_unsupported" ->
                Message(R.string.skin_import_reason_manifest_version)
            "manifest_duplicate_id" ->
                Message(R.string.skin_import_reason_manifest_dup_id)
            "manifest_duplicate_path" ->
                Message(R.string.skin_import_reason_manifest_dup_path)
            "manifest_file_missing" ->
                Message(R.string.skin_import_reason_manifest_file_missing, listOf(tail))
            "manifest_sha_mismatch" ->
                Message(R.string.skin_import_reason_manifest_sha_mismatch, listOf(tail))
            "manifest_size_mismatch" ->
                Message(R.string.skin_import_reason_manifest_size_mismatch, listOf(tail))
            "manifest_unknown_type" ->
                Message(R.string.skin_import_reason_manifest_unknown_type, listOf(tail))
            "unlisted_file" ->
                Message(R.string.skin_import_reason_unlisted_file, listOf(tail))
            // ---- 皮肤本体层 ----
            "skin_missing" -> Message(R.string.skin_import_reason_skin_missing)
            "skin_count_not_one" -> Message(R.string.skin_import_reason_skin_count)
            "skin_unparseable" -> Message(R.string.skin_import_reason_skin_unparseable)
            "skin_meta_id_mismatch" -> Message(R.string.skin_import_reason_meta_id_mismatch)
            // ---- 布局层 ----
            "layout_unparseable" ->
                Message(R.string.skin_import_reason_layout_unparseable, listOf(tail))
            "layout_json_limits_exceeded" ->
                Message(R.string.skin_import_reason_layout_limits, listOf(tail))
            "layout_invalid" -> resolveLayoutInvalid(layoutId = tail, issues = issues)
            // ---- 配额／视频层 ----
            "quota_exceeded" -> Message(R.string.skin_import_reason_quota)
            "video_count_exceeded" -> Message(R.string.skin_import_reason_video_count)
            "video_file_too_large" -> Message(R.string.skin_import_reason_video_too_large)
            "video_invalid" ->
                Message(R.string.skin_import_reason_video_invalid, listOf(tail))
            "video_dimensions_invalid" ->
                Message(R.string.skin_import_reason_video_dimensions)
            "video_missing" ->
                Message(R.string.skin_import_reason_video_missing, listOf(tail))
            // ---- 入库事务层：transaction:<stage>:<内层码> ----
            "transaction" -> resolveTransaction(tail)
            // ---- 皮肤校验拒绝码（SkinValidator） ----
            "json_depth_exceeded" -> Message(R.string.skin_import_reason_json_depth)
            "json_string_too_long" -> Message(R.string.skin_import_reason_json_string)
            "format_version_missing_or_invalid" ->
                Message(R.string.skin_import_reason_format_missing)
            "format_version_unsupported" ->
                Message(R.string.skin_import_reason_format_unsupported, listOf(tail))
            "min_app_version_invalid" ->
                Message(R.string.skin_import_reason_min_app_invalid)
            "app_version_too_low" ->
                Message(R.string.skin_import_reason_app_too_low, listOf(tail))
            "key_mappings_too_many" ->
                Message(R.string.skin_import_reason_key_mappings, listOf(tail))
            "no_color_scheme" -> Message(R.string.skin_import_reason_no_color)
            "skin_missing_dark_mode" -> Message(R.string.skin_import_reason_no_dark)
            // ---- 其他 ----
            else -> Message(R.string.skin_import_reason_unknown, listOf(reason))
        }
    }

    private fun resolveZip(tail: String): Message {
        val sub = tail.substringBefore(":")
        val arg = tail.substringAfter(":", "")
        return when (sub) {
            "zip_empty" -> Message(R.string.skin_import_reason_zip_empty)
            "file_missing" -> Message(R.string.skin_import_reason_zip_file_missing)
            "zip_too_large" -> Message(R.string.skin_import_reason_zip_too_large, listOf(arg))
            "too_many_entries" -> Message(R.string.skin_import_reason_zip_too_many)
            "duplicate_path" -> Message(R.string.skin_import_reason_zip_dup_path, listOf(arg))
            "nested_archive" -> Message(R.string.skin_import_reason_zip_nested, listOf(arg))
            "entry_too_large" -> Message(R.string.skin_import_reason_zip_entry_large, listOf(arg))
            "expanded_too_large" -> Message(R.string.skin_import_reason_zip_expanded_large)
            "zip_unreadable" -> Message(R.string.skin_import_reason_zip_unreadable, listOf(arg))
            "empty_path", "backslash_in_path", "absolute_path", "drive_letter_path",
            "parent_ref_in_path", "empty_segment_in_path" ->
                Message(R.string.skin_import_reason_zip_bad_path, listOf(arg.ifEmpty { sub }))
            else -> Message(R.string.skin_import_reason_zip, listOf(tail.ifEmpty { sub }))
        }
    }

    private fun resolveTransaction(tail: String): Message {
        val stage = tail.substringBefore(":")
        val inner = tail.substringAfter(":", "")
        return when (inner) {
            "no_space" -> Message(R.string.skin_import_reason_no_space)
            "corrupt" -> Message(R.string.skin_import_reason_corrupt)
            "verify_failed" -> Message(R.string.skin_import_reason_verify_failed)
            "rename_failed" -> Message(R.string.skin_import_reason_rename_failed)
            "failed" -> Message(R.string.skin_import_reason_transaction_failed, listOf(stage))
            else -> Message(
                R.string.skin_import_reason_transaction,
                listOf(stage, inner.ifEmpty { "failed" }),
            )
        }
    }

    /**
     * layout_invalid:<布局 id>：指明哪个布局、哪个键、哪个字段。
     * issues 里取第一条属于该布局的 issue（path 形如
     * `layouts.<id>.<keypath>`，detail 形如 `tap_invalid:value_invalid`），
     * keypath 去掉 `layouts.<id>.` 前缀后展示，detail 逐段译成人话，
     * 未知词原样保留。
     */
    private fun resolveLayoutInvalid(layoutId: String, issues: List<SkinIssue>): Message {
        val prefix = "layouts.$layoutId."
        val first = issues.firstOrNull { it.path.startsWith(prefix) }
            ?: issues.firstOrNull()
        if (first == null) {
            return Message(R.string.skin_import_reason_layout_invalid_nodetail, listOf(layoutId))
        }
        val keyPath = first.path.removePrefix(prefix)
        val detail = humanizeDetail(first.detail)
        return Message(
            R.string.skin_import_reason_layout_invalid,
            listOf(layoutId, "$keyPath（$detail）"),
        )
    }

    private fun humanizeDetail(detail: String): String =
        detail.split(":").joinToString("，") { humanizeToken(it) }

    private fun humanizeToken(token: String): String = when (token) {
        "tap_invalid" -> "按键动作无效"
        "value_invalid" -> "值无效"
        "missing" -> "缺失"
        "invalid" -> "无效"
        "too_many" -> "数量超限"
        "too_long" -> "过长"
        "too_large" -> "过大"
        "unsupported" -> "不支持"
        else -> token
    }
}
