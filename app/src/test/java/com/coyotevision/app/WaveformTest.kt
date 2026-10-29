package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WaveformTest {
    @Test fun allTwentyFourOfficialWaveformsMatchThePinnedUpstreamData() {
        val root = File(System.getProperty("coyote.projectRoot"))
        val source = File(root, "relay/upstream/dglab-kit-waveforms/coyote.ts").readText()
        val pattern = Regex("\\[COYOTE_WAVEFORM\\.(\\w+)]:[\\s\\S]*?raw:\\s*\\[([^]]+)]")
        val entries = pattern.findAll(source).toList()
        assertEquals(24, entries.size)
        for (entry in entries) {
            val frames = Regex("'([0-9A-Fa-f]{16})'").findAll(entry.groupValues[2]).map { it.groupValues[1] }.toList()
            assertEquals(frames, PulseShape.valueOf("OFFICIAL_${entry.groupValues[1]}").frames)
        }
    }
    @Test fun allSelectableFramesSatisfyCoyoteByteRanges() {
        for (shape in PulseShape.entries) for (frame in shape.frames) {
            assertTrue(frame.matches(Regex("[0-9A-Fa-f]{16}")))
            val bytes = frame.chunked(2).map { it.toInt(16) }
            assertTrue(bytes.take(4).all { it in 10..240 }); assertTrue(bytes.drop(4).all { it in 0..100 })
        }
    }
    @Test fun everyCueVariantProducesExactlyThreeSeparatedBurstsInThreeSeconds() {
        val cues = PulseShape.entries.filter { it.cueOnly }
        assertEquals(3, cues.size)
        for (shape in cues) {
            val frames = EffectPreset(strength = 8, durationSeconds = 3, shape = shape).pulseFrames()
            assertEquals(30, frames.size)
            var bursts = 0; var previous = false
            for (frame in frames) {
                val active = frame.substring(8).chunked(2).any { it.toInt(16) > 0 }
                if (active && !previous) bursts++
                previous = active
            }
            assertEquals(3, bursts); assertFalse(previous)
        }
    }
    @Test fun streamingLeasesContinueLongWaveformsInsteadOfRestartingThem() {
        val playback = WavePlayback()
        val preset = EffectPreset(strength = 10, shape = PulseShape.OFFICIAL_TEMPO_TAP)
        assertEquals(preset.shape.frames[0], playback.at(preset, 0, "normal").pulseFrames()[0])
        assertEquals(preset.shape.frames[5], playback.at(preset, 500, "normal").pulseFrames()[0])
        assertEquals(preset.shape.frames[15], playback.at(preset, 1500, "normal").pulseFrames()[0])
        assertEquals(0, playback.at(preset, 1600, "curtsey").frameOffset)
        playback.reset(); assertEquals(0, playback.at(preset, 1800, "normal").frameOffset)
    }
    @Test fun finitePenaltyRetainsItsChosenWaveformForUpToThirtySeconds() {
        val preset = EffectPreset(strength = 5, durationSeconds = 30, shape = PulseShape.OFFICIAL_TIDE)
        val output = CurrentRules.penalty(10, preset, 20, 60_000)!!
        assertEquals(15, output.strength); assertEquals(30, output.durationSeconds)
        val wire = JSONObject(V3Protocol.pulse("controller", "app", output))
        val frames = JSONArray(wire.getString("message").substringAfter(':'))
        assertEquals(300, frames.length())
        assertEquals(preset.shape.frames[20], frames.getString(20))
        assertEquals(preset.shape.frames[0], frames.getString(23))
        assertEquals(2, CurrentRules.penalty(10, preset, 20, 2500)!!.durationSeconds)
    }
}
