package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Models an app tick: queued one-shot zeros can be cancelled by a concurrent clear. */
class V4CurtseyTransitionTest {
    @Test fun threeSecondCueToCurtseyJudgmentDoesNotDisconnectWhenClearCanCancelQueuedZero() {
        for (model in listOf("COYOTE_020", "COYOTE_030")) for (processChannel in listOf("A", "B")) {
            val app = V4ScheduledApp(model)
            val game = CurtseyGame(GameSettings(60_000, 5000, 5000, 3000), 0, Random(1))
            val standing = StandingMetrics(tracked = true, calibrated = true, measurementReady = true, neutral = true)
            for (time in 0L..4900L step 100) game.update(time, standing)
            app.now = 5000
            assertEquals(GameEvent.CUE, game.update(app.now, standing)); assertTrue(game.startCue(app.now))
            // The submitted cue is renewed once per second, as in the shipped relay.
            for (time in 5000L..7900L step 100) {
                app.now = time
                if (time % 1000L == 0L) app.play("A", 8, PulseShape.TRIPLE_SHORT)
                app.drain(); game.update(time, standing)
            }
            app.now = 8000
            assertEquals(GameEvent.PROMPT, game.update(app.now, standing))
            app.stopChannels(); app.play(processChannel, 12, PulseShape.STEADY); app.drain()
            assertEquals("$model / $processChannel: ${app.errors}", emptyList<String>(), app.errors)
            assertEquals(GamePhase.REQUEST, game.phase)
            assertTrue(app.operations.any { it.optInt("t") == 4 && it.optInt("v") == 12 && it.optInt("c") == if (processChannel == "A") 0 else 1 })
            val beforeStop = app.operations.size
            app.stopChannels(); app.drain(); app.now += 2000; app.drain()
            assertEquals(beforeStop, app.operations.size)
        }
    }
}
