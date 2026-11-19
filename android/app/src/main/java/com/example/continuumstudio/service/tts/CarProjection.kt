package com.example.continuumstudio.service.tts

import android.content.Context
import android.util.Log

/**
 * Whether a car head unit is currently projecting this phone.
 *
 * ## Why this is its own unit
 *
 * The car layout has been wrong twice for the same underlying reason: the answer
 * was inferred from something else.
 *
 *  1. First it trusted `UI_MODE_TYPE_CAR`, which a real Samsung S24 projecting to
 *     a Subaru reported as **NORMAL** (2026-11-18), so the layout never appeared.
 *  2. Then it inferred projection from a connected MediaSession or a
 *     `TYPE_REMOTE_SUBMIX` output - both of which only exist while audio is being
 *     routed, so a layout decision taken before a read always said "not
 *     projecting". Worse, that was wrapped in `remember`, freezing the false
 *     answer for the life of the composition.
 *
 * So this asks the car framework directly, and falls back to UiMode. Nothing here
 * infers; it only reports what a system API says.
 */
object CarProjection {

    private const val TAG = "CarProjection"

    /**
     * @return true when a head unit is projecting, or when UiMode says CAR.
     *
     * Reflective on purpose: a compile-time `androidx.car.app` dependency would
     * fail to resolve on devices without Play services, and this app must install
     * on a Samsung phone that may never see a car.
     */
    fun isProjecting(context: Context): Boolean {
        val viaCar = runCatching {
            val connCls = Class.forName("androidx.car.app.connection.CarConnection")
            // CAR_CONNECTION_TYPE_PROJECTING == 2 on published versions, but the
            // constant is read from the class rather than hardcoded so a rebase
            // cannot silently change the meaning.
            val projecting = connCls
                .getField("CAR_CONNECTION_TYPE_PROJECTING")
                .getInt(null)
            val instance = connCls
                .getMethod("getInstance", Context::class.java)
                .invoke(null, context)
            val current = connCls.getMethod("getCarConnectionType").invoke(instance) as? Int
            current == projecting
        }.onFailure { Log.d(TAG, "Car available? ${it.message}") }.getOrDefault(false)
        if (viaCar) return true

        // UiMode is authoritative when the head unit reports it, and is simply
        // false when it does not - which is the safe direction for layout.
        return runCatching {
            context.getSystemService(android.app.UiModeManager::class.java)
                ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_CAR
        }.getOrDefault(false)
    }

    /**
     * How projection was (not) detected, for the diagnostics report.
     *
     * Naming the *evidence* matters because "car mode is NORMAL" was true and
     * misleading at the same time.
     */
    fun describe(context: Context): String {
        val uiMode = runCatching {
            when (context.getSystemService(android.app.UiModeManager::class.java)?.currentModeType) {
                android.content.res.Configuration.UI_MODE_TYPE_CAR -> "CAR"
                android.content.res.Configuration.UI_MODE_TYPE_NORMAL -> "NORMAL"
                else -> "other"
            }
        }.getOrDefault("unknown")
        val carApi = runCatching {
            val connCls = Class.forName("androidx.car.app.connection.CarConnection")
            val instance = connCls.getMethod("getInstance", Context::class.java)
                .invoke(null, context)
            connCls.getMethod("getCarConnectionType").invoke(instance)?.toString() ?: "null"
        }.getOrElse { "unavailable" }
        return "projecting=${isProjecting(context)} uiMode=$uiMode carConnectionType=$carApi"
    }
}
