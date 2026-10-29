package com.coyotevision.app

/** Full-scale ranges describe extra distance after the learned noise threshold, in shoulder percent. */
data class MotionMappingSettings(val handRangePercent: Int = 65, val footRangePercent: Int = 35,
    val torsoRangePercent: Int = 50) {
    fun valid() = handRangePercent in 5..200 && footRangePercent in 5..200 && torsoRangePercent in 5..200
}

/** Values use fixed preview coordinates, so the console can explain the exact scoring input. */
data class MotionMappingReading(val input: Float, val threshold: Float, val range: Float,
    val source: String, val score: Float) {
    val excess get() = (input - threshold).coerceAtLeast(0f)
    val fullAt get() = threshold + range
}

object MotionMapping {
    fun evaluate(input: Float, threshold: Float, range: Float, source: String): MotionMappingReading? {
        if (!input.isFinite() || !threshold.isFinite() || !range.isFinite() ||
            input < 0 || threshold < 0 || range <= 0 || !(threshold + range).isFinite()) return null
        return MotionMappingReading(input, threshold, range, source,
            ((input - threshold) / range).coerceIn(0f, 1f))
    }
}
