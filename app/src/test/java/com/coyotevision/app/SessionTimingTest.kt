package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SessionTimingTest {
    @Test fun ninetyMinuteSessionKeepsTheBudgetAcrossPausesAndStopsExactlyAtTheDeadline() {
        val clock = SessionClock().apply { start(100_000, 5_400_000) }
        assertEquals(3_600_000L, clock.remaining(1_900_000))
        clock.pause(1_900_000)
        assertEquals(3_600_000L, clock.remaining(9_000_000))
        assertTrue(clock.resume(10_000_000))
        assertEquals(1L, clock.remaining(13_599_999))
        assertEquals(0L, clock.remaining(13_600_000))
        assertFalse(clock.resume(13_600_001))
        val game = CurtseyGame(GameSettings(durationMs = 5_400_000), 0, Random(1))
        assertNotEquals(GameEvent.FINISHED, game.update(1_800_000, neutral))
        assertEquals(GameEvent.FINISHED, game.update(5_400_000, neutral))
        assertEquals(0, game.misses)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sessionsLongerThanNinetyMinutesAreRejected() {
        SessionClock().start(0, 5_400_001)
    }
    private val neutral = StandingMetrics(tracked = true, calibrated = true, standing = true, neutral = true)
    private fun game() = CurtseyGame(GameSettings(60_000, 5000, 5000, 3000), 0, Random(1))

    @Test fun preparationAndRepeatedRecalibrationNeverConsumeOrResetThePlayBudget() {
        val clock = SessionClock()
        assertEquals(0L, clock.elapsed(90_000)); assertFalse(clock.running)
        clock.start(100_000, 60_000)
        assertEquals(55_000L, clock.remaining(105_000))
        clock.pause(105_000)
        assertEquals(55_000L, clock.remaining(300_000)); assertFalse(clock.running)
        clock.pause(300_000)
        assertTrue(clock.resume(400_000)); assertFalse(clock.resume(400_001))
        assertEquals(45_000L, clock.remaining(410_000))
        clock.pause(410_000); assertTrue(clock.resume(900_000))
        assertEquals(1L, clock.remaining(944_999))
        assertEquals(0L, clock.remaining(945_000)); clock.pause(945_000)
        assertFalse(clock.resume(950_000))
        clock.reset(); assertFalse(clock.started); assertFalse(clock.running)
    }

    @Test fun cueJudgingPenaltyAndTrainingShareTheSameTotalClock() {
        val clock = SessionClock().apply { start(100_000, 60_000) }
        val game = game()
        for (real in 100_000L..105_000 step 100) game.update(clock.elapsed(real), neutral)
        assertTrue(game.startCue(clock.elapsed(105_000)))
        for (real in 105_100L..111_000 step 100) game.update(clock.elapsed(real), neutral)
        assertEquals(1, game.misses)
        assertEquals(49_000L, clock.remaining(111_000)) // 5s wait + 3s cue + 3s judging.
        assertEquals(GameEvent.FINISHED, game.update(clock.elapsed(160_000), neutral))
        assertEquals(0L, clock.remaining(160_000))
        val training = EnduranceTraining(EnduranceSettings(true, 5000, 5000, 3000, 3000), 0, 60_000, Random(1))
        assertEquals(TrainingEvent.START, training.update(5000, false))
        assertEquals(TrainingEvent.END, training.update(clock.elapsed(160_000), false))
        assertNull(CurrentRules.penalty(10, EffectPreset(strength = 5, durationSeconds = 30), 50, clock.remaining(160_000)))
    }

    @Test fun interruptedCueAndJudgingAreCancelledWithoutPunishmentAfterCalibration() {
        val clock = SessionClock().apply { start(100_000, 60_000) }
        val game = game()
        for (t in 0L..5000 step 100) game.update(t, neutral)
        game.startCue(5000)
        clock.pause(105_100)
        assertEquals(5100L, clock.elapsed(500_000))
        assertTrue(clock.resume(500_000))
        game.resumeAfterCalibration(clock.elapsed(500_000))
        assertEquals(GamePhase.WAITING, game.phase); assertFalse(game.cueStarted)
        assertEquals(0L, game.responseDeadline); assertEquals(0, game.misses)
        for (t in 5100L..10_000 step 100) assertNull(game.update(t, neutral))
        assertEquals(GameEvent.CUE, game.update(10_100, neutral)); game.startCue(10_100)
        for (t in 10_200L..13_100 step 100) game.update(t, neutral)
        assertEquals(GamePhase.REQUEST, game.phase)
        game.resumeAfterCalibration(13_200)
        assertEquals(GamePhase.WAITING, game.phase); assertEquals(0, game.misses)
        assertEquals(60_000L, game.endAt)
    }

    @Test fun completedResultsSurviveRecalibrationAndOldPenaltyCannotReplay() {
        val game = game()
        for (t in 0L..5000 step 100) game.update(t, neutral)
        game.startCue(5000)
        for (t in 5100L..11_000 step 100) game.update(t, neutral)
        assertEquals(1, game.misses)
        game.resumeAfterCalibration(11_100)
        assertEquals(1, game.misses); assertEquals(GamePhase.WAITING, game.phase)
        assertNull(game.update(11_100, neutral))
        assertEquals(GameEvent.FINISHED, game.update(60_000, neutral))
        game.resumeAfterCalibration(60_000); assertEquals(GamePhase.FINISHED, game.phase)
    }

    @Test fun interruptedTrainingReschedulesWithoutExtendingTheSharedDeadline() {
        val settings = EnduranceSettings(true, 5000, 5000, 3000, 3000)
        val training = EnduranceTraining(settings, 0, 60_000, Random(1))
        assertEquals(TrainingEvent.START, training.update(5000, false))
        training.resumeAfterCalibration(5500)
        assertFalse(training.active); assertEquals(0L, training.until)
        assertNull(training.update(10_499, false))
        assertEquals(TrainingEvent.START, training.update(10_500, false))
        training.resumeAfterCalibration(59_000)
        assertNull(training.update(59_900, false)); assertFalse(training.active)
    }

    @Test fun aLongCurtseyIntervalDoesNotRejectOrExtendTheOverallGameDuration() {
        val settings = GameSettings(60_000, 300_000, 300_000, 30_000)
        assertTrue(settings.valid())
        val game = CurtseyGame(settings, 0, Random(1))
        for (t in 0L..59_900 step 100) assertNull(game.update(t, neutral))
        assertEquals(GameEvent.FINISHED, game.update(60_000, neutral))
        assertEquals(0, game.misses)
    }
}
