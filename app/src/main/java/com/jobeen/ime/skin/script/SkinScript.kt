package com.jobeen.ime.skin.script

import kotlinx.serialization.Serializable

/**
 * App-level script model.
 *
 * Scripts live at App level, not per skin: a skin references a script by
 * id, and the same script can serve any skin. The store/registry below is
 * the single place scripts are listed, added, removed and enabled; skins
 * never carry their own script copies.
 *
 * Contract-delta note: this is entirely new code — no existing contract
 * changes, no existing interface gains a method, nothing is relocated.
 */
@Serializable
data class SkinScriptMeta(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
)

/** A stored script: its metadata plus its source text. */
data class SkinScript(
    val meta: SkinScriptMeta,
    val source: String,
)

/** Result of [SkinScriptRegistry.add]: success carries the stored metadata. */
sealed interface SkinScriptAddResult {
    data class Added(val meta: SkinScriptMeta) : SkinScriptAddResult
    data object InvalidId : SkinScriptAddResult
    data object InvalidName : SkinScriptAddResult
    data object EmptySource : SkinScriptAddResult
    data object TooLarge : SkinScriptAddResult
    data object AlreadyExists : SkinScriptAddResult
    data object WriteFailed : SkinScriptAddResult
}

/**
 * App-level script registry.
 *
 * Implementations are plain file I/O with no threading guarantee: callers
 * coordinate threads (management UI on the main thread for list/toggle,
 * script runs off the main thread — see [SkinScriptRunner]).
 *
 * Delegation-trap guard: any future `object : SkinScriptRegistry by impl`
 * delegate must add an explicit override per method, and
 * SkinScriptRegistryContractTest asserts the delegate declares every
 * interface method (see workspace AGENTS.md, 2026-10-19 lesson).
 */
interface SkinScriptRegistry {
    /** All stored scripts, sorted by id. Never reads script sources. */
    fun list(): List<SkinScriptMeta>

    /** One script with its source, or null when absent or unreadable. */
    fun get(id: String): SkinScript?

    /** Store a new script; an existing id is never overwritten. */
    fun add(id: String, name: String, source: String): SkinScriptAddResult

    /** Delete a script and its stored files; false when it did not exist. */
    fun remove(id: String): Boolean

    /** Enable/disable a script; false when it did not exist. Disabled
     * scripts are never executed (see [SkinScriptRunner.runScript]). */
    fun setEnabled(id: String, enabled: Boolean): Boolean
}

/** Shared id validation for script ids (also used by the store's path guard). */
object SkinScriptIds {
    private val ID_REGEX = Regex("^[A-Za-z0-9._-]{1,64}$")

    /** Clean ids only: no path separators, no "." / ".." escapes. */
    fun isValid(id: String): Boolean {
        if (!ID_REGEX.matches(id)) return false
        if (id == "." || id == "..") return false
        return true
    }
}
