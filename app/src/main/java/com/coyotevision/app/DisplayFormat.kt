package com.coyotevision.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DisplayFormat {
    fun one(value: Number) = String.format(Locale.ROOT, "%.1f", value.toDouble())
    fun seconds(ms: Long) = "${one(ms.coerceAtLeast(0) / 1000.0)} 秒"
    fun duration(ms: Long): String {
        val tenths = (ms.coerceAtLeast(0) + 50) / 100
        return "%d:%02d.%d".format(Locale.ROOT, tenths / 600, tenths / 10 % 60, tenths % 10)
    }
    fun timestamp(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms)) + ".${ms.mod(1000) / 100}"
}
