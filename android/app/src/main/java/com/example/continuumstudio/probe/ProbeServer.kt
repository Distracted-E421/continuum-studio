package com.example.continuumstudio.probe

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A small HTTP server for agent-driven diagnostics.
 *
 * See `docs/PROBE_BRIDGE.md` for why this exists: six release cycles of fixing car
 * audio from second-hand descriptions, when the device could have been answering
 * directly.
 *
 * ## Safety properties, in order of importance
 *
 *  1. **Off unless explicitly started.** Nothing here runs at boot or at launch.
 *     A dormant network listener in release builds would repeat the resident
 *     media-session mistake in a worse form.
 *  2. **Loopback by default.** Binding to a non-loopback address requires the
 *     caller to pass it explicitly; the UI makes that a separate switch with a
 *     warning, because a phone on a car's hotspot is not a trusted network.
 *  3. **Token for everything except `ping`.** `ping` is deliberately open so a
 *     client can discover the server and learn it needs a token, rather than
 *     concluding the app is broken.
 *  4. **No eval, no exec, no arbitrary paths.** The route table is a fixed
 *     enumeration ([ProbeRoute]); there is no dynamic dispatch to anything.
 *  5. **One request per connection, `Connection: close`.** No keep-alive state to
 *     get wrong, and a hung client cannot hold a worker.
 *
 * Deliberately hand-rolled over `ServerSocket`: the app has no HTTP server
 * dependency, `minSdk` is 34 so nothing is missing, and adding Netty or Ktor to a
 * diagnostic feature expands both the APK and the attack surface for no gain.
 */
class ProbeServer(
    /** Supplies the token. Null or blank means "not configured" - refuse. */
    private val tokenProvider: () -> String?,
    /** Answers a read route. Called off the main thread. */
    private val readHandler: (ProbeRequest) -> ProbeResponse,
    /**
     * Log sink.
     *
     * Injected rather than calling `android.util.Log` because that is unimplemented
     * in JVM unit tests, which would make this class untestable exactly where it
     * matters - the auth and request-parsing paths. The app passes `Log::i`, tests
     * pass a collector.
     */
    private val log: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "ProbeServer"
        /** Default port; 0 asks the OS for a free one, which tests use. */
        const val DEFAULT_PORT = 8765
        private const val SOCKET_TIMEOUT_MS = 10_000
        /** Cap on an accepted request line, so a malformed client cannot grow memory. */
        private const val MAX_LINE = 4096
    }

    /** A status code plus body, so handlers do not touch the socket. */
    data class ProbeResponse(val status: Int, val body: String)

    /**
     * A resolved request.
     *
     * The query is passed as a map rather than folded into the route so handlers
     * never see a raw string to parse - in particular the `dumpsys` handler must
     * only ever receive an allowlisted command name ([com.example.continuumstudio.probe.ProbeCommand]).
     */
    data class ProbeRequest(val route: ProbeRoute, val query: Map<String, String>) {
        fun queryValue(key: String): String? = query[key]
    }

    /** Parse `a=1&b=2`, tolerating junk. Values are URL-decoded; keys are not needed. */
    internal fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val k = part.substring(0, idx)
            val v = java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            k to v
        }.toMap()
    }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    /** The port actually bound, or -1 when stopped. Useful when port 0 was requested. */
    @Volatile
    var boundPort: Int = -1
        private set

    /** Whether the listener is accepting. */
    val isRunning: Boolean get() = running

    /**
     * Start listening.
     *
     * @param bindAddress which interface. Defaults to loopback; pass
     *        [InetAddress.getByName] for a tailnet address only with the owner's
     *        explicit intent.
     * @return true when the listener is up
     */
    @Synchronized
    fun start(bindAddress: InetAddress = InetAddress.getLoopbackAddress(), port: Int = DEFAULT_PORT): Boolean {
        if (running) return true
        return try {
            val socket = ServerSocket(port, 8, bindAddress)
            server = socket
            boundPort = socket.localPort
            running = true
            thread(name = "probe-server", isDaemon = true) { acceptLoop(socket) }
            log("listening on ${bindAddress.hostAddress}:$boundPort")
            true
        } catch (e: Exception) {
            log("could not start: ${e.message}")
            running = false
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        boundPort = -1
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                // Closing the ServerSocket lands here; only log while running so a
                // deliberate stop is not noisy.
                if (running) log("accept failed: ${e.message}")
                break
            }
            thread(name = "probe-conn", isDaemon = true) { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = readLine(reader) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(client, 400, ProbeJson.error("bad_request", "malformed request line"))
                return
            }
            val method = parts[0]
            val rawTarget = parts[1]
            val path = rawTarget.substringBefore('?')
            val query = parseQuery(rawTarget.substringAfter('?', ""))

            // Drain headers, capturing the token. Bounded so a huge header block
            // cannot exhaust memory.
            var token: String? = null
            var headerLines = 0
            while (headerLines < 64) {
                val line = readLine(reader) ?: break
                if (line.isEmpty()) break
                headerLines++
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val name = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (name.equals("X-Probe-Token", ignoreCase = true)) token = value
                }
            }

            val route = ProbeRoute.match(method, path)
            when (val auth = ProbeAuthorizer.authorize(route, token, tokenProvider())) {
                ProbeAuth.Allowed -> {
                    val r = readHandler(ProbeRequest(route!!, query))
                    respond(client, r.status, r.body)
                }
                ProbeAuth.NoTokenConfigured ->
                    respond(
                        client,
                        503,
                        ProbeJson.error(
                            "no_token_configured",
                            "This probe server has no token set. Enable it in Settings so " +
                                "a token is generated, then send X-Probe-Token.",
                        ),
                    )
                ProbeAuth.Unauthorised ->
                    respond(
                        client,
                        401,
                        ProbeJson.error(
                            "unauthorised",
                            "Send the probe token in the X-Probe-Token header.",
                        ),
                    )
            }
        } catch (e: Exception) {
            log("request failed: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readLine(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_LINE) {
            val c = reader.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun respond(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        BufferedOutputStream(client.getOutputStream()).apply {
            write(header.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        503 -> "Service Unavailable"
        else -> "Error"
    }
}
