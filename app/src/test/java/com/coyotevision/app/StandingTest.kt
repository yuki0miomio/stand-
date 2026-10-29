package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class StandingTest {
    private fun body(dx: Float = 0f, bow: Boolean = false, lift: Boolean = true): FullBody {
        val positions = listOf(200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f,
            (if (bow && lift) 150f else 290f) to (if (bow) if (lift) 460f else 530f else 400f),
            (if (bow && lift) 450f else 310f) to (if (bow) if (lift) 460f else 530f else 400f),
            230f to (if (bow) 480f else 450f), 370f to (if (bow) 480f else 450f),
            270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f)
        return FullBody(BodyJoint.entries.zip(positions).associate { (joint, p) -> joint to BodyPoint(p.first + dx, p.second, .99f) })
    }
    private fun calibrated(): StandingTracker = StandingTracker().also { tracker ->
        tracker.beginCalibration(body(bow = true), body())
        for (now in 0L..5100L step 100) tracker.observe(body(), 640, 1000, now)
        assertTrue(tracker.calibrated)
    }
    @Test fun standingCalibrationRequiresFiveEffectiveStillSeconds() {
        val tracker = StandingTracker(); tracker.beginCalibration()
        for (now in 0L..4900L step 100) tracker.observe(body(), 640, 1000, now)
        assertFalse(tracker.calibrated)
        val result = tracker.observe(body(), 640, 1000, 5000)
        assertTrue(result.calibrated); assertTrue(result.standing); assertEquals(0f, result.score)
    }
    @Test fun movingDuringCalibrationRestartsTheWindow() {
        val tracker = StandingTracker(); tracker.beginCalibration()
        for (now in 0L..6000L step 100) tracker.observe(body(if (now % 400 < 200) 0f else 30f), 640, 1000, now)
        assertFalse(tracker.calibrated)
    }
    @Test fun raisedHandsCannotBeCalibratedAsNeutralStanding() {
        val tracker = StandingTracker(); tracker.beginCalibration()
        val points = body().points.toMutableMap()
        points[BodyJoint.LW] = points.getValue(BodyJoint.LW).copy(y = 100f)
        points[BodyJoint.RW] = points.getValue(BodyJoint.RW).copy(y = 100f)
        for (now in 0L..6000L step 100) tracker.observe(FullBody(points), 640, 1000, now)
        assertFalse(tracker.calibrated)
    }
    @Test fun standingAtReferenceHasZeroAndLargerDeviationsHaveHigherScores() {
        val tracker = calibrated()
        assertEquals(0f, tracker.observe(body(), 640, 1000, 5200).score)
        var small = StandingMetrics(); var large = StandingMetrics()
        for (now in 5300L..6300L step 100) small = tracker.observe(body(30f), 640, 1000, now)
        for (now in 6400L..7400L step 100) large = tracker.observe(body(80f), 640, 1000, now)
        assertTrue(small.score > 0); assertTrue(large.score > small.score); assertTrue(large.score <= 1)
        for (now in 7500L..9500L step 100) large = tracker.observe(body(), 640, 1000, now)
        assertEquals(0f, large.score)
    }
    @Test fun calibrationNoiseDoesNotBecomeContinuousOutput() {
        val tracker = StandingTracker(); tracker.beginCalibration()
        for (now in 0L..5100L step 100) tracker.observe(body(if (now % 200 == 0L) -9f else 9f), 640, 1000, now)
        assertTrue(tracker.calibrated)
        for (now in 5200L..7000L step 100) {
            assertEquals(0f, tracker.observe(body(if (now % 200 == 0L) -9f else 9f), 640, 1000, now).score)
        }
    }
    @Test fun curtseyNeedsBothLoweringAndRelativeHandLift() {
        val tracker = calibrated()
        val bow = tracker.observe(body(bow = true), 640, 1000, 5200)
        assertTrue(bow.tracked); assertTrue(bow.curtsey); assertFalse(bow.standing)
        assertFalse(tracker.observe(body(bow = true, lift = false), 640, 1000, 5300).curtsey)
        assertFalse(tracker.observe(body(), 640, 1000, 5400).curtsey)
    }
    @Test fun coveredKneesAndOutOfFrameFeetDoNotProduceEvidence() {
        val tracker = calibrated()
        val bad = body().points.toMutableMap()
        bad[BodyJoint.LK] = bad.getValue(BodyJoint.LK).copy(confidence = .1f)
        val covered = tracker.observe(FullBody(bad), 640, 1000, 5200)
        assertTrue(covered.tracked); assertFalse(covered.measurementReady)
        bad[BodyJoint.LK] = body()[BodyJoint.LK]
        bad[BodyJoint.LA] = bad.getValue(BodyJoint.LA).copy(y = 1100f)
        val out = tracker.observe(FullBody(bad), 640, 1000, 5300)
        assertTrue(out.tracked); assertFalse(out.measurementReady)
    }
    @Test fun intensityIsBoundedRisesGraduallyAndDropsImmediately() {
        val limiter = MotionIntensity()
        assertEquals(0, limiter.update(1f, 20, 0))
        assertEquals(1, limiter.update(1f, 20, 250))
        assertEquals(2, limiter.update(1f, 20, 500))
        assertEquals(0, limiter.update(0f, 20, 600))
        assertTrue(limiter.update(1f, 2, 100_000) <= 2)
        assertEquals(0, limiter.update(Float.NaN, 20, 100_100))
    }
    @Test fun invalidPresetCannotPassOutputGate() {
        assertFalse(EffectPreset().validFor(0))
        assertFalse(EffectPreset(strength = 30).validFor(20))
        assertFalse(EffectPreset(strength = -1).validFor(20))
        assertFalse(EffectPreset(strength = 10, durationSeconds = 31).validFor(20))
        assertFalse(EffectPreset(channel = "C", strength = 10).validFor(20))
        assertTrue(EffectPreset(strength = 10).validFor(20))
    }
    @Test fun chosenHandSeparationWithinTheConfiguredDistanceIsLearned() {
        val tracker = StandingTracker().apply { wristNearPercent = 150 }; tracker.beginCalibration()
        val points = body().points.toMutableMap()
        points[BodyJoint.LW] = points.getValue(BodyJoint.LW).copy(x = 180f)
        points[BodyJoint.RW] = points.getValue(BodyJoint.RW).copy(x = 420f)
        for (now in 0L..6000L step 100) tracker.observe(FullBody(points), 640, 1000, now)
        assertTrue(tracker.calibrated)
    }
    @Test fun handFootAndWholeBodyMovementHaveIndependentMeasurements() {
        val tracker = calibrated()
        val hands = body().points.toMutableMap()
        hands[BodyJoint.LW] = hands.getValue(BodyJoint.LW).copy(x = 180f)
        hands[BodyJoint.RW] = hands.getValue(BodyJoint.RW).copy(x = 420f)
        val handResult = tracker.observe(FullBody(hands), 640, 1000, 5200)
        assertTrue(handResult.hands > .8f); assertEquals(0f, handResult.feet); assertFalse(handResult.outside)
        val feet = body().points.toMutableMap()
        feet[BodyJoint.LA] = feet.getValue(BodyJoint.LA).copy(x = 180f)
        val footResult = tracker.observe(FullBody(feet), 640, 1000, 5300)
        assertTrue(footResult.feet > 0f); assertTrue(footResult.hands > 0f) // Recent hand travel persists for one second.
        val moved = tracker.observe(body(120f), 640, 1000, 5400)
        assertTrue(moved.tracked); assertTrue(moved.outside)
        assertTrue(moved.hands > 0f); assertTrue(moved.feet > 0f) // Fixed background coordinates, including whole-body translation.
    }
    @Test fun horizontalStartSignalNeedsBothWristsNearShoulderHeight() {
        val tracker = calibrated()
        val points = body().points.toMutableMap()
        listOf(BodyJoint.LW to 40f, BodyJoint.LE to 120f, BodyJoint.RE to 480f, BodyJoint.RW to 560f).forEach { (j, x) ->
            points[j] = BodyPoint(x, 250f, .99f)
        }
        assertTrue(tracker.observe(FullBody(points), 640, 1000, 5200).tPose)
        points[BodyJoint.RW] = points.getValue(BodyJoint.RW).copy(y = 400f)
        assertFalse(tracker.observe(FullBody(points), 640, 1000, 5300).tPose)
    }
}
