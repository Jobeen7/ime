// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManifest
import com.jobeen.ime.data.backup.ManifestCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份包内清单（D-M1）：采集侧计数与解析侧实际计数逐项核对。
 * 纯 Kotlin 数据比较，不碰 org.json（android.jar 的 org.json 是桩），
 * 可主机单测。JSON 转换（toJsonObject／fromJsonObject）与
 * “无清单旧备份跳过校验”的接线在 BackupManager.parsePayload，
 * 走 Android，不在本地单测覆盖内。
 */
class BackupManifestTest {

    private fun fullCounts() = ManifestCounts(
        prefsFiles = 12,
        prefsEntries = 345,
        clipboardCount = 100,
        clipboardGroupsCount = 3,
        phrasesCount = 20,
        prefersCount = 500,
        sortingsCount = 10,
        inputStatsPresent = true,
        skinLibraryPresent = true,
        skinLibraryFiles = 42,
        skinScriptsPresent = true,
        skinScriptsFiles = 5,
    )

    private fun manifest(counts: ManifestCounts = fullCounts()) =
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
        val mismatches = manifest().validate(fullCounts().copy(prefsFiles = 11))
        assertEquals(1, mismatches.size)
        assertTrue(mismatches[0].contains("prefsFiles"))
    }

    @Test
    fun prefsEntriesMismatch_reported() {
        val mismatches = manifest().validate(fullCounts().copy(prefsEntries = 344))
        assertEquals(1, mismatches.size)
        assertTrue(mismatches[0].contains("prefsEntries"))
    }

    @Test
    fun clipboardCountMismatch_reported() {
        val mismatches = manifest().validate(fullCounts().copy(clipboardCount = 99))
        assertEquals(1, mismatches.size)
        assertTrue(mismatches[0].contains("clipboard"))
    }

    @Test
    fun groupsPhrasesPrefersSortingsMismatch_eachReported() {
        val actual = fullCounts().copy(
            clipboardGroupsCount = 2,
            phrasesCount = 19,
            prefersCount = 499,
            sortingsCount = 9,
        )
        val mismatches = manifest().validate(actual)
        assertEquals(4, mismatches.size)
        assertTrue(mismatches.any { it.contains("clipboardGroups") })
        assertTrue(mismatches.any { it.contains("phrases") })
        assertTrue(mismatches.any { it.contains("candidatePrefers") })
        assertTrue(mismatches.any { it.contains("candidateSortings") })
    }

    @Test
    fun optionalSectionsMismatch_reported() {
        val actual = fullCounts().copy(
            inputStatsPresent = false,
            skinLibraryPresent = false,
            skinLibraryFiles = 0,
            skinScriptsPresent = false,
            skinScriptsFiles = 0,
        )
        val mismatches = manifest().validate(actual)
        assertEquals(5, mismatches.size)
        assertTrue(mismatches.any { it.contains("inputStats") })
        assertTrue(mismatches.any { it.contains("skinLibrary:") })
        assertTrue(mismatches.any { it.contains("skinLibraryFiles") })
        assertTrue(mismatches.any { it.contains("skinScripts:") })
        assertTrue(mismatches.any { it.contains("skinScriptsFiles") })
    }

    @Test
    fun emptyBackup_allZero_validateEmpty() {
        val empty = ManifestCounts(
            prefsFiles = 0,
            prefsEntries = 0,
            clipboardCount = 0,
            clipboardGroupsCount = 0,
            phrasesCount = 0,
            prefersCount = 0,
            sortingsCount = 0,
            inputStatsPresent = false,
            skinLibraryPresent = false,
            skinLibraryFiles = 0,
            skinScriptsPresent = false,
            skinScriptsFiles = 0,
        )
        assertTrue(manifest(empty).validate(empty).isEmpty())
    }
}
