package com.example.continuumstudio.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the daemon's JSON shapes.
 *
 * These exist because a field-name mismatch does not crash anything - it
 * silently yields null forever. That is exactly what happened with history
 * timestamps: the app read `completed_at`, the daemon sends `answered_at`, so
 * History showed no times at all and nothing reported an error.
 *
 * The payloads below are captured verbatim from a live daemon
 * (`curl http://127.0.0.1:8080/api/history`) on 2026-11-17, not hand-written, so
 * a change here means the daemon's contract actually moved.
 */
class DialogContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Verbatim shape from the live daemon, trimmed to one entry. */
    private val historyPayload = """
        {"success":true,"data":[
          {"answered_at":"2026-11-17 22:49:32","cancelled":false,"comment":null,
           "created_at":"2026-11-17 22:41:14","dialog_type":"choice",
           "id":"4367a72e-007f-4d9f-b9d0-141f10d34ed3",
           "prompt":"Some prompt","title":"Some title","selection":"a"}
        ],"error":null}
    """.trimIndent()

    @Test
    fun `history timestamp is parsed from the field the daemon actually sends`() {
        val parsed = json.decodeFromString<ApiResponse<List<HistoryItem>>?>(historyPayload)
        val item = parsed?.data?.firstOrNull()
        assertNotNull("history must parse", item)
        assertEquals("2026-11-17 22:49:32", item!!.answeredAt)
    }

    @Test
    fun `history parses every field the daemon sends`() {
        val item = json.decodeFromString<ApiResponse<List<HistoryItem>>?>(historyPayload)!!.data!!.first()
        assertEquals("4367a72e-007f-4d9f-b9d0-141f10d34ed3", item.id)
        assertEquals("Some title", item.title)
        assertEquals("choice", item.dialogType)
        assertEquals("2026-11-17 22:41:14", item.createdAt)
        assertEquals(false, item.cancelled)
    }

    @Test
    fun `history survives unknown fields without throwing`() {
        // The daemon will grow fields; that must not break history.
        val withExtra = """
            {"success":true,"data":[
              {"id":"x","title":"t","prompt":"p","dialog_type":"text",
               "brand_new_field":123,"another":{"nested":true}}
            ],"error":null}
        """.trimIndent()
        val item = json.decodeFromString<ApiResponse<List<HistoryItem>>?>(withExtra)!!.data!!.first()
        assertEquals("x", item.id)
    }

    @Test
    fun `optional fields absent from the daemon become null not defaults that lie`() {
        val item = json.decodeFromString<ApiResponse<List<HistoryItem>>?>(historyPayload)!!.data!!.first()
        // The daemon does not send these; they must be null rather than a
        // plausible-looking default that hides the absence.
        assertNull(item.comment)
        assertNull(item.options)
    }

    @Test
    fun `status payload parses`() {
        val status = """
            {"success":true,"data":{"has_active":false,"hold_mode":true,
             "queue_length":0,"toast_count":0},"error":null}
        """.trimIndent()
        val parsed = json.decodeFromString<ApiResponse<StatusResponse>>(status)
        assertTrue(parsed.success)
        assertEquals(0, parsed.data?.queueLength)
        assertEquals(true, parsed.data?.holdMode)
    }

    @Test
    fun `no active dialog is reported as an unsuccessful api response not an error`() {
        // The daemon answers 200 with success=false; the app must treat that as
        // "nothing pending", not as a failure to show.
        val none = """{"success":false,"data":null,"error":"No active dialog"}"""
        val parsed = json.decodeFromString<ApiResponse<DialogDetails>>(none)
        assertEquals(false, parsed.success)
        assertNull(parsed.data)
        assertEquals("No active dialog", parsed.error)
    }

    // --- WebSocket frames ------------------------------------------------

    /**
     * The daemon serialises its update enum with `#[serde(tag = "type")]`, so
     * every frame is a flat object with a `type` discriminator. These fixtures
     * mirror `DialogUpdate` in dialog/src/web.rs.
     */
    private val wsJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `initial frame matches the daemon's actual handshake`() {
        // Captured verbatim: the first frame after the upgrade.
        val frame = """{"active_id":null,"has_active":false,"hold_mode":true,
                        "queue_count":0,"type":"initial"}"""
        val parsed = wsJson.decodeFromString<ServerMessage>(frame)
        assertTrue(parsed is ServerMessage.Initial)
        parsed as ServerMessage.Initial
        assertEquals(false, parsed.hasActive)
        assertEquals(0, parsed.queueCount)
        assertEquals(true, parsed.holdMode)
    }

    @Test
    fun `NewDialog frame deserialises with the daemon's field names`() {
        val frame = """
            {"type":"NewDialog","id":"abc","title":"Deploy?",
             "prompt":"Ship it?","dialog_type":"choice","timeout_ms":60000}
        """.trimIndent()
        val parsed = wsJson.decodeFromString<ServerMessage>(frame)
        assertTrue(parsed is ServerMessage.NewDialog)
        parsed as ServerMessage.NewDialog
        assertEquals("abc", parsed.id)
        assertEquals("choice", parsed.dialogType)
        assertEquals(60000, parsed.timeoutMs)
    }

    @Test
    fun `NewDialog tolerates the optional agent field`() {
        val frame = """
            {"type":"NewDialog","id":"abc","title":"t","prompt":"p",
             "dialog_type":"text","agent":{"name":"orchestrator"}}
        """.trimIndent()
        val parsed = wsJson.decodeFromString<ServerMessage>(frame)
        assertTrue(parsed is ServerMessage.NewDialog)
    }

    @Test
    fun `DialogCompleted frame deserialises`() {
        val frame = """{"type":"DialogCompleted","id":"abc","selection":"a","cancelled":false}"""
        val parsed = wsJson.decodeFromString<ServerMessage>(frame)
        assertTrue(parsed is ServerMessage.DialogCompleted)
        parsed as ServerMessage.DialogCompleted
        assertEquals("abc", parsed.id)
        assertEquals(false, parsed.cancelled)
    }

    @Test
    fun `QueueUpdate and HoldModeChanged deserialise`() {
        val q = wsJson.decodeFromString<ServerMessage>(
            """{"type":"QueueUpdate","active_id":null,"queue_count":3}"""
        )
        assertTrue(q is ServerMessage.QueueUpdate)
        assertEquals(3, (q as ServerMessage.QueueUpdate).queueCount)

        val h = wsJson.decodeFromString<ServerMessage>(
            """{"type":"HoldModeChanged","enabled":true}"""
        )
        assertTrue(h is ServerMessage.HoldModeChanged)
        assertEquals(true, (h as ServerMessage.HoldModeChanged).enabled)
    }
}
