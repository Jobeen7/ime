// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.backup

import org.json.JSONObject

/**
 * 备份包内清单（D-M1，fcitx `rime-profiles.json` 清单 v2 形状）：
 * 备份包自带一份各源计数／存在标记，恢复前逐项核对，缺失／错位
 * 时恢复前报错。清单本身在加密载荷内，GCM 已防篡改；清单防的
 * 是采集与解析之间的内部不一致（某段采集时漏写、解析时漏读），
 * 不是外部篡改。
 *
 * 版本：清单只从 payload v2 起写；旧备份无清单字段，解析时跳过
 * 校验直接兼容（见 BackupManager.parsePayload）。清单字段一律
 * 平铺、无嵌套。
 *
 * JSON 转换（toJsonObject／fromJsonObject）走 org.json，本地 JVM
 * 单测跑不通（android.jar 的 org.json 是桩）；可测的是纯逻辑
 * [validate]，见 BackupManifestTest。
 */
internal data class BackupManifest(
    val payloadVersion: Int,
    val counts: ManifestCounts,
) {
    /** 逐项核对清单与实际计数，返回不符项描述（空 = 一致）。 */
    fun validate(actual: ManifestCounts): List<String> {
        val expected = counts
        val mismatches = ArrayList<String>()
        if (expected.prefsFiles != actual.prefsFiles) {
            mismatches += "prefsFiles: manifest=${expected.prefsFiles} actual=${actual.prefsFiles}"
        }
        if (expected.prefsEntries != actual.prefsEntries) {
            mismatches += "prefsEntries: manifest=${expected.prefsEntries} actual=${actual.prefsEntries}"
        }
        if (expected.clipboardCount != actual.clipboardCount) {
            mismatches += "clipboard: manifest=${expected.clipboardCount} actual=${actual.clipboardCount}"
        }
        if (expected.clipboardGroupsCount != actual.clipboardGroupsCount) {
            mismatches += "clipboardGroups: manifest=${expected.clipboardGroupsCount} actual=${actual.clipboardGroupsCount}"
        }
        if (expected.phrasesCount != actual.phrasesCount) {
            mismatches += "phrases: manifest=${expected.phrasesCount} actual=${actual.phrasesCount}"
        }
        if (expected.prefersCount != actual.prefersCount) {
            mismatches += "candidatePrefers: manifest=${expected.prefersCount} actual=${actual.prefersCount}"
        }
        if (expected.sortingsCount != actual.sortingsCount) {
            mismatches += "candidateSortings: manifest=${expected.sortingsCount} actual=${actual.sortingsCount}"
        }
        if (expected.inputStatsPresent != actual.inputStatsPresent) {
            mismatches += "inputStats: manifest=${expected.inputStatsPresent} actual=${actual.inputStatsPresent}"
        }
        if (expected.skinLibraryPresent != actual.skinLibraryPresent) {
            mismatches += "skinLibrary: manifest=${expected.skinLibraryPresent} actual=${actual.skinLibraryPresent}"
        }
        if (expected.skinLibraryFiles != actual.skinLibraryFiles) {
            mismatches += "skinLibraryFiles: manifest=${expected.skinLibraryFiles} actual=${actual.skinLibraryFiles}"
        }
        if (expected.skinScriptsPresent != actual.skinScriptsPresent) {
            mismatches += "skinScripts: manifest=${expected.skinScriptsPresent} actual=${actual.skinScriptsPresent}"
        }
        if (expected.skinScriptsFiles != actual.skinScriptsFiles) {
            mismatches += "skinScriptsFiles: manifest=${expected.skinScriptsFiles} actual=${actual.skinScriptsFiles}"
        }
        return mismatches
    }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("payloadVersion", payloadVersion)
        .put("prefsFiles", counts.prefsFiles)
        .put("prefsEntries", counts.prefsEntries)
        .put("clipboardCount", counts.clipboardCount)
        .put("clipboardGroupsCount", counts.clipboardGroupsCount)
        .put("phrasesCount", counts.phrasesCount)
        .put("prefersCount", counts.prefersCount)
        .put("sortingsCount", counts.sortingsCount)
        .put("inputStatsPresent", counts.inputStatsPresent)
        .put("skinLibraryPresent", counts.skinLibraryPresent)
        .put("skinLibraryFiles", counts.skinLibraryFiles)
        .put("skinScriptsPresent", counts.skinScriptsPresent)
        .put("skinScriptsFiles", counts.skinScriptsFiles)

    companion object {
        const val CURRENT_PAYLOAD_VERSION = 2

        /**
         * 解析清单 JSON：字段缺失或类型不符返回 null（调用方按
         * “清单损坏”判整包失败）。payloadVersion 由调用方核对。
         */
        fun fromJsonObject(obj: JSONObject): BackupManifest? = try {
            val counts = ManifestCounts(
                prefsFiles = obj.getInt("prefsFiles"),
                prefsEntries = obj.getInt("prefsEntries"),
                clipboardCount = obj.getInt("clipboardCount"),
                clipboardGroupsCount = obj.getInt("clipboardGroupsCount"),
                phrasesCount = obj.getInt("phrasesCount"),
                prefersCount = obj.getInt("prefersCount"),
                sortingsCount = obj.getInt("sortingsCount"),
                inputStatsPresent = obj.getBoolean("inputStatsPresent"),
                skinLibraryPresent = obj.getBoolean("skinLibraryPresent"),
                skinLibraryFiles = obj.getInt("skinLibraryFiles"),
                skinScriptsPresent = obj.getBoolean("skinScriptsPresent"),
                skinScriptsFiles = obj.getInt("skinScriptsFiles"),
            )
            BackupManifest(payloadVersion = obj.getInt("payloadVersion"), counts = counts)
        } catch (e: Exception) {
            null
        }
    }
}

/** 清单计数字段（采集侧填期望值，解析侧填实际值，逐项核对）。 */
internal data class ManifestCounts(
    val prefsFiles: Int,
    val prefsEntries: Int,
    val clipboardCount: Int,
    val clipboardGroupsCount: Int,
    val phrasesCount: Int,
    val prefersCount: Int,
    val sortingsCount: Int,
    val inputStatsPresent: Boolean,
    val skinLibraryPresent: Boolean,
    val skinLibraryFiles: Int,
    val skinScriptsPresent: Boolean,
    val skinScriptsFiles: Int,
)
