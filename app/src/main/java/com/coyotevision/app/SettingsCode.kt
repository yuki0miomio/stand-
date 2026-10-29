package com.coyotevision.app

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Versioned portable settings only: no session state, pairing IDs, logs or calibration points. */
internal object SettingsCode {
    const val PREFIX = "CVSET1."
    const val MAX_CODE_SIZE = 24_000
    private const val MAX_RAW_SIZE = 16_384
    class Invalid(message: String) : IllegalArgumentException(message)
    private fun need(condition: Boolean, message: String) { if (!condition) throw Invalid(message) }
    data class NumberField(val value: Int, val range: IntRange, val title: String)
    val numbers = linkedMapOf(
        "minutes" to NumberField(3, 1..90, "罚站总时长"),
        "wrist_near_percent" to NumberField(70, 5..150, "手腕靠近距离"),
        "limit" to NumberField(0, 0..200, "全局最高强度"),
        "normal_strength" to NumberField(0, 0..200, "常态强度"),
        "motion_rise_rate" to NumberField(4, 1..200, "动作上升速度"),
        "hand_peak" to NumberField(0, 0..200, "手部最高强度"),
        "hand_mapping_range" to NumberField(65, 5..200, "手部满幅范围"),
        "foot_peak" to NumberField(0, 0..200, "脚步最高强度"),
        "foot_mapping_range" to NumberField(35, 5..200, "脚步满幅范围"),
        "torso_peak" to NumberField(0, 0..200, "躯干最高强度"),
        "torso_mapping_range" to NumberField(50, 5..200, "躯干满幅范围"),
        "zone_peak" to NumberField(0, 0..200, "离位强度"),
        "zone_radius" to NumberField(50, 20..200, "站立半径"),
        "gap_min" to NumberField(15, 5..300, "行礼最短间隔"),
        "gap_max" to NumberField(30, 5..300, "行礼最长间隔"),
        "response" to NumberField(8, 3..30, "行礼期限"),
        "curtsey_range" to NumberField(30, 10..80, "屈膝礼判定范围"),
        "train_gap_min" to NumberField(30, 5..300, "训练最短间隔"),
        "train_gap_max" to NumberField(60, 5..300, "训练最长间隔"),
        "train_duration_min" to NumberField(3, 1..30, "训练最短时长"),
        "train_duration_max" to NumberField(8, 1..30, "训练最长时长"),
        "face_cover_size" to NumberField(80, 20..400, "实验遮脸大小"),
        "face_cover_x" to NumberField(0, -50..50, "实验遮脸左右微调"),
        "face_cover_y" to NumberField(0, -50..50, "实验遮脸上下微调"),
        "face_cover_width" to NumberField(100, 20..300, "实验遮脸宽度"),
        "face_cover_height" to NumberField(75, 20..300, "实验遮脸高度")
    )
    val presetKeys = listOf("motion", "penalty_delta", "curtsey", "cue", "training")
    val holdOptions = listOf(500L, 1000L, 1500L, 2000L, 3000L, 5000L, 8000L, 10_000L)
    fun defaults(): Map<String, Any> = linkedMapOf<String, Any>().apply {
        numbers.forEach { (key, value) -> put(key, value.value) }
        put("server", ""); put("curtsey_hold_ms", 500L)
        put("game", true); put("training", false); put("mirror_mode", false); put("face_mosaic", false); put("hide_skeleton", false)
        put("console_visible", true); put("mode_virtual", true); put("mode_device", false)
        presetKeys.forEach { key ->
            put("${key}_strength", 0); put("${key}_channel", 0)
            put("${key}_duration", if (key == "cue") 2 else 0)
            put("${key}_waveform", if (key == "cue") PulseShape.TRIPLE_SHORT.name else PulseShape.RAMP.name)
        }
    }

