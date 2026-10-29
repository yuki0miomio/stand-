package com.coyotevision.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/** Official DG-LAB 4 application RPC. Select exactly one connected Coyote 2.0 or 3.0. */
class V4AppPeer(private val emit: (JSONObject) -> Unit, private val ready: (String) -> Unit,
    private val failed: (String) -> Unit, private val status: (String) -> Unit = {},
    private val telemetry: (JSONObject) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Request(val sentAt: Long, val method: String, val channel: String?, val barrier: Boolean)
    private data class Pulse(val frames: JSONArray, val strength: Int, val until: Long)
    private val devices = linkedMapOf<String, JSONObject>()
    private val pending = linkedMapOf<String, Request>()
    private val cleanup = mutableMapOf("A" to mutableSetOf<String>(), "B" to mutableSetOf<String>())
    private val clearWanted = mutableSetOf<String>()
    private val zeroWanted = mutableSetOf<String>()
    private val cleanupStarted = mutableMapOf<String, Long>()
    private val zeroRetries = mutableMapOf<String, Int>()
    private val queued = mutableMapOf<String, Pulse>()
    private var sampleAt = 0L
    private var lastRpc = "尚无指令回执"
    private val strengths = mutableMapOf("A" to 0, "B" to 0)
    private val prefix = UUID.randomUUID().toString()
    private var sequence = 0L
    var slot = ""; private set
    var deviceType = ""; private set
    private var stopped = false

    companion object {
        private val modelNames = mapOf("COYOTE_020" to "郊狼 2.0", "COYOTE_030" to "郊狼 3.0")
        fun pairingLink(address: String, id: String): String {
            require(V3Protocol.validServer(address) && id.matches(Regex("[a-zA-Z0-9-]+")))
            val url = address.trimEnd('/') + "?tid=" + id
            return "https://dungeon-lab.cn/s/?v=1&action=socket&url=" + URLEncoder.encode(url, "UTF-8").replace("+", "%20")
        }
    }

    fun requestDevices() = rpc("devices.get")
    /** Terminal transport shutdown: forbid new output but permit best-effort clear/zero before close. */
    fun closeOutput() {
        stopped = true; queued.clear(); clearWanted.clear(); zeroWanted.clear()
        cleanup.values.forEach { it.clear() }; cleanupStarted.clear(); zeroRetries.clear()
    }
    private fun abort(reason: String) {
        if (!stopped) {
            closeOutput()
            failed(reason)
        }
    }
    fun receive(data: JSONObject) {
        if (stopped) return
        if (data.optString("t") == "resp") {
            val id = data.optString("reqId")
            val request = pending.remove(id) ?: return
            val error = data.opt("error")
            if (error != null && error != JSONObject.NULL && error.toString().isNotBlank()) {
                lastRpc = "${request.method} 失败：${data.optString("error")}"
                publish(); abort("DGLAB 4 拒绝指令：$lastRpc"); return
            }
            lastRpc = "${request.method} · ${data.optJSONObject("result")?.optString("reason")?.takeIf { it.isNotEmpty() } ?: "已响应"}"
            val reason = data.optJSONObject("result")?.optString("reason") ?: ""
            if (request.barrier && request.channel != null) {
                val channel = request.channel
                cleanup.getValue(channel).remove(id)
                if (request.method == "device.op" && reason.isNotBlank() && reason != "completed") {
                    val retries = zeroRetries[channel] ?: 0
                    if (reason !in setOf("cleared", "replaced", "cancelled") || retries >= 2 ||
                        clock() - (cleanupStarted[channel] ?: request.sentAt) > 5000) {
                        publish(); abort("DGLAB 4 归零确认失败（$channel）：$reason，重试 $retries 次"); return
                    }
                    zeroRetries[channel] = retries + 1
                    zeroWanted.add(channel)
                    lastRpc = "$channel 归零回执 $reason，重新确认（${retries + 1}/2）"
                }
                if (request.method == "device.op.clear") {
                    // The clear response proves older channel tasks were removed. Some apps omit their terminal replies.
                    pending.entries.removeAll { (_, old) -> old.channel == channel && !old.barrier && old.method == "device.op" }
                }
            }
            data.optJSONObject("result")?.optJSONArray("devices")?.let { snapshot(it) }
            request.channel?.let { pumpCleanup(it) }
            publish()
            return
        }
        if (data.optString("t") != "ev") return
        when (data.optString("ev")) {
            "devices.snapshot" -> snapshot(data.optJSONArray("devices") ?: JSONArray())
            "devices.patch" -> {
                val removed = data.optJSONArray("removed") ?: JSONArray()
                for (i in 0 until removed.length()) devices.remove(removed.optString(i))
                val added = data.optJSONArray("added") ?: JSONArray()
                for (i in 0 until added.length()) added.optJSONObject(i)?.let { devices[it.optString("slotId")] = it }
                select()
            }
            "slots.patch" -> {
                val slots = data.optJSONArray("slots") ?: JSONArray()
                for (i in 0 until slots.length()) {
                    val patch = slots.optJSONObject(i) ?: continue
                    val current = devices[patch.optString("slotId")] ?: continue
                    for (key in listOf("props", "slotState")) patch.optJSONObject(key)?.let { update ->
                        val value = current.optJSONObject(key) ?: JSONObject().also { current.put(key, it) }
                        merge(value, update)
                    }
                }
                select()
            }
        }
    }
    private fun snapshot(array: JSONArray) {
        devices.clear()
        for (i in 0 until array.length()) array.optJSONObject(i)?.let {
            if (it.optString("slotId").isNotBlank()) devices[it.getString("slotId")] = it
        }
        select()
    }
    private fun select() {
        sampleAt = clock()
        val supported = devices.values.filter { it.optString("type") in modelNames && it.optString("slotId").isNotBlank() &&
            it.optJSONObject("slotState")?.optBoolean("hasDevice", false) == true }
        if (slot.isNotEmpty()) {
            if (supported.size != 1 || supported.single().optString("slotId") != slot ||
                supported.single().optString("type") != deviceType)
                abort("DGLAB 4 设备已断开或设备列表变化，请重新配对")
            publish(); return
        }
        if (supported.size == 1) {
            val selected = supported.single()
            slot = selected.getString("slotId")
            deviceType = selected.getString("type")
            val model = modelNames.getValue(deviceType)
            val name = selected.optString("name").trim()
            ready(if (name.isEmpty() || name == model) model else "$model · $name")
        } else status(if (supported.isEmpty()) "DGLAB 4 已接入：等待一台已连接的郊狼 2.0 或 3.0"
            else "DGLAB 4 已接入：检测到多台郊狼，请仅保留一台已连接")
        publish()
    }

    /** No nonzero persistent intensity is issued: positive values only accompany a finite pulse lease. */
    fun forward(message: String) {
        if (slot.isEmpty()) return
        when {
            message.startsWith("strength-") -> {
                val channel = if (message.substringAfter('-').startsWith("1")) "A" else "B"
                val strength = message.substringAfterLast('+').toIntOrNull()?.coerceIn(0, 200) ?: 0
                if (stopped && strength > 0) return
                strengths[channel] = strength
                if (strength == 0) {
                    queued.remove(channel)
                    if (stopped) op(channel, 7, 0) else requestCleanup(channel, clear = false)
                }
            }
            message.startsWith("clear-") -> {
                val channel = if (message.endsWith("1")) "A" else "B"
                queued.remove(channel)
                if (stopped) rpc("device.op.clear", JSONObject().put("s", slot).put("c", if (channel == "A") 0 else 1))
                else requestCleanup(channel, clear = true)
            }
            message.startsWith("pulse-") -> {
                if (stopped) return
                val channel = message.substringAfter("pulse-").substringBefore(':')
                if (channel !in cleanup) return
                val frames = runCatching { JSONArray(message.substringAfter(':')) }.getOrNull() ?: return
                queued[channel] = Pulse(frames, strengths[channel] ?: 0, clock() + 1000)
                flush(channel)
            }
        }
        publish()
    }
    private fun requestCleanup(channel: String, clear: Boolean) {
        cleanupStarted.putIfAbsent(channel, clock())
        val active = cleanup.getValue(channel).firstOrNull()?.let { pending[it] }
        // Coalesce a repeated request only while the same cleanup kind is already in flight.
        if (clear && active?.method != "device.op.clear") clearWanted.add(channel)
        if (!clear && active?.method != "device.op") zeroWanted.add(channel)
        pumpCleanup(channel)
    }
    private fun pumpCleanup(channel: String) {
        if (stopped || cleanup.getValue(channel).isNotEmpty()) return
        when {
            clearWanted.remove(channel) -> rpc("device.op.clear",
                JSONObject().put("s", slot).put("c", if (channel == "A") 0 else 1), channel, true)
            zeroWanted.remove(channel) -> op(channel, 7, 0, barrier = true)
            else -> {
                cleanupStarted.remove(channel); zeroRetries.remove(channel)
                flush(channel)
            }
        }
    }
    private fun flush(channel: String) {
        if (stopped || cleanup.getValue(channel).isNotEmpty() || channel in clearWanted || channel in zeroWanted) return
        val pulse = queued.remove(channel) ?: return
        val remaining = (pulse.until - clock()).coerceIn(0, 1000).toInt()
        if (remaining <= 0 || pulse.strength <= 0) return
        // Clear/zero RPCs complete before the new pair is issued. Never replay an expired lease.
        op(channel, 4, pulse.strength, remaining)
        op(channel, 0, pulse.frames, remaining)
    }
    private fun op(channel: String, type: Int, value: Any, duration: Int = 0, barrier: Boolean = false) {
        val operation = JSONObject().put("s", slot).put("t", type).put("c", if (channel == "A") 0 else 1)
            .put("p", 1).put("d", duration).put("im", true).put("v", value)
        // ver describes the supplied frame encoding, not the hardware generation.
        // Keep the SDK's V3 eight-byte format for both models; the official app owns BLE encoding.
        if (type == 0) operation.put("ver", 3)
        rpc("device.op", operation, channel, barrier)
    }
    private fun rpc(method: String, data: JSONObject? = null, channel: String? = null, barrier: Boolean = false): String {
        val id = "$prefix-${++sequence}"
        pending[id] = Request(clock(), method, channel, barrier)
        if (barrier && channel != null) cleanup.getValue(channel).add(id)
        val payload = JSONObject().put("t", "req").put("reqId", id).put("m", method)
        if (data != null) payload.put("data", data)
        emit(JSONObject().put("type", "message").put("data", payload))
        return id
    }
    private fun publish() {
        val device = devices[slot] ?: return
        telemetry(JSONObject().put("device", JSONObject(device.toString())).put("ageMs", (clock() - sampleAt).coerceAtLeast(0))
            .put("rpc", lastRpc).put("waitingCleanup", cleanup.values.sumOf { it.size } + clearWanted.size + zeroWanted.size)
            .put("queuedPulses", queued.size))
    }
    private fun merge(target: JSONObject, patch: JSONObject) {
        patch.keys().forEach { key ->
            val update = patch.get(key)
            val current = target.optJSONObject(key)
            if (update is JSONObject && current != null) merge(current, update) else target.put(key, update)
        }
    }
    fun checkTimeout(now: Long = clock()) {
        if (cleanupStarted.values.any { now - it > 5000 }) {
            abort("DGLAB 4 清理或归零确认超时，已停止本轮"); return
        }
        if (pending.values.any { now - it.sentAt > 5000 }) abort("DGLAB 4 指令响应超时，已停止本轮")
    }
}
