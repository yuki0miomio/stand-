package com.coyotevision.app

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Actual shipped WebSocket server, with an official-format V4 app simulator (no hardware). */
class V4RelayTest {
    @Test(timeout = 10_000) fun coyoteThreePairingFiniteOutputAndIndependentGameDeadline() =
        exercise("COYOTE_030", "A")
    @Test(timeout = 10_000) fun coyoteTwoPairingFiniteOutputAndIndependentGameDeadline() =
        exercise("COYOTE_020", "B")
    @Test(timeout = 10_000) fun delayedAndReorderedCleanupAcknowledgementsStillDeliverFiniteCoyoteThreeOutput() =
        exercise("COYOTE_030", "A", true)
    @Test(timeout = 15_000) fun cueToCurtseyProcessSurvivesAClearedZeroOnTheActualRelay() =
        exercise("COYOTE_030", "A", transition = true)
    @Test(timeout = 15_000) fun enduranceSwitchesChannelsAndReturnsToNormalWithoutDisconnectingCoyoteThree() =
        exercise("COYOTE_030", "A", delayCleanup = true, trainingTransition = true)
    @Test(timeout = 15_000) fun enduranceSwitchesChannelsAndReturnsToNormalWithoutDisconnectingCoyoteTwo() =
        exercise("COYOTE_020", "B", delayCleanup = true, trainingTransition = true)
    @Test(timeout = 10_000) fun ninetyMinuteSessionIsAcceptedAndCanBeStoppedOverTheActualV4Relay() =
        exercise("COYOTE_030", "A", sessionMs = 5_400_000)

