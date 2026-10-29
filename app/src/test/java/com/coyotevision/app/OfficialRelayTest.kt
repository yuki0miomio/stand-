package com.coyotevision.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Talks only to a loopback official relay and a virtual app. No physical device is involved. */
class OfficialRelayTest {
    private class Peer(val client: OkHttpClient, address: String) {
        val incoming = LinkedBlockingQueue<JSONObject>()
        val socket = client.newWebSocket(Request.Builder().url(address).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { incoming.add(JSONObject(text)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.add(JSONObject().put("type", "testFailure").put("message", t.toString()))
            }
        })
        fun await(type: String, messagePrefix: String = ""): JSONObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val message = incoming.poll(100, TimeUnit.MILLISECONDS) ?: continue
                check(message.optString("type") != "testFailure") { message.toString() }
                if (message.optString("type") == type && message.optString("message").startsWith(messagePrefix)) return message
            }
            error("Timed out waiting for $type / $messagePrefix")
        }
    }

    @Test fun officialRelayPairsForwardsPresetAndCancelsRemainingPulsePackets() {
        val root = File(requireNotNull(System.getProperty("coyote.projectRoot")))
        val bun = File(root, ".tools/bun-windows-x64-baseline/bun.exe")
        val upstream = File(root, "relay/upstream/v3-server.ts")
        assumeTrue("Optional integration runtime not prepared", bun.exists() && upstream.exists())
        val port = ServerSocket(0).use { it.localPort }
        val temporarySource = File.createTempFile("relay-loopback-", ".ts", File(root, ".tools"))
        val source = upstream.readText()
        check(source.contains("hostname: '::'"))
        temporarySource.writeText(source.replace("hostname: '::'", "hostname: '127.0.0.1'"))
        val serverLog = File(root, ".tools/relay-integration.log")
        val process = ProcessBuilder(bun.absolutePath, temporarySource.absolutePath).apply {
            environment()["PORT"] = port.toString()
            environment()["LOG_LEVEL"] = "error"
            redirectErrorStream(true); redirectOutput(serverLog)
        }.start()
        val http = OkHttpClient()
        var control: Peer? = null
        var app: Peer? = null
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var listening = false
            while (System.nanoTime() < deadline && process.isAlive) {
                try { Socket("127.0.0.1", port).use { }; listening = true; break }
                catch (_: Exception) { Thread.sleep(50) }
            }
            assertTrue("Relay did not start: ${serverLog.readText()}", listening)
            control = Peer(http, "ws://127.0.0.1:$port")
            val assigned = control.await("bind", "targetId")
            val own = assigned.getString("clientId")
            assertEquals(RelayEvent.Assigned(own), V3Protocol.parse(assigned.toString(), ""))
            app = Peer(http, "ws://127.0.0.1:$port/$own")
            val pairing = control.await("bind", "200")
            val appId = pairing.getString("targetId")
            assertEquals(RelayEvent.Paired(appId), V3Protocol.parse(pairing.toString(), own))
            val preset = EffectPreset("A", 8, 3)
            assertTrue(control.socket.send(V3Protocol.strength(own, appId, "A", preset.strength)))
            assertEquals("strength-1+2+8", app.await("msg", "strength").getString("message"))
            assertTrue(control.socket.send(V3Protocol.pulse(own, appId, preset)))
            assertTrue(app.await("msg", "pulse-A:").getString("message").contains(preset.shape.frame))
            assertTrue(control.socket.send(V3Protocol.clear(own, appId, "A")))
            assertEquals("clear-1", app.await("msg", "clear").getString("message"))
            assertTrue(control.socket.send(V3Protocol.strength(own, appId, "A", 0)))
            assertEquals("strength-1+2+0", app.await("msg", "strength").getString("message"))
            val next = app.incoming.poll(1300, TimeUnit.MILLISECONDS)
            assertNull("A pulse remained queued after clear: $next", next)
        } finally {
            control?.socket?.cancel(); app?.socket?.cancel()
            http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            temporarySource.delete()
        }
    }
}
