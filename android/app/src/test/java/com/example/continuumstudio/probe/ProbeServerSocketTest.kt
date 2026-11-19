package com.example.continuumstudio.probe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket

/**
 * Exercises the real socket over loopback.
 *
 * Runs in a JVM unit test with no device: the server is plain `java.net`, and the
 * only Android dependency is a `Context` that the handlers do not need for these
 * routes. That is deliberate - a network-reachable component that can only be tested
 * by installing an APK on a phone and driving to a car is not testable in practice.
 */
class ProbeServerSocketTest {

    private var server: ProbeServer? = null
    private val token = "test-token-123"

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun startServer(configuredToken: String? = token): ProbeServer {
        val handler: (ProbeServer.ProbeRequest) -> ProbeServer.ProbeResponse = { req ->
            val route = req.route
            when (route) {
                ProbeRoute.PING -> ProbeServer.ProbeResponse(
                    200,
                    ProbeJson.ping("1.19.56", 11956, tokensRequired = true),
                )
                ProbeRoute.AUDIO -> ProbeServer.ProbeResponse(
                    200,
                    ProbeJson.audio(
                        com.example.continuumstudio.audio.AudioReport(
                            lines = listOf("volume music" to "2/15", "outputs" to "bt-sco(X)"),
                            situation = "test",
                        ),
                        findings = listOf("music volume is very low (2/15)"),
                    ),
                )
                ProbeRoute.STATE -> ProbeServer.ProbeResponse(
                    200,
                    ProbeJson.fields(mapOf("queue" to "3", "activeDialog" to null)),
                )
                ProbeRoute.PROBE_AUDIO -> ProbeServer.ProbeResponse(
                    200,
                    ProbeJson.fields(mapOf("probe completed" to "true")),
                )
                ProbeRoute.DUMPSYS -> {
                    // Only an allowlisted name may be echoed back - the route must
                    // never accept a raw command.
                    val what = req.queryValue("what")
                    val cmd = ProbeCommand.byKey(what)
                    if (cmd == null) {
                        ProbeServer.ProbeResponse(400, ProbeJson.error("unknown_command", "no"))
                    } else {
                        ProbeServer.ProbeResponse(
                            200,
                            ProbeJson.fields(mapOf("command" to cmd.command)),
                        )
                    }
                }
            }
        }
        val s = ProbeServer(
            tokenProvider = { configuredToken },
            readHandler = handler,
            // Collected so a swallowed server-side exception is visible in the
            // assertion message instead of appearing as an empty response.
            log = { logs += it },
        )
        assertTrue("server must bind on loopback", s.start(InetAddress.getLoopbackAddress(), 0))
        server = s
        return s
    }

    /** Server-side messages, for diagnosing a silent connection close. */
    private val logs = mutableListOf<String>()

    /** Minimal HTTP client: one request, read everything, close. */
    private fun call(port: Int, method: String, path: String, token: String? = null): Pair<Int, String> {
        Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
            sock.soTimeout = 5000
            val out = sock.getOutputStream()
            val sb = StringBuilder()
            sb.append("$method $path HTTP/1.1\r\n")
            sb.append("Host: localhost\r\n")
            token?.let { sb.append("X-Probe-Token: $it\r\n") }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()

            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val statusLine = reader.readLine()
            if (statusLine == null) {
                throw AssertionError("connection closed with no response; server log: $logs")
            }
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            // Skip headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val body = reader.readText()
            return status to body
        }
    }

    // --- Tests -----------------------------------------------------------

    @Test
    fun `ping answers without a token`() {
        val s = startServer()
        val (status, body) = call(s.boundPort, "GET", "/probe/ping")
        assertEquals(200, status)
        assertTrue(body.contains("continuum-probe"))
    }

    @Test
    fun `a data route without a token is 401`() {
        val s = startServer()
        val (status, body) = call(s.boundPort, "GET", "/probe/audio")
        assertEquals(401, status)
        assertTrue("must name the missing header: $body", body.contains("X-Probe-Token"))
        // Must not leak any audio data on the refusal path.
        assertTrue("refusal must not include report data: $body", !body.contains("volume music"))
    }

    @Test
    fun `a wrong token is 401`() {
        val s = startServer()
        val (status, _) = call(s.boundPort, "GET", "/probe/audio", token = "wrong")
        assertEquals(401, status)
    }

    @Test
    fun `the right token returns the report`() {
        val s = startServer()
        val (status, body) = call(s.boundPort, "GET", "/probe/audio", token = token)
        assertEquals(200, status)
        assertTrue(body.contains("volume music"))
    }

    @Test
    fun `an unconfigured token yields 503, not 401`() {
        // Distinguishing "not enabled" from "bad credentials" is what stops a caller
        // retrying with a token that does not exist.
        val s = startServer(configuredToken = null)
        val (status, body) = call(s.boundPort, "GET", "/probe/audio", token = "anything")
        assertEquals(503, status)
        assertTrue(body.contains("no_token_configured"))
    }

    @Test
    fun `GET on the probe route is refused, POST works`() {
        // Guards the rule that a stray GET cannot make the phone speak.
        val s = startServer()
        val (getStatus, _) = call(s.boundPort, "GET", "/probe/audio/probe", token = token)
        assertEquals(401, getStatus) // unrouted -> unauthorised, never executed
        val (postStatus, body) = call(s.boundPort, "POST", "/probe/audio/probe", token = token)
        assertEquals(200, postStatus)
        assertTrue(body.contains("probe completed"))
    }

    @Test
    fun `an unknown path does not reveal whether a token is valid`() {
        val s = startServer()
        val (withToken, _) = call(s.boundPort, "GET", "/probe/secret", token = token)
        val (without, _) = call(s.boundPort, "GET", "/probe/secret")
        assertEquals(withToken, without)
        assertEquals(401, withToken)
    }

    @Test
    fun `two requests in sequence both succeed after a stop and start`() {
        // The server must be restartable: the UI toggles it.
        val s = startServer()
        assertEquals(200, call(s.boundPort, "GET", "/probe/ping").first)
        s.stop()
        assertTrue("stop must close the listener", !s.isRunning)
        assertTrue(s.start(InetAddress.getLoopbackAddress(), 0))
        assertNotNull(s.boundPort)
        assertEquals(200, call(s.boundPort, "GET", "/probe/ping").first)
    }
}
