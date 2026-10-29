package com.coyotevision.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Tests the same embedded server shipped inside the APK, using virtual phones only. */
class PhoneRelayTest {
    private val http = OkHttpClient()
    private lateinit var relay: PhoneRelayServer
    private val peers = mutableListOf<Peer>()
    private var port = 0
    private val failures = LinkedBlockingQueue<String>()

    private inner class Peer(path: String = "") {
        val messages = LinkedBlockingQueue<JSONObject>()
        val closed = CountDownLatch(1)
        val socket = http.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port$path").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { messages.add(JSONObject(text)) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown() }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    messages.add(JSONObject().put("type", "testFailure").put("message", t.toString()))
                    closed.countDown()
                }
            })
        fun next(type: String, prefix: String = ""): JSONObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < deadline) {
                val packet = messages.poll(50, TimeUnit.MILLISECONDS) ?: continue
                check(packet.optString("type") != "testFailure") { packet.toString() }
                if (packet.optString("type") == type && packet.optString("message").startsWith(prefix)) return packet
            }
            error("Timeout: $type / $prefix")
        }
    }

    @Before fun start() {
        val running = CountDownLatch(1)
        relay = PhoneRelayServer(InetSocketAddress("127.0.0.1", 0), {
            port = it; running.countDown()
        }, {}, { failures.add(it); running.countDown() })
        relay.start()
        assertTrue(running.await(5, TimeUnit.SECONDS))
        assertTrue(failures.toString(), failures.isEmpty())
        assertTrue(port > 0)
    }

    @After fun stop() {
        relay.shutdown()
        peers.forEach { it.socket.cancel() }
        http.dispatcher.executorService.shutdownNow()
        http.connectionPool.evictAll()
    }

    @Test(timeout = 6000) fun shutdownReleasesRelayMonitorBeforeClosingAWebSocket() {
        paired()
        val peer = relay.connections.first()
        val finished = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val callback = Thread {
            try {
                synchronized(peer) {
                    val shutdown = Thread { relay.shutdown(); finished.countDown() }.apply { isDaemon = true }
                    shutdown.start()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    var waitingOnPeer = false
                    while (System.nanoTime() < deadline) {
                        if (shutdown.state == Thread.State.BLOCKED) { waitingOnPeer = true; break }
                        Thread.sleep(5)
                    }
                    check(waitingOnPeer) { "Shutdown did not reach the peer close operation" }
                    // The library invokes this callback while holding the peer monitor.
                    relay.onClose(peer, 1000, "simulated_simultaneous_close", true)
                }
            } catch (e: Throwable) { failure.set(e); finished.countDown() }
        }.apply { isDaemon = true }
        callback.start()
        assertTrue("Shutdown must not deadlock with onClose", finished.await(4, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Shutdown callback failed", it) }
    }

    private fun peer(path: String = "") = Peer(path).also { peers.add(it) }
    private data class Pairing(val control: Peer, val app: Peer, val own: String, val target: String)
    private fun paired(): Pairing {
        val control = peer()
        val own = control.next("bind", "targetId").getString("clientId")
        val link = V3Protocol.pairingLink("ws://127.0.0.1:$port", own)
        val app = peer(URI(link.substringAfter("#DGLAB-SOCKET#")).path)
        val target = app.next("bind", "targetId").getString("clientId")
        assertEquals(target, control.next("bind", "200").getString("targetId"))
        assertEquals(own, app.next("bind", "200").getString("clientId"))
        return Pairing(control, app, own, target)
    }

    @Test fun qrPathPairsOnlyTheExpectedControllerAndProducesNoOutput() {
        val p = paired()
        val app = p.app
        assertNull(app.messages.poll(150, TimeUnit.MILLISECONDS))
        assertEquals(RelayEvent.Paired(p.target), V3Protocol.parse(
            JSONObject().put("type", "bind").put("clientId", p.own).put("targetId", p.target)
                .put("message", "200").toString(), p.own))
    }

    @Test fun explicitBindIsSupportedAndRepeatedBindIsIdempotent() {
        val control = peer()
        val own = control.next("bind", "targetId").getString("clientId")
        val app = peer()
        val id = app.next("bind", "targetId").getString("clientId")
        val bind = JSONObject().put("type", "bind").put("clientId", own).put("targetId", id)
            .put("message", "DGLAB").toString()
        repeat(2) {
            assertTrue(app.socket.send(bind))
            assertEquals(id, control.next("bind", "200").getString("targetId"))
            app.next("bind", "200")
        }
    }

    @Test fun strengthAndFinitePulseReachAppAndRelayOwnsZeroDeadline() {
        val p = paired(); val control = p.control; val app = p.app
        control.socket.send(V3Protocol.strength(p.own, p.target, "B", 8))
        assertEquals("strength-2+2+8", app.next("msg", "strength").getString("message"))
        control.socket.send(V3Protocol.pulse(p.own, p.target, EffectPreset("B", 8, 1)))
        assertEquals("clear-2", app.next("msg", "clear").getString("message"))
        val pulse = app.next("msg", "pulse-B:").getString("message")
        assertEquals(10, org.json.JSONArray(pulse.substringAfter(':')).length())
        assertEquals("clear-2", app.next("msg", "clear").getString("message"))
        assertEquals("strength-2+2+0", app.next("msg", "strength").getString("message"))
        control.next("notify", "发送完毕")
    }

    @Test fun clearCancelsUnsentPulseBatches() {
        val p = paired(); val control = p.control; val app = p.app
        control.socket.send(V3Protocol.pulse(p.own, p.target, EffectPreset("A", 8, 3)))
        app.next("msg", "pulse-A:")
        control.socket.send(V3Protocol.clear(p.own, p.target, "A"))
        app.next("msg", "clear-1")
        assertNull("A cancelled pulse was sent later", app.messages.poll(1300, TimeUnit.MILLISECONDS))
    }

    @Test fun wrongQrTokenAndASecondPhoneCannotReplaceThePairedPhone() {
        val p = paired(); val control = p.control; val app = p.app
        peer("/not-the-controller").next("error", "401")
        peer("/${p.own}").next("error", "400")
        control.socket.send(V3Protocol.strength(p.own, p.target, "A", 5))
        assertEquals("strength-1+2+5", app.next("msg", "strength").getString("message"))
    }

    @Test fun phoneReportsReturnToThePoseController() {
        val p = paired(); val control = p.control; val app = p.app
        app.socket.send(JSONObject().put("type", "msg").put("clientId", p.target)
            .put("targetId", p.own).put("message", "strength-0+0+30+30").toString())
        val report = control.next("msg", "strength-")
        assertEquals(RelayEvent.Report("strength-0+0+30+30"), V3Protocol.parse(report.toString(), p.own))
    }

    @Test fun multiSecondWaveformKeepsItsSequenceAcrossRelayBatches() {
        val p = paired()
        val preset = EffectPreset(strength = 8, durationSeconds = 2, shape = PulseShape.OFFICIAL_RHYTHM)
        p.control.socket.send(V3Protocol.pulse(p.own, p.target, preset))
        val first = org.json.JSONArray(p.app.next("msg", "pulse-A:").getString("message").substringAfter(':'))
        val second = org.json.JSONArray(p.app.next("msg", "pulse-A:").getString("message").substringAfter(':'))
        assertEquals(10, first.length()); assertEquals(10, second.length())
        assertEquals(preset.shape.frames[0], first.getString(0))
        assertEquals(preset.shape.frames[10], second.getString(0))
        assertEquals(preset.shape.frames[6], second.getString(9))
    }

    @Test fun aPhoneCannotPretendToBeThePoseController() {
        val p = paired(); val control = p.control; val app = p.app
        app.socket.send(V3Protocol.strength(p.own, p.target, "A", 50))
        app.next("error", "404")
        control.next("break", "209")
        assertTrue(app.closed.await(3, TimeUnit.SECONDS))
    }

    @Test fun disconnectInvalidatesOldQrAndDoesNotReplayIntoNewSession() {
        val old = paired(); val control = old.control; val app = old.app
        control.socket.send(V3Protocol.pulse(old.own, old.target, EffectPreset("A", 8, 3)))
        app.next("msg", "pulse-A:")
        control.socket.close(1000, "test")
        app.next("break", "209")
        assertTrue(app.closed.await(3, TimeUnit.SECONDS))
        val current = paired()
        assertNotEquals(old.own, current.own)
        peer("/${old.own}").next("error", "401")
        assertNull(current.app.messages.poll(1300, TimeUnit.MILLISECONDS))
    }

    @Test fun mobileDataAndVpnInterfacesAreNotAdvertisedAsWifi() {
        assertTrue(LocalAddresses.isLanInterface("wlan0"))
        assertTrue(LocalAddresses.isLanInterface("ap_br_wlan1"))
        assertFalse(LocalAddresses.isLanInterface("rmnet_data0"))
        assertFalse(LocalAddresses.isLanInterface("tun0"))
    }

    @Test fun controllerDisconnectionClearsAndZerosBothChannelsBeforeBreak() {
        val p = paired()
        p.control.socket.close(1000, "controller_left")
        assertEquals("clear-1", p.app.next("msg", "clear-1").getString("message"))
        assertEquals("strength-1+2+0", p.app.next("msg", "strength-1").getString("message"))
        assertEquals("clear-2", p.app.next("msg", "clear-2").getString("message"))
        assertEquals("strength-2+2+0", p.app.next("msg", "strength-2").getString("message"))
        p.app.next("break", "209")
    }

    private fun session(p: Pairing, start: Boolean, duration: Long = 0) = JSONObject()
        .put("type", "session").put("clientId", p.own).put("targetId", p.target)
        .put("message", if (start) "start" else "stop").put("durationMs", duration).toString()

    @Test fun relayEnforcesSessionDeadlineWithoutAnyUiStopCommand() {
        val p = paired()
        p.control.socket.send(session(p, true, 350))
        p.control.socket.send(V3Protocol.strength(p.own, p.target, "A", 8))
        p.app.next("msg", "strength-1+2+8")
        p.control.socket.send(V3Protocol.pulse(p.own, p.target, EffectPreset("A", 8, 3)))
        p.app.next("msg", "pulse-A:")
        p.control.next("notify", "session-ended")
        p.app.next("msg", "clear-1")
        p.app.next("msg", "strength-1+2+0")
        p.app.next("msg", "clear-2")
        p.app.next("msg", "strength-2+2+0")
        assertNull(p.app.messages.poll(1200, TimeUnit.MILLISECONDS))
    }

    @Test fun expiredSessionRejectsNewOutputInsteadOfExtendingTheDeadline() {
        val p = paired()
        p.control.socket.send(session(p, true, 150))
        p.control.next("notify", "session-ended")
        p.control.socket.send(V3Protocol.strength(p.own, p.target, "A", 20))
        p.control.next("error", "410")
        p.app.next("break", "209")
        assertTrue(p.control.closed.await(3, TimeUnit.SECONDS))
    }

    @Test fun explicitSessionStopCancelsItsOldDeadline() {
        val p = paired()
        p.control.socket.send(session(p, true, 250))
        p.control.socket.send(session(p, false))
        p.app.next("msg", "strength-2+2+0")
        assertNull(p.control.messages.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test fun sessionsBeyondNinetyMinutesAreRejectedByTheRelay() {
        val p = paired()
        p.control.socket.send(session(p, true, 5_400_001))
        p.control.next("error", "403")
        p.app.next("break", "209")
    }
}
