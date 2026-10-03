// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import timber.log.Timber

class RimeConfig private constructor(
    private val peer: Long,
) : AutoCloseable {

    // close 必须幂等：旧实现每次 close 都把同一 peer 交给 native 释放，
    // use{} 与手动清理叠加时就是 double-free。关闭后再读写同样是野指针访问，一并拦截。
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun peerOrNull(): Long? = if (closed.get()) null else peer

    fun getInt(key: String): Int? = peerOrNull()?.let { getRimeConfigInt(it, key) }

    fun getString(key: String): String? = peerOrNull()?.let { getRimeConfigString(it, key) }

    fun getBool(key: String): Boolean? = peerOrNull()?.let { getRimeConfigBool(it, key) }

    fun <E : Any> getList(key: String, getAction: RimeConfig.(String) -> E?): List<E> {
        val p = peerOrNull() ?: return emptyList()
        val paths = getRimeConfigListItemPath(p, key)
        val result = ArrayList<E>(paths.size)
        for (path in paths) {
            val value = getAction(this, path)
            if (value == null) {
                Timber.w("Failed to get value on config path '$path'")
                continue
            }
            result.add(value)
        }
        return result
    }

    fun setBool(key: String, value: Boolean) {
        peerOrNull()?.let { setRimeConfigBool(it, key, value) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeRimeConfig(peer)
        }
    }

    companion object {
        fun openConfig(configId: String): RimeConfig =
            RimeConfig(openRimeConfig(configId))

        fun openUserConfig(configId: String): RimeConfig =
            RimeConfig(openRimeUserConfig(configId))

        fun openSchema(schemaId: String): RimeConfig =
            RimeConfig(openRimeSchema(schemaId))

        @JvmStatic
        private external fun openRimeConfig(configId: String): Long

        @JvmStatic
        private external fun openRimeUserConfig(configId: String): Long

        @JvmStatic
        private external fun openRimeSchema(schemaId: String): Long

        @JvmStatic
        private external fun getRimeConfigInt(peer: Long, key: String): Int?

        @JvmStatic
        private external fun getRimeConfigString(peer: Long, key: String): String?

        @JvmStatic
        private external fun getRimeConfigBool(peer: Long, key: String): Boolean?

        @JvmStatic
        private external fun getRimeConfigListItemPath(peer: Long, key: String): Array<String>

        @JvmStatic
        private external fun setRimeConfigBool(peer: Long, key: String, value: Boolean)

        @JvmStatic
        private external fun closeRimeConfig(peer: Long)
    }
}
