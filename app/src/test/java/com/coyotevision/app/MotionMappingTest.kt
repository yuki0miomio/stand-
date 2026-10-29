package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class MotionMappingTest {
    private fun body(scale: Float = 1f) = FullBody(BodyJoint.entries.zip(listOf(
        200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f, 290f to 400f, 310f to 400f,
        230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f
    )).associate { (joint, xy) -> joint to BodyPoint(xy.first * scale, xy.second * scale, .99f) })
    private fun tracker(mapping: MotionMappingSettings = MotionMappingSettings(20, 20), scale: Float = 1f) =
        StandingTracker().apply {
            motionMapping = mapping
            beginCalibration()
            for (t in 0L..5100 step 100) observe(body(scale), 720, 1280, t)
            assertTrue(calibrated)
        }
    private fun shifted(joint: BodyJoint, distance: Float, scale: Float = 1f): FullBody {
        val original = body(scale)
        return FullBody(original.points + (joint to original[joint].copy(x = original[joint].x + distance)))
    }

    @Test fun handDistanceMapsFromTheLearnedThresholdThroughHalfToTheConfiguredMaximum() {
        for ((distance, score) in listOf(0f to 0f, 40f to .5f, 60f to 1f, 100f to 1f)) {
            val metrics = tracker().observe(shifted(BodyJoint.LW, distance), 720, 1280, 5200)
            assertEquals(score, metrics.hands, .0001f)
            assertEquals(0f, metrics.feet, .0001f)
            assertEquals(20f, metrics.handMapping!!.threshold, .0001f)
            assertEquals(40f, metrics.handMapping.range, .0001f)
            assertEquals(60f, metrics.handMapping.fullAt, .0001f)
            assertEquals((score * 20).toInt(), MotionWeights(20, 20).target(metrics, 100))
        }
    }

    @Test fun feetUseTheirOwnNoiseThresholdAndRangeWithoutChangingHandScores() {
        for ((distance, score) in listOf(0f to 0f, 34f to .5f, 54f to 1f, 90f to 1f)) {
            val metrics = tracker().observe(shifted(BodyJoint.LA, distance), 720, 1280, 5200)
            assertEquals(score, metrics.feet, .0001f)
            assertEquals(0f, metrics.hands, .0001f)
            assertEquals(14f, metrics.footMapping!!.threshold, .0001f)
            assertEquals(54f, metrics.footMapping.fullAt, .0001f)
            assertEquals((score * 20).toInt(), MotionWeights(20, 20).target(metrics, 100))
        }
    }

    @Test fun changingOnlyOneMappingRangeChangesOnlyItsContributionAndKeepsTheSameReference() {
        val tracker = tracker(MotionMappingSettings(40, 20))
        val moved = FullBody(shifted(BodyJoint.LW, 60f).points + (BodyJoint.LA to shifted(BodyJoint.LA, 54f)[BodyJoint.LA]))
        val before = tracker.observe(moved, 720, 1280, 5200)
        assertEquals(.5f, before.hands, .0001f); assertEquals(1f, before.feet, .0001f)
        tracker.motionMapping = MotionMappingSettings(20, 40)
        val after = tracker.observe(moved, 720, 1280, 5300)
        assertEquals(1f, after.hands, .0001f); assertEquals(.5f, after.feet, .0001f)
        assertEquals(before.tracking!!.points.getValue(BodyJoint.LW).reference,
            after.tracking!!.points.getValue(BodyJoint.LW).reference)
    }

    @Test fun handAndFootFullScaleAreIndependentOfTheBodySensitivityMultiplier() {
        val moved = FullBody(shifted(BodyJoint.LW, 60f).points + (BodyJoint.LA to shifted(BodyJoint.LA, 54f)[BodyJoint.LA]))
        for (sensitivity in listOf(.65f, 1f, 1.5f)) {
            val tracker = tracker().apply { this.sensitivity = sensitivity }
            val metrics = tracker.observe(moved, 720, 1280, 5200)
            assertEquals(1f, metrics.hands, .0001f); assertEquals(1f, metrics.feet, .0001f)
            assertEquals(40, MotionWeights(20, 20).target(metrics, 100))
        }
    }

    @Test fun equalShoulderRelativeMovementHasTheSameMappingForDifferentBodySizes() {
        for (scale in listOf(.8f, 1f, 1.2f)) {
            val metrics = tracker(scale = scale).observe(shifted(BodyJoint.LW, 40f * scale, scale), 720, 1280, 5200)
            assertEquals(.5f, metrics.hands, .0001f)
            assertEquals(40f * scale, metrics.handMapping!!.range, .001f)
        }
    }

    @Test fun minimumRangeStillRejectsSmallJitterAndMissingMeasurementsHaveNoMapping() {
        val tracker = tracker(MotionMappingSettings(5, 5))
        for (t in 5200L..6500 step 100) {
            val jitter = if (t % 200 == 0L) 9f else -9f
            val metrics = tracker.observe(shifted(BodyJoint.LW, jitter), 720, 1280, t)
            assertEquals(0f, metrics.hands); assertEquals(0f, metrics.feet)
        }
        val missing = tracker.observe(FullBody(body().points - BodyJoint.LW), 720, 1280, 6600)
        assertFalse(missing.measurementReady); assertNull(missing.handMapping); assertNull(missing.footMapping)
    }

    @Test fun mappedMaximumStillRespectsTheTotalCapAndOutputRiseLimitInVirtualPlayback() {
        val tracker = tracker()
        val limiter = MotionIntensity()
        val output = VirtualOutput()
        val moved = FullBody(shifted(BodyJoint.LW, 60f).points + (BodyJoint.LA to shifted(BodyJoint.LA, 54f)[BodyJoint.LA]))
        var current = 0
        for (t in 5200L..11_200 step 100) {
            val metrics = tracker.observe(moved, 720, 1280, t)
            assertEquals(1f, metrics.hands); assertEquals(1f, metrics.feet)
            val target = MotionWeights(20, 20).target(metrics, 25, base = 10)
            assertEquals(25, target)
            current = CurrentRules.withMotionFloor(10, target, 25, limiter, t)
            assertTrue(current in 10..25)
            if (t == 5200L) assertEquals(10, current)
            if (t == 6200L) assertTrue(current <= 14)
            if (t % 500 == 0L) {
                assertTrue(output.submit(EffectPreset(strength = current), 25, t))
                assertEquals(current, output.sample(t)!!.preset.strength)
            }
        }
        assertEquals(25, current)
    }

    @Test fun invalidMappingCannotProduceHandOrFootScores() {
        assertNull(MotionMapping.evaluate(Float.NaN, 20f, 40f, "test"))
        assertNull(MotionMapping.evaluate(100f, 20f, 0f, "test"))
        assertNull(MotionMapping.evaluate(100f, Float.POSITIVE_INFINITY, 40f, "test"))
        val tracker = tracker(MotionMappingSettings(0, 20))
        val metrics = tracker.observe(shifted(BodyJoint.LW, 100f), 720, 1280, 5200)
        assertEquals(0f, metrics.hands); assertEquals(0f, metrics.feet)
        assertEquals(10, MotionWeights(20, 20).target(metrics, 100, base = 10))
    }
}
