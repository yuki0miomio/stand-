package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TrainingTest {
    private val body = StandingMetrics(tracked = true, calibrated = true, tPose = true)
    @Test fun independentWeightsAndTrainingBaseNeverAccumulateOrExceedTheLimit() {
        val weights = MotionWeights(10, 20, 30)
        val pose = body.copy(hands = .5f, feet = .5f, outside = true)
        assertEquals(45, weights.target(pose, 100))
        assertEquals(60, weights.target(pose, 60, base = 25))
        assertEquals(45, weights.target(pose, 100))
        assertEquals(0, weights.target(pose.copy(tracked = false), 100, 25))
        assertEquals(0, MotionWeights().target(pose, 100))
    }
    private val settings = EnduranceSettings(true, 5000, 5000, 3000, 3000)
    @Test fun trainingWaitsForCurtseyAndEndsAfterAFiniteDuration() {
        val training = EnduranceTraining(settings, 0, 60_000, Random(1))
        assertNull(training.update(4900, false)); assertNull(training.update(5000, true))
        assertFalse(training.active)
        assertEquals(TrainingEvent.START, training.update(9000, false))
        assertTrue(training.active); assertEquals(12_000L, training.until)
        assertNull(training.update(11_999, false))
        assertEquals(TrainingEvent.END, training.update(12_000, false)); assertFalse(training.active)
        assertNull(training.update(16_999, false)); assertEquals(TrainingEvent.START, training.update(17_000, false))
    }
    @Test fun curtseyPromptsResumeWithANewGapAfterTraining() {
        val game = CurtseyGame(GameSettings(60_000, 5000, 5000, 3000, cueMs = 0), 0, Random(1))
        val neutral = body.copy(standing = true, neutral = true)
        for (t in 0L..10_000 step 100) assertNull(game.update(t, neutral, training = true))
        assertTrue(game.motionAllowed)
        for (t in 10_100L..14_900 step 100) assertNull(game.update(t, neutral))
        assertEquals(GameEvent.PROMPT, game.update(15_000, neutral))
    }
    @Test fun totalDeadlineWinsAndTrainingCannotStartNearTheEnd() {
        val training = EnduranceTraining(settings, 0, 10_000, Random(1))
        assertEquals(TrainingEvent.START, training.update(5000, false))
        assertEquals(TrainingEvent.END, training.update(10_000, false))
        assertNull(training.update(20_000, false))
        val tooShort = EnduranceTraining(settings, 6000, 10_000, Random(1))
        assertNull(tooShort.update(9000, false))
    }
    @Test fun invalidTrainingIntervalsAreRejected() {
        assertFalse(settings.copy(maxGapMs = 100).valid())
        assertFalse(settings.copy(maxDurationMs = 31_000).valid())
        assertFalse(settings.copy(minDurationMs = 0).valid())
    }
}
