package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class ContinuousMotionTest {
    private fun body(dx: Float = 0f) = FullBody(BodyJoint.entries.zip(listOf(
        200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f, 290f to 400f, 310f to 400f,
        230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f
    )).associate { (joint, xy) -> joint to BodyPoint(xy.first + dx, xy.second, .99f) })
    private fun tracker() = StandingTracker().apply {
        beginCalibration()
        for (now in 0L..5100 step 100) observe(body(), 720, 1280, now)
        assertTrue(calibrated)
    }
    private fun modify(vararg changes: Pair<BodyJoint, BodyPoint>) = FullBody(body().points + changes)

    @Test fun lateralMovementContinuouslyUpdatesEveryJointWithoutLosingThePersonOrMovingTheAnchors() {
        val tracker = tracker()
        var previousFrame = 0L
        for ((index, dx) in listOf(10f, 20f, 40f, 60f, 100f, 140f, 80f, 40f, 0f).withIndex()) {
            val result = tracker.observe(body(dx), 720, 1280, 5200L + index * 100)
            assertTrue("translation $dx: ${result.trackingMessage}", result.tracked)
            val sample = result.tracking!!
            assertTrue(sample.frame > previousFrame); previousFrame = sample.frame
            assertEquals(290f, sample.points.getValue(BodyJoint.LW).reference.x, .001f)
            assertEquals(270f, sample.points.getValue(BodyJoint.LA).reference.x, .001f)
            assertEquals(300f, sample.handTarget.x, .001f)
            if (dx >= 60) { assertTrue(result.hands > 0); assertTrue(result.feet > 0); assertFalse(result.neutral) }
        }
    }

    @Test fun returningToTheStartStillRegistersTravelThenDecaysAfterOneSecondOfStillness() {
        val tracker = tracker()
        tracker.observe(body(80f), 720, 1280, 5200)
        tracker.observe(body(80f), 720, 1280, 5300)
        val returned = tracker.observe(body(), 720, 1280, 5400)
        assertEquals(0f, returned.tracking!!.displacement(ContinuousTracking.hands), .001f)
        assertTrue(returned.tracking.path(ContinuousTracking.hands) >= 159f)
        assertTrue(returned.hands > 0); assertTrue(returned.feet > 0)
        var still = returned
        for (now in 5500L..7500 step 100) still = tracker.observe(body(), 720, 1280, now)
        assertEquals(0f, still.hands); assertEquals(0f, still.feet); assertEquals(0f, still.score)
        assertEquals(0f, still.tracking!!.path(ContinuousTracking.hands)); assertTrue(still.neutral)
    }

    @Test fun validSidewaysOrNonstandardPosturesAreNotClassifiedAsAPersonDisappearing() {
        val tracker = tracker()
        val leaned = modify(BodyJoint.LS to BodyPoint(320f, 250f, .99f), BodyJoint.RS to BodyPoint(520f, 250f, .99f))
        val a = tracker.observe(leaned, 720, 1280, 5200)
        assertTrue(a.tracked); assertFalse(a.standing); assertNotNull(a.tracking)
        val narrow = modify(BodyJoint.LS to BodyPoint(270f, 250f, .99f), BodyJoint.RS to BodyPoint(330f, 250f, .99f))
        assertTrue(tracker.observe(narrow, 720, 1280, 5300).tracked)
    }

    @Test fun optionalElbowsCannotInterruptStandingTrackingAndLoweredHandsAreNotATPose() {
        val tracker = tracker()
        val obscured = FullBody(body(30f).points.filterKeys { it != BodyJoint.LE && it != BodyJoint.RE })
        val result = tracker.observe(obscured, 720, 1280, 5200)
        assertTrue(result.tracked); assertFalse(result.tPose); assertNotNull(result.tracking)
        val moderate = FullBody(body(50f).points.mapValues { (_, p) -> p.copy(confidence = .6f) })
        assertTrue(tracker.observe(moderate, 720, 1280, 5300).tracked)
        val lost = modify(BodyJoint.LA to BodyPoint(270f, 850f, .1f))
        val missing = tracker.observe(lost, 720, 1280, 5400)
        assertTrue(missing.tracked); assertFalse(missing.measurementReady); assertTrue(missing.trackingMessage.contains("左踝"))
    }

    @Test fun calibrationRequiresStandingLegsAndLoweredHandsRatherThanAnAnatomicalTarget() {
        val tracker = StandingTracker().apply { beginCalibration() }
        val apart = modify(BodyJoint.LK to BodyPoint(230f, 650f, .99f), BodyJoint.RK to BodyPoint(370f, 650f, .99f),
            BodyJoint.LA to BodyPoint(230f, 850f, .99f), BodyJoint.RA to BodyPoint(370f, 850f, .99f))
        for (now in 0L..6000 step 100) {
            val result = tracker.observe(apart, 720, 1280, now)
            assertTrue(result.tracked); assertFalse(result.legsTogether)
        }
        assertFalse(tracker.calibrated)
        val highHands = modify(BodyJoint.LW to BodyPoint(290f, 300f, .99f), BodyJoint.RW to BodyPoint(310f, 300f, .99f))
        for (now in 6100L..9000 step 100) assertFalse(tracker.observe(highHands, 720, 1280, now).handsInReference)
        assertFalse(tracker.calibrated)
        for (now in 9100L..14100 step 100) tracker.observe(body(), 720, 1280, now)
        assertTrue(tracker.calibrated)
    }

    @Test fun aContinuousTrackerToWeightedOutputPipelineProducesRepeatedFiniteVirtualFragments() {
        val tracker = tracker()
        val weights = MotionWeights(20, 20, 10)
        val limiter = MotionIntensity()
        val output = VirtualOutput()
        var emitted = 0
        for (now in 5200L..11200 step 100) {
            val dx = if ((now / 500) % 2 == 0L) 80f else 120f
            val result = tracker.observe(body(dx), 720, 1280, now)
            assertTrue(result.tracked)
            val target = weights.target(result, 30)
            val strength = CurrentRules.withMotionFloor(0, target, 30, limiter, now)
            assertTrue(target > 0); assertTrue(strength in 0..30)
            if (now % 500 == 0L && strength > 0) {
                assertTrue(output.submit(EffectPreset(strength = strength), 30, now)); emitted++
                assertEquals(strength, output.sample(now)!!.preset.strength)
            }
        }
        assertTrue("continuous outputs: $emitted", emitted >= 10)
    }

    @Test fun weightedFractionalContributionsAreAddedBeforeRounding() {
        val metrics = StandingMetrics(tracked = true, hands = .03f, feet = .03f, score = .03f)
        assertEquals(1, MotionWeights(20, 20).target(metrics, 30, bodyWeight = 20))
    }

    @Test fun chosenHandGuideIsLiveBeforeCalibrationAndFixedOnlyAfterFiveSeconds() {
        val tracker = StandingTracker()
        val guide = tracker.observe(body(), 720, 1280, 0).tracking!!
        assertFalse(guide.fixed); assertEquals(300f, guide.handTarget.x, .001f)
        val moved = tracker.observe(body(20f), 720, 1280, 100).tracking!!
        assertFalse(moved.fixed); assertTrue(moved.frame > guide.frame)
        assertEquals(320f, moved.handTarget.x, .001f)
        tracker.beginCalibration()
        for (now in 200L..5100 step 100) assertFalse(tracker.observe(body(), 720, 1280, now).tracking!!.fixed)
        val locked = tracker.observe(body(), 720, 1280, 5200)
        assertTrue(locked.calibrated); assertTrue(locked.tracking!!.fixed)
        assertEquals(300f, tracker.observe(body(40f), 720, 1280, 5300).tracking!!.handTarget.x, .001f)
    }
}
