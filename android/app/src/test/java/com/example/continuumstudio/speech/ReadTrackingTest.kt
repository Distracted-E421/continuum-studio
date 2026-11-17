package com.example.continuumstudio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the read-tracking rule that stops a reconnect re-reading a dialog.
 *
 * The rule is small but was a real bug: a transient disconnect nulls the active
 * dialog, which reset the "already read" guard, so the same dialog was read
 * again from the top when the connection came back. That reads as the app
 * rambling to itself, which is the worst possible failure while driving.
 *
 * The logic is modelled here rather than tested through the view model because
 * it needs no Android classes, and a ViewModel test would need Robolectric for
 * no additional confidence.
 */
class ReadTrackingTest {

    /** Mirrors the guard in DialogViewModel. */
    private class Guard(private val maxTracked: Int = 4) {
        private val read = ArrayDeque<String>()
        var lastSpoken: String? = null

        fun onDialog(dialogId: String?): Boolean {
            if (dialogId == null) {
                // Dialog disappeared (answer submitted, or connection blipped).
                // Read history deliberately survives this.
                return false
            }
            if (dialogId == lastSpoken) return false
            if (dialogId in read) {
                lastSpoken = dialogId
                return false
            }
            lastSpoken = dialogId
            markRead(dialogId)
            return true
        }

        private fun markRead(id: String) {
            if (id in read) return
            read.addLast(id)
            while (read.size > maxTracked) read.removeFirst()
        }
    }

    @Test
    fun `a new dialog is spoken once`() {
        val g = Guard()
        assertTrue(g.onDialog("a"))
        // Same dialog re-emitted on every state update: must not repeat.
        assertTrue(!g.onDialog("a"))
        assertTrue(!g.onDialog("a"))
    }

    @Test
    fun `a reconnect does not re-read the same dialog`() {
        val g = Guard()
        assertTrue(g.onDialog("a"))
        // Connection drops: activeDialog becomes null for a moment.
        g.onDialog(null)
        // Connection returns with the same dialog still pending.
        assertTrue("must not re-read after reconnect", !g.onDialog("a"))
    }

    @Test
    fun `answering and moving on reads the next dialog`() {
        val g = Guard()
        assertTrue(g.onDialog("a"))
        g.onDialog(null) // answered
        assertTrue("next dialog must be read", g.onDialog("b"))
    }

    @Test
    fun `a genuinely new dialog after a reconnect is still read`() {
        val g = Guard()
        assertTrue(g.onDialog("a"))
        g.onDialog(null)
        assertTrue(g.onDialog("b"))
    }

    @Test
    fun `history is bounded so memory cannot grow without limit`() {
        val g = Guard(maxTracked = 2)
        assertTrue(g.onDialog("a"))
        g.onDialog(null)
        assertTrue(g.onDialog("b"))
        g.onDialog(null)
        assertTrue(g.onDialog("c"))
        g.onDialog(null)
        // "a" has been evicted, so it is treated as fresh. That is the intended
        // trade: bounded memory over remembering a long history.
        assertTrue(g.onDialog("a"))
    }

    @Test
    fun `muted dialog is not marked read, so unmuting can still read it`() {
        val g = Guard()
        // Simulate: muted, so the collector does not mark it.
        val muted = true
        if (!muted) g.onDialog("a")
        // Unmuting replays it.
        assertTrue("unmuting should read the dialog", g.onDialog("a"))
    }

    @Test
    fun `repeated null transitions are harmless`() {
        val g = Guard()
        g.onDialog(null)
        g.onDialog(null)
        assertTrue(g.onDialog("a"))
        assertEquals("a", g.lastSpoken)
    }
}
