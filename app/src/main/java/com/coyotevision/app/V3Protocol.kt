package com.coyotevision.app

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

sealed class RelayEvent {
    data class Assigned(val id: String) : RelayEvent()
    data class Paired(val appId: String) : RelayEvent()
    data class Report(val text: String) : RelayEvent()
    data class DeviceState(val data: DeviceOutputReport) : RelayEvent()
    data class Failure(val reason: String) : RelayEvent()
    data object Ignored : RelayEvent()
}

object V3Protocol {
    /** A single ordered transition: optional all-channel stop, then the selected finite preset. */
    fun effectBatch(own: String, app: String, preset: EffectPreset, clearAll: Boolean = false): List<String> {
        val stop = if (clearAll) listOf("A", "B").flatMap { channel ->
            listOf(clear(own, app, channel), strength(own, app, channel, 0))
        } else listOf(clear(own, app, preset.channel))
        return stop + listOf(strength(own, app, preset.channel, preset.strength), pulse(own, app, preset))
    }
    fun validServer(address: String): Boolean = runCatching {
        val uri = URI(address)
        uri.scheme in setOf("ws", "wss") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.fragment == null &&
            uri.query == null && (uri.port == -1 || uri.port in 1..65535)
    }.getOrDefault(false)

    fun pairingLink(address: String, id: String): String {
        require(validServer(address) && id.isNotBlank())
        val destination = address.trimEnd('/') + "/" + URLEncoder.encode(id, "UTF-8")
        // DGLAB 3.0's legacy scanner expects the third # segment to start with ws(s)://.
        return "https://www.dungeon-lab.com/app-download.php#DGLAB-SOCKET#$destination"
    }

    fun parse(raw: String, ownId: String): RelayEvent {
        val json = try { JSONObject(raw) } catch (_: Exception) {
            return RelayEvent.Failure("服务器返回了无效消息")
        }
        val type = json.optString("type")
        val client = json.optString("clientId")
        val target = json.optString("targetId")
        val message = json.optString("message")
        if (type == "heartbeat") return RelayEvent.Ignored
        if (type == "hello") return RelayEvent.Failure("这是 V4 服务，请改用 V3 后端")
        if (type == "bind" && message == "targetId" && target.isEmpty() && client.isNotBlank()) {
            return if (ownId.isEmpty() || ownId == client) RelayEvent.Assigned(client)
                else RelayEvent.Failure("连接标识发生变化，请重新连接")
        }
        if (type == "bind" && message == "200" && client == ownId && target.isNotBlank() && target != ownId) {
            return RelayEvent.Paired(target)
        }
        if (type == "bind" && message != "200") return RelayEvent.Failure("配对失败：$message")
        if (type == "break" || type == "error") return RelayEvent.Failure("连接中断：$message")
        if (type == "device-state" && ownId.isNotEmpty() && client == ownId && target.isNotBlank() && target != ownId) {
            return json.optJSONObject("data")?.let { DeviceOutputReport.parse(it) }?.let { RelayEvent.DeviceState(it) }
                ?: RelayEvent.Ignored
        }
        if (ownId.isNotEmpty() && (client == ownId || target == ownId) && type in setOf("msg", "notify")) {
            return RelayEvent.Report(message.take(180))
        }
        return RelayEvent.Ignored
    }

    private fun envelope(type: Any, own: String, app: String, message: String): JSONObject {
        require(own.isNotBlank() && app.isNotBlank() && own != app)
        return JSONObject().put("type", type).put("clientId", own)
            .put("targetId", app).put("message", message)
    }

    fun strength(own: String, app: String, channel: String, strength: Int): String {
        require(channel in setOf("A", "B") && strength in 0..200)
        // The official V3 relay routes any message beginning with "strength" as a
        // report before checking numeric types. Keep this numeric command's message empty.
        return envelope(3, own, app, "").put("channel", channel)
            .put("strength", strength).toString()
    }

    fun clear(own: String, app: String, channel: String): String {
        require(channel in setOf("A", "B"))
        return envelope(4, own, app, "clear").put("channel", channel).toString()
    }

    fun pulse(own: String, app: String, preset: EffectPreset): String {
        require(preset.validFor(200))
        val pulse = "${preset.channel}:" + org.json.JSONArray(preset.pulseFrames()).toString()
        return envelope("clientMsg", own, app, pulse).put("channel", preset.channel)
            .put("time", preset.durationSeconds).toString()
    }
}
