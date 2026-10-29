package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class LearnedHandPositionTest {
    private fun body(handY: Float = 520f) = FullBody(BodyJoint.entries.zip(listOf(
        200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f,
        240f to handY, 360f to handY, 230f to 450f, 370f to 450f,
        270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f
    )).associate { (joint, xy) -> joint to BodyPoint(xy.first, xy.second, .99f) })
    private fun capture(handY: Float = 520f) = StandingTracker().apply {
        beginCalibration()
        for (t in 0L..4900 step 100) assertFalse(observe(body(handY), 720, 1280, t).calibrated)
        assertTrue(observe(body(handY), 720, 1280, 5000).calibrated)
    }

    @Test fun fiveSecondsTeachActualHandsEvenOutsideTheFormerAbdominalTarget() {
        val tracker = capture()
        val resting = tracker.observe(body(), 720, 1280, 5100)
        assertTrue(resting.neutral); assertEquals(0f, resting.hands); assertEquals(0f, resting.feet)
        val fixed = resting.tracking!!
        assertTrue(fixed.fixed); assertEquals(520f, fixed.handTarget.y, .001f)
        for (joint in ContinuousTracking.points) {
            assertEquals(body()[joint].x, fixed.points.getValue(joint).reference.x, .001f)
            assertEquals(body()[joint].y, fixed.points.getValue(joint).reference.y, .001f)
        }
        assertTrue(fixed.handZone.contains(body()[BodyJoint.LW]))
        assertTrue(fixed.handZone.contains(body()[BodyJoint.RW]))
    }
    @Test fun movingToAnEstimatedNavelIsMovementAndReturningToLearnedHandsRestoresNeutral() {
        val tracker = capture()
        val wrong = tracker.observe(body(400f), 720, 1280, 5100)
        assertFalse(wrong.neutral); assertTrue(wrong.hands > 0f); assertFalse(wrong.handsInReference)
        assertEquals(520f, wrong.tracking!!.handTarget.y, .001f)
        var returned = wrong
        for (t in 5200L..7200 step 100) returned = tracker.observe(body(), 720, 1280, t)
        assertTrue(returned.neutral); assertEquals(0f, returned.hands)
        assertEquals(520f, returned.tracking!!.handTarget.y, .001f)
    }
    @Test fun movingDuringCaptureRequiresFiveFreshStableSecondsAtTheNewPosition() {
        val tracker = StandingTracker().apply { beginCalibration() }
        for (t in 0L..2900 step 100) assertFalse(tracker.observe(body(), 720, 1280, t).calibrated)
        val moved = tracker.observe(body(560f), 720, 1280, 3000)
        assertFalse(moved.calibrated); assertEquals(0f, moved.calibrationProgress, .001f)
        for (t in 3100L..7900 step 100) assertFalse(tracker.observe(body(560f), 720, 1280, t).calibrated)
        val fixed = tracker.observe(body(560f), 720, 1280, 8000)
        assertTrue(fixed.calibrated); assertEquals(560f, fixed.tracking!!.handTarget.y, .001f)
    }
    @Test fun holdingTheStartGestureOrMissingWristsCannotTeachTheRestingPosition() {
        for (pose in listOf(
            FullBody(body().points + mapOf(BodyJoint.LW to BodyPoint(40f, 250f, .99f), BodyJoint.RW to BodyPoint(560f, 250f, .99f))),
            FullBody(body().points - BodyJoint.LW))) {
            val tracker = StandingTracker().apply { beginCalibration() }
            for (t in 0L..6000 step 100) assertFalse(tracker.observe(pose, 720, 1280, t).calibrated)
        }
    }
    @Test fun aNewCaptureReplacesThePreviousHandReference() {
        val tracker = capture()
        tracker.beginCalibration()
        for (t in 6000L..10_900 step 100) assertFalse(tracker.observe(body(460f), 720, 1280, t).calibrated)
        val result = tracker.observe(body(460f), 720, 1280, 11_000)
        assertTrue(result.calibrated); assertEquals(460f, result.tracking!!.handTarget.y, .001f)
    }
}
