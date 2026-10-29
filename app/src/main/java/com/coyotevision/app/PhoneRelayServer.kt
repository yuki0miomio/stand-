package com.coyotevision.app

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One loopback controller, a finite-output scheduler, and a V3 or V4 official-app peer. */
class PhoneRelayServer(
    bindAddress: InetSocketAddress,
    private val started: (Int) -> Unit,
    private val peerLost: (String) -> Unit,
    private val fatal: (String) -> Unit,
    private val appV4: Boolean = false,
) : WebSocketServer(bindAddress, 2) {
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private var controller: WebSocket? = null
    private var controllerId = ""
    private var app: WebSocket? = null
    private var appId = ""
    private var v4: V4AppPeer? = null
    private var v4FailureReason = ""
    private val pending = mutableMapOf<WebSocket, String>()
    private val idleTasks = mutableMapOf<WebSocket, ScheduledFuture<*>>()
    private val pulseTasks = mutableMapOf<String, MutableList<ScheduledFuture<*>>>()
    private val pulseEpoch = mutableMapOf("A" to 0L, "B" to 0L)
    private var sessionTimer: ScheduledFuture<*>? = null
    private var sessionEpoch = 0L
    private var sessionExpired = false
    private var sessionActive = false
    @Volatile private var stopping = false
    val hasController: Boolean
        @Synchronized get() = controller?.isOpen == true

    init { isReuseAddr = true; connectionLostTimeout = 5 }

    override fun onStart() {
        if (stopping) return
        timers.scheduleAtFixedRate({ synchronized(this) {
            if (!stopping) {
                controller?.let { send(it, packet("heartbeat", "200")) }
                v4?.checkTimeout()
                app?.let { send(it, JSONObject().put("type", "heartbeat").put("clientId", appId)
                    .put("targetId", controllerId).put("message", "200")) }
            }
        } }, 5, 5, TimeUnit.SECONDS)
        started(port)
    }

    @Synchronized override fun onOpen(connection: WebSocket, handshake: ClientHandshake) {
        if (stopping) { closePeer(connection, 1001, "relay_stopped"); return }
        val uri = runCatching { URI(handshake.resourceDescriptor) }.getOrNull()
        val target = uri?.path?.trim('/') ?: ""
        val v4Target = uri?.rawQuery?.substringAfter("tid=", "")?.substringBefore('&') ?: ""
        if (appV4 && v4Target.isNotEmpty()) {
            if (controller == null || v4Target != controllerId || app != null) {
                closePeer(connection, 4001, "controller_not_found"); return
            }
            app = connection; appId = UUID.randomUUID().toString()
            v4FailureReason = ""
            send(connection, JSONObject().put("type", "hello").put("clientId", appId))
            send(connection, JSONObject().put("type", "controller_attached").put("clientId", controllerId))
            v4 = V4AppPeer({ send(connection, it) }, { name ->
                controller?.let { send(it, packet("bind", "200")); send(it, packet("notify", "DGLAB 4 · $name")) }
            }, { reason ->
                v4FailureReason = reason
                stopOutput()
                controller?.let { send(it, packet("notify", reason)) }
                closePeer(connection, 1008, "device_unavailable")
            }, { message ->
                controller?.let { send(it, packet("notify", message)) }
            }, { data ->
                controller?.let { send(it, packet("device-state", "").put("data", data)) }
            })
            v4?.requestDevices()
            return
        }
        if (target.isEmpty() && controller == null && connection.remoteSocketAddress.address.isLoopbackAddress) {
            controller = connection
            controllerId = UUID.randomUUID().toString()
            send(connection, JSONObject().put("type", "bind").put("clientId", controllerId)
                .put("targetId", "").put("message", "targetId"))
            return
        }
        if (controller == null || (target.isNotEmpty() && target != controllerId)) {
            reject(connection, "401", 4001); return
        }
        if (appV4) { reject(connection, "401"); return }
        if (app != null || pending.size >= 3) { reject(connection, "400", 4000); return }
        val id = UUID.randomUUID().toString()
        pending[connection] = id
        send(connection, JSONObject().put("type", "bind").put("clientId", id)
            .put("targetId", "").put("message", "targetId"))
        idleTasks[connection] = timers.schedule({ synchronized(this) {
            if (pending.remove(connection) != null) closePeer(connection, 4002, "pairing_timeout")
            idleTasks.remove(connection)
        } }, 15, TimeUnit.SECONDS)
        if (target == controllerId) pair(connection, id)
    }

    @Synchronized override fun onMessage(connection: WebSocket, raw: String) {
        if (stopping) return
        if (raw.length > (if (appV4 && connection === app) 65536 else 8192)) { reject(connection, "403"); return }
        val data = runCatching { JSONObject(raw) }.getOrElse { reject(connection, "403"); return }
        val type = data.optString("type")
        if (appV4 && connection === app) {
            when (type) {
                "message" -> data.optJSONObject("data")?.let { v4?.receive(it) }
                "ping" -> send(connection, JSONObject().put("type", "pong").put("ts", System.currentTimeMillis()))
                "heartbeat", "pong" -> Unit
                else -> closePeer(connection, 1008, "invalid_frame")
            }
            return
        }
        if (type == "heartbeat") return
        val source = data.optString("clientId")
        val target = data.optString("targetId")
        if (type == "bind" && connection !== controller) {
            val id = if (connection === app) appId else pending[connection]
            if (id == null || source != controllerId || target != id) { reject(connection, "401"); return }
            pair(connection, id)
            return
        }
        if (app == null || setOf(source, target) != setOf(controllerId, appId)) {
            reject(connection, "402"); return
        }
        if (connection === app) {
            val message = data.optString("message")
            if (type == "msg" && (message.startsWith("strength-") || message.startsWith("feedback-"))) {
                controller?.let { send(it, packet("msg", message)) }
            } else reject(connection, "404")
            return
        }
        if (connection !== controller || source != controllerId || target != appId) {
            reject(connection, "404"); return
        }
        if (type == "session") {
            when (data.optString("message")) {
                "start" -> {
                    val duration = data.optLong("durationMs", 0)
                    if (duration !in 100..SessionClock.MAX_DURATION_MS || sessionActive) { reject(connection, "403"); return }
                    sessionTimer?.cancel(false)
                    sessionActive = true; sessionExpired = false
                    val epoch = ++sessionEpoch
                    sessionTimer = timers.schedule({ synchronized(this) {
                        if (!stopping && sessionEpoch == epoch) {
                            sessionExpired = true; sessionActive = false
                            stopOutput()
                            controller?.let { send(it, packet("notify", "session-ended")) }
                        }
                    } }, duration, TimeUnit.MILLISECONDS)
                }
                "stop" -> {
                    sessionEpoch++; sessionTimer?.cancel(false); sessionTimer = null
                    sessionExpired = true; sessionActive = false; stopOutput()
                }
                else -> reject(connection, "403")
            }
            return
        }
        val channel = data.optString("channel").uppercase().let {
            when (it) { "1", "A" -> "A"; "2", "B" -> "B"; else -> null }
        }
        if (channel == null) { reject(connection, "406"); return }
        if ((sessionExpired || (appV4 && !sessionActive)) && (type == "clientMsg" || data.optInt("strength", 0) > 0)) {
            reject(connection, "410"); return
        }
        when (type) {
            "3", "4" -> {
                if (type == "4" && data.optString("message").contains("clear")) {
                    clearChannel(channel)
                } else {
                    val value = data.optInt("strength", -1)
                    if (value !in 0..200) { reject(connection, "403"); return }
                    sendApp("strength-${number(channel)}+2+$value")
                }
            }
            "clientMsg" -> enqueuePulse(connection, channel, data)
            else -> reject(connection, "403")
        }
    }

    private fun pair(connection: WebSocket, id: String) {
        if (app != null && app !== connection) { reject(connection, "400"); return }
        pending.remove(connection)
        idleTasks.remove(connection)?.cancel(false)
        app = connection
        appId = id
        val bind = packet("bind", "200")
        controller?.let { send(it, bind) }
        send(connection, bind)
    }

    private fun enqueuePulse(connection: WebSocket, channel: String, data: JSONObject) {
        val duration = data.optInt("time", 0)
        val message = data.optString("message")
        if (duration !in 1..30 || !message.startsWith("$channel:")) { reject(connection, "403"); return }
        val frames = runCatching {
            val array = JSONArray(message.substringAfter(':'))
            require(array.length() in 1..300)
            (0 until array.length()).map { index -> array.getString(index).also { frame ->
                require(frame.matches(Regex("[0-9a-fA-F]{16}")))
                val bytes = frame.chunked(2).map { it.toInt(16) }
                require(bytes.take(4).all { it in 10..240 } && bytes.drop(4).all { it in 0..100 })
            } }
        }.getOrElse { reject(connection, "403"); return }
        clearChannel(channel)
        val epoch = pulseEpoch.getValue(channel)
        val peer = app ?: return
        val tasks = mutableListOf<ScheduledFuture<*>>()
        pulseTasks[channel] = tasks
        // At most one second of data is sent ahead to the official app.
        for (second in 0 until duration) {
            val batch = JSONArray((0 until 10).map { frames[(second * 10 + it) % frames.size] })
            val payload = "pulse-$channel:$batch"
            tasks += timers.schedule({ synchronized(this) {
                if (!stopping && app === peer && pulseEpoch[channel] == epoch) sendApp(payload)
            } }, second * 1000L, TimeUnit.MILLISECONDS)
        }
        // The relay owns its own deadline, independent of the activity's UI timer.
        tasks += timers.schedule({ synchronized(this) {
            if (!stopping && app === peer && pulseEpoch[channel] == epoch) {
                clearChannel(channel)
                sendApp("strength-${number(channel)}+2+0")
                controller?.let { send(it, packet("notify", "发送完毕")) }
            }
        } }, duration * 1000L, TimeUnit.MILLISECONDS)
    }

    private fun clearChannel(channel: String) {
        pulseEpoch[channel] = pulseEpoch.getValue(channel) + 1
        pulseTasks.remove(channel)?.forEach { it.cancel(false) }
        sendApp("clear-${number(channel)}")
    }

    private fun stopOutput() {
        for (channel in listOf("A", "B")) {
            clearChannel(channel)
            sendApp("strength-${number(channel)}+2+0")
        }
    }

    private fun number(channel: String) = if (channel == "A") 1 else 2
    private fun packet(type: String, message: String) = JSONObject().put("type", type)
        .put("clientId", controllerId).put("targetId", appId).put("message", message)
    private fun sendApp(message: String) {
        if (appV4) v4?.forward(message) else app?.let { send(it, packet("msg", message)) }
    }
    private fun send(connection: WebSocket, packet: JSONObject) {
        if (connection.isOpen) runCatching { connection.send(packet.toString()) }
    }
    // WebSocket invokes onClose while holding its own monitor. Never acquire that
    // monitor while holding the relay monitor: close on the worker outside it.
    private fun closePeer(connection: WebSocket, code: Int, reason: String) {
        runCatching { timers.execute { runCatching { connection.close(code, reason) } } }
    }
    private fun reject(connection: WebSocket, code: String, closeCode: Int = 1008) {
        send(connection, packet("error", code))
        closePeer(connection, closeCode, "invalid_session")
    }

    @Synchronized override fun onClose(connection: WebSocket, code: Int, reason: String, remote: Boolean) {
        pending.remove(connection)
        idleTasks.remove(connection)?.cancel(false)
        if (connection !== controller && connection !== app) return
        val wasController = connection === controller
        sessionEpoch++; sessionTimer?.cancel(false); sessionTimer = null
        sessionExpired = false; sessionActive = false
        v4?.closeOutput()
        stopOutput()
        val other = if (wasController) app else controller
        other?.let { send(it, if (appV4 && wasController)
            JSONObject().put("type", "controller_disconnected").put("clientId", controllerId)
            else packet("break", "209")) }
        controller = null; app = null; controllerId = ""; appId = ""
        v4 = null
        other?.let { closePeer(it, 1000, "partner_disconnected") }
        if (!stopping) peerLost(v4FailureReason.ifEmpty { "手机连接已断开，请重新开启两手机连接" })
        v4FailureReason = ""
    }

    override fun onError(connection: WebSocket?, exception: Exception) {
        if (!stopping && connection == null) fatal("内置连接服务异常：${exception.message ?: "端口不可用"}")
    }

    /** Call on a worker thread; this waits briefly for close frames to flush. */
    fun shutdown() {
        val peers: List<WebSocket>
        synchronized(this) {
            if (stopping) return
            stopping = true
            sessionEpoch++; sessionTimer?.cancel(false)
            v4?.closeOutput()
            stopOutput()
            idleTasks.values.forEach { it.cancel(false) }
            idleTasks.clear()
            peers = listOfNotNull(controller, app)
        }
        timers.shutdownNow()
        peers.forEach { runCatching { it.close(1001, "relay_stopped") } }
        runCatching { stop(1000) }
    }
}
