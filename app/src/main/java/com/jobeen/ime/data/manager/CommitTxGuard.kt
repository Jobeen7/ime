// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 候选提交恰好一次守卫（WO38 E1-1）：纯内存幂等键登记，不含 Android
 * 依赖、不碰数据库，可单测。
 *
 * 解决的问题：一次选词点按在三处可能被重复记账——
 * 1. 提交：UI 双击／dispatchAction 补投使同一选词走两次
 *    [CandidatePreferCache.recordClick]（每次无条件 +1）；
 * 2. 撤销：撤销已上屏词后偏好计数不回退（现行口径：撤销不减
 *    计数，见下“接线差距”）；
 * 3. 同步重试：偏好表经 .jbk 备份还原合并（BackupManager 的
 *    mergePrefersForRestore 按 text 去重、当前行优先——重放
 *    还原不双计），而 Rime 原生用户词典走 WebDavSync 的
 *    putWithRereadRetry（全量重导出＋PUT，内容幂等、PUT 只
 *    发一次，见 WebDavSyncTest）——两路各有幂等口径，缺的是
 *    提交入口的一道统一幂等键。
 *
 * 接线（精确差距，均在 WO36 施工中的红线文件内，本文件只提供
 * 守卫、此处不接线）：
 * - 提交：RimeEngine.kt:1140-1156，selectCandidateInternal 内
 *   preferDbScope.launch { CandidatePreferCache.recordClick(...) }
 *   之前加：val txKey = CommitTxGuard.key(candidate.text,
 *   compositionCached.preedit.orEmpty(), schemaCached.schemaId,
 *   predictionFieldGen.get())；tryClaim(txKey) 为 false 即
 *   return@launch（跳过这次 recordClick）。守卫实例应与
 *   CandidatePreferCache 同级进程单例。
 * - 撤销：RimeEngine.kt:1269 undo → Undo 动作处理（约
 *   L1400-1490）目前无已提交键账本，撤销时不知道释放哪把键；
 *   需在提交落库成功后把 txKey 记入一笔“已提交”账本（键 →
 *   落库与否），撤销命中时调 release(txKey)。仅 release 还
 *   不够：已落库的 +1 仍在表里，完整补偿还需一条递减通道
 *   （CandidatePreferDao 无 decrement、CandidatePreferCache
 *   无反向落点，两处皆红线），故撤销补偿整体记为差距。
 * - 还原／裁剪归属过期：BackupManager.kt:1813
 *   CandidatePreferCache.invalidate() 处应同调
 *   guard.invalidateAll()——整表被替换后，旧表名下的在途重
 *   试键一律作废，不得再落到新表上。BackupManager.kt 亦在
 *   WO36 修改中，本批不碰。
 *
 * 键口径：schemaId + fieldGen + preedit（组字码）+ text。
 * 同一词在不同组字／不同输入框是两次独立提交（键不同，各计
 * 一次）；同一点击被重复投递时四项全同（键相同，只计一次）。
 * 键只做相等比较、不做解析，text 内含分隔符不影响正确性。
 *
 * 有界性：TTL 过期键在每次操作时清掉；超上限时淘汰最早登记
 * 的键。TTL 与上限只防内存无界增长，不参与正确性——过期后
 * 的重复投递会被再次放行（与 CandidatePreferCache 的代次
 * 校验同口径：过期即归属过期）。
 */
class CommitTxGuard(
    private val maxKeys: Int = DEFAULT_MAX_KEYS,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxKeys > 0) { "maxKeys must be positive" }
        require(ttlMs > 0) { "ttlMs must be positive" }
    }

    /** 键 → 首次登记时刻（插入序＝登记序，超限淘汰取最旧者）。 */
    private val claims = LinkedHashMap<String, Long>()

    /**
     * 认领一次提交：首次认领返回 true（调用方执行落库）；
     * TTL 内重复认领同一键返回 false（调用方跳过本次落库）。
     */
    @Synchronized
    fun tryClaim(key: String): Boolean {
        val now = nowMs()
        evictExpiredLocked(now)
        if (claims.containsKey(key)) return false
        claims[key] = now
        while (claims.size > maxKeys) {
            val eldest = claims.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
        return true
    }

    /** 撤销补偿：释放键（之后同一键的提交可再次认领）。未知键返回 false。 */
    @Synchronized
    fun release(key: String): Boolean = claims.remove(key) != null

    /** TTL 内是否已认领（只读查询，过期键视作未认领）。 */
    @Synchronized
    fun isClaimed(key: String): Boolean {
        evictExpiredLocked(nowMs())
        return claims.containsKey(key)
    }

    /** 归属过期：整表替换／裁剪后，全部在途键一律作废。 */
    @Synchronized
    fun invalidateAll() {
        claims.clear()
    }

    /** 仅测试／诊断：当前登记键数。 */
    internal fun claimedCount(): Int = synchronized(this) { claims.size }

    private fun evictExpiredLocked(now: Long) {
        claims.entries.removeIf { (_, claimedAt) -> now - claimedAt >= ttlMs }
    }

    companion object {
        const val DEFAULT_MAX_KEYS = 1024
        const val DEFAULT_TTL_MS = 60_000L

        /** 幂等键：同一点击重复投递时四项全同；任一不同即独立提交。 */
        fun key(text: String, preedit: String, schemaId: String, fieldGen: Long): String =
            "$schemaId|$fieldGen|$preedit|$text"
    }
}
