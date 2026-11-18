package com.example.continuumstudio.service

import com.example.continuumstudio.data.ServerEndpoint
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for choosing which endpoint a service should connect to.
 *
 * A bug here is silent in the worst way: the background watcher simply never
 * connects, and the symptom is "no notifications", which looks like a hundred
 * other things. This mirrors the selection rule in `EndpointConfig.activeEndpoint`
 * so the rule itself is visible and checked.
 */
class EndpointSelectionTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Mirrors EndpointConfig.activeEndpoint's selection logic. */
    private fun choose(endpointsJson: String?, activeIndexRaw: String?): String? {
        val endpoints: List<ServerEndpoint> = endpointsJson?.let { raw ->
            try {
                json.decodeFromString<List<ServerEndpoint>>(raw)
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()

        val enabled = endpoints.filter { it.enabled && it.url.isNotBlank() }
        if (enabled.isEmpty()) return null
        val index = activeIndexRaw?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        return (enabled.getOrNull(index) ?: enabled.first()).url
    }

    private fun endpoints(vararg eps: ServerEndpoint): String =
        Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ServerEndpoint.serializer()), eps.toList())

    @Test
    fun `nothing configured means no connection attempt`() {
        assertNull(choose(null, null))
        assertNull(choose("[]", "0"))
    }

    @Test
    fun `malformed json is treated as unconfigured rather than crashing`() {
        // A corrupt pref must not take down a foreground service on start.
        assertNull(choose("{not json", "0"))
    }

    @Test
    fun `the active endpoint is used`() {
        val raw = endpoints(
            ServerEndpoint("home", "http://a:8080"),
            ServerEndpoint("car", "http://b:8080"),
        )
        assertEquals("http://b:8080", choose(raw, "1"))
    }

    @Test
    fun `an out of range index falls back to the first enabled endpoint`() {
        val raw = endpoints(ServerEndpoint("home", "http://a:8080"))
        assertEquals("http://a:8080", choose(raw, "7"))
    }

    @Test
    fun `a negative or nonsense index falls back to the first enabled endpoint`() {
        val raw = endpoints(ServerEndpoint("home", "http://a:8080"))
        assertEquals("http://a:8080", choose(raw, "-3"))
        assertEquals("http://a:8080", choose(raw, "banana"))
    }

    @Test
    fun `disabled endpoints are never chosen`() {
        val raw = endpoints(
            ServerEndpoint("off", "http://off:8080", enabled = false),
            ServerEndpoint("on", "http://on:8080"),
        )
        // Index 1 in the *enabled* list is out of range, so it falls back.
        assertEquals("http://on:8080", choose(raw, "1"))
        assertEquals("http://on:8080", choose(raw, "0"))
    }

    @Test
    fun `blank urls are skipped`() {
        val raw = endpoints(
            ServerEndpoint("blank", "   "),
            ServerEndpoint("real", "http://real:8080"),
        )
        assertEquals("http://real:8080", choose(raw, "0"))
    }

    @Test
    fun `an endpoint with only disabled entries yields nothing`() {
        val raw = endpoints(ServerEndpoint("off", "http://off:8080", enabled = false))
        assertNull(choose(raw, "0"))
    }

    @Test
    fun `credentials travel with the chosen endpoint`() {
        val raw = endpoints(
            ServerEndpoint("cf", "http://cf:8080", cfAccessClientId = "id", cfAccessClientSecret = "secret")
        )
        val parsed = json.decodeFromString<List<ServerEndpoint>>(raw).single()
        assertEquals("id", parsed.cfAccessClientId)
        assertEquals("secret", parsed.cfAccessClientSecret)
        assertTrue(parsed.enabled)
    }
}
