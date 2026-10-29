package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CurtseyGameTest {
    private val standing = StandingMetrics(true, standing = true, calibrated = true, neutral = true)
    private val bow = StandingMetrics(true, curtsey = true, calibrated = true)
    private val settings = GameSettings(60_000, 5000, 5000, 3000, cueMs = 0)
    private fun game() = CurtseyGame(settings, 0, Random(42))
    private fun feed(game: CurtseyGame, from: Long, to: Long, body: StandingMetrics): List<GameEvent> =
        (from..to step 100).mapNotNull { game.update(it, body) }

    @Test fun randomPromptUsesConfiguredWindowAndDisablesMotionDuringTask() {
        val game = game()
        assertTrue(feed(game, 0, 4900, standing).isEmpty())
        assertEquals(GameEvent.PROMPT, game.update(5000, standing))
        assertFalse(game.motionAllowed); assertEquals(8000L, game.responseDeadline)
    }
    @Test fun standingThenHeldBowCompletesBeforeReturningAndSuppressesMotionForTwoSeconds() {
        val game = game(); feed(game, 0, 5000, standing)
        feed(game, 5100, 5400, standing)
        assertEquals(CurtseyStage.BEND_AND_LIFT, game.stage)
        assertEquals(listOf(GameEvent.SUCCESS), feed(game, 5500, 6000, bow))
        assertEquals(GamePhase.RECOVERY, game.phase)
        assertTrue(feed(game, 6100, 6600, standing).isEmpty())
        assertEquals(1, game.successes); assertEquals(0, game.misses)
        assertFalse(game.motionAllowed)
        feed(game, 6700, 8200, standing)
        assertTrue(game.motionAllowed)
    }
    @Test fun alreadyBowingWhenPromptAppearsIsNotACompletedTask() {
        val game = game(); feed(game, 0, 4900, standing)
        assertEquals(GameEvent.PROMPT, game.update(5000, bow))
        feed(game, 5100, 7900, bow)
        assertEquals(0, game.successes)
        assertEquals(GameEvent.PENALTY, game.update(8000, bow))
    }
    @Test fun aMissTriggersExactlyOnePenaltyAndNeverQueuesMoreDuringRecovery() {
        val game = game(); feed(game, 0, 7900, standing)
        assertEquals(GameEvent.PENALTY, game.update(8000, standing))
        assertTrue(feed(game, 8100, 11_400, standing).isEmpty())
        assertEquals(1, game.misses); assertFalse(game.motionAllowed)
    }
    @Test fun holdingABentPoseCannotExtendRecoveryIndefinitely() {
        val game = game(); feed(game, 0, 8000, standing)
        feed(game, 8100, 11_400, bow)
        assertFalse(game.motionAllowed)
        game.update(11_500, bow)
        assertTrue(game.motionAllowed)
    }
    @Test fun trackingLossAtResponseDeadlineDoesNotPunish() {
        val game = game(); feed(game, 0, 7900, standing)
        assertEquals(GameEvent.TRACKING_LOST, game.update(8000, StandingMetrics()))
        assertEquals(0, game.misses); assertEquals(GamePhase.STOPPED, game.phase)
    }
    @Test fun sessionDeadlineHasPriorityOverAnyOverdueTaskAndFiresOnlyOnce() {
        val game = game(); feed(game, 0, 5000, standing)
        assertEquals(GameEvent.FINISHED, game.update(60_000, standing))
        assertEquals(0, game.misses)
        assertNull(game.update(60_100, standing))
        assertFalse(game.motionAllowed)
    }
    @Test fun stopCannotBeResumedByLaterFrames() {
        val game = game(); game.stop()
        assertNull(game.update(10_000, standing)); assertFalse(game.motionAllowed)
    }
    @Test fun configurationRejectsReversedIntervalsAndUnboundedDuration() {
        assertFalse(settings.copy(durationMs = 0).valid())
        assertTrue(settings.copy(durationMs = 5_400_000).valid())
        assertFalse(settings.copy(durationMs = 5_400_001).valid())
        assertFalse(settings.copy(minGapMs = 20_000, maxGapMs = 5000).valid())
        assertFalse(settings.copy(responseMs = 0).valid())
    }
    @Test fun imperfectReturnCannotUndoAnAlreadySuccessfulHoldOrGenerateAPenalty() {
        val game = game(); feed(game, 0, 5400, standing)
        assertEquals(listOf(GameEvent.SUCCESS), feed(game, 5500, 6000, bow))
        assertTrue(feed(game, 6100, 7900, standing.copy(neutral = false)).isEmpty())
        assertEquals(1, game.successes); assertEquals(0, game.misses)
        assertFalse(game.motionAllowed)
        assertNull(game.update(8000, standing.copy(neutral = false)))
        assertTrue(game.motionAllowed)
    }
}
