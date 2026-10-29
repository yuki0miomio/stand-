package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class WristStandingTest {
    private fun body(gap: Float = 80f) = FullBody(BodyJoint.entries.zip(listOf(
        200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f, (300f - gap / 2) to 400f, (300f + gap / 2) to 400f,
        230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f
    )).associate { (j, p) -> j to BodyPoint(p.first, p.second, .99f) })
    private fun obscured(pose: FullBody) = FullBody(pose.points - setOf(BodyJoint.LE, BodyJoint.RE, BodyJoint.LS))
    private fun tracker() = StandingTracker().apply { beginCalibration(positionReference = body()) }

    @Test fun intermittentShouldersAndMissingElbowsCannotResetTheFiveSecondWristHold() {
        val tracker = tracker()
        for (t in 0L..4900L step 100) {
            val metrics = tracker.observe(if (t % 200 == 0L) body() else obscured(body()), 720, 1280, t)
            assertFalse(metrics.calibrated)
            assertEquals(t / 5000f, metrics.calibrationProgress, .001f)
        }
        assertTrue(tracker.observe(body(), 720, 1280, 5000).calibrated)
    }

    @Test fun shoulderCoordinateJitterDoesNotActAsWristMovementOrChangeThePositionedScale() {
        val tracker = tracker()
        for (t in 0L..5000L step 100) {
            val delta = if (t % 200 == 0L) 35f else -35f
            val pose = FullBody(body().points.mapValues { (j, p) ->
                if (j in setOf(BodyJoint.LS, BodyJoint.RS)) p.copy(x = p.x + delta, y = p.y + delta) else p
            })
            val metrics = tracker.observe(pose, 720, 1280, t)
            assertEquals(t / 5000f, metrics.calibrationProgress, .001f)
            if (t == 5000L) assertTrue(metrics.calibrated)
        }
    }

    @Test fun configuredWristDistanceChangesWhetherTheSameHandPositionCanBeConfirmed() {
        val tracker = tracker()
        for (t in 0L..4900L step 100) assertEquals(0f, tracker.observe(body(150f), 720, 1280, t).calibrationProgress, .001f)
        assertFalse(tracker.calibrated)
        tracker.wristNearPercent = 80
        for (t in 5000L..9900L step 100) assertFalse(tracker.observe(body(150f), 720, 1280, t).calibrated)
        assertTrue(tracker.observe(body(150f), 720, 1280, 10_000).calibrated)
        assertEquals(150f, tracker.observe(body(150f), 720, 1280, 10_100).tracking!!.handGap, .001f)
        for (invalid in listOf(-1, 0, 4, 151, Int.MAX_VALUE)) assertFalse(WristCalibration.valid(invalid))
        assertTrue(WristCalibration.valid(5)); assertTrue(WristCalibration.valid(150))
    }

    @Test fun completedWristTimeWaitsForFreshRealBodyFramesInsteadOfInventingMissingShoulders() {
        val tracker = tracker()
        for (t in 0L..4900L step 100) {
            val metrics = tracker.observe(obscured(body()), 720, 1280, t)
            assertEquals(t / 5000f, metrics.calibrationProgress, .001f)
            assertFalse(metrics.calibrated)
        }
        assertFalse(tracker.observe(body(), 720, 1280, 5000).calibrated)
        assertFalse(tracker.observe(body(), 720, 1280, 5100).calibrated)
        val ready = tracker.observe(body(), 720, 1280, 5200)
        assertTrue(ready.calibrated); assertTrue(ready.tracking!!.fixed)
        assertEquals(260f, ready.tracking!!.points.getValue(BodyJoint.LW).reference.x, .001f)
    }

    @Test fun missingWristsAndActualHandMovementStillCannotAccumulateStableTime() {
        val tracker = tracker()
        for (t in 0L..2900L step 100) tracker.observe(body(), 720, 1280, t)
        val hidden = FullBody(body().points - BodyJoint.LW)
        for (t in 3000L..3900L step 100) assertFalse(tracker.observe(hidden, 720, 1280, t).calibrated)
        assertEquals(0f, tracker.observe(body(), 720, 1280, 4000).calibrationProgress, .001f)
        val moved = FullBody(body().points.mapValues { (j, p) -> if (j in ContinuousTracking.hands) p.copy(y = p.y + 40) else p })
        for (t in 4100L..7000L step 100) tracker.observe(moved, 720, 1280, t)
        assertFalse(tracker.calibrated)
    }

    @Test fun theRealThreeStageFlowFinishesWithShoulderFlickerDuringTheFinalStep() {
        val tracker = StandingTracker()
        val prep = SessionPreparation().apply { begin() }
        val position = FullBody(body().points + mapOf(BodyJoint.LW to BodyPoint(40f, 250f, .99f), BodyJoint.RW to BodyPoint(560f, 250f, .99f)))
        val bow = FullBody(body().points + mapOf(
            BodyJoint.LH to BodyPoint(230f, 490f, .99f), BodyJoint.RH to BodyPoint(370f, 490f, .99f),
            BodyJoint.LW to BodyPoint(150f, 560f, .99f), BodyJoint.RW to BodyPoint(450f, 560f, .99f)))
        val events = mutableListOf<PreparationEvent>()
        for (t in 0L..10_200L step 100) {
            val pose = when { t <= 2000 -> position; t <= 5100 -> bow; t % 200 == 0L -> body(); else -> obscured(body()) }
            val metrics = tracker.observe(pose, 720, 1280, t, prep.phase == PreparationPhase.CAPTURE_CURTSEY)
            prep.update(t, metrics, pose)?.let { event ->
                events += event
                when (event) {
                    PreparationEvent.POSITION_CONFIRMED -> tracker.reset()
                    PreparationEvent.CURTSEY_CONFIRMED -> tracker.beginCalibration(prep.curtseyReference, prep.positionReference)
                    PreparationEvent.START_READY -> assertEquals(10_200L, t)
                    else -> fail("Shoulder flicker must not discard the confirmed position or wrist progress")
                }
            }
        }
        assertEquals(listOf(PreparationEvent.POSITION_CONFIRMED, PreparationEvent.CURTSEY_CONFIRMED, PreparationEvent.START_READY), events)
    }
}
