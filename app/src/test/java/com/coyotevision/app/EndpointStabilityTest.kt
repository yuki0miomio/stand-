package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class EndpointStabilityTest {
    private fun body() = FullBody(BodyJoint.entries.zip(listOf(200f to 250f, 400f to 250f,
        180f to 350f, 420f to 350f, 290f to 400f, 310f to 400f,
        230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f))
        .associate { (joint, xy) -> joint to BodyPoint(xy.first, xy.second, .99f) })
    private fun change(body: FullBody, vararg shifts: Pair<BodyJoint, Pair<Float, Float>>) = FullBody(body.points.toMutableMap().apply {
        shifts.forEach { (joint, shift) -> this[joint] = getValue(joint).let { it.copy(x = it.x + shift.first, y = it.y + shift.second) } }
    })
    private fun tracker() = StandingTracker().apply {
        beginCalibration(change(body(), BodyJoint.LH to (0f to 30f), BodyJoint.RH to (0f to 30f),
            BodyJoint.LW to (-140f to 60f), BodyJoint.RW to (140f to 60f)), body())
        for (now in 0L..5100 step 100) observe(body(), 640, 1000, now)
        assertTrue(calibrated)
    }

    @Test fun smallWristAndAnkleJitterDoesNotCreateAnyMotionIncrement() {
        val tracker = tracker()
        for (now in 5200L..8000 step 100) {
            val delta = if (now % 200 == 0L) 9f else -9f
            val pose = change(body(), BodyJoint.LW to (delta to 0f), BodyJoint.RW to (-delta to 0f),
                BodyJoint.LA to (delta * .7f to 0f), BodyJoint.RA to (-delta * .7f to 0f))
            val metrics = tracker.observe(pose, 640, 1000, now)
            assertTrue(metrics.tracked); assertTrue(metrics.neutral)
            assertEquals(0f, metrics.hands); assertEquals(0f, metrics.feet); assertEquals(0f, metrics.score)
        }
    }

    @Test fun elbowAndSmallKneePositionChangesDoNotIncreaseTheMainMotionScore() {
        val tracker = tracker()
        val pose = change(body(), BodyJoint.LE to (120f to 0f), BodyJoint.RE to (-120f to 0f),
            BodyJoint.LK to (5f to 0f), BodyJoint.RK to (-5f to 0f))
        for (now in 5200L..6500 step 100) {
            val metrics = tracker.observe(pose, 640, 1000, now)
            assertTrue(metrics.tracked); assertTrue(metrics.standing)
            assertEquals(0f, metrics.score); assertEquals(0f, metrics.hands); assertEquals(0f, metrics.feet)
        }
    }

    @Test fun deliberateHandMovementAndSlowSmallStepsStillProduceIndependentScores() {
        val tracker = tracker()
        val movedHands = change(body(), BodyJoint.LW to (-110f to 0f), BodyJoint.RW to (110f to 0f))
        val hands = tracker.observe(movedHands, 640, 1000, 5200)
        assertTrue(hands.hands > .8f); assertEquals(0f, hands.feet)
        var metrics = tracker.observe(body(), 640, 1000, 5300)
        for (i in 1..20) metrics = tracker.observe(change(body(), BodyJoint.LA to (i * 2f to 0f)), 640, 1000, 5300L + i * 100)
        assertTrue(metrics.tracked); assertTrue(metrics.feet > .2f); assertEquals(0f, metrics.hands)
    }

    @Test fun oppositeFootMovementStillScoresWithoutInvalidatingTheHandAndHipCurtsey() {
        val tracker = tracker().apply { curtseyRange = .20f }
        val bow = change(body(), BodyJoint.LH to (0f to 30f), BodyJoint.RH to (0f to 30f),
            BodyJoint.LW to (-140f to 60f), BodyJoint.RW to (140f to 60f))
        assertTrue(tracker.observe(bow, 640, 1000, 5200).curtsey)
        val spread = change(bow, BodyJoint.LA to (-60f to 0f), BodyJoint.RA to (60f to 0f))
        val metrics = tracker.observe(spread, 640, 1000, 5300)
        assertTrue(metrics.tracked); assertTrue(metrics.curtsey); assertTrue(metrics.feet > 0)
        tracker.curtseyRange = .40f
        val relaxed = tracker.observe(spread, 640, 1000, 5400)
        assertTrue(relaxed.curtsey); assertTrue(relaxed.feet > 0)
    }

    @Test fun missingOrOutOfFrameRawEndpointsCannotBeCoveredByTheFilter() {
        val tracker = tracker()
        val hidden = FullBody(body().points.toMutableMap().apply { this[BodyJoint.LW] = getValue(BodyJoint.LW).copy(confidence = .1f) })
        assertFalse(tracker.observe(hidden, 640, 1000, 5200).measurementReady)
        assertFalse(tracker.observe(change(body(), BodyJoint.LA to (0f to 300f)), 640, 1000, 5300).measurementReady)
        assertTrue(tracker.observe(body(), 640, 1000, 5400).tracked)
    }

    @Test fun filterDoesNotReusePreviousCoordinatesAfterAGapOrReset() {
        val filter = EndpointFilter()
        filter.seed(body(), 0)
        val small = change(body(), BodyJoint.LW to (10f to 0f))
        assertTrue(filter.apply(small, 200f, 100)[BodyJoint.LW].x < small[BodyJoint.LW].x)
        assertEquals(small[BodyJoint.LW].x, filter.apply(small, 200f, 600)[BodyJoint.LW].x)
        filter.reset()
        assertEquals(body()[BodyJoint.LW].x, filter.apply(body(), 200f, 700)[BodyJoint.LW].x)
    }
}
