package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class CurrentRulesTest {
    private val increment = EffectPreset(channel = "B", strength = 5, durationSeconds = 3, shape = PulseShape.STEADY)

    @Test fun penaltyAddsToNormalInsteadOfReplacingItOrAccumulatingPriorPunishments() {
        repeat(3) {
            val output = CurrentRules.penalty(10, increment, 30, 10_000)!!
            assertEquals(15, output.strength)
            assertEquals("B", output.channel); assertEquals(PulseShape.STEADY, output.shape)
            assertEquals(3, output.durationSeconds)
        }
        assertEquals(5, CurrentRules.penalty(0, increment, 30, 10_000)!!.strength)
    }
    @Test fun summedPenaltyCannotExceedGlobalLimitAndIncrementMayBeLargerThanTheLimit() {
        assertEquals(12, CurrentRules.penalty(10, increment, 12, 10_000)!!.strength)
        assertEquals(20, CurrentRules.penalty(10, increment.copy(strength = 200), 20, 10_000)!!.strength)
        assertEquals(0, CurrentRules.normal(10, 0))
        assertEquals(20, CurrentRules.normal(200, 20))
    }
    @Test fun zeroIncrementDisablesExtraPunishmentAndDeadlineClipsItsDuration() {
        assertNull(CurrentRules.penalty(10, increment.copy(strength = 0), 20, 10_000))
        assertNull(CurrentRules.penalty(10, increment, 0, 10_000))
        assertNull(CurrentRules.penalty(10, increment, 20, 999))
        assertEquals(1, CurrentRules.penalty(10, increment, 20, 1500)!!.durationSeconds)
    }
    @Test fun normalCurrentIsMaintainedWhileOnlyMovementIncrementRamps() {
        val limiter = MotionIntensity()
        assertEquals(10, CurrentRules.withMotionFloor(10, 18, 20, limiter, 0))
        assertEquals(11, CurrentRules.withMotionFloor(10, 18, 20, limiter, 250))
        assertEquals(12, CurrentRules.withMotionFloor(10, 18, 20, limiter, 500))
        assertEquals(10, CurrentRules.withMotionFloor(10, 10, 20, limiter, 600))
        assertEquals(10, CurrentRules.withMotionFloor(10, 10, 20, limiter, 800))
        assertEquals(10, CurrentRules.withMotionFloor(10, 0, 20, limiter, 1000))
    }
    @Test fun restoringNormalAfterPenaltyDoesNotKeepPenaltyIncrementOrExceedLimit() {
        val limiter = MotionIntensity()
        assertEquals(15, CurrentRules.penalty(10, increment, 20, 5000)!!.strength)
        limiter.reset()
        assertEquals(10, CurrentRules.withMotionFloor(10, 10, 20, limiter, 5000))
        assertEquals(8, CurrentRules.withMotionFloor(10, 30, 8, limiter, 5100))
        assertEquals(0, CurrentRules.withMotionFloor(10, 30, 0, limiter, 5200))
    }
}
