package com.jobeen.ime.skin.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 皮肤库磁盘格式（详设 §2.2/§2.4/§2.5，字段冻结）。
 * 内容哈希定义：sha256(原始文件字节)，对 skin.json / layout.json
 * 原文计算、不做规范化（与定稿三.2 的 round-trip 原样保留一致）。
 */

@Serializable
data class LibraryIndex(
    val version: Int = CURRENT_VERSION,
    val skins: List<SkinIndexEntry> = emptyList(),
    val layouts: List<LayoutIndexEntry> = emptyList(),
    val assets: List<AssetIndexEntry> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class SkinIndexEntry(
    val id: String,
    val name: String,
    /** skin.json 内容 sha256 */
    val hash: String,
    val generation: Int,
    /** 相对库根的代次目录，如 skins/<id>/gen-3 */
    val dir: String,
    val sizeBytes: Long,
    val updatedAt: Long,
)

@Serializable
data class LayoutIndexEntry(
    val id: String,
    /** layout.json 内容 sha256 */
    val hash: String,
    val generation: Int,
    val dir: String,
    val sizeBytes: Long,
    val updatedAt: Long,
)

@Serializable
data class AssetIndexEntry(
    val sha256: String,
    val sizeBytes: Long,
    /**
     * 仅缓存：引用计数以启动对账按引用图全量重建为准（定稿七.5），
     * 本数值不得作为删除依据。
     */
    val refcountCache: Int = 0,
)

/** 应用事务两态（详设 §2.4）：pending = 已切换未验证，启动见之必回退。 */
@Serializable
enum class ApplyState {
    @SerialName("pending")
    PENDING,

    @SerialName("verified")
    VERIFIED,
}

/**
 * 生效记录 active.json（详设 §2.4）。skinId 为 null 表示无 v3 皮肤
 * 生效（走现行内置主题路径，行为与 v1.1.14 一致）。
 */
@Serializable
data class ActiveRecord(
    val skinId: String? = null,
    val hash: String? = null,
    val generation: Int = 0,
    val state: ApplyState = ApplyState.VERIFIED,
    val appliedAt: Long = 0L,
)

/** Last-Good 指针 last_good.json（详设 §2.5）：只存指针，代次实体另行保留。 */
@Serializable
data class LastGoodPointer(
    val skinId: String,
    val hash: String,
    val generation: Int,
)

/** 指向某皮肤某一代次的引用（事务与运行态共用）。 */
data class SkinGenRef(
    val skinId: String,
    val hash: String,
    val generation: Int,
)
