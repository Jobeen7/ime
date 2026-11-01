package com.jobeen.ime.skin.script

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delegation-trap guard (workspace AGENTS.md, 2026-10-19 lesson): every
 * [SkinScriptRegistry] method must be explicitly declared on the store
 * class, so a future `object : SkinScriptRegistry by impl` delegate (or
 * any wrapper) cannot silently lose a forwarder under incremental builds.
 * Today the store implements the interface directly, so this pins that
 * the declaration set stays complete if the shape ever changes.
 */
class SkinScriptRegistryContractTest {

    @Test
    fun store_declaresEveryRegistryMethod() {
        val ifaceMethods = SkinScriptRegistry::class.java.declaredMethods
            .filter { !it.isSynthetic }
            .map { it.name to it.parameterTypes.toList() }
            .toSet()
        val storeMethods = SkinScriptStore::class.java.declaredMethods
            .filter { !it.isSynthetic }
            .map { it.name to it.parameterTypes.toList() }
            .toSet()
        val missing = ifaceMethods - storeMethods
        assertTrue("store must declare every registry method, missing: $missing", missing.isEmpty())
    }
}
