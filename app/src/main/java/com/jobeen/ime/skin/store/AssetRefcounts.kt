package com.jobeen.ime.skin.store

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 资产引用图（定稿七.5）：引用来源 = 各保留代次 manifest.json 的
 * contents 中 type=asset 的条目。启动对账全量重建（详设 §2.8.4）与
 * 导入事务更新缓存共用本口径，保证两处计数一致。
 */
internal object AssetRefcounts {
    /** 返回 sha256 → 引用次数。 */
    fun compute(layout: SkinLibraryLayout, index: LibraryIndex): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val genDirs = index.skins.map { layout.fileFromRelative(it.dir) } +
            index.layouts.map { layout.fileFromRelative(it.dir) }
        for (dir in genDirs) {
            val manifest = File(dir, "manifest.json")
            if (!manifest.isFile) continue
            val rootObj = runCatching { Json.parseToJsonElement(manifest.readText()).jsonObject }
                .getOrNull() ?: continue
            val contents = rootObj["contents"] as? JsonArray ?: continue
            for (element in contents) {
                val obj = element as? JsonObject ?: continue
                if (obj["type"]?.jsonPrimitive?.contentOrNull != "asset") continue
                val sha = obj["sha256"]?.jsonPrimitive?.contentOrNull ?: continue
                counts[sha] = (counts[sha] ?: 0) + 1
            }
        }
        return counts
    }
}
