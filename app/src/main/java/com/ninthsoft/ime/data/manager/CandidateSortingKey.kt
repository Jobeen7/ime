package com.ninthsoft.ime.data.manager

/**
 * 候选排序键生成算法。
 *
 * 对候选集合中“所有选项”的身份计算一个**与顺序无关**且唯一的 64 位指纹：
 * 无论用户如何拖拽调整顺序，同一批候选的指纹保持不变，因此可以用它作为
 * 持久化排序记录的稳定主键。
 *
 * 使用非密码学 FNV-1a（64 位）而非 SHA-256：只需保证同一集合指纹稳定、
 * 不同集合基本不冲突，单遍字节运算开销极低。
 */
object CandidateSortingKey {

    private val SEED = 0x9E3779B97F4A7C15uL.toLong()

    fun compute(identities: Collection<String>): String {
        var hash = SEED
        var count = 0

        for (identity in identities) {
            hash += hash64(identity)
            count++
        }

        hash = mix(hash xor count.toLong())
        return "%016x".format(hash)
    }

    private fun hash64(value: String): Long {
        var hash = SEED xor value.length.toLong()
        for (c in value) {
            hash = (hash xor c.code.toLong()) * -0x61C8864680B583EBL
            hash = hash xor (hash ushr 29)
        }
        return mix(hash)
    }

    private fun mix(value: Long): Long {
        var x = value
        x = (x xor (x ushr 30)) * -0x40A7B892E31B1A47L
        x = (x xor (x ushr 27)) * -0x6B2FB644ECC5C6B5L
        return x xor (x ushr 31)
    }
}