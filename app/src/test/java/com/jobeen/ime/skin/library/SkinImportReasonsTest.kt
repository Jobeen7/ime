// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import com.jobeen.ime.R
import com.jobeen.ime.skin.model.SkinIssue
import org.junit.Assert.assertEquals
import org.junit.Test

/** SkinImportReasons 码表测试（2026-11-16 皮肤编辑器修法 3）。 */
class SkinImportReasonsTest {

    @Test
    fun manifestShaMismatch_namesFile() {
        val msg = SkinImportReasons.resolve("manifest_sha_mismatch:skin.json")
        assertEquals(R.string.skin_import_reason_manifest_sha_mismatch, msg.resId)
        assertEquals(listOf("skin.json"), msg.args)
    }

    @Test
    fun layoutInvalid_namesLayoutKeyAndField() {
        val msg = SkinImportReasons.resolve(
            "layout_invalid:symbolic",
            listOf(
                SkinIssue(
                    path = "layouts.symbolic.rows[0].keys[0].tap",
                    kind = SkinIssue.Kind.FIELD_FALLBACK,
                    detail = "tap_invalid:value_invalid",
                ),
            ),
        )
        assertEquals(R.string.skin_import_reason_layout_invalid, msg.resId)
        assertEquals(
            listOf("symbolic", "rows[0].keys[0].tap（按键动作无效，值无效）"),
            msg.args,
        )
    }

    @Test
    fun layoutInvalidWithoutIssues_usesNodetailVariant() {
        val msg = SkinImportReasons.resolve("layout_invalid:symbolic")
        assertEquals(R.string.skin_import_reason_layout_invalid_nodetail, msg.resId)
        assertEquals(listOf("symbolic"), msg.args)
    }

    @Test
    fun zipEmpty() {
        val msg = SkinImportReasons.resolve("zip:zip_empty")
        assertEquals(R.string.skin_import_reason_zip_empty, msg.resId)
        assertEquals(emptyList<String>(), msg.args)
    }

    @Test
    fun transactionInnerNoSpace_mapsToNoSpace() {
        val msg = SkinImportReasons.resolve("transaction:rename:no_space")
        assertEquals(R.string.skin_import_reason_no_space, msg.resId)
    }

    @Test
    fun transactionUnknownInner_keepsStageAndReason() {
        val msg = SkinImportReasons.resolve("transaction:commit:boom")
        assertEquals(R.string.skin_import_reason_transaction, msg.resId)
        assertEquals(listOf("commit", "boom"), msg.args)
    }

    @Test
    fun unknownCode_fallsBackToGenericWithRawReason() {
        val msg = SkinImportReasons.resolve("something_weird")
        assertEquals(R.string.skin_import_reason_unknown, msg.resId)
        assertEquals(listOf("something_weird"), msg.args)
    }

    @Test
    fun validatorRejections() {
        assertEquals(
            R.string.skin_import_reason_no_color,
            SkinImportReasons.resolve("no_color_scheme").resId,
        )
        assertEquals(
            R.string.skin_import_reason_no_dark,
            SkinImportReasons.resolve("skin_missing_dark_mode").resId,
        )
        val version = SkinImportReasons.resolve("format_version_unsupported:2")
        assertEquals(R.string.skin_import_reason_format_unsupported, version.resId)
        assertEquals(listOf("2"), version.args)
        val mappings = SkinImportReasons.resolve("key_mappings_too_many:600")
        assertEquals(R.string.skin_import_reason_key_mappings, mappings.resId)
        assertEquals(listOf("600"), mappings.args)
        val appVersion = SkinImportReasons.resolve("app_version_too_low:216>215")
        assertEquals(R.string.skin_import_reason_app_too_low, appVersion.resId)
        assertEquals(listOf("216>215"), appVersion.args)
    }

    @Test
    fun manifestCount_keepsCount() {
        val msg = SkinImportReasons.resolve("manifest_count:0")
        assertEquals(R.string.skin_import_reason_manifest_count, msg.resId)
        assertEquals(listOf("0"), msg.args)
    }
}
