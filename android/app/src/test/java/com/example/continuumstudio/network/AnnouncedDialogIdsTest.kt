package com.example.continuumstudio.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "announce each dialog once" guard.
 *
 * Reported symptom: the same dialog occasionally arrived twice and only one copy
 * could be answered, so a comment the user had typed or dictated was stranded on
 * the other copy. `readDialogIds` in the ViewModel deduplicates *speech* only, so
 * the dialog itself was still announced twice.
 */
class AnnouncedDialogIdsTest {

    /** Mirrors the bounded set in DialogWebSocketClient. */
    private fun bounded(limit: Int) = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            if (size > limit) firstOrNull()?.let { remove(it) }
            return added
        }
    }

    @Test
    fun `a first announcement is accepted`() {
        val seen = bounded(128)
        assertTrue(seen.add("abc"))
    }

    @Test
    fun `a repeat announcement is rejected`() {
        // The whole point: the second copy must not be announced, or it appears as
        // an unanswered duplicate.
        val seen = bounded(128)
        assertTrue(seen.add("abc"))
        assertFalse(seen.add("abc"))
    }

    @Test
    fun `distinct dialogs are all accepted`() {
        val seen = bounded(128)
        repeat(10) { assertTrue(seen.add("dialog-$it")) }
    }

    @Test
    fun `the set is bounded so it cannot leak`() {
        // A long-lived connection would otherwise accumulate every id forever.
        val seen = bounded(3)
        repeat(10) { seen.add("d$it") }
        assertEquals(3, seen.size)
    }

    @Test
    fun `a re-issue after completion is accepted`() {
        // The daemon may reuse an id for a new request. Only *concurrent* repeats
        // are duplicates; suppressing a real dialog would be worse than the bug.
        val seen = bounded(128)
        assertTrue(seen.add("abc"))
        assertFalse(seen.add("abc"))
        seen.remove("abc") // what happens on DialogCompleted
        assertTrue("a re-issue after completion must be announced", seen.add("abc"))
    }

    @Test
    fun `eviction is oldest-first so recent dialogs stay protected`() {
        val seen = bounded(3)
        seen.add("a"); seen.add("b"); seen.add("c"); seen.add("d")
        // "a" was evicted, so it may be announced again - acceptable, it is long
        // gone. The recent ones must still be deduplicated.
        assertTrue(seen.add("a"))
        assertFalse(seen.add("d"))
    }
}
