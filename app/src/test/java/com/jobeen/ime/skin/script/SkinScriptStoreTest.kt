package com.jobeen.ime.skin.script

import com.jobeen.ime.base.speech.cloud.PluginContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** App-level script store: list/add/remove/enable, validation, persistence. */
class SkinScriptStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SkinScriptStore(tmp.root)

    @Test
    fun add_thenListAndGet_roundTrip() {
        val store = store()
        val result = store.add("demo.greet", "Greet", "function onEvent(ctx) { return 'hi'; }")
        assertTrue(result is SkinScriptAddResult.Added)
        assertEquals(listOf("demo.greet"), store.list().map { it.id })
        val got = store.get("demo.greet")
        assertEquals("Greet", got?.meta?.name)
        assertTrue(got?.meta?.enabled == true)
        assertTrue(got?.source?.contains("onEvent") == true)
    }

    @Test
    fun list_survivesNewStoreInstance() {
        store().add("a.one", "One", "function onEvent(ctx) { return '1'; }")
        val reopened = store()
        assertEquals(listOf("a.one"), reopened.list().map { it.id })
        assertEquals("One", reopened.get("a.one")?.meta?.name)
    }

    @Test
    fun add_duplicateId_rejectedAndOriginalKept() {
        val store = store()
        store.add("dup", "First", "function onEvent(ctx) { return '1'; }")
        assertTrue(store.add("dup", "Second", "function onEvent(ctx) { return '2'; }") is SkinScriptAddResult.AlreadyExists)
        assertEquals("First", store.get("dup")?.meta?.name)
    }

    @Test
    fun add_invalidIds_rejected() {
        val store = store()
        for (bad in listOf("", ".", "..", "../escape", "a/b", "a b", "a!b")) {
            assertTrue(
                "id must be rejected: $bad",
                store.add(bad, "N", "function onEvent(ctx) { return 'x'; }") is SkinScriptAddResult.InvalidId,
            )
        }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun add_invalidNameAndBlankSource_rejected() {
        val store = store()
        assertTrue(store.add("ok.id", "   ", "function onEvent(ctx) {}") is SkinScriptAddResult.InvalidName)
        assertTrue(store.add("ok.id", "Name", "   ") is SkinScriptAddResult.EmptySource)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun add_oversizeSource_rejected() {
        val store = store()
        val big = "x".repeat(PluginContract.MAX_SCRIPT_BYTES + 1)
        assertTrue(store.add("big", "Big", big) is SkinScriptAddResult.TooLarge)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun setEnabled_togglesAndPersists() {
        val store = store()
        store.add("t.one", "T", "function onEvent(ctx) { return 'x'; }")
        assertTrue(store.setEnabled("t.one", false))
        assertFalse(store.get("t.one")?.meta?.enabled == true)
        assertFalse(store().get("t.one")?.meta?.enabled == true)
        assertTrue(store.setEnabled("t.one", true))
        assertFalse(store.setEnabled("missing", false))
    }

    @Test
    fun remove_deletesAndUnknownReturnsFalse() {
        val store = store()
        store.add("r.one", "R", "function onEvent(ctx) { return 'x'; }")
        assertTrue(store.remove("r.one"))
        assertNull(store.get("r.one"))
        assertTrue(store.list().isEmpty())
        assertFalse(store.remove("r.one"))
        assertFalse(store.remove("../escape"))
    }

    @Test
    fun list_sortedById() {
        val store = store()
        store.add("b.two", "B", "function onEvent(ctx) { return 'b'; }")
        store.add("a.one", "A", "function onEvent(ctx) { return 'a'; }")
        assertEquals(listOf("a.one", "b.two"), store.list().map { it.id })
    }

    @Test
    fun ids_validation() {
        assertTrue(SkinScriptIds.isValid("abc-1.2_x"))
        assertFalse(SkinScriptIds.isValid(".."))
        assertFalse(SkinScriptIds.isValid("a/b"))
    }
}
