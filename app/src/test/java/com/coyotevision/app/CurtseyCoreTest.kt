package com.coyotevision.app

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CurtseyCoreTest {
    private val legs = setOf(BodyJoint.LK, BodyJoint.RK, BodyJoint.LA, BodyJoint.RA)
    private fun standing() = FullBody(BodyJoint.entries.zip(listOf(
        200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f, 290f to 400f, 310f to 400f,
        230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f
    )).associate { (j, p) -> j to BodyPoint(p.first, p.second, .99f) })
    private fun bow() = FullBody(standing().points + mapOf(
        BodyJoint.LH to BodyPoint(230f, 490f, .99f), BodyJoint.RH to BodyPoint(370f, 490f, .99f),
        BodyJoint.LW to BodyPoint(150f, 560f, .99f), BodyJoint.RW to BodyPoint(450f, 560f, .99f)))
    private fun hidden(pose: FullBody) = FullBody(pose.points - legs)
    private fun flashing(pose: FullBody) = FullBody(pose.points.mapValues { (joint, point) ->
        if (joint in legs) point.copy(x = Float.NaN, y = -300f, confidence = .1f) else point
    })
    private fun tracker() = StandingTracker().apply {
        beginCalibration(bow(), standing())
        for (t in 0L..5000L step 100) observe(standing(), 720, 1280, t)
        assertTrue(calibrated); assertTrue(curtseyCalibrated)
    }

    @Test fun missingAndFlickeringLegsDoNotInvalidateTheCurrentHandAndHipMeasurement() {
        val tracker = tracker()
        for (t in 5100L..9000L step 100) {
            val pose = when (t % 300) {
                0L -> hidden(bow())
                100L -> flashing(bow())
                else -> FullBody(bow().points.mapValues { (joint, point) ->
                    if (joint in legs) point.copy(x = -900f, y = 7000f, confidence = .99f) else point
                })
            }
            val metrics = tracker.observe(pose, 720, 1280, t, curtseyCore = true)
            assertTrue(metrics.tracked); assertTrue(metrics.measurementReady); assertTrue(metrics.curtsey)
            assertTrue(metrics.curtseyCore)
            assertEquals(PersonalCurtsey.stableJoints, metrics.curtseyMatch!!.offsets.keys)
            assertEquals(0f, metrics.hands); assertEquals(0f, metrics.feet)
            assertNull(metrics.footMapping)
        }
        assertFalse(tracker.observe(hidden(bow()), 720, 1280, 9100).measurementReady)
    }

    @Test fun missingRequiredHandsOrHipsStillPauseAndCannotBorrowAnOldAcceptedFrame() {
        for (joint in listOf(BodyJoint.LW, BodyJoint.RW, BodyJoint.LH, BodyJoint.RH)) {
            val tracker = tracker()
            assertTrue(tracker.observe(hidden(bow()), 720, 1280, 5100, true).curtsey)
            val metrics = tracker.observe(FullBody(hidden(bow()).points - joint), 720, 1280, 5200, true)
            assertFalse(metrics.measurementReady); assertFalse(metrics.curtsey)
            assertFalse(PersonalCurtsey(bow()).matches(FullBody(hidden(bow()).points - joint), standing()))
            val uncertain = FullBody(hidden(bow()).points + (joint to bow()[joint].copy(confidence = .2f)))
            assertFalse(tracker.observe(uncertain, 720, 1280, 5300, true).measurementReady)
        }
    }

    @Test fun handsAndActualHipDescentAreBothNeededWhileLegGeometryCannotSupplyTheBend() {
        val template = PersonalCurtsey(bow())
        assertTrue(template.matches(hidden(bow()), standing()))
        val handsOnly = FullBody(standing().points + mapOf(
            BodyJoint.LW to bow()[BodyJoint.LW], BodyJoint.RW to bow()[BodyJoint.RW]))
        val hipsOnly = FullBody(standing().points + mapOf(
            BodyJoint.LH to bow()[BodyJoint.LH], BodyJoint.RH to bow()[BodyJoint.RH]))
        assertFalse(template.matches(handsOnly, standing()))
        assertFalse(template.matches(hipsOnly, standing()))
        val kneesOnly = FullBody(handsOnly.points + mapOf(
            BodyJoint.LK to BodyPoint(100f, 700f, .99f), BodyJoint.RK to BodyPoint(500f, 700f, .99f)))
        assertFalse(PersonalCurtsey.canTeach(kneesOnly, standing()))
        assertFalse(template.matches(kneesOnly, standing()))
        val shifted = FullBody(bow().points.mapValues { (_, p) -> p.copy(x = p.x + 80) })
        assertFalse(template.matches(shifted, standing()))
    }

    @Test fun legDropoutsDuringCueJudgmentAndRecoveryDoNotRestartTheTaskOrItsHold() {
        val tracker = tracker()
        val game = CurtseyGame(GameSettings(60_000, 5000, 5000, 6000, curtseyHoldMs = 1000), 0, Random(1))
        val events = mutableListOf<GameEvent>()
        for (t in 0L..11_100L step 100) {
            val mode = game.phase in listOf(GamePhase.CUE, GamePhase.REQUEST, GamePhase.RECOVERY)
            val pose = if (t <= 8000 || t >= 9200) standing() else bow()
            val observed = if (!mode) pose else if (t % 200 == 0L) hidden(pose) else flashing(pose)
            val metrics = tracker.observe(observed, 720, 1280, t + 20_000, mode)
            assertTrue(metrics.measurementReady)
            game.update(t, metrics)?.let { event ->
                events.add(event)
                if (event == GameEvent.CUE) assertTrue(game.startCue(t))
            }
            if (t < 9100) assertEquals(0, game.successes)
            if (t in 9200L..11_000L) assertEquals(GamePhase.RECOVERY, game.phase)
        }
        assertEquals(listOf(GameEvent.CUE, GameEvent.PROMPT, GameEvent.SUCCESS), events)
        assertEquals(1, game.successes); assertEquals(0, game.misses)
        assertEquals(GamePhase.WAITING, game.phase)
    }

    @Test fun legDropoutsDoNotExtendTheJudgmentDeadlineOrCreateRepeatedPenalties() {
        val tracker = tracker()
        val game = CurtseyGame(GameSettings(60_000, 5000, 5000, 3000), 0, Random(1))
        val events = mutableListOf<GameEvent>()
        for (t in 0L..12_000L step 100) {
            val mode = game.phase in listOf(GamePhase.CUE, GamePhase.REQUEST, GamePhase.RECOVERY)
            val metrics = tracker.observe(if (mode) hidden(standing()) else standing(), 720, 1280, t + 20_000, mode)
            game.update(t, metrics)?.let { event ->
                events.add(event)
                if (event == GameEvent.CUE) assertTrue(game.startCue(t))
            }
            if (t == 8000L) assertEquals(11_000L, game.responseDeadline)
        }
        assertEquals(listOf(GameEvent.CUE, GameEvent.PROMPT, GameEvent.PENALTY), events)
        assertEquals(1, game.misses); assertEquals(0, game.successes)
    }

    @Test fun leglessBowCalibrationStillRequiresTheFinalFiveSecondStandingBeforeStarting() {
        val tracker = StandingTracker()
        val prep = SessionPreparation().apply { begin() }
        val position = FullBody(standing().points + mapOf(
            BodyJoint.LW to BodyPoint(40f, 250f, .99f), BodyJoint.RW to BodyPoint(560f, 250f, .99f)))
        val events = mutableListOf<PreparationEvent>()
        var startedAt: Long? = null
        for (t in 0L..10_200L step 100) {
            val mode = prep.phase == PreparationPhase.CAPTURE_CURTSEY
            val pose = when { t <= 2000 -> position; t <= 5100 -> if (t % 200 == 0L) hidden(bow()) else flashing(bow()); else -> standing() }
            prep.update(t, tracker.observe(pose, 720, 1280, t, mode), pose)?.let { event ->
                events.add(event)
                when (event) {
                    PreparationEvent.POSITION_CONFIRMED -> tracker.reset()
                    PreparationEvent.CURTSEY_CONFIRMED -> tracker.beginCalibration(prep.curtseyReference, prep.positionReference)
                    PreparationEvent.START_READY -> startedAt = t
                    else -> fail("Leg flicker must not restart valid core calibration")
                }
            }
            if (t < 10_200) assertNull(startedAt)
        }
        assertEquals(listOf(PreparationEvent.POSITION_CONFIRMED, PreparationEvent.CURTSEY_CONFIRMED, PreparationEvent.START_READY), events)
        assertEquals(10_200L, startedAt)
        assertTrue(prep.curtseyReference!!.points.keys.none { it in legs })
        assertTrue(tracker.observe(hidden(bow()), 720, 1280, 10_300, true).curtsey)
    }
}
