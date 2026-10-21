package com.jobeen.ime.engine

/**
 * 光标前文缓存的快照存储（纯逻辑、无 Android 依赖，可单测）：
 * 内容、读取时刻、读取时所属的预测代次三者打成一个不可变快照，
 * 经单个 @Volatile 引用整体发布——读者要么看到完整的旧快照、
 * 要么看到完整的新快照，不存在旧实现「内容与时间两个独立字段
 * 两次分离存储」的中间态。
 *
 * 代次标签是 R4 的防线：换框推进代次后，旧代次的快照即使时间
 * 未过期也不再命中；发布侧再核一次代次，读取飞行中已换框的在
 * 途结果不得回填（见 [publish]）。
 */
internal class BeforeCursorCache {

    internal data class Snapshot(
        val text: String,
        val capturedAtMs: Long,
        val fieldGen: Long,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /**
     * 新鲜命中才返回快照内容：快照存在、未过 TTL、且代次与当前
     * 代次一致，三者缺一即不命中（返回 null，调用方重新读取）。
     */
    fun freshText(nowMs: Long, currentGen: Long): String? {
        val snap = snapshot ?: return null
        if (snap.capturedAtMs == 0L || nowMs - snap.capturedAtMs > TTL_MS) return null
        if (snap.fieldGen != currentGen) return null
        return snap.text
    }

    /**
     * 发布一次读取结果：仅当发布时点的当前代次仍等于读取时捕获
     * 的代次（[capturedGen]）才写入。读取与提交之间隔着一次调度
     * 切换，其间换框会推进代次并失效缓存；旧框前文若无条件回
     * 填，会把已失效的缓存复活，新框后续读取命中旧框上下文
     * （R4）。代次已变时拒绝发布并返回 false，调用方应整轮作废。
     */
    fun publish(text: String, capturedAtMs: Long, capturedGen: Long, currentGen: Long): Boolean {
        if (currentGen != capturedGen) return false
        snapshot = Snapshot(text, capturedAtMs, capturedGen)
        return true
    }

    /** 主动失效：整体置空，其后任何读取必然未命中，直至下一次成功发布。 */
    fun invalidate() {
        snapshot = null
    }

    companion object {
        const val TTL_MS = 800L
    }
}
