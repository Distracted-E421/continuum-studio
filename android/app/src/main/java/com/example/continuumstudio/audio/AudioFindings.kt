package com.example.continuumstudio.audio

/**
 * Flags values in an [AudioReport] that would explain silence.
 *
 * The first version of the report simply printed values, which required the
 * reader to know what a good reading looks like - unreasonable in a car, and it
 * is exactly how `volume music: 2/15` and `notifications: 0/15` were almost
 * missed in a real report.
 *
 * Pure, so the rules are testable; it works on the rendered `label: value`
 * lines rather than reaching into Android.
 */
object AudioFindings {

    /** Volume at or below this is effectively inaudible in a car. */
    const val LOW_VOLUME = 3

    /**
     * Suspicious values, most likely cause first.
     *
     * Returns a list of plain-language sentences, each naming the field it came
     * from, so no interpretation is needed to act on them.
     */
    fun inspect(report: AudioReport): List<String> {
        val map = report.lines.toMap()
        val out = mutableListOf<String>()

        // --- Volume -------------------------------------------------------
        //
        // The single most common "the app is silent" cause, and the cheapest to
        // rule out. Checked before anything clever because it explains silence in
        // *every* app at once, which is the symptom that was reported.
        for ((label, stream) in listOf(
            "volume music" to "music",
            "volume notifications" to "notifications",
            "volume system" to "system",
        )) {
            val raw = map[label] ?: continue
            val muted = raw.contains("MUTED", ignoreCase = true)
            val level = raw.substringBefore('/').trim().toIntOrNull()
            when {
                muted -> out += "$stream volume is MUTED ($raw)"
                level == 0 -> out += "$stream volume is zero ($raw) - nothing can be heard"
                level != null && level <= LOW_VOLUME ->
                    out += "$stream volume is very low ($raw) - barely audible in a car"
            }
        }

        // --- Routes -------------------------------------------------------
        val outputs = map["outputs"].orEmpty()
        val hasA2dp = outputs.contains("bt-a2dp")
        val hasSco = outputs.contains("bt-sco")
        val hasRemoteSubmix = outputs.contains("remote-submix")

        if (hasRemoteSubmix) {
            out += "A remote-submix output is present: audio is being routed to a " +
                "remote capture (Android Auto over USB). Media may be going there " +
                "rather than to a speaker."
        }
        if (hasSco && !hasA2dp) {
            out += "Bluetooth offers SCO (call audio) but no A2DP (media). Media " +
                "cannot play over SCO, so music has no Bluetooth route at all - " +
                "this is expected while projecting to a car over USB, and it means " +
                "the head unit must be the one playing."
        }
        if (!hasA2dp && !hasRemoteSubmix && !outputs.contains("speaker")) {
            out += "No usable media output is listed: no A2DP, no speaker and no " +
                "remote-submix."
        }

        // --- Mode ---------------------------------------------------------
        if (map["audio mode"].orEmpty() != "NORMAL") {
            out += "Audio mode is ${map["audio mode"]}, not NORMAL. A call/communication " +
                "mode redirects media to the earpiece or the call route."
        }
        if (map["isSpeakerphoneOn"].orEmpty() == "true") {
            out += "Speakerphone is on, which can force the call route."
        }
        if (map["car mode (UiModeManager)"].orEmpty() != "CAR (Android Auto)") {
            out += "Android does not report car mode, so the app cannot switch to its " +
                "car layout from that signal alone."
        }
        if (map["session connected"].orEmpty() == "true" &&
            map["isMusicActive"].orEmpty() == "false"
        ) {
            out += "Our media session is connected while no music is playing - it may " +
                "be holding the car's channel."
        }

        // --- Who else is making sound ---------------------------------------
        //
        // Only reported when something IS playing. "Nothing is playing" is the normal
        // state of a phone at rest, so emitting a finding for it made a healthy report
        // look broken - caught by the healthy-report test.
        //
        // The useful case is a genuine conflict: another app audible while ours is
        // silent means our audio path is the problem.
        val others = map["other apps playing"].orEmpty()
        if (others.isNotEmpty() && others != "none") {
            out += "Other apps ARE playing ($others). If our dialog is silent while " +
                "those are audible, our own audio path is the problem."
        }
        if (map["media sessions readable"].orEmpty() == "false") {
            out += "Media sessions cannot be read, so a conflict with another app could " +
                "not be checked. Enable the Continuum notification listener in system " +
                "settings (no Shizuku needed)."
        }

        return out
    }
}
