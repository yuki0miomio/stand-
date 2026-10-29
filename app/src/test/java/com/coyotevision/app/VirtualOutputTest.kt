package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class VirtualOutputTest {
    @Test fun simulatorUsesTheExactSelectedWaveformAndFiniteLease() {
        val output = VirtualOutput()
        val preset = EffectPreset("B", 20, 3, PulseShape.TRIPLE_SHORT)
        assertTrue(output.submit(preset, 30, 1000))
        for (index in 0 until 30) {
            val sample = output.sample(1000L + index * 100)!!
            assertEquals(preset.pulseFrames()[index], sample.frame)
            assertEquals("B", sample.preset.channel); assertEquals(20, sample.preset.strength)
        }
        assertEquals(1L, output.sample(3999)!!.remainingMs)
        assertNull(output.sample(4000)); assertNull(output.sample(5000))
    }

    @Test fun pauseClearsVirtualPenaltyAndCannotReplayIt() {
        val output = VirtualOutput()
        val penalty = CurrentRules.penalty(10, EffectPreset(strength = 5, durationSeconds = 30), 20, 60_000)!!
        assertTrue(output.submit(penalty, 20, 1000))
        assertEquals(15, output.sample(1100)!!.preset.strength)
        output.clear()
        assertNull(output.sample(1200)); assertNull(output.sample(5000))
        assertTrue(output.submit(EffectPreset(strength = 10), 20, 6000))
        assertEquals(10, output.sample(6000)!!.preset.strength)
    }

    @Test fun invalidOrZeroSettingsNeverProduceVirtualOutput() {
        val output = VirtualOutput()
        assertFalse(output.submit(EffectPreset(strength = 10), 0, 0))
        assertFalse(output.submit(EffectPreset(strength = 30), 20, 0))
        assertFalse(output.submit(EffectPreset(strength = 0), 20, 0))
        assertNull(output.sample(0))
    }

    @Test fun motionLogDistinguishesRecognizedCausesFromTheActualBoundedIncrement() {
        val body = StandingMetrics(tracked = true, calibrated = true, hands = .5f, feet = 1f, outside = true, score = .5f)
        val entry = MotionOutputAudit.describe(body, MotionWeights(20, 30, 40), 10, 10, 25, 25)!!
        assertTrue(entry.contains("手部 +10")); assertTrue(entry.contains("脚步 +30"))
        assertTrue(entry.contains("离位 +40")); assertTrue(entry.contains("身体幅度 +5"))
        assertTrue(entry.contains("实际动作加成 +15"))
        assertNull(MotionOutputAudit.describe(body, MotionWeights(20, 30, 40), 10, 10, 25, 10))
        assertNull(MotionOutputAudit.describe(body.copy(tracked = false), MotionWeights(20, 30, 40), 10, 10, 25, 25))
        assertNull(MotionOutputAudit.describe(body, MotionWeights(), 0, 10, 25, 25))
    }
}
