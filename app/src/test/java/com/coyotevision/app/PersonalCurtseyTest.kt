package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PersonalCurtseyTest {
    private fun standing() = FullBody(BodyJoint.entries.zip(listOf(
        200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f, 290f to 500f, 310f to 500f,
        230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f
    )).associate { (joint, xy) -> joint to BodyPoint(xy.first, xy.second, .99f) })
    private fun taught() = FullBody(standing().points + mapOf(
        BodyJoint.LH to BodyPoint(230f, 500f, .99f), BodyJoint.RH to BodyPoint(370f, 500f, .99f),
        BodyJoint.LW to BodyPoint(200f, 360f, .99f), BodyJoint.RW to BodyPoint(400f, 360f, .99f)))
    private fun tracker() = StandingTracker().apply {
        beginCalibration(taught(), standing())
        for (t in 0L..5000 step 100) observe(standing(), 720, 1280, t)
        assertTrue(calibrated); assertTrue(curtseyCalibrated)
    }

    @Test fun theActualTaughtPoseMatchesInsteadOfTheFormerGenericHandHeightRule() {
        assertTrue(PersonalCurtsey.canTeach(taught(), standing()))
        val metrics = tracker().observe(taught(), 720, 1280, 5100)
        assertTrue(metrics.curtsey); assertFalse(metrics.neutral)
        val generic = FullBody(standing().points + mapOf(
            BodyJoint.LH to BodyPoint(230f, 480f, .99f), BodyJoint.RH to BodyPoint(370f, 480f, .99f),
            BodyJoint.LW to BodyPoint(150f, 460f, .99f), BodyJoint.RW to BodyPoint(450f, 460f, .99f)))
        assertFalse(tracker().observe(generic, 720, 1280, 5100).curtsey)
    }
    @Test fun handMovementWithoutTheLearnedBodyBendCannotCompleteThePose() {
        val handsOnly = FullBody(standing().points + mapOf(BodyJoint.LW to taught()[BodyJoint.LW], BodyJoint.RW to taught()[BodyJoint.RW]))
        assertFalse(PersonalCurtsey.canTeach(handsOnly, standing()))
        assertFalse(tracker().observe(handsOnly, 720, 1280, 5100).curtsey)
        assertFalse(tracker().observe(standing(), 720, 1280, 5100).curtsey)
    }
    @Test fun simplyClosingTheFeetAfterTheStartGestureCannotPretendToBeAKneeBend() {
        val start = FullBody(standing().points + mapOf(
            BodyJoint.LK to BodyPoint(230f, 650f, .99f), BodyJoint.RK to BodyPoint(370f, 650f, .99f),
            BodyJoint.LA to BodyPoint(230f, 850f, .99f), BodyJoint.RA to BodyPoint(370f, 850f, .99f)))
        val handsOut = FullBody(standing().points + mapOf(BodyJoint.LW to taught()[BodyJoint.LW], BodyJoint.RW to taught()[BodyJoint.RW]))
        assertFalse(PersonalCurtsey.canTeach(handsOut, start))
    }
    @Test fun wholeBodyTranslationIsRejectedButLegMovementDoesNotInvalidateTheHandAndHipMatch() {
        val shifted = FullBody(taught().points.mapValues { (_, p) -> p.copy(x = p.x + 80) })
        assertFalse(tracker().observe(shifted, 720, 1280, 5100).curtsey)
        val feetMoved = FullBody(taught().points + (BodyJoint.LA to taught()[BodyJoint.LA].copy(x = 200f)))
        assertTrue(tracker().observe(feetMoved, 720, 1280, 5100).curtsey)
    }
    @Test fun missingWristsPauseMeasurementInsteadOfMatchingAnOldTemplateFrame() {
        val tracker = tracker(); assertTrue(tracker.observe(taught(), 720, 1280, 5100).curtsey)
        val missing = tracker.observe(FullBody(taught().points - BodyJoint.LW), 720, 1280, 5200)
        assertFalse(missing.measurementReady); assertFalse(missing.curtsey)
        assertTrue(tracker.observe(taught(), 720, 1280, 5300).curtsey)
    }
    @Test fun aTrackerWithoutATaughtTemplateHasNoGenericCurtseyFallback() {
        val tracker = StandingTracker().apply { beginCalibration() }
        for (t in 0L..5000 step 100) tracker.observe(standing(), 720, 1280, t)
        assertTrue(tracker.calibrated); assertFalse(tracker.curtseyCalibrated)
        assertFalse(tracker.observe(taught(), 720, 1280, 5100).curtsey)
    }
    @Test fun theGameUsesTheTaughtPoseAndCompletesAtTheChosenHoldWithoutWaitingForRestingHands() {
        val tracker = tracker()
        val game = CurtseyGame(GameSettings(60_000, 5000, 5000, 6000, curtseyHoldMs = 500), 0, Random(1))
        fun feed(from: Long, to: Long, body: FullBody): List<GameEvent> = (from..to step 100).mapNotNull { t ->
            val event = game.update(t, tracker.observe(body, 720, 1280, t + 6000))
            if (event == GameEvent.CUE) game.startCue(t)
            event
        }
        assertEquals(listOf(GameEvent.CUE, GameEvent.PROMPT), feed(0, 8400, standing()))
        assertTrue(feed(8500, 8900, taught()).isEmpty()); assertEquals(0, game.successes)
        assertEquals(listOf(GameEvent.SUCCESS), feed(9000, 9000, taught()))
        assertEquals(GamePhase.RECOVERY, game.phase)
        assertTrue(feed(9100, 10_900, taught()).isEmpty()); assertFalse(game.motionAllowed)
        assertTrue(feed(11_000, 11_000, standing()).isEmpty()); assertTrue(game.motionAllowed)
        assertEquals(1, game.successes); assertEquals(0, game.misses)
    }
}
