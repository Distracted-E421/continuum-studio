package com.example.continuumstudio.probe

/**
 * What a probe request is asking for.
 *
 * Pure, so routing is testable without opening a socket. The server maps these
 * onto the live app state.
 */
enum class ProbeRoute(val method: String, val path: String) {
    /** Liveness. The only route that needs no token, so a client can discover the
     * server and learn that it needs one instead of failing silently. */
    PING("GET", "/probe/ping"),

    /** Audio routing/volume/session snapshot. */
    AUDIO("GET", "/probe/audio"),

    /** Dialog and playback state. */
    STATE("GET", "/probe/state"),

    /** Play the test tone. POST because it makes sound - GET handlers must never
     * change anything, and that rule is the reason the read endpoints are safe to
     * hit at any moment, including while the owner is driving. */
    PROBE_AUDIO("POST", "/probe/audio/probe"),
    ;

    val requiresToken: Boolean get() = this != PING

    companion object {
        /** Resolve a request line to a route, or null when unrecognised. */
        fun match(method: String, path: String): ProbeRoute? =
            entries.firstOrNull {
                it.method.equals(method, ignoreCase = true) &&
                    it.path == path.trimEnd('/').ifEmpty { "/" }
            }
    }
}

/** Outcome of authorising a request. */
sealed interface ProbeAuth {
    data object Allowed : ProbeAuth

    /** The route needs a token and none (or a wrong one) was supplied. */
    data object Unauthorised : ProbeAuth

    /**
     * The route needs a token and the server has none configured.
     *
     * Distinct from [Unauthorised] deliberately: it means the feature is not
     * enabled, which is a setup problem, not a credentials problem. Collapsing the
     * two is how "just retry with the token" advice gets given when the token does
     * not exist.
     */
    data object NoTokenConfigured : ProbeAuth
}

/**
 * Decides whether a request may proceed.
 *
 * Separated from the server so the security rule can be tested directly. Using
 * `==` on tokens is deliberate and sufficient here: the comparison is between a
 * caller-supplied header and an in-memory value, not a stored credential, and the
 * realistic attack on a diagnostics port on a tailnet is not a timing oracle.
 */
object ProbeAuthorizer {

    fun authorize(route: ProbeRoute?, suppliedToken: String?, configuredToken: String?): ProbeAuth =
        when {
            route == null -> ProbeAuth.Unauthorised
            !route.requiresToken -> ProbeAuth.Allowed
            configuredToken.isNullOrBlank() -> ProbeAuth.NoTokenConfigured
            suppliedToken.isNullOrBlank() -> ProbeAuth.Unauthorised
            suppliedToken == configuredToken -> ProbeAuth.Allowed
            else -> ProbeAuth.Unauthorised
        }
}
