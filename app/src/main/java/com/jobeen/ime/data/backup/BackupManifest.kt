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
 * 计数表是 Map<String, Any>（Int 计数＋Boolean 存在标记共用一张
 * 表）：校验按 [MANIFEST_KEYS] 遍历，不再逐字段手抄；JSON 写出
 * 遍历 counts map 本身（手写 map 须恰好 12 键，否则往返不一致）。
 * 加字段需在 [manifestCounts] 与 [MANIFEST_KEYS] 各加一行，
 * Boolean 键另进 BOOLEAN_KEYS、显示名不同另进 [MANIFEST_DISPLAY]。
 *
 * JSON 转换（toJsonObject／fromJsonObject）走 org.json，本地 JVM
 * 单测跑不通（android.jar 的 org.json 是桩）；可测的是纯逻辑
 * [validate]，见 BackupManifestTest。
 */
internal data class BackupManifest(
    val payloadVersion: Int,
    val counts: Map<String, Any>,
) {
    /** 逐项核对清单与实际计数，返回不符项描述（空 = 一致）。 */
    fun validate(actual: Map<String, Any>): List<String> {
        val mismatches = ArrayList<String>()
        for (key in MANIFEST_KEYS) {
            val expected = counts[key]
            val got = actual[key]
            if (expected != got) {
                mismatches += "${MANIFEST_DISPLAY[key] ?: key}: manifest=$expected actual=$got"
            }
        }
        return mismatches
    }

    fun toJsonObject(): JSONObject {
        val obj = JSONObject().put("payloadVersion", payloadVersion)
        for ((key, value) in counts) obj.put(key, value)
        return obj
    }

    companion object {
        const val CURRENT_PAYLOAD_VERSION = 2

        /** 清单字段键（JSON 键＝校验键），顺序即校验顺序。 */
        val MANIFEST_KEYS: List<String> = listOf(
            "prefsFiles", "prefsEntries",
            "clipboardCount", "clipboardGroupsCount",
            "phrasesCount", "prefersCount", "sortingsCount",
            "inputStatsPresent", "skinLibraryPresent", "skinLibraryFiles",
            "skinScriptsPresent", "skinScriptsFiles",
        )

        /** 不符时的显示名（与 JSON 键不同的几项；未列出的即键本身）。 */
        val MANIFEST_DISPLAY: Map<String, String> = mapOf(
            "clipboardCount" to "clipboard",
            "clipboardGroupsCount" to "clipboardGroups",
            "phrasesCount" to "phrases",
            "prefersCount" to "candidatePrefers",
            "sortingsCount" to "candidateSortings",
            "inputStatsPresent" to "inputStats",
            "skinLibraryPresent" to "skinLibrary",
            "skinScriptsPresent" to "skinScripts",
        )

        /** Boolean 存在标记键（其余一律为 Int 计数）。 */
        private val BOOLEAN_KEYS: Set<String> = setOf(
            "inputStatsPresent", "skinLibraryPresent", "skinScriptsPresent",
        )

        /**
         * 解析清单 JSON：字段缺失或类型不符返回 null（调用方按
         * “清单损坏”判整包失败）。payloadVersion 由调用方核对。
         */
        fun fromJsonObject(obj: JSONObject): BackupManifest? = try {
            val counts = LinkedHashMap<String, Any>()
            for (key in MANIFEST_KEYS) {
                counts[key] = if (key in BOOLEAN_KEYS) obj.getBoolean(key) else obj.getInt(key)
            }
            BackupManifest(payloadVersion = obj.getInt("payloadVersion"), counts = counts)
        } catch (e: Exception) {
            null
        }
    }
}

/** 构造清单计数表（命名参数，调用点与旧 ManifestCounts 同形）。 */
internal fun manifestCounts(
    prefsFiles: Int,
    prefsEntries: Int,
    clipboardCount: Int,
    clipboardGroupsCount: Int,
    phrasesCount: Int,
    prefersCount: Int,
    sortingsCount: Int,
    inputStatsPresent: Boolean,
    skinLibraryPresent: Boolean,
    skinLibraryFiles: Int,
    skinScriptsPresent: Boolean,
    skinScriptsFiles: Int,
): Map<String, Any> = linkedMapOf(
    "prefsFiles" to prefsFiles,
    "prefsEntries" to prefsEntries,
    "clipboardCount" to clipboardCount,
    "clipboardGroupsCount" to clipboardGroupsCount,
    "phrasesCount" to phrasesCount,
    "prefersCount" to prefersCount,
    "sortingsCount" to sortingsCount,
    "inputStatsPresent" to inputStatsPresent,
    "skinLibraryPresent" to skinLibraryPresent,
    "skinLibraryFiles" to skinLibraryFiles,
    "skinScriptsPresent" to skinScriptsPresent,
    "skinScriptsFiles" to skinScriptsFiles,
)