    private fun exercise(model: String, channel: String, delayCleanup: Boolean = false, transition: Boolean = false,
        trainingTransition: Boolean = false, sessionMs: Long = 350) {
        val client = OkHttpClient()
        val responses = Executors.newSingleThreadScheduledExecutor()
        val cancelFirstZero = AtomicBoolean(transition || trainingTransition)
        val started = CountDownLatch(1)
        var port = 0
        val server = PhoneRelayServer(InetSocketAddress("127.0.0.1", 0), { port = it; started.countDown() }, {}, {}, appV4 = true)
        val sockets = mutableListOf<WebSocket>()
        fun connect(path: String, messages: LinkedBlockingQueue<JSONObject>, ack: Boolean = false): WebSocket {
            val socket = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port$path").build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = JSONObject(text); messages.add(frame)
                    val payload = frame.optJSONObject("data")
                    if (ack && payload?.optString("t") == "req") {
                        val delay = if (!delayCleanup) 0L else if (payload.optString("m") == "device.op.clear") 120L
                            else if (payload.optJSONObject("data")?.optInt("t", -1) == 7) 40L else 0L
                        val reason = if (payload.optJSONObject("data")?.optInt("t", -1) == 7 && cancelFirstZero.compareAndSet(true, false))
                            "cleared" else "completed"
                        responses.schedule({ webSocket.send(JSONObject().put("type", "message")
                            .put("data", JSONObject().put("t", "resp").put("reqId", payload.getString("reqId"))
                                .put("result", JSONObject().put("reason", reason))).toString()) },
                            delay, TimeUnit.MILLISECONDS)
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            })
            sockets.add(socket); return socket
        }
        fun next(queue: LinkedBlockingQueue<JSONObject>, matches: (JSONObject) -> Boolean): JSONObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < deadline) {
                val message = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (matches(message)) return message
            }
            error("Missing expected WebSocket frame")
        }
        server.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val controlFrames = LinkedBlockingQueue<JSONObject>(); val appFrames = LinkedBlockingQueue<JSONObject>()
            val control = connect("", controlFrames)
            val own = next(controlFrames) { it.optString("type") == "bind" }.getString("clientId")
            val app = connect("?tid=$own", appFrames, true)
            val appId = next(appFrames) { it.optString("type") == "hello" }.getString("clientId")
            assertEquals(own, next(appFrames) { it.optString("type") == "controller_attached" }.getString("clientId"))
            next(appFrames) { it.optJSONObject("data")?.optString("m") == "devices.get" }
            assertNull(controlFrames.poll(100, TimeUnit.MILLISECONDS))
            app.send(JSONObject().put("type", "message").put("data", JSONObject().put("t", "ev").put("ev", "devices.snapshot")
                .put("devices", JSONArray().put(JSONObject().put("slotId", "test-slot").put("name", "Coyote")
                    .put("type", model).put("slotState", JSONObject().put("hasDevice", true))))).toString())
            assertEquals(appId, next(controlFrames) { it.optString("type") == "bind" }.getString("targetId"))
            control.send(JSONObject().put("type", "session").put("clientId", own).put("targetId", appId)
                .put("message", "start").put("durationMs", if (transition || trainingTransition) 7000 else sessionMs).toString())
            control.send(V3Protocol.strength(own, appId, channel, if (transition) 8 else 12))
            control.send(V3Protocol.pulse(own, appId, EffectPreset(channel, strength = if (transition) 8 else 12,
                durationSeconds = if (transition) 3 else 2, shape = if (transition) PulseShape.TRIPLE_SHORT else PulseShape.RAMP)))
            if (trainingTransition) {
                fun stopChannels() {
                    for (stopChannel in listOf("A", "B")) {
                        control.send(V3Protocol.clear(own, appId, stopChannel))
                        control.send(V3Protocol.strength(own, appId, stopChannel, 0))
                    }
                }
                next(appFrames) { it.optJSONObject("data")?.optJSONObject("data")?.optInt("t", -1) == 0 }
                val trainingChannel = if (channel == "A") "B" else "A"
                stopChannels()
                V3Protocol.effectBatch(own, appId, EffectPreset(trainingChannel, 24, 1, PulseShape.RAMP)).forEach { control.send(it) }
                val trainingOutput = next(appFrames) {
                    val data = it.optJSONObject("data")?.optJSONObject("data")
                    data?.optInt("t", -1) == 4 && data.optInt("v") == 24
                }.getJSONObject("data").getJSONObject("data")
                assertEquals(if (trainingChannel == "A") 0 else 1, trainingOutput.getInt("c"))
                assertTrue(trainingOutput.getInt("d") in 1..1000)
                next(appFrames) { it.optJSONObject("data")?.optJSONObject("data")?.optInt("t", -1) == 0 }
                // Exit before the training lease completes, reproducing an overlapping cleanup boundary.
                cancelFirstZero.set(true)
                stopChannels()
                V3Protocol.effectBatch(own, appId, EffectPreset(channel, 12, 1, PulseShape.STEADY)).forEach { control.send(it) }
            }
            if (transition) {
                next(appFrames) { it.optJSONObject("data")?.optJSONObject("data")?.optInt("t", -1) == 0 }
                Thread.sleep(3100)
                // MainActivity clears both channels at PROMPT, then submits the independent process preset.
                for (stopChannel in listOf("A", "B")) {
                    control.send(V3Protocol.clear(own, appId, stopChannel))
                    control.send(V3Protocol.strength(own, appId, stopChannel, 0))
                }
                V3Protocol.effectBatch(own, appId, EffectPreset(channel, 12, 1, PulseShape.STEADY)).forEach { control.send(it) }
            }
            val temporary = next(appFrames) {
                val data = it.optJSONObject("data")?.optJSONObject("data")
                data?.optInt("t", -1) == 4 && data.optInt("v") == 12
            }
            assertTrue(temporary.getJSONObject("data").getJSONObject("data").getInt("d") in 1..1000)
            assertEquals(12, temporary.getJSONObject("data").getJSONObject("data").getInt("v"))
            assertEquals(if (channel == "A") 0 else 1, temporary.getJSONObject("data").getJSONObject("data").getInt("c"))
            val pulse = next(appFrames) { it.optJSONObject("data")?.optJSONObject("data")?.optInt("t", -1) == 0 }
                .getJSONObject("data").getJSONObject("data")
            assertEquals(3, pulse.getInt("ver"))
            assertEquals("test-slot", pulse.getString("s")); assertTrue(pulse.getInt("d") in 1..1000)
            assertEquals(10, pulse.getJSONArray("v").length())
            if (transition || trainingTransition || sessionMs > 350) {
                assertTrue(appFrames.none { it.optString("type") == "controller_disconnected" })
                assertTrue(controlFrames.none { it.optString("type") in setOf("break", "error") })
                control.send(JSONObject().put("type", "session").put("clientId", own).put("targetId", appId).put("message", "stop").toString())
            } else next(controlFrames) { it.optString("message") == "session-ended" }
            next(appFrames) { it.optJSONObject("data")?.optJSONObject("data")?.optInt("t", -1) == 7 }
            Thread.sleep(1100)
            assertFalse(appFrames.toList().any { it.optJSONObject("data")?.optJSONObject("data")?.optInt("t", -1) in listOf(0, 4) })
            control.send(V3Protocol.strength(own, appId, channel, 12))
            assertEquals("410", next(controlFrames) { it.optString("type") == "error" }.getString("message"))
        } finally {
            server.shutdown(); sockets.forEach { it.cancel() }
            responses.shutdownNow()
            client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
        }
    }
}