    /** Validate a complete snapshot before any preferences or UI controls are changed. */
    fun validate(input: Map<String, Any>): Map<String, Any> {
        // Keep the legacy face_mosaic key for the lightweight circular cover.
        // CVSET1 codes from 0.16.10/11 predate this optional display preference.
        val values = mapOf<String, Any>("face_mosaic" to false, "hide_skeleton" to false) + FaceCoverSettings.defaults + input
        val schema = defaults()
        need(values.keys == schema.keys, "设置代码缺少参数或包含不支持的参数")
        val result = linkedMapOf<String, Any>()
        schema.forEach { (key, default) ->
            val value = values.getValue(key)
            result[key] = when (default) {
                is Boolean -> { need(value is Boolean, "$key 需为开关值"); value }
                is String -> { need(value is String, "$key 需为文字"); value }
                else -> {
                    need(value is Int || value is Long, "$key 需为整数")
                    val n = (value as Number).toLong()
                    need(n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "$key 数值超出范围")
                    if (default is Long) n else n.toInt()
                }
            }
        }
        fun n(key: String) = result.getValue(key) as Int
        fun b(key: String) = result.getValue(key) as Boolean
        numbers.forEach { (key, spec) -> need(n(key) in spec.range, "${spec.title}需为 ${spec.range.first}–${spec.range.last}") }
        val direct = DirectMotionSettings(n("normal_strength"), n("limit"), n("hand_peak"), n("foot_peak"), n("torso_peak"), n("zone_peak"))
        need(direct.valid(), "常态不能超过全局最高；动作最高需为 0 或在常态至全局最高之间")
        need(n("gap_min") <= n("gap_max"), "行礼最短间隔不能大于最长间隔")
        need(n("train_gap_min") <= n("train_gap_max"), "训练最短间隔不能大于最长间隔")
        need(n("train_duration_min") <= n("train_duration_max"), "训练最短时长不能大于最长时长")
        val hold = result.getValue("curtsey_hold_ms") as Long
        need(hold in holdOptions, "不支持这个屈膝礼保持时长")
        need(!b("game") || hold + 500 <= n("response") * 1000L, "行礼期限需至少比保持时长长 0.5 秒")
        need(!(b("mode_virtual") && b("mode_device")), "虚拟与设备输出模式不能同时启用")
        presetKeys.forEach { key ->
            need(n("${key}_strength") in 0..200, "$key 强度需为 0–200")
            need(n("${key}_channel") in 0..1, "$key 通道无效")
            need(n("${key}_duration") in 0..29, "$key 波形时长无效")
            val shape = PulseShape.entries.firstOrNull { it.name == result["${key}_waveform"] }
            need(shape != null && shape.cueOnly == (key == "cue"), "$key 波形不支持或类别不匹配")
            if (key != "penalty_delta") need(n("${key}_duration") == if (key == "cue") 2 else 0, "$key 需使用固定片段时长")
        }
        need(n("motion_strength") == 0, "普通罚站强度由各动作最高参数决定")
        need(!b("training") || n("training_strength") <= n("limit"), "训练基础不能超过全局最高")
        need(!b("game") || (n("cue_strength") <= n("limit") && n("curtsey_strength") <= n("limit")), "行礼提示和过程强度不能超过全局最高")
        val server = result.getValue("server") as String
        need(server.length <= 1024, "服务器地址过长")
        if (server.isNotEmpty()) {
            val uri = runCatching { URI(server) }.getOrNull()
            need(uri != null && uri.scheme in setOf("ws", "wss") && !uri.host.isNullOrEmpty(), "服务器地址需为空或有效的 ws://、wss:// 地址")
        }
        return result
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    fun encode(values: Map<String, Any>): String {
        val settings = JSONObject()
        validate(values).toSortedMap().forEach { (key, value) -> settings.put(key, value) }
        val raw = JSONObject().put("app", "CoyoteVision").put("version", 1).put("settings", settings).toString().toByteArray(Charsets.UTF_8)
        need(raw.size <= MAX_RAW_SIZE, "设置代码内容过长")
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        val bytes = output.toByteArray()
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "." + digest(bytes)
    }

    fun decode(code: String): Map<String, Any> = try {
        need(code.length <= MAX_CODE_SIZE + 512, "设置代码过长")
        val compact = code.filterNot { it.isWhitespace() }
        need(compact.length <= MAX_CODE_SIZE, "设置代码过长")
        need(compact.startsWith(PREFIX), "不是支持的 CVSET1 设置代码")
        val parts = compact.split('.')
        need(parts.size == 3 && parts[1].isNotEmpty(), "设置代码格式不完整")
        val bytes = Base64.getUrlDecoder().decode(parts[1])
        need(digest(bytes) == parts[2], "设置代码校验失败，请重新完整复制")
        val raw = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                need(raw.size() + count <= MAX_RAW_SIZE, "设置代码解压内容过长")
                raw.write(buffer, 0, count)
            }
        }
        val envelope = JSONObject(raw.toString("UTF-8"))
        need(envelope.keys().asSequence().toSet() == setOf("app", "version", "settings") && envelope.opt("app") == "CoyoteVision" && envelope.opt("version") == 1,
            "设置代码来自不兼容的应用或版本")
        val settings = envelope.getJSONObject("settings")
        validate(settings.keys().asSequence().associateWith { settings.get(it) })
    } catch (error: Invalid) { throw error }
    catch (_: Exception) { throw Invalid("设置代码损坏或格式不正确，请重新复制") }
}
