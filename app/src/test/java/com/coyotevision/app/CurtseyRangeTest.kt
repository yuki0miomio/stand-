package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Real preparation -> recorded template -> tracker -> game, without supplying synthetic pose flags. */
class CurtseyRangeTest {
    private fun rest() = FullBody(BodyJoint.entries.zip(listOf(
        200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f, 290f to 400f, 310f to 400f,
        230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f
    )).associate { (joint, xy) -> joint to BodyPoint(xy.first, xy.second, .99f) })
    private fun positioned() = FullBody(rest().points + mapOf(
        BodyJoint.LW to BodyPoint(40f, 250f, .99f), BodyJoint.RW to BodyPoint(560f, 250f, .99f)))
    private fun bow() = FullBody(rest().points + mapOf(
        BodyJoint.LH to BodyPoint(230f, 490f, .99f), BodyJoint.RH to BodyPoint(370f, 490f, .99f),
        BodyJoint.LW to BodyPoint(180f, 580f, .99f), BodyJoint.RW to BodyPoint(440f, 590f, .99f),
        BodyJoint.LA to BodyPoint(150f, 850f, .99f)))
    private fun calibrated(): Pair<StandingTracker, SessionPreparation> {
        val tracker = StandingTracker()
        val preparation = SessionPreparation().apply { begin() }
        var started = false
        for (t in 0L..10_200 step 100) {
            val body = when { t <= 2000 -> positioned(); t <= 5100 -> bow(); else -> rest() }
            when (preparation.update(t, tracker.observe(body, 720, 1280, t), body)) {
                PreparationEvent.POSITION_CONFIRMED -> tracker.reset()
                PreparationEvent.CURTSEY_CONFIRMED -> tracker.beginCalibration(preparation.curtseyReference, preparation.positionReference)
                PreparationEvent.START_READY -> started = true
                PreparationEvent.POSITION_RESET, PreparationEvent.CURTSEY_RETRY -> fail("Valid personal calibration was rejected")
                null -> Unit
            }
        }
        assertTrue(started); assertTrue(tracker.curtseyCalibrated)
        return tracker to preparation
    }

    @Test fun recordedCoordinatesAndConfiguredRadiusReachTheRuntimeMatcher() {
        val (tracker, preparation) = calibrated()
        tracker.curtseyRange = .30f
        val metrics = tracker.observe(bow(), 720, 1280, 10_300)
        assertTrue(metrics.curtsey)
        val report = metrics.curtseyMatch!!
        for (joint in PersonalCurtsey.stableJoints) {
            assertEquals(preparation.curtseyReference!![joint].x, report.reference[joint].x, .001f)
            assertEquals(preparation.curtseyReference!![joint].y, report.reference[joint].y, .001f)
        }
        assertEquals(60f, report.radius, .001f)
        assertTrue(report.offsets.values.all { it < .001f })
    }

    @Test fun adjustableRadiusAppliesToWristsAndHips() {
        for (joint in listOf(BodyJoint.LW, BodyJoint.LH)) {
            val (tracker, _) = calibrated()
            val original = bow()[joint]
            val variant = FullBody(bow().points + (joint to original.copy(x = original.x - 45f)))
            tracker.curtseyRange = .20f
            val strict = tracker.observe(variant, 720, 1280, 10_300)
            assertFalse(strict.curtsey)
            assertTrue(strict.curtseyMatch!!.reason.contains(joint.label))
            tracker.curtseyRange = .30f
            assertTrue(tracker.observe(variant, 720, 1280, 10_400).curtsey)
            assertFalse(tracker.observe(variant, 720, 1280, 10_500).curtseyMatch!!.reference.points.containsKey(BodyJoint.LA))
        }
    }

    @Test fun cueStandingThenPersonalBowCompletesBeforeTheOptionalReturnInsideSelectedRange() {
        val (tracker, _) = calibrated()
        tracker.curtseyRange = .30f
        val game = CurtseyGame(GameSettings(60_000, 5000, 5000, 6000, curtseyHoldMs = 500), 0, Random(1))
        fun tick(t: Long, pose: FullBody): GameEvent? {
            val event = game.update(t, tracker.observe(pose, 720, 1280, t + 20_000))
            if (event == GameEvent.CUE) assertTrue(game.startCue(t))
            return event
        }
        for (t in 0L..7900 step 100) tick(t, rest())
        val variant = FullBody(bow().points + (BodyJoint.LA to bow()[BodyJoint.LA].copy(x = 105f)))
        assertEquals(GameEvent.PROMPT, tick(8000, variant))
        assertEquals(CurtseyStage.BEND_AND_LIFT, game.stage)
        for (t in 8100L..8500 step 100) assertNull(tick(t, variant))
        assertEquals(CurtseyStage.BEND_AND_LIFT, game.stage)
        assertEquals(GameEvent.SUCCESS, tick(8600, variant))
        assertEquals(GamePhase.RECOVERY, game.phase)
        val relaxedReturn = FullBody(rest().points + mapOf(
            BodyJoint.LW to rest()[BodyJoint.LW].copy(x = 330f),
            BodyJoint.RW to rest()[BodyJoint.RW].copy(x = 350f)))
        val returned = tracker.observe(relaxedReturn, 720, 1280, 28_650)
        assertFalse(returned.neutral) // Ordinary motion monitoring retains its own stricter resting-hand rule.
        assertTrue(returned.curtseyNeutral)
        for (t in 8700L..9100 step 100) assertNull(tick(t, relaxedReturn))
        assertNull(tick(9200, relaxedReturn)); assertFalse(game.motionAllowed)
        for (t in 9300L..10_500 step 100) assertNull(tick(t, relaxedReturn))
        assertNull(tick(10_600, relaxedReturn)); assertTrue(game.motionAllowed)
        assertEquals(1, game.successes); assertEquals(0, game.misses)
    }

    @Test fun widerRadiusCannotTreatStandingOrHandsOnlyAsTheLearnedBow() {
        val (tracker, _) = calibrated(); tracker.curtseyRange = .80f
        assertFalse(tracker.observe(rest(), 720, 1280, 10_300).curtsey)
        val handsOnly = FullBody(rest().points + mapOf(
            BodyJoint.LW to bow()[BodyJoint.LW], BodyJoint.RW to bow()[BodyJoint.RW]))
        assertFalse(tracker.observe(handsOnly, 720, 1280, 10_400).curtsey)
        val actual = tracker.observe(bow(), 720, 1280, 10_500)
        assertTrue(actual.curtsey); assertFalse(actual.curtseyNeutral)
    }

    @Test fun invalidRangeAndMissingPointsCannotCreateAnAcceptedPose() {
        val template = PersonalCurtsey(bow())
        for (range in listOf(Float.NaN, Float.POSITIVE_INFINITY, .09f, .81f))
            assertFalse(template.matches(bow(), rest(), range))
        assertFalse(template.matches(FullBody(bow().points - BodyJoint.LW), rest(), .80f))
        assertFalse(template.matches(FullBody(bow().points +
            (BodyJoint.LW to bow()[BodyJoint.LW].copy(confidence = Float.NaN))), rest(), .80f))
        val (tracker, _) = calibrated(); tracker.curtseyRange = .80f
        val hidden = tracker.observe(FullBody(bow().points - BodyJoint.LA), 720, 1280, 10_300)
        assertFalse(hidden.measurementReady); assertFalse(hidden.curtsey); assertNull(hidden.curtseyMatch)
    }
}
