package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CurtseyRecoveryTest {
    private val stand = StandingMetrics(tracked = true, calibrated = true, standing = true, neutral = true)
    private val bow = stand.copy(standing = false, neutral = false, curtsey = true, hands = 1f, feet = 1f)
    private fun game() = CurtseyGame(GameSettings(60_000, 5000, 5000, 3000, cueMs = 0), 0, Random(1)).apply {
        for (t in 0L..5400 step 100) update(t, stand)
        assertEquals(CurtseyStage.BEND_AND_LIFT, stage)
    }
    private fun successful() = game().apply {
        for (t in 5500L..5900 step 100) assertNull(update(t, bow))
        assertEquals(GameEvent.SUCCESS, update(6000, bow))
    }

    @Test fun standingUpEarlyDoesNotShortenTheGraceAndMotionTargetsStayAtTheNormalFloor() {
        val game = successful()
        val output = VirtualOutput()
        val weights = MotionWeights(20, 20)
        for (t in 6100L..7900 step 100) {
            // Alternating valid and imperfect returning poses never changes the already successful result.
            val pose = if (t < 6500) bow else stand.copy(hands = 1f, neutral = false)
            assertNull(game.update(t, pose)); assertTrue(game.successRecoveryActive)
            assertFalse(game.motionAllowed)
            val target = if (game.motionAllowed) weights.target(pose, 30, base = 5) else 5
            assertEquals(5, target)
            assertTrue(output.submit(EffectPreset(strength = target), 30, t))
            assertEquals(5, output.sample(t)!!.preset.strength)
        }
        assertNull(game.update(8000, bow)); assertFalse(game.successRecoveryActive); assertTrue(game.motionAllowed)
        assertEquals(30, weights.target(bow, 30, base = 5))
        assertEquals(1, game.successes); assertEquals(0, game.misses)
    }

    @Test fun successJustBeforeTheOldResponseDeadlineCannotTurnIntoAPenaltyDuringRecovery() {
        val game = game()
        for (t in 5500L..7300 step 100) assertNull(game.update(t, stand))
        for (t in 7400L..7800 step 100) assertNull(game.update(t, bow))
        assertEquals(GameEvent.SUCCESS, game.update(7900, bow))
        assertEquals(9900L, game.recoveryUntil)
        for (t in 8000L..9800 step 100) {
            assertNull(game.update(t, bow)); assertTrue(game.successRecoveryActive); assertFalse(game.motionAllowed)
        }
        assertNull(game.update(9900, bow)); assertTrue(game.motionAllowed)
        assertEquals(1, game.successes); assertEquals(0, game.misses)
    }

    @Test fun trackingLossAndRecalibrationKeepTheSuccessWithoutReplayingOrExtendingTheOldTask() {
        val game = successful()
        assertNull(game.update(6100, StandingMetrics()))
        assertEquals(GameEvent.TRACKING_LOST, game.update(6600, StandingMetrics()))
        assertFalse(game.successRecoveryActive)
        game.resumeAfterCalibration(7000)
        assertTrue(game.motionAllowed); assertFalse(game.successRecoveryActive)
        assertEquals(1, game.successes); assertEquals(0, game.misses)
        assertNull(game.update(7100, stand))
    }

    @Test fun theSharedSessionDeadlineStillStopsAnAlreadySuccessfulRecovery() {
        val game = successful()
        assertEquals(GameEvent.FINISHED, game.update(60_000, bow))
        assertFalse(game.motionAllowed); assertFalse(game.successRecoveryActive)
        assertNull(game.update(60_100, bow))
        assertEquals(1, game.successes); assertEquals(0, game.misses)
    }
}
