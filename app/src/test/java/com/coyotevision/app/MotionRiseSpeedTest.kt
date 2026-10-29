package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

class MotionRiseSpeedTest {
    @Test fun ordinaryRatesRampOnlyTheIncrementWhileKeepingTheNormalFloorAndTotalCap() {
        for (rate in listOf(1, 4, 10, 30, 200)) {
            val limiter = MotionIntensity()
            val speed = MotionRiseSpeed.forMode(rate, false)
            for (t in 0L..2000 step 250) {
                val output = CurrentRules.withMotionFloor(10, 200, 200, limiter, t, speed)
                assertEquals(10 + minOf(190f, rate * t / 1000f).roundToInt(), output)
            }
        }
    }

    @Test fun enduranceUsesThirtyForBothBaseAndMovementRegardlessOfTheOrdinarySetting() {
        val metrics = StandingMetrics(tracked = true, calibrated = true, hands = 1f, feet = 1f)
        val target = MotionWeights(20, 20).target(metrics, 100, base = 20)
        assertEquals(60, target)
        for (ordinary in listOf(1, 4, 100, 200)) {
            val limiter = MotionIntensity()
            val training = EnduranceTraining(EnduranceSettings(true, 5000, 5000, 3000, 3000), 0, 60_000, Random(1))
            assertEquals(TrainingEvent.START, training.update(5000, false))
            val output = VirtualOutput()
            for (t in 5000L..7000 step 250) {
                val speed = MotionRiseSpeed.forMode(ordinary, training.active)
                assertEquals(30, speed)
                val strength = CurrentRules.withMotionFloor(0, target, 100, limiter, t, speed)
                assertEquals(minOf(60f, (t - 5000) * .03f).roundToInt(), strength)
                if (strength > 0) {
                    assertTrue(output.submit(EffectPreset(strength = strength), 100, t))
                    assertEquals(strength, output.sample(t)!!.preset.strength)
                }
            }
            assertEquals(TrainingEvent.END, training.update(8000, false))
            assertEquals(ordinary, MotionRiseSpeed.forMode(ordinary, training.active))
        }
    }

    @Test fun fastTrainingAndOrdinaryRatesCannotOvershootALowerGlobalCap() {
        for (speed in listOf(30, 200)) {
            val limiter = MotionIntensity()
            for (t in 0L..2000 step 100)
                assertTrue(CurrentRules.withMotionFloor(0, 200, 17, limiter, t, speed) in 0..17)
            assertEquals(17, CurrentRules.withMotionFloor(0, 200, 17, limiter, 2100, speed))
            assertEquals(0, CurrentRules.withMotionFloor(0, 200, 0, limiter, 2200, speed))
        }
    }

    @Test fun decreasesAreImmediateAndResetNeverGivesAFastTrainingFreeJump() {
        val limiter = MotionIntensity()
        for (t in 0L..1000 step 100) limiter.update(1f, 100, t, 30)
        assertEquals(30, limiter.update(1f, 100, 1000, 30))
        assertEquals(5, limiter.update(.05f, 100, 1100, 30))
        assertEquals(0, limiter.update(Float.NaN, 100, 1200, 30))
        limiter.reset()
        assertEquals(0, limiter.update(1f, 100, 1300, 30))
        assertEquals(3, limiter.update(1f, 100, 1400, 30))
    }

    @Test fun longFrameGapsCannotAccumulateUnlimitedRiseCredit() {
        val limiter = MotionIntensity()
        assertEquals(0, limiter.update(1f, 200, 0, 30))
        assertEquals(12, limiter.update(1f, 200, 10_000, 30))
        assertEquals(15, limiter.update(1f, 200, 10_100, 30))
    }

    @Test fun invalidOrdinaryRatesCannotCauseAnUnboundedIncrease() {
        for (invalid in listOf(-1, 0, 201, Int.MAX_VALUE)) {
            assertFalse(MotionRiseSpeed.valid(invalid))
            assertEquals(0, MotionRiseSpeed.forMode(invalid, false))
            val limiter = MotionIntensity()
            limiter.update(1f, 200, 0, invalid)
            assertEquals(0, limiter.update(1f, 200, 100, invalid))
            assertEquals(30, MotionRiseSpeed.forMode(invalid, true))
        }
    }
}
