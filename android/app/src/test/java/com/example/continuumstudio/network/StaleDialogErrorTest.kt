package com.example.continuumstudio.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for classifying daemon answer errors.
 *
 * The distinction matters because the daemon answers HTTP 200 for logical
 * failures, so the status code cannot tell "the network hiccuped" from "this
 * dialog is already gone". Getting it wrong in one direction means pointless
 * retries; in the other it means telling the user their answer failed when it
 * was simply too late.
 *
 * Every string here is taken from dialog/src/web.rs, verified against the live
 * daemon with `curl -X POST /api/answer`.
 */
class StaleDialogErrorTest {

    @Test
    fun `the three real stale-dialog messages are recognised`() {
        // All three appear in the daemon source.
        assertTrue("No active dialog".isStaleDialogError())
        assertTrue("Dialog not found or not active".isStaleDialogError())
        assertTrue("Dialog not found or already responded".isStaleDialogError())
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue("DIALOG NOT FOUND".isStaleDialogError())
        assertTrue("No Active Dialog".isStaleDialogError())
    }

    @Test
    fun `transient failures are not mistaken for stale dialogs`() {
        // These must still be retried, so they must not match.
        assertFalse("connection reset".isStaleDialogError())
        assertFalse("timeout".isStaleDialogError())
        assertFalse("unreadable response".isStaleDialogError())
        assertFalse("empty response".isStaleDialogError())
        assertFalse("HTTP 502".isStaleDialogError())
    }

    @Test
    fun `an empty message is not stale`() {
        assertFalse("".isStaleDialogError())
    }

    @Test
    fun `a phrase merely containing the word found is not enough`() {
        // Guard against a classifier so loose it swallows real errors.
        assertFalse("server found a problem".isStaleDialogError())
    }
}
