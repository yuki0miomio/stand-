package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Exercises the shared cleanup path with finite training and normal effects, without hardware. */
class V4TrainingTransitionTest {
    private fun training() = EnduranceTraining(
        EnduranceSettings(true, 5000, 5000, 3000, 3000), 0, 60_000, Random(1))

    @Test fun normalTrainingAndNormalSurviveCancelledZerosOnEitherChannelAndModel() {
        for (model in listOf("COYOTE_020", "COYOTE_030")) {
            for (normalChannel in listOf("A", "B")) for (trainingChannel in listOf("A", "B")) {
                val app = V4ScheduledApp(model)
                val training = training()
                app.omitCancelledTaskReplies = true
                app.now = 4900
                app.play(normalChannel, 8, PulseShape.STEADY); app.drain()
                app.now = 5000
                assertEquals(TrainingEvent.START, training.update(app.now, false))
                val before = app.operations.size
                app.cancelNextZero = true
                app.stopChannels(); app.play(trainingChannel, 24, PulseShape.RAMP)
                // New output cannot escape while either clear/zero for its channel is unconfirmed.
                assertEquals(before, app.operations.size)
                app.drain()
                assertTrue(app.operations.drop(before).any { it.optInt("t") == 4 && it.optInt("v") == 24 })
                assertTrue(app.active.all { it.first.getJSONObject("data").getInt("c") == channelNumber(trainingChannel) })
                // Refresh finite training leases as the game does. Missing replies for cleared old tasks
                // must not later be mistaken for an RPC timeout.
                for (time in 5500L..6500L step 500) {
                    app.now = time; assertNull(training.update(time, false))
                    app.play(trainingChannel, 24, PulseShape.RAMP); app.drain()
                }
                app.now = 8000
                assertEquals(TrainingEvent.END, training.update(app.now, false))
                app.cancelNextZero = true
                app.stopChannels(); app.play(normalChannel, 8, PulseShape.STEADY); app.drain()
                assertEquals(8, app.operations.last { it.optInt("t") == 4 }.getInt("v"))
                assertTrue(app.active.all { it.first.getJSONObject("data").getInt("c") == channelNumber(normalChannel) })
                app.stopChannels(); app.drain()
                val afterStop = app.operations.size
                app.now = 14_000; app.drain(); app.peer.checkTimeout()
                assertEquals(afterStop, app.operations.size)
                assertTrue(app.active.isEmpty())
                assertEquals("$model / $normalChannel → $trainingChannel: ${app.errors}", emptyList<String>(), app.errors)
            }
        }
    }

    @Test fun interruptionWhileTrainingCleanupIsPendingDropsTheQueuedEffectWithoutReplay() {
        for (model in listOf("COYOTE_020", "COYOTE_030")) for (channel in listOf("A", "B")) {
            val app = V4ScheduledApp(model)
            val training = training()
            app.now = 4900; app.play(channel, 8, PulseShape.STEADY); app.drain()
            app.now = 5000; assertEquals(TrainingEvent.START, training.update(app.now, false))
            val before = app.operations.size
            app.stopChannels(); app.play(channel, 24, PulseShape.RAMP)
            // Person lost / stop arrives before the official app confirms the transition cleanup.
            app.stopChannels(); app.drain()
            training.resumeAfterCalibration(5100)
            assertFalse(training.active)
            app.now = 10_000; app.drain(); app.peer.checkTimeout()
            assertEquals(before, app.operations.size)
            assertTrue(app.active.isEmpty())
            assertNull(training.update(app.now, false))
            assertEquals(emptyList<String>(), app.errors)
        }
    }

    @Test fun trainingEndsBeforeANewCurtseyCueAndItsProcessOutputCanStart() {
        val app = V4ScheduledApp("COYOTE_030")
        val training = training()
        val game = CurtseyGame(GameSettings(60_000, 5000, 5000, 3000), 0, Random(1))
        val standing = StandingMetrics(tracked = true, calibrated = true, measurementReady = true, neutral = true)
        for (time in 0L..8000L step 100) {
            app.now = time
            val busy = game.phase in listOf(GamePhase.CUE, GamePhase.REQUEST, GamePhase.RECOVERY)
            when (training.update(time, busy)) {
                TrainingEvent.START -> { app.stopChannels(); app.play("B", 24, PulseShape.RAMP) }
                TrainingEvent.END -> { app.stopChannels(); app.play("A", 8, PulseShape.STEADY) }
                null -> Unit
            }
            assertNull(game.update(time, standing, training.active))
            assertEquals(GamePhase.WAITING, game.phase)
            app.drain()
        }
        // Training's final active frame is 7900: next cue is scheduled 5 seconds from there.
        for (time in 8100L..12_800L step 100) assertNull(game.update(time, standing))
        app.now = 12_900
        assertEquals(GameEvent.CUE, game.update(app.now, standing))
        assertNull(training.update(app.now, true)); assertFalse(training.active)
        app.stopChannels(); app.play("B", 8, PulseShape.TRIPLE_SHORT); app.drain()
        assertTrue(game.startCue(app.now))
        for (time in 13_000L..15_800L step 100) {
            app.now = time
            assertNull(training.update(time, true))
            assertNull(game.update(time, standing))
            app.drain()
        }
        app.now = 15_900
        assertEquals(GameEvent.PROMPT, game.update(app.now, standing))
        app.stopChannels(); app.play("A", 12, PulseShape.STEADY); app.drain()
        assertEquals(GamePhase.REQUEST, game.phase)
        assertEquals(12, app.operations.last { it.optInt("t") == 4 }.getInt("v"))
        app.peer.checkTimeout()
        assertEquals(emptyList<String>(), app.errors)
    }

    private fun channelNumber(channel: String) = if (channel == "A") 0 else 1
}
