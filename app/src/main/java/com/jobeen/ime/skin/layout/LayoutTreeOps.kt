package com.jobeen.ime.skin.layout

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * layout.json 原始树操作（副本入库用）：只动格式已知的引用位置，
 * 其余节点逐一原样（定稿七.4 与 round-trip 口径一致）。
 */
object LayoutTreeOps {

    /**
     * 改写 meta.id 为副本新 id（铁律 3：meta 唯一 id 必须绑定其
     * 入库目录）。meta 缺失/非对象时按空对象补齐 id。
     */
    fun rewriteMetaId(tree: JsonObject, newId: String): JsonObject {
        val meta = tree["meta"] as? JsonObject ?: JsonObject(emptyMap())
        val newMeta = JsonObject(meta + ("id" to JsonPrimitive(newId)))
        return JsonObject(tree + ("meta" to newMeta))
    }
}
