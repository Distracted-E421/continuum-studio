package com.datapunk.aa.video

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import android.util.Log

/**
 * Entry point Android Auto binds to.
 *
 * Registered under the NAVIGATION category because that is the family of
 * templates which carries a Surface. This app does no navigation; the category
 * is a means to the surface.
 */
class VideoCarAppService : CarAppService() {

    companion object {
        private const val TAG = "VideoCarAppService"
    }

    override fun createHostValidator(): HostValidator {
        // Debug builds must accept the Desktop Head Unit's signature, which is
        // not a production host. ALLOW_ALL_HOSTS_VALIDATOR is debug-only by
        // design; it must never ship to a release build.
        return if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        Log.i(TAG, "session created")
        return object : Session() {
            override fun onCreateScreen(intent: android.content.Intent): androidx.car.app.Screen =
                VideoScreen(carContext)
        }
    }
}
