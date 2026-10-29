package com.coyotevision.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream

class SettingsCodeTest {
    private fun changed(vararg entries: Pair<String, Any>) = SettingsCode.defaults() + entries.toMap()
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid settings should not be accepted") }
        catch (error: SettingsCode.Invalid) { assertTrue(error.message.orEmpty().isNotBlank()) }
    }
    private fun pack(raw: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        val bytes = output.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return SettingsCode.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "." + hash
    }
    private fun envelope(values: Map<String, Any>) = JSONObject().put("app", "CoyoteVision")
        .put("version", 1).put("settings", JSONObject(values))

    @Test fun unconfiguredDefaultsRoundTripWithEverySettingAndWithoutSessionData() {
        val defaults = SettingsCode.defaults()
        val code = SettingsCode.encode(defaults)
        assertTrue(code.startsWith(SettingsCode.PREFIX))
        assertTrue(code.length < 3000)
        assertEquals(defaults, SettingsCode.decode(code))
        assertEquals(0, defaults["limit"])
        assertEquals(false, defaults["mode_device"])
        assertFalse(defaults.keys.any { it.contains("calibration") || it.contains("session") || it.contains("clientId") })
    }
    @Test fun customizedProfileRestoresAllNumbersTogglesChannelsAndWaveforms() {
        val values = changed("minutes" to 90, "wrist_near_percent" to 120, "limit" to 80,
            "normal_strength" to 10, "hand_peak" to 30, "foot_peak" to 40, "torso_peak" to 50, "zone_peak" to 60,
            "hand_mapping_range" to 22, "foot_mapping_range" to 33, "torso_mapping_range" to 44,
            "zone_radius" to 80, "motion_rise_rate" to 11, "curtsey_hold_ms" to 3000L,
            "response" to 12, "curtsey_range" to 42, "gap_min" to 20, "gap_max" to 50,
            "game" to false, "training" to true, "train_gap_min" to 60, "train_gap_max" to 90,
            "train_duration_min" to 10, "train_duration_max" to 15, "training_strength" to 25,
            "curtsey_strength" to 20, "cue_strength" to 15, "penalty_delta_strength" to 40,
            "penalty_delta_duration" to 29, "penalty_delta_channel" to 1, "training_channel" to 1,
            "mirror_mode" to true, "console_visible" to false, "mode_virtual" to false, "mode_device" to true,
            "server" to "wss://example.test:9999/path")
        assertEquals(values, SettingsCode.decode(SettingsCode.encode(values)))
    }
    @Test fun everySupportedWaveformAndHoldOptionCanBeImported() {
        for (shape in PulseShape.entries) {
            val key = if (shape.cueOnly) "cue_waveform" else "motion_waveform"
            val values = changed(key to shape.name)
            assertEquals(shape.name, SettingsCode.decode(SettingsCode.encode(values))[key])
        }
        for (hold in SettingsCode.holdOptions) {
            val values = changed("response" to 30, "curtsey_hold_ms" to hold)
            assertEquals(hold, SettingsCode.decode(SettingsCode.encode(values))["curtsey_hold_ms"])
        }
    }
    @Test fun copiedLineWrapsAndSurroundingWhitespaceAreAccepted() {
        val code = SettingsCode.encode(SettingsCode.defaults())
        assertEquals(SettingsCode.defaults(), SettingsCode.decode("\n  " + code.chunked(60).joinToString("\n ") + "  \n"))
    }
    @Test fun corruptionTruncationAndWrongFormatFailBeforeApplyingAnything() {
        val code = SettingsCode.encode(SettingsCode.defaults())
        rejects { SettingsCode.decode(code.dropLast(1)) }
        rejects { SettingsCode.decode(code + ".extra") }
        rejects { SettingsCode.decode(code.replaceRange(8, 9, if (code[8] == 'A') "B" else "A")) }
        rejects { SettingsCode.decode("CVSET2." + code.substringAfter('.')) }
        rejects { SettingsCode.decode("not a code") }
        rejects { SettingsCode.decode("") }
    }
    @Test fun sizeLimitsBoundBothClipboardAndDecompressedPayload() {
        rejects { SettingsCode.decode("A".repeat(SettingsCode.MAX_CODE_SIZE + 513)) }
        rejects { SettingsCode.decode(pack("A".repeat(30_000))) }
    }
    @Test fun invalidJsonOrAnotherApplicationAndVersionAreRejected() {
        rejects { SettingsCode.decode(pack("{broken json")) }
        rejects { SettingsCode.decode(pack(envelope(SettingsCode.defaults()).put("app", "Other").toString())) }
        rejects { SettingsCode.decode(pack(envelope(SettingsCode.defaults()).put("version", 2).toString())) }
    }
    @Test fun missingUnknownAndWrongTypedSettingsCannotBePartiallyLoaded() {
        for (values in listOf(SettingsCode.defaults() - "limit", changed("unknown" to 1), changed("minutes" to "3"),
            changed("game" to "true"), changed("server" to false), changed("limit" to 10.5), changed("limit" to Long.MAX_VALUE))) {
            rejects { SettingsCode.decode(pack(envelope(values).toString())) }
        }
    }
    @Test fun numberBoundsAndCrossFieldErrorsAreValidated() {
        for (values in listOf(changed("minutes" to 91), changed("limit" to 10, "normal_strength" to 11),
            changed("limit" to 30, "normal_strength" to 10, "hand_peak" to 9),
            changed("gap_min" to 100, "gap_max" to 50), changed("train_gap_min" to 100),
            changed("train_duration_min" to 10, "train_duration_max" to 5), changed("curtsey_hold_ms" to 700L),
            changed("curtsey_hold_ms" to 10_000L, "response" to 8), changed("hand_mapping_range" to 0))) {
            rejects { SettingsCode.encode(values) }
        }
    }
    @Test fun modesAndPresetTypesAreValidatedWithoutStartingAnyOutput() {
        rejects { SettingsCode.encode(changed("mode_virtual" to true, "mode_device" to true)) }
        rejects { SettingsCode.encode(changed("cue_waveform" to PulseShape.RAMP.name)) }
        rejects { SettingsCode.encode(changed("motion_waveform" to PulseShape.TRIPLE_SHORT.name)) }
        rejects { SettingsCode.encode(changed("training_waveform" to "unknown")) }
        rejects { SettingsCode.encode(changed("penalty_delta_channel" to 2)) }
        rejects { SettingsCode.encode(changed("penalty_delta_duration" to 30)) }
        rejects { SettingsCode.encode(changed("cue_duration" to 0)) }
        rejects { SettingsCode.encode(changed("motion_strength" to 10)) }
        // Device mode is a selection, not pairing authorization or an active session.
        val device = changed("mode_virtual" to false, "mode_device" to true)
        assertEquals(device, SettingsCode.decode(SettingsCode.encode(device)))
    }
    @Test fun activePresetsRespectCapAndDisabledTasksCanRetainTheirParameters() {
        rejects { SettingsCode.encode(changed("training" to true, "training_strength" to 20)) }
        rejects { SettingsCode.encode(changed("cue_strength" to 20)) }
        val disabled = changed("training" to false, "training_strength" to 20, "game" to false,
            "cue_strength" to 20, "curtsey_hold_ms" to 10_000L, "response" to 3)
        assertEquals(disabled, SettingsCode.decode(SettingsCode.encode(disabled)))
    }
    @Test fun urlsArePortableAndMalformedUrlsAreRejected() {
        for (url in listOf("", "ws://192.168.1.2:9999", "wss://example.test/socket")) {
            val values = changed("server" to url)
            assertEquals(url, SettingsCode.decode(SettingsCode.encode(values))["server"])
        }
        for (url in listOf("https://example.test", "ws://", "server address")) rejects { SettingsCode.encode(changed("server" to url)) }
    }
    @Test fun failureDoesNotMutateTheCallersSettingsAndLegacyFlagsAreNotIncluded() {
        val values = changed("minutes" to 91).toMutableMap()
        val original = values.toMap()
        rejects { SettingsCode.encode(values) }
        assertEquals(original, values)
        assertFalse(SettingsCode.defaults().containsKey("direct_motion_v1"))
        assertFalse(SettingsCode.defaults().containsKey("hand_weight"))
    }
    @Test fun circularCoverKeepsLegacyPreferenceAndOlderCodesDefaultItToOff() {
        val enabled = changed("face_mosaic" to true)
        assertEquals(enabled, SettingsCode.decode(SettingsCode.encode(enabled)))
        val legacy = SettingsCode.defaults() - "face_mosaic"
        assertEquals(false, SettingsCode.decode(pack(envelope(legacy).toString()))["face_mosaic"])
        rejects { SettingsCode.encode(changed("face_mosaic" to "yes")) }
    }

    @Test fun codesBeforeExperimentalAdjustmentsKeepGameSettingsAndUseDisplayDefaults() {
        val previous = changed("minutes" to 90, "face_mosaic" to true) - FaceCoverSettings.defaults.keys
        val decoded = SettingsCode.decode(pack(envelope(previous).toString()))
        assertEquals(90, decoded["minutes"])
        assertEquals(true, decoded["face_mosaic"])
        FaceCoverSettings.defaults.forEach { (key, default) -> assertEquals(default, decoded[key]) }
        val oldest = previous - "face_mosaic"
        assertEquals(false, SettingsCode.decode(pack(envelope(oldest).toString()))["face_mosaic"])
        rejects { SettingsCode.decode(pack(envelope(previous - "limit").toString())) }
    }

    @Test fun experimentalDisplaySettingsRoundTripAndRejectBadRangesOrTypes() {
        val values = changed("face_mosaic" to true, "face_cover_size" to 400, "face_cover_x" to -50, "face_cover_y" to 50, "face_cover_width" to 300, "face_cover_height" to 20)
        assertEquals(values, SettingsCode.decode(SettingsCode.encode(values)))
        for (bad in listOf("face_cover_size" to 19, "face_cover_size" to 401, "face_cover_x" to -51,
            "face_cover_y" to 51, "face_cover_width" to 19, "face_cover_height" to 301, "face_cover_x" to "5", "face_cover_y" to false)) {
            rejects { SettingsCode.encode(changed(bad)) }
        }
    }
    @Test fun oldCircleProfilesKeepSizeAndOffsetsAndGainOvalAndSkeletonDefaults() {
        val previous = changed("face_mosaic" to true, "face_cover_size" to 120, "face_cover_x" to -12,
            "face_cover_y" to 8) - setOf("face_cover_width", "face_cover_height", "hide_skeleton")
        val decoded = SettingsCode.decode(pack(envelope(previous).toString()))
        assertEquals(120, decoded["face_cover_size"])
        assertEquals(-12, decoded["face_cover_x"])
        assertEquals(8, decoded["face_cover_y"])
        assertEquals(100, decoded["face_cover_width"])
        assertEquals(75, decoded["face_cover_height"])
        assertEquals(false, decoded["hide_skeleton"])
    }

    @Test fun hidingSkeletonIsIndependentOfMirrorAndFaceCoverAndRoundTrips() {
        for (hide in listOf(false, true)) for (mirror in listOf(false, true)) for (cover in listOf(false, true)) {
            val values = changed("hide_skeleton" to hide, "mirror_mode" to mirror, "face_mosaic" to cover)
            assertEquals(values, SettingsCode.decode(SettingsCode.encode(values)))
        }
        rejects { SettingsCode.encode(changed("hide_skeleton" to "true")) }
    }

}
