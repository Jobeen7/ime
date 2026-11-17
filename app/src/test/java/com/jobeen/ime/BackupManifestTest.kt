// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManifest
import com.jobeen.ime.data.backup.manifestCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份包内清单（D-M1）：采集侧计数与解析侧实际计数逐项核对。
 * 纯 Kotlin 数据比较，不碰 org.json（android.jar 的 org.json 是桩），
 * 可主机单测。JSON 转换（toJsonObject／fromJsonObject）与
 * “无清单旧备份跳过校验”的接线在 BackupManager.parsePayload，
 * 走 Android，不在本地单测覆盖内。
 *
 * 计数表是 Map<String, Any>（WO45 P2-2：旧 ManifestCounts 数据类
 * 已改为 manifestCounts 工厂＋Map，构造、验证、JSON 各一遍遍历）。
 */
class BackupManifestTest {

    private fun fullCounts(): Map<String, Any> = manifestCounts(
        prefsFiles = 12,
        prefsEntries = 345,
        clipboardCount = 100,
        clipboardGroupsCount = 3,
        phrasesCount = 20,
        prefersCount = 500,
        sortingsCount = 10,
        aiProvidersCount = 2,
        aiInstructionsCount = 4,
        aiConversationsCount = 6,
        aiMessagesCount = 30,
        inputStatsPresent = true,
    )

    private fun manifest(counts: Map<String, Any> = fullCounts()) =
        BackupManifest(
            payloadVersion = BackupManifest.CURRENT_PAYLOAD_VERSION,
            counts = counts,
        )

    @Test
    fun matchingCounts_validateEmpty() {
        assertTrue(manifest().validate(fullCounts()).isEmpty())
    }

    @Test
    fun prefsFilesMismatch_reported() {
        val mismatches = manifest().validate(fullCounts() + ("prefsFiles" to 11))
        assertEquals(1, mismatches.size)
        assertTrue(mismatches[0].contains("prefsFiles"))
    }

    @Test
    fun prefsEntriesMismatch_reported() {
        val mismatches = manifest().validate(fullCounts() + ("prefsEntries" to 344))
        assertEquals(1, mismatches.size)
        assertTrue(mismatches[0].contains("prefsEntries"))
    }

    @Test
    fun clipboardCountMismatch_reported() {
        val mismatches = manifest().validate(fullCounts() + ("clipboardCount" to 99))
        assertEquals(1, mismatches.size)
        assertTrue(mismatches[0].contains("clipboard"))
    }

    @Test
    fun groupsPhrasesPrefersSortingsMismatch_eachReported() {
        val actual = fullCounts() + mapOf(
            "clipboardGroupsCount" to 2,
            "phrasesCount" to 19,
            "prefersCount" to 499,
            "sortingsCount" to 9,
        )
        val mismatches = manifest().validate(actual)
        assertEquals(4, mismatches.size)
        assertTrue(mismatches.any { it.startsWith("clipboardGroups:") })
        assertTrue(mismatches.any { it.startsWith("phrases:") })
        assertTrue(mismatches.any { it.contains("candidatePrefers") })
        assertTrue(mismatches.any { it.contains("candidateSortings") })
    }

    @Test
    fun optionalSectionsMismatch_reported() {
        val actual = fullCounts() + mapOf(
            "inputStatsPresent" to false,
        )
        val mismatches = manifest().validate(actual)
        assertEquals(1, mismatches.size)
        assertTrue(mismatches.any { it.contains("inputStats") })
    }

    @Test
    fun aiCountsMismatch_eachReported() {
        val actual = fullCounts() + mapOf(
            "aiProvidersCount" to 1,
            "aiInstructionsCount" to 3,
            "aiConversationsCount" to 5,
            "aiMessagesCount" to 29,
        )
        val mismatches = manifest().validate(actual)
        assertEquals(4, mismatches.size)
        assertTrue(mismatches.any { it.contains("ai_providers") })
        assertTrue(mismatches.any { it.contains("ai_instructions") })
        assertTrue(mismatches.any { it.contains("ai_conversations") })
        assertTrue(mismatches.any { it.contains("ai_messages") })
    }

    @Test
    fun legacy10KeyManifest_validatesAgainst12KeyActual() {
        // 旧清单只有 10 键（含已删的皮肤键）：validate 只核对清单里
        // 有的键，旧备份不因新键缺失被判不符（L-32 兼容性）。旧清单
        // 形状直接手写（manifestCounts 恒出 12 键，造不出旧形状）。
        val legacy = mapOf<String, Any>(
            "prefsFiles" to 12,
            "prefsEntries" to 345,
            "clipboardCount" to 100,
            "clipboardGroupsCount" to 3,
            "phrasesCount" to 20,
            "prefersCount" to 500,
            "sortingsCount" to 10,
            "inputStatsPresent" to true,
            "skinLibraryPresent" to true,
            "skinLibraryFiles" to 42,
        )
        assertEquals(10, legacy.size)
        assertTrue(manifest(legacy).validate(fullCounts()).isEmpty())
    }

    @Test
    fun emptyBackup_allZero_validateEmpty() {
        val empty = manifestCounts(
            prefsFiles = 0,
            prefsEntries = 0,
            clipboardCount = 0,
            clipboardGroupsCount = 0,
            phrasesCount = 0,
            prefersCount = 0,
            sortingsCount = 0,
            inputStatsPresent = false,
        )
        assertTrue(manifest(empty).validate(empty).isEmpty())
    }
}
