package com.ninthsoft.ime.base.marisa

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

    fun isEmpty() = nativePtr == 0L

    fun create() {
        if (nativePtr == 0L) {
            nativePtr = MarisaJNI.create()
        }
    }

    fun destroy() {
        if (nativePtr != 0L) {
            MarisaJNI.destroy(nativePtr)
            nativePtr = 0
        }
    }

    fun clear() {
        require(nativePtr != 0L) { "Trie not created" }
        MarisaJNI.clear(nativePtr)
    }

    fun build(keys: Array<String>, weights: FloatArray? = null, configFlags: Int = 0) {
        require(nativePtr != 0L) { "Trie not created" }
        MarisaJNI.build(nativePtr, keys, weights, configFlags)
    }

    fun mmap(filename: String) {
        require(nativePtr != 0L) { "Trie not created" }
        MarisaJNI.mmap(nativePtr, filename)
    }

    fun load(filename: String) {
        require(nativePtr != 0L) { "Trie not created" }
        MarisaJNI.load(nativePtr, filename)
    }

    fun save(filename: String) {
        require(nativePtr != 0L) { "Trie not created" }
        MarisaJNI.save(nativePtr, filename)
    }

    fun lookup(query: String): Int {
        require(nativePtr != 0L) { "Trie not created" }
        return MarisaJNI.lookup(nativePtr, query)
    }

    fun commonPrefixSearch(query: String): List<MatchResult> {
        require(nativePtr != 0L) { "Trie not created" }
        return parseSearchResults(MarisaJNI.commonPrefixSearch(nativePtr, query))
    }

    fun commonPrefixSearchBytes(query: ByteArray): List<ByteArray> {
        require(nativePtr != 0L) { "Trie not created" }
        return MarisaJNI.commonPrefixSearchBytes(nativePtr, query)?.toList() ?: emptyList()
    }

    fun predictiveSearchBytes(query: ByteArray, limit: Int = 0): List<ByteArray> {
        require(nativePtr != 0L) { "Trie not created" }
        return MarisaJNI.predictiveSearchBytes(nativePtr, query, limit)?.toList() ?: emptyList()
    }

    fun lookupBytes(query: ByteArray): ByteArray? {
        require(nativePtr != 0L) { "Trie not created" }
        return MarisaJNI.lookupBytes(nativePtr, query)
    }

    fun dumpKeys(limit: Int = 10): List<ByteArray> {
        require(nativePtr != 0L) { "Trie not created" }
        return MarisaJNI.dumpKeys(nativePtr, limit)?.toList() ?: emptyList()
    }

    fun predictiveSearch(query: String, limit: Int = 0): List<MatchResult> {
        require(nativePtr != 0L) { "Trie not created" }
        return parseSearchResults(MarisaJNI.predictiveSearch(nativePtr, query, limit))
    }

    @Throws(IllegalArgumentException::class)
    fun reverseLookup(keyId: Int): String {
        require(nativePtr != 0L) { "Trie not created" }
        return MarisaJNI.reverseLookupKey(nativePtr, keyId)
            ?: throw IllegalArgumentException("Key ID $keyId not found")
    }

    val numKeys: Int
        get() {
            require(nativePtr != 0L) { "Trie not created" }
            return MarisaJNI.numKeys(nativePtr)
        }

    val numTries: Int
        get() {
            require(nativePtr != 0L) { "Trie not created" }
            return MarisaJNI.numTries(nativePtr)
        }

    val numNodes: Int
        get() {
            require(nativePtr != 0L) { "Trie not created" }
            return MarisaJNI.numNodes(nativePtr)
        }

    val empty: Boolean
        get() {
            require(nativePtr != 0L) { "Trie not created" }
            return MarisaJNI.empty(nativePtr)
        }

    val size: Long
        get() {
            require(nativePtr != 0L) { "Trie not created" }
            return MarisaJNI.size(nativePtr)
        }

    val totalSize: Long
        get() {
            require(nativePtr != 0L) { "Trie not created" }
            return MarisaJNI.totalSize(nativePtr)
        }

    val ioSize: Long
        get() {
            require(nativePtr != 0L) { "Trie not created" }
            return MarisaJNI.ioSize(nativePtr)
        }

    protected fun finalize() {
        destroy()
    }

    private fun parseSearchResults(arr: Array<Any>?): List<MatchResult> {
        if (arr == null) return emptyList()
        val results = mutableListOf<MatchResult>()
        var i = 0
        while (i < arr.size) {
            val key = arr[i] as String
            val id = (arr[i + 1] as String).toInt()
            results.add(MatchResult(key, id))
            i += 2
        }
        return results
    }

    data class MatchResult(val key: String, val id: Int)
}
