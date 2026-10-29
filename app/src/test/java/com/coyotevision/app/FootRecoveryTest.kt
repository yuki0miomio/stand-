package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class FootRecoveryTest {
    private fun body() = FullBody(BodyJoint.entries.zip(listOf(200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f,
        290f to 400f, 310f to 400f, 230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f))
        .associate { (joint, xy) -> joint to BodyPoint(xy.first, xy.second, .99f) })
    private fun calibrated() = StandingTracker().apply {
        beginCalibration(); for (now in 0L..5100 step 100) observe(body(), 720, 1280, now)
        assertTrue(calibrated)
    }
    @Test fun aMovingFootWithModerateConfidenceRemainsTrackedAndProducesMotion() {
        val tracker = calibrated()
        val moving = FullBody(body().points.toMutableMap().apply {
            this[BodyJoint.LA] = getValue(BodyJoint.LA).copy(x = 220f, confidence = .4f)
        })
        val metrics = tracker.observe(moving, 720, 1280, 5200)
        assertTrue(metrics.tracked); assertTrue(metrics.measurementReady); assertTrue(metrics.feet > 0)
    }
    @Test fun anOccludedOrOutOfFrameFootKeepsThePersonAndCalibrationWithoutInventingMeasurements() {
        val tracker = calibrated()
        val bad = FullBody(body().points.toMutableMap().apply { this[BodyJoint.LA] = getValue(BodyJoint.LA).copy(confidence = .1f) })
        for (now in 5200L..8000 step 100) {
            val result = tracker.observe(bad, 720, 1280, now)
            assertTrue(result.tracked); assertTrue(result.calibrated); assertFalse(result.measurementReady)
            assertNull(result.tracking); assertEquals(0f, result.feet)
        }
        val returned = tracker.observe(body(), 720, 1280, 8100)
        assertTrue(returned.measurementReady); assertEquals(270f, returned.tracking!!.points.getValue(BodyJoint.LA).reference.x)
    }
    @Test fun coincidentKneeAndAnkleAreLocalUncertaintyRatherThanAPersonDisappearing() {
        val tracker = calibrated()
        val bad = FullBody(body().points + (BodyJoint.LA to body()[BodyJoint.LK]))
        val result = tracker.observe(bad, 720, 1280, 5200)
        assertTrue(result.tracked); assertFalse(result.measurementReady); assertTrue(tracker.calibrated)
    }
    @Test fun actualTorsoDisappearanceIsStillReportedAsPersonLoss() {
        val tracker = calibrated()
        val bad = FullBody(body().points - BodyJoint.LH)
        assertFalse(tracker.observe(bad, 720, 1280, 5200).tracked)
        assertFalse(tracker.observe(null, 720, 1280, 5300).tracked)
    }
    @Test fun localRecoveryFreezesTheClockAndRequiresFreshContinuousGoodFrames() {
        val clock = SessionClock().apply { start(1000, 60_000) }
        val gate = LimbRecovery()
        val output = VirtualOutput().apply { submit(EffectPreset(strength = 10), 20, 1000) }
        clock.pause(2000); gate.suspend(); output.clear()
        assertEquals(59_000L, clock.remaining(20_000)); assertNull(output.sample(2500))
        assertFalse(gate.canResume(20_000, true)); assertFalse(gate.canResume(20_100, false))
        assertFalse(gate.canResume(20_200, true)); assertFalse(gate.canResume(20_300, true))
        assertTrue(gate.canResume(20_400, true)); assertTrue(clock.resume(20_400)); gate.reset()
        assertEquals(58_000L, clock.remaining(21_400))
        gate.suspend(); assertFalse(gate.canResume(22_000, true)); assertFalse(gate.canResume(23_000, true))
        assertTrue(gate.canResume(23_200, true))
    }
    @Test fun repeatedPresentationOfOneOldPoseFrameCannotCompleteRecovery() {
        val gate = LimbRecovery().apply { suspend() }
        repeat(10) { assertFalse(gate.canResume(1000, true)) }
        assertFalse(gate.canResume(1100, true))
        assertTrue(gate.canResume(1200, true))
    }
}
