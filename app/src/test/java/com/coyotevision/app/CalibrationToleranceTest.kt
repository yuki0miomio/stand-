package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class CalibrationToleranceTest {
    private fun body(handY: Float = 400f): FullBody = FullBody(BodyJoint.entries.zip(listOf(
        200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f,
        256f to handY, 344f to handY,
        230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f
    )).associate { (j, xy) -> j to BodyPoint(xy.first, xy.second, .99f) })

    @Test fun briefThresholdWobblePausesProgressAndDoesNotCountAsGoodHoldTime() {
        val tracker = StandingTracker().apply { beginCalibration() }
        for (t in 0L..900 step 100) tracker.observe(body(321f), 720, 1280, t)
        val bad = tracker.observe(body(319f), 720, 1280, 1000)
        assertFalse(bad.neutral); assertFalse(bad.calibrated)
        assertEquals(.18f, bad.calibrationProgress, .001f)
        tracker.observe(body(319f), 720, 1280, 1100)
        val recovered = tracker.observe(body(321f), 720, 1280, 1200)
        assertEquals(.18f, recovered.calibrationProgress, .001f)
        for (t in 1300L..5200 step 100) assertFalse(tracker.observe(body(321f), 720, 1280, t).calibrated)
        assertTrue(tracker.observe(body(321f), 720, 1280, 5300).calibrated)
    }
    @Test fun sustainedBadPostureClearsTheHoldAndCannotStart() {
        val tracker = StandingTracker().apply { beginCalibration() }
        for (t in 0L..900 step 100) tracker.observe(body(321f), 720, 1280, t)
        for (t in 1000L..2000 step 100) assertFalse(tracker.observe(body(319f), 720, 1280, t).calibrated)
        val reset = tracker.observe(body(321f), 720, 1280, 2100)
        assertEquals(0f, reset.calibrationProgress, .001f)
        for (t in 2200L..7000 step 100) assertFalse(tracker.observe(body(321f), 720, 1280, t).calibrated)
        assertTrue(tracker.observe(body(321f), 720, 1280, 7100).calibrated)
    }
    @Test fun slowButContinuousGoodFramesCanStillCompleteCalibration() {
        val tracker = StandingTracker().apply { beginCalibration() }
        for (t in 0L..4800 step 400) assertFalse(tracker.observe(body(), 720, 1280, t).calibrated)
        assertTrue(tracker.observe(body(), 720, 1280, 5200).calibrated)
    }
    @Test fun raisedLoweredOneSidedOrUnreliableWristsCannotConfirmPosition() {
        for ((left, right) in listOf(
            BodyPoint(50f, 50f, .99f) to BodyPoint(550f, 50f, .99f),
            BodyPoint(50f, 450f, .99f) to BodyPoint(550f, 450f, .99f),
            BodyPoint(450f, 250f, .99f) to BodyPoint(550f, 250f, .99f),
            BodyPoint(50f, 250f, .1f) to BodyPoint(550f, 250f, .99f))) {
            val pose = FullBody(body().points + mapOf(BodyJoint.LW to left, BodyJoint.RW to right))
            assertFalse(StandingTracker().observe(pose, 720, 1280, 0).tPose)
        }
    }
}
