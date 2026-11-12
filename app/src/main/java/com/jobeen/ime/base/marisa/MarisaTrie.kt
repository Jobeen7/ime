package com.jobeen.ime.base.marisa

private object MarisaJNI {
    init {
        System.loadLibrary("marisa_jni")
    }

    external fun create(): Long
    external fun destroy(ptr: Long)
    external fun clear(ptr: Long)
    external fun build(ptr: Long, keys: Array<String>, weights: FloatArray?, configFlags: Int)
    external fun mmap(ptr: Long, filename: String)
    external fun load(ptr: Long, filename: String)
    external fun save(ptr: Long, filename: String)
    external fun lookup(ptr: Long, query: String): Int
    external fun commonPrefixSearch(ptr: Long, query: String): Array<Any>?
    external fun commonPrefixSearchBytes(ptr: Long, query: ByteArray): Array<ByteArray>?
    external fun predictiveSearchBytes(ptr: Long, query: ByteArray, limit: Int): Array<ByteArray>?
    external fun lookupBytes(ptr: Long, query: ByteArray): ByteArray?
    external fun predictiveSearch(ptr: Long, query: String, limit: Int): Array<Any>?
    external fun reverseLookup(ptr: Long, keyId: Int): Int
    external fun reverseLookupKey(ptr: Long, keyId: Int): String?
    external fun numKeys(ptr: Long): Int
    external fun numTries(ptr: Long): Int
    external fun numNodes(ptr: Long): Int
    external fun empty(ptr: Long): Boolean
    external fun size(ptr: Long): Long
    external fun totalSize(ptr: Long): Long
    external fun ioSize(ptr: Long): Long
    external fun dumpKeys(ptr: Long, limit: Int): Array<ByteArray>?
}

class MarisaTrie {

    private var nativePtr: Long = 0

    /**
     * native 指针锁（B-L3）：create／destroy 与全部 native 调用共用
     * 同一把锁——此前 create／destroy 只以 nativePtr != 0L 检查后
     * 赋值、无任何同步，并发 destroy 可两次释放同一指针；其余方法
     * 在 require 与 native 调用之间同样可能撞上 destroy。用同一把
     * 锁罩住“取指针＋调 native”，destroy 期间任何调用都在锁外等，
     * 等到锁时指针已是 0、require 拦下，不再调已释放的指针。
     */
    private val ptrLock = Any()

    fun isEmpty(): Boolean = synchronized(ptrLock) { nativePtr == 0L }

    fun create() {
        synchronized(ptrLock) {
            if (nativePtr == 0L) {
                nativePtr = MarisaJNI.create()
            }
        }
    }

    fun destroy() {
        synchronized(ptrLock) {
            if (nativePtr != 0L) {
                MarisaJNI.destroy(nativePtr)
                nativePtr = 0
            }
        }
    }

    /** 锁内取指针并调 native：指针非 0 才调，destroy 无法插进中间。 */
    private inline fun <T> withPtr(block: (Long) -> T): T = synchronized(ptrLock) {
        val ptr = nativePtr
        require(ptr != 0L) { "Trie not created" }
        block(ptr)
    }

    fun clear() {
        withPtr { MarisaJNI.clear(it) }
    }

    fun build(keys: Array<String>, weights: FloatArray? = null, configFlags: Int = 0) {
        withPtr { MarisaJNI.build(it, keys, weights, configFlags) }
    }

    fun mmap(filename: String) {
        withPtr { MarisaJNI.mmap(it, filename) }
    }

    fun load(filename: String) {
        withPtr { MarisaJNI.load(it, filename) }
    }

    fun save(filename: String) {
        withPtr { MarisaJNI.save(it, filename) }
    }

    fun lookup(query: String): Int {
        return withPtr { MarisaJNI.lookup(it, query) }
    }

    fun commonPrefixSearch(query: String): List<MatchResult> {
        return parseSearchResults(withPtr { MarisaJNI.commonPrefixSearch(it, query) })
    }

    fun commonPrefixSearchBytes(query: ByteArray): List<ByteArray> {
        return withPtr { MarisaJNI.commonPrefixSearchBytes(it, query) }?.toList() ?: emptyList()
    }

    fun predictiveSearchBytes(query: ByteArray, limit: Int = 0): List<ByteArray> {
        return withPtr { MarisaJNI.predictiveSearchBytes(it, query, limit) }?.toList() ?: emptyList()
    }

    fun lookupBytes(query: ByteArray): ByteArray? {
        return withPtr { MarisaJNI.lookupBytes(it, query) }
    }

    fun dumpKeys(limit: Int = 10): List<ByteArray> {
        return withPtr { MarisaJNI.dumpKeys(it, limit) }?.toList() ?: emptyList()
    }

    fun predictiveSearch(query: String, limit: Int = 0): List<MatchResult> {
        return parseSearchResults(withPtr { MarisaJNI.predictiveSearch(it, query, limit) })
    }

    @Throws(IllegalArgumentException::class)
    fun reverseLookup(keyId: Int): String {
        return withPtr { MarisaJNI.reverseLookupKey(it, keyId) }
            ?: throw IllegalArgumentException("Key ID $keyId not found")
    }

    val numKeys: Int
        get() = withPtr { MarisaJNI.numKeys(it) }

    val numTries: Int
        get() = withPtr { MarisaJNI.numTries(it) }

    val numNodes: Int
        get() = withPtr { MarisaJNI.numNodes(it) }

    val empty: Boolean
        get() = withPtr { MarisaJNI.empty(it) }

    val size: Long
        get() = withPtr { MarisaJNI.size(it) }

    val totalSize: Long
        get() = withPtr { MarisaJNI.totalSize(it) }

    val ioSize: Long
        get() = withPtr { MarisaJNI.ioSize(it) }

    protected fun finalize() {
        destroy()
    }

    /**
     * 解析 native 返回的 [key, id, key, id, …] 数组（internal 供单测）。
     * 不再假设数组形态合法（B-L3）：长度须为偶数、偶位须为 String、
     * 奇位须为可解析为 Int 的 String，否则抛 IllegalArgumentException，
     * 不走到 ArrayIndexOutOfBounds／ClassCast／NumberFormat 的
     * 原生崩溃形态。
     */
    internal fun parseSearchResults(arr: Array<Any>?): List<MatchResult> {
        if (arr == null) return emptyList()
        require(arr.size % 2 == 0) {
            "Marisa search result has odd length: ${arr.size}"
        }
        val results = ArrayList<MatchResult>(arr.size / 2)
        var i = 0
        while (i < arr.size) {
            val key = arr[i] as? String
                ?: throw IllegalArgumentException("Marisa search result [$i] is not a String")
            val idText = arr[i + 1] as? String
                ?: throw IllegalArgumentException("Marisa search result [${i + 1}] is not a String")
            val id = idText.toIntOrNull()
                ?: throw IllegalArgumentException("Marisa search result id is not an integer: $idText")
            results.add(MatchResult(key, id))
            i += 2
        }
        return results
    }

    data class MatchResult(val key: String, val id: Int)
}
