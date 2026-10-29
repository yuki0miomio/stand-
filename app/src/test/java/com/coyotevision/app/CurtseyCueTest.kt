package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CurtseyCueTest {
    private val standing = StandingMetrics(tracked = true, calibrated = true, standing = true, neutral = true)
    private val bow = standing.copy(standing = false, neutral = false, curtsey = true)
    private fun game(penalty: Long = 3000) = CurtseyGame(GameSettings(60_000, 5000, 5000, 3000,
        penaltyMs = penalty), 0, Random(1))
    private fun feed(game: CurtseyGame, from: Long, to: Long, body: StandingMetrics = standing) =
        (from..to step 100).mapNotNull { game.update(it, body) }

    @Test fun threePulseCueFinishesBeforeTheFullResponseWindowBegins() {
        val game = game(); feed(game, 0, 4900)
        assertEquals(GameEvent.CUE, game.update(5000, standing))
        assertEquals(GamePhase.CUE, game.phase); assertEquals(0L, game.responseDeadline)
        assertFalse(game.motionAllowed); assertTrue(game.startCue(5000)); assertFalse(game.startCue(5100))
        assertTrue(feed(game, 5100, 7900, bow).isEmpty())
        assertEquals(0, game.successes); assertEquals(0, game.misses)
        assertEquals(GameEvent.PROMPT, game.update(8000, standing))
        assertEquals(11_000L, game.responseDeadline); assertEquals(CurtseyStage.STAND_FIRST, game.stage)
    }
    @Test fun delayedCueSubmissionDoesNotConsumeJudgingTimeAndCannotWaitForever() {
        val game = game(); feed(game, 0, 5000)
        feed(game, 5100, 5500)
        assertTrue(game.startCue(5500))
        feed(game, 5600, 8400)
        assertEquals(GameEvent.PROMPT, game.update(8500, standing))
        assertEquals(11_500L, game.responseDeadline)
        val failed = game(); feed(failed, 0, 6900)
        assertEquals(GameEvent.CUE_FAILED, failed.update(7000, standing))
        assertEquals(0, failed.misses)
    }
    @Test fun trackingLossOrGlobalDeadlineDuringCueCannotBecomeAPenalty() {
        val game = game(); feed(game, 0, 5000); game.startCue(5000)
        assertEquals(GameEvent.TRACKING_LOST, game.update(5100, StandingMetrics()))
        assertEquals(0, game.misses)
        val deadline = game(); feed(deadline, 0, 5000); deadline.startCue(5000)
        assertEquals(GameEvent.FINISHED, deadline.update(60_000, standing)); assertEquals(0, deadline.misses)
    }
    @Test fun fullSequenceAfterCueSucceedsAndNeverPunishes() {
        val game = game(); feed(game, 0, 5000); game.startCue(5000)
        feed(game, 5100, 8400)
        assertEquals(listOf(GameEvent.SUCCESS), feed(game, 8500, 9000, bow))
        assertTrue(feed(game, 9100, 9600).isEmpty())
        assertEquals(1, game.successes); assertEquals(0, game.misses)
    }
    @Test fun missedTaskAllowsTheEntireChosenPenaltyDurationBeforeAnyNewTask() {
        val game = game(30_000); feed(game, 0, 5000); game.startCue(5000)
        feed(game, 5100, 10_900)
        assertEquals(GameEvent.PENALTY, game.update(11_000, standing))
        assertTrue(feed(game, 11_100, 41_400).isEmpty())
        assertEquals(GamePhase.RECOVERY, game.phase); assertEquals(1, game.misses)
        game.update(41_500, standing); assertEquals(GamePhase.WAITING, game.phase)
    }
}
