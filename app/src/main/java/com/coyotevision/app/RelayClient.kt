package com.coyotevision.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** All public methods and UI callbacks run on the main thread. No automatic reconnect/output replay. */
class RelayClient(private val changed: () -> Unit, private val failed: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(2, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var generation = 0L
    var ownId = ""
        private set
    var appId = ""
        private set
    var status = "尚未连接"
        private set
    var report = ""
        private set
    var deviceReport: DeviceOutputReport? = null
        private set
    var deviceReportAt = 0L
        private set
    var address = ""
        private set
    val ready get() = socket != null && ownId.isNotEmpty() && appId.isNotEmpty()
    val idle get() = ready && socket?.queueSize() == 0L

    fun connect(server: String) {
        disconnect()
        if (!V3Protocol.validServer(server)) { status = "请输入有效的 ws:// 或 wss:// V3 服务地址"; changed(); return }
        address = server
        val epoch = generation
        status = "正在连接…"
        changed()
        socket = client.newWebSocket(Request.Builder().url(server).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = deliver(epoch) {
                when (val event = V3Protocol.parse(text, ownId)) {
                    is RelayEvent.Assigned -> { ownId = event.id; status = "等待官方 App 扫码配对" }
                    is RelayEvent.Paired -> { appId = event.appId; status = "官方 App 已配对" }
                    is RelayEvent.Report -> report = event.text
                    is RelayEvent.DeviceState -> {
                        deviceReport = event.data
                        deviceReportAt = android.os.SystemClock.elapsedRealtime()
                    }
                    is RelayEvent.Failure -> fail(event.reason)
                    RelayEvent.Ignored -> Unit
                }
                changed()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                deliver(epoch) { fail("连接失败：${t.message ?: "网络不可用"}") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                deliver(epoch) { fail("连接已关闭（$code）") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        })
    }

    private fun deliver(epoch: Long, block: () -> Unit) {
        main.post { if (generation == epoch) block() }
    }

    private fun fail(reason: String) {
        disconnect()
        status = reason
        changed()
        failed(reason)
    }

    fun sendEffect(preset: EffectPreset, limit: Int, clearAll: Boolean = false): Boolean {
        if (!ready || !preset.validFor(limit) || socket?.queueSize() != 0L) return false
        // Validate the queue before the batch, not between its own stop/start messages.
        for (message in V3Protocol.effectBatch(ownId, appId, preset, clearAll)) if (!send(message)) return false
        return true
    }

    /** True means enqueued on the socket, NOT confirmed by the physical device. */
    fun stopAll(): Boolean {
        if (!ready) return false
        val messages = listOf("A", "B").flatMap { channel ->
            listOf(V3Protocol.clear(ownId, appId, channel), V3Protocol.strength(ownId, appId, channel, 0))
        }
        var accepted = true
        for (message in messages) if (!send(message)) accepted = false
        return accepted
    }

    private fun send(message: String) = socket?.send(message) == true

    fun timedSession(start: Boolean, durationMs: Long = 0): Boolean {
        if (!ready) return false
        if (start && durationMs !in 100..SessionClock.MAX_DURATION_MS) return false
        if (start) report = ""
        return send(JSONObject().put("type", "session").put("clientId", ownId).put("targetId", appId)
            .put("message", if (start) "start" else "stop").put("durationMs", durationMs).toString())
    }

    fun disconnect() {
        generation++
        socket?.cancel()
        socket = null
        ownId = ""
        appId = ""
        status = "已断开"
        report = ""
        deviceReport = null; deviceReportAt = 0
        changed()
    }

    fun release() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
