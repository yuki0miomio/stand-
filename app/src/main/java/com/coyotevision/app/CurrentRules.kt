package com.coyotevision.app

/** Intensity numbers are protocol units, not measured amperage. Never add to a previous output. */
object CurrentRules {
    fun normal(value: Int, limit: Int) = value.coerceIn(0, limit.coerceIn(0, 200))

    fun withMotionFloor(base: Int, target: Int, limit: Int, limiter: MotionIntensity, now: Long,
        risePerSecond: Int = MotionRiseSpeed.DEFAULT): Int {
        val floor = normal(base, limit)
        val extraLimit = limit.coerceIn(0, 200) - floor
        val extra = (target.coerceIn(0, limit.coerceIn(0, 200)) - floor).coerceAtLeast(0)
        return floor + limiter.update(if (extraLimit > 0) extra.toFloat() / extraLimit else 0f, extraLimit, now, risePerSecond)
    }

    fun penalty(baseStrength: Int, incrementPreset: EffectPreset, limit: Int, remainingMs: Long): EffectPreset? {
        if (incrementPreset.strength !in 1..200 || remainingMs < 1000 || limit !in 1..200) return null
        val strength = (normal(baseStrength, limit) + incrementPreset.strength).coerceAtMost(limit)
        val preset = incrementPreset.copy(strength = strength,
            durationSeconds = minOf(incrementPreset.durationSeconds, (remainingMs / 1000).coerceAtMost(30).toInt()))
        return preset.takeIf { it.validFor(limit) }
    }
}
