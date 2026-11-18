package com.example.continuumstudio.service

import android.content.Context
import android.util.Log
import com.example.continuumstudio.data.ServerEndpoint
import com.example.continuumstudio.viewmodel.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Reads the saved endpoint configuration for services.
 *
 * The Activity gets this from its view model; services have no view model and
 * previously had no way to know where the server was, which is part of why the
 * background path never worked.
 *
 * Blocking on purpose: callers are `onStartCommand` and service startup, where a
 * short synchronous read is simpler than threading a coroutine through. A timeout
 * keeps a wedged DataStore from hanging a foreground service start.
 */
internal object EndpointConfig {

    private const val TAG = "EndpointConfig"

    data class Active(
        val url: String,
        val clientId: String = "",
        val clientSecret: String = "",
    )

    /**
     * The endpoint to connect to: the persisted active one, or the first enabled.
     *
     * Returns null when nothing is configured, which the caller should treat as
     * "nothing to do" rather than an error - a fresh install has no endpoints.
     */
    fun activeEndpoint(context: Context): Active? {
        val store = context.applicationContext.dataStore
        val prefs = try {
            runBlocking { withTimeoutOrNull(2_000L) { store.data.first() } }
        } catch (e: Exception) {
            Log.d(TAG, "could not read endpoints: ${e.message}")
            return null
        } ?: return null

        val json = Json { ignoreUnknownKeys = true }
        val endpoints: List<ServerEndpoint> = prefs[
            androidx.datastore.preferences.core.stringPreferencesKey("endpoints_json")
        ]?.let { raw ->
            try {
                json.decodeFromString<List<ServerEndpoint>>(raw)
            } catch (e: Exception) {
                Log.w(TAG, "malformed endpoints_json: ${e.message}")
                emptyList()
            }
        } ?: emptyList()

        val enabled = endpoints.filter { it.enabled && it.url.isNotBlank() }
        if (enabled.isEmpty()) return null

        val index = prefs[
            androidx.datastore.preferences.core.stringPreferencesKey("active_endpoint_index")
        ]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val chosen = enabled.getOrNull(index) ?: enabled.first()
        return Active(chosen.url, chosen.cfAccessClientId, chosen.cfAccessClientSecret)
    }
}
