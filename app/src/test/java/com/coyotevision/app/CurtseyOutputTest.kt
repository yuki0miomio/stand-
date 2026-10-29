package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import kotlin.random.Random

class CurtseyOutputTest {
    private val preset = EffectPreset(channel = "B", strength = 12, durationSeconds = 3, shape = PulseShape.STEADY)
    private fun output(phase: GamePhase?, now: Long = 6000, tracked: Boolean = true, strength: Int = 12,
        limit: Int = 20, sessionEnd: Long = 60_000) = CurtseyOutputRules.process(
            phase, tracked, now, 8000, sessionEnd, preset.copy(strength = strength), limit)

    @Test fun processUsesIndependentConstantPresetAndOneSecondLease() {
        val effect = output(GamePhase.REQUEST)!!
        assertEquals(12, effect.strength); assertEquals("B", effect.channel)
        assertEquals(PulseShape.STEADY, effect.shape); assertEquals(1, effect.durationSeconds)
        assertEquals(12, output(GamePhase.REQUEST, now = 7900)!!.strength)
    }
    @Test fun processHonorsZeroGlobalLimitTrackingAndDeadlines() {
        assertNull(output(GamePhase.REQUEST, strength = 0))
        assertNull(output(GamePhase.REQUEST, limit = 0))
        assertEquals(8, output(GamePhase.REQUEST, limit = 8)!!.strength)
        assertNull(output(GamePhase.REQUEST, tracked = false))
        assertNull(output(GamePhase.REQUEST, now = 8000))
        assertNull(output(GamePhase.REQUEST, sessionEnd = 7000))
    }
    @Test fun noProcessOutputInWaitingRecoveryOrTerminalStates() {
        for (phase in GamePhase.entries.filter { it != GamePhase.REQUEST }) assertNull(output(phase))
        assertNull(output(null))
    }
    @Test fun missedTaskLeavesProcessWindowAndEmitsOnlyOnePenaltyEvent() {
        val game = CurtseyGame(GameSettings(60_000, 5000, 5000, 3000, cueMs = 0), 0, Random(1))
        val standing = StandingMetrics(tracked = true, standing = true, calibrated = true, neutral = true)
        for (now in 0L..4900L step 100) game.update(now, standing)
        assertEquals(GameEvent.PROMPT, game.update(5000, standing))
        assertNotNull(output(game.phase))
        for (now in 5100L..7900L step 100) game.update(now, standing)
        assertEquals(GameEvent.PENALTY, game.update(8000, standing))
        assertNull(output(game.phase)); assertNull(game.update(8100, standing)); assertEquals(1, game.misses)
    }
    @Test fun successfulTaskStopsProcessWithoutPenalty() {
        val game = CurtseyGame(GameSettings(60_000, 5000, 5000, 3000, cueMs = 0), 0, Random(1))
        val standing = StandingMetrics(tracked = true, standing = true, calibrated = true, neutral = true)
        val bow = standing.copy(standing = false, neutral = false, curtsey = true)
        for (now in 0L..5400L step 100) game.update(now, standing)
        for (now in 5500L..5900L step 100) game.update(now, bow)
        assertEquals(GameEvent.SUCCESS, game.update(6000, bow))
        assertNull(output(game.phase))
        for (now in 6100L..6500L step 100) game.update(now, standing)
        assertNull(game.update(6600, standing))
        assertNull(output(game.phase)); assertEquals(0, game.misses)
    }
    @Test fun penaltyTransitionClearsAndZerosBothChannelsBeforeStartingTheNewEffect() {
        val batch = V3Protocol.effectBatch("controller", "app", preset, clearAll = true).map { JSONObject(it) }
        assertEquals(6, batch.size)
        assertEquals("A", batch[0].getString("channel")); assertTrue(batch[0].getString("message").contains("clear"))
        assertEquals(0, batch[1].getInt("strength"))
        assertEquals("B", batch[2].getString("channel")); assertTrue(batch[2].getString("message").contains("clear"))
        assertEquals(0, batch[3].getInt("strength"))
        assertEquals(12, batch[4].getInt("strength")); assertEquals("B", batch[4].getString("channel"))
        assertEquals("clientMsg", batch[5].getString("type"))
    }
}
