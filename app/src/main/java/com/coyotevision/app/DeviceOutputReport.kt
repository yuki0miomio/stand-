package com.coyotevision.app

import org.json.JSONObject

/** Read-only official-app telemetry. A reported value is never a physical-current confirmation. */
class DeviceOutputReport private constructor(val device: JSONObject, val ageMs: Long, val rpc: String,
    val waitingCleanup: Int, val queuedPulses: Int) {
    companion object {
        fun parse(data: JSONObject): DeviceOutputReport? {
            val device = data.optJSONObject("device") ?: return null
            if (device.optString("slotId").isBlank() || device.optString("type") !in setOf("COYOTE_020", "COYOTE_030")) return null
            return DeviceOutputReport(JSONObject(device.toString()), data.optLong("ageMs", 0).coerceAtLeast(0),
                data.optString("rpc", "尚无指令回执"), data.optInt("waitingCleanup", 0), data.optInt("queuedPulses", 0))
        }
    }
    private fun number(value: Any?): String = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
        ?.let { DisplayFormat.one(it) } ?: "未上报"
    val signature: String get() {
        val props = device.optJSONObject("props")
        val state = device.optJSONObject("slotState")
        return listOf(device.opt("slotId"), props?.opt("intensityA"), props?.opt("intensityB"),
            props?.opt("channelAStatus"), props?.opt("channelBStatus"),
            state?.optJSONObject("channelA")?.opt("isMuted"), state?.optJSONObject("channelA")?.opt("intensityMax"),
            state?.optJSONObject("channelB")?.opt("isMuted"), state?.optJSONObject("channelB")?.opt("intensityMax")).joinToString("|")
    }
    fun summary(): String {
        val props = device.optJSONObject("props")
        return "官方 App 回报强度：A ${number(props?.opt("intensityA"))} / B ${number(props?.opt("intensityB"))}"
    }
    fun outputProblems(): String = buildList {
        val props = device.optJSONObject("props")
        val state = device.optJSONObject("slotState")
        for (channel in listOf("A", "B")) {
            if (state?.optJSONObject("channel$channel")?.opt("isMuted") == true) add("$channel 静音")
            when ((props?.opt("channel${channel}Status") as? Number)?.toInt()) {
                1 -> add("$channel 未形成回路")
                3 -> add("$channel 输出损坏")
                4 -> add("$channel 通道屏蔽")
            }
        }
    }.joinToString("、")
    fun details(): String = buildString {
        append(summary()).append('\n')
        val state = device.optJSONObject("slotState")
        for (channel in listOf("A", "B")) {
            val value = state?.optJSONObject("channel$channel")
            val muted = when (value?.opt("isMuted")) { true -> "是"; false -> "否"; else -> "未上报" }
            append("$channel 静音：$muted · App 上限 ${number(value?.opt("intensityMax"))}\n")
        }
        val props = device.optJSONObject("props")
        val names = mapOf(0 to "无输出或过低", 1 to "未形成回路", 2 to "输出正常", 3 to "输出损坏", 4 to "通道屏蔽")
        for (channel in listOf("A", "B")) {
            val status = (props?.opt("channel${channel}Status") as? Number)?.toInt()
            if (status != null) append("$channel 主机状态回报：${names[status] ?: "未知($status)"}\n")
        }
        append("RPC：$rpc · 待确认清理 $waitingCleanup · 待发片段 $queuedPulses\n")
        append("以上为官方 App 回报，未测量人体电流")
    }
}
