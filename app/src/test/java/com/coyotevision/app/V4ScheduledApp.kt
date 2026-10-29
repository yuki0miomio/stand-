package com.coyotevision.app

import org.json.JSONArray
import org.json.JSONObject

/** App scheduler in which a clear cancels queued zero and active channel tasks. */
internal class V4ScheduledApp(val model: String) {
    var now = 0L
    var cancelNextZero = false
    var omitCancelledTaskReplies = false
    val errors = mutableListOf<String>()
    val outgoing = ArrayDeque<JSONObject>()
    val operations = mutableListOf<JSONObject>()
    val active = mutableListOf<Pair<JSONObject, Long>>()
    val peer = V4AppPeer({ outgoing.add(it.getJSONObject("data")) }, {}, { errors.add(it) }, clock = { now })
    init {
        peer.receive(JSONObject().put("t", "ev").put("ev", "devices.snapshot").put("devices", JSONArray().put(
            JSONObject().put("slotId", "slot").put("type", model).put("slotState", JSONObject().put("hasDevice", true)))))
    }
    fun reply(request: JSONObject, reason: String) {
        peer.receive(JSONObject().put("t", "resp").put("reqId", request.getString("reqId"))
            .put("result", JSONObject().put("reason", reason)))
    }
    fun tick() {
        val batch = outgoing.toList(); outgoing.clear()
        val zeros = batch.filter { it.optJSONObject("data")?.optInt("t", -1) == 7 }.toMutableList()
        for (clear in batch.filter { it.optString("m") == "device.op.clear" }) {
            val channel = clear.getJSONObject("data").getInt("c")
            for (zero in zeros.filter { it.getJSONObject("data").getInt("c") == channel }) {
                zeros.remove(zero); reply(zero, "cleared")
            }
            for (task in active.filter { it.first.getJSONObject("data").getInt("c") == channel }) {
                active.remove(task)
                if (!omitCancelledTaskReplies) reply(task.first, "cleared")
            }
            reply(clear, "completed")
        }
        zeros.forEach {
            val reason = if (cancelNextZero) "cleared" else "completed"
            cancelNextZero = false
            reply(it, reason)
        }
        for (request in batch.filter { it.optJSONObject("data")?.optInt("t", -1) in listOf(0, 4) }) {
            val data = request.getJSONObject("data")
            operations.add(data)
            active.add(request to now + data.getInt("d"))
        }
        for (task in active.filter { now >= it.second }) { active.remove(task); reply(task.first, "completed") }
    }
    fun drain() {
        repeat(12) { tick(); if (outgoing.isEmpty()) return }
        error("Cleanup did not settle")
    }
    fun stopChannels() {
        for (number in 1..2) { peer.forward("clear-$number"); peer.forward("strength-$number+2+0") }
    }
    fun play(channel: String, strength: Int, shape: PulseShape) {
        peer.forward("clear-${if (channel == "A") 1 else 2}")
        peer.forward("strength-${if (channel == "A") 1 else 2}+2+$strength")
        peer.forward("pulse-$channel:" + JSONArray(EffectPreset(shape = shape).pulseFrames()))
    }
}
