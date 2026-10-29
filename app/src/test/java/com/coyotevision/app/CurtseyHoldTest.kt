package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CurtseyHoldTest {
    private val neutral = StandingMetrics(tracked = true, calibrated = true, standing = true, neutral = true)
    private val bow = neutral.copy(standing = false, neutral = false, curtsey = true)
    private fun game(hold: Long = 2000, response: Long = 8000) = CurtseyGame(
        GameSettings(60_000, 5000, 5000, response, cueMs = 0, curtseyHoldMs = hold), 0, Random(1)).apply {
        for (t in 0L..5400 step 100) update(t, neutral)
        assertEquals(CurtseyStage.BEND_AND_LIFT, stage)
    }
    private fun feed(game: CurtseyGame, from: Long, to: Long, pose: StandingMetrics) =
        (from..to step 100).mapNotNull { game.update(it, pose) }

    @Test fun selectedHoldImmediatelySucceedsAndGivesExactlyTwoSecondsToStandUp() {
        val game = game()
        feed(game, 5500, 7400, bow)
        assertEquals(CurtseyStage.BEND_AND_LIFT, game.stage); assertEquals(.95f, game.holdProgress, .001f)
        assertEquals(GameEvent.SUCCESS, game.update(7500, bow))
        assertEquals(GamePhase.RECOVERY, game.phase); assertEquals(1, game.successes)
        assertEquals(9500L, game.recoveryUntil)
        game.update(7550, bow); assertEquals(1f, game.holdProgress)
        assertTrue(feed(game, 7600, 9400, neutral).isEmpty()); assertFalse(game.motionAllowed)
        assertNull(game.update(9500, neutral)); assertTrue(game.motionAllowed)
        assertEquals(1, game.successes)
    }

    @Test fun anInterruptedHoldMustRestartFromZero() {
        val game = game()
        feed(game, 5500, 6500, bow)
        assertEquals(.5f, game.holdProgress, .001f)
        game.update(6600, neutral); assertEquals(0f, game.holdProgress)
        feed(game, 6700, 8600, bow)
        assertEquals(CurtseyStage.BEND_AND_LIFT, game.stage)
        assertEquals(GameEvent.SUCCESS, game.update(8700, bow)); assertEquals(GamePhase.RECOVERY, game.phase)
    }

    @Test fun aFrameGapCannotCountAsTimeHoldingThePose() {
        val game = game()
        feed(game, 5500, 6400, bow)
        game.update(7000, bow)
        assertEquals(0f, game.holdProgress)
        feed(game, 7100, 8900, bow); assertEquals(CurtseyStage.BEND_AND_LIFT, game.stage)
        assertEquals(GameEvent.SUCCESS, game.update(9000, bow)); assertEquals(GamePhase.RECOVERY, game.phase)
    }

    @Test fun aLongHoldCannotExtendTheResponseDeadlineOrTheTotalDeadline() {
        val game = game(10_000, 12_000)
        feed(game, 5500, 16_900, neutral)
        assertEquals(GameEvent.PENALTY, game.update(17_000, bow)); assertEquals(1, game.misses)
        assertEquals(GameEvent.FINISHED, game.update(60_000, neutral)); assertEquals(1, game.misses)
    }

    @Test fun recalibrationCancelsPartialHoldAndRequiresANewCompleteSequence() {
        val game = game()
        feed(game, 5500, 6500, bow)
        game.resumeAfterCalibration(6600)
        assertEquals(0f, game.holdProgress); assertEquals(CurtseyStage.STAND_FIRST, game.stage)
        assertEquals(GamePhase.WAITING, game.phase); assertEquals(0, game.misses)
    }

    @Test fun invalidHoldAndImpossibleResponseSettingsAreRejected() {
        val settings = GameSettings()
        assertTrue(settings.copy(curtseyHoldMs = 500, responseMs = 3000).valid())
        assertTrue(settings.copy(curtseyHoldMs = 10_000, responseMs = 12_000).valid())
        assertFalse(settings.copy(curtseyHoldMs = 499).valid())
        assertFalse(settings.copy(curtseyHoldMs = 10_001, responseMs = 30_000).valid())
        assertTrue(settings.copy(curtseyHoldMs = 5000, responseMs = 6000).valid())
        assertFalse(settings.copy(curtseyHoldMs = 5000, responseMs = 5000).valid())
    }
}
