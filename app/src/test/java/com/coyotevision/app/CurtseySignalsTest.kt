package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class CurtseySignalsTest {
    private val standing = StandingMetrics(tracked = true, calibrated = true, standing = true, neutral = true)
    private fun frame(signals: CurtseySignals, now: Long, phase: GamePhase = GamePhase.RECOVERY) =
        signals.sample(now, true, false, phase)

    @Test fun cueFlashesThreeTimesStartingAtSubmissionAndOnlyThenShowsTheResponseBudget() {
        val game = CurtseyGame(GameSettings(60_000, 5000, 5000, 6000), 0, Random(1))
        val signals = CurtseySignals()
        for (t in 0L..5000 step 100) game.update(t, standing)?.let { signals.onEvent(it, t) }
        assertEquals(CurtseySignalKind.CUE_WAITING, signals.sample(5000, true, false, game.phase).kind)
        for (t in 5100L..5500 step 100) game.update(t, standing)
        assertTrue(game.startCue(5500))
        var brightBefore = false; var flashes = 0
        for (t in 5500L..8490 step 10) {
            val cue = signals.sample(t, true, false, game.phase, game.cueUntil)
            assertEquals(CurtseySignalKind.CUE, cue.kind); assertNull(cue.progress)
            if (cue.bright && !brightBefore) flashes++
            brightBefore = cue.bright
        }
        assertEquals(3, flashes)
        assertFalse(signals.sample(8500, true, false, game.phase, game.cueUntil).bright)
        for (t in 5600L..8400 step 100) game.update(t, standing)
        val prompt = game.update(8500, standing)!!
        assertEquals(GameEvent.PROMPT, prompt); signals.onEvent(prompt, 8500)
        val response = signals.sample(8500, true, false, game.phase,
            responseDeadline = game.responseDeadline, responseDuration = game.settings.responseMs)
        assertEquals(CurtseySignalKind.REQUEST, response.kind); assertEquals(1f, response.progress!!, .001f)
    }

    @Test fun theResponseStripShrinksWithinTheExistingDeadline() {
        val signals = CurtseySignals()
        for ((now, expected) in listOf(8000L to 1f, 11_000L to .5f, 14_000L to 0f, 15_000L to 0f)) {
            val result = signals.sample(now, true, false, GamePhase.REQUEST,
                responseDeadline = 14_000, responseDuration = 6000)
            assertEquals(CurtseySignalKind.REQUEST, result.kind)
            assertTrue(result.bright); assertEquals(expected, result.progress!!, .001f)
        }
    }

    @Test fun successStaysVisibleForTheTwoSecondStandingGraceAndDoesNotFlash() {
        val signals = CurtseySignals(); signals.onEvent(GameEvent.SUCCESS, 1000)
        for (t in 1000L..2999 step 100) {
            assertEquals(CurtseySignalKind.SUCCESS, frame(signals, t).kind)
            assertTrue(frame(signals, t).bright)
        }
        assertEquals(CurtseySignalKind.NONE, frame(signals, 3000).kind)
    }

    @Test fun timeoutFlashesEvenWithNoPenaltyOutputAndCoversLongPenaltyDurations() {
        val signals = CurtseySignals(); signals.onEvent(GameEvent.PENALTY, 1000, 0)
        assertEquals(CurtseySignalKind.FAILURE, frame(signals, 1000).kind)
        assertTrue(frame(signals, 1000).bright); assertFalse(frame(signals, 1700).bright)
        assertTrue(frame(signals, 2000).bright)
        assertEquals(CurtseySignalKind.FAILURE, frame(signals, 3999).kind)
        assertEquals(CurtseySignalKind.NONE, frame(signals, 4000).kind)
        signals.onEvent(GameEvent.PENALTY, 5000, 30_000)
        assertEquals(CurtseySignalKind.FAILURE, frame(signals, 34_999).kind)
        assertEquals(CurtseySignalKind.NONE, frame(signals, 35_000).kind)
    }

    @Test fun newTasksOverrideAnOldResultWithoutExtendingEitherGameTimer() {
        val signals = CurtseySignals(); signals.onEvent(GameEvent.SUCCESS, 1000)
        assertEquals(CurtseySignalKind.CUE_WAITING, frame(signals, 1100, GamePhase.CUE).kind)
        assertEquals(CurtseySignalKind.REQUEST, frame(signals, 1100, GamePhase.REQUEST).kind)
        signals.onEvent(GameEvent.CUE, 1100)
        assertEquals(CurtseySignalKind.NONE, frame(signals, 1200).kind)
    }

    @Test fun pauseStopTrackingLossAndCueFailureNeverShowTimeoutOrReplayAResult() {
        for (event in listOf(GameEvent.TRACKING_LOST, GameEvent.CUE_FAILED, GameEvent.FINISHED)) {
            val signals = CurtseySignals(); signals.onEvent(GameEvent.PENALTY, 1000, 30_000)
            assertEquals(CurtseySignalKind.PAUSED, signals.sample(1100, true, true, GamePhase.REQUEST).kind)
            assertEquals(CurtseySignalKind.NONE, signals.sample(1100, false, false, GamePhase.RECOVERY).kind)
            signals.onEvent(event, 1100)
            assertEquals(CurtseySignalKind.NONE, frame(signals, 1200, GamePhase.WAITING).kind)
            signals.onEvent(GameEvent.SUCCESS, 1300); signals.clear()
            assertEquals(CurtseySignalKind.NONE, frame(signals, 1400).kind)
        }
    }
}
