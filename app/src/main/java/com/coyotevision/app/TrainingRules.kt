package com.coyotevision.app

import kotlin.random.Random

/** Increment metadata for motion alerts. Runtime output is calculated by DirectMotionSettings. */
data class MotionWeights(val hands: Int = 0, val feet: Int = 0, val outside: Int = 0) {
    fun valid() = listOf(hands, feet, outside).all { it in 0..200 }
    /** Legacy sum retained to verify that migration does not raise old requested strengths. */
    fun target(body: StandingMetrics, limit: Int, base: Int = 0, bodyWeight: Int = 0): Int {
        if (!body.tracked) return 0
        fun score(x: Float) = if (x.isFinite()) x.coerceIn(0f, 1f) else 0f
        return (score(body.hands) * hands.coerceIn(0, 200) + score(body.feet) * feet.coerceIn(0, 200) +
            (if (body.outside) outside.coerceIn(0, 200) else 0) + score(body.score) * bodyWeight.coerceIn(0, 200) + base.coerceIn(0, 200))
            .toInt().coerceIn(0, limit.coerceIn(0, 200))
    }
}

data class EnduranceSettings(val enabled: Boolean = false, val minGapMs: Long = 30_000,
    val maxGapMs: Long = 60_000, val minDurationMs: Long = 3000, val maxDurationMs: Long = 8000) {
    fun valid() = minGapMs in 5000..300_000 && maxGapMs in minGapMs..300_000 &&
        minDurationMs in 1000..30_000 && maxDurationMs in minDurationMs..30_000
}
enum class TrainingEvent { START, END }

/** All intervals are finite. A busy curtsey window postpones training; training blocks new curtseys. */
class EnduranceTraining(private val settings: EnduranceSettings, start: Long,
    private val endAt: Long, private val random: Random = Random.Default) {
    var active = false; private set
    var until = 0L; private set
    private var next = Long.MAX_VALUE
    init { require(settings.valid()); schedule(start) }
    fun resumeAfterCalibration(now: Long) {
        active = false; until = 0; schedule(now)
    }
    private fun schedule(now: Long) {
        next = if (!settings.enabled || endAt - now < settings.minGapMs + settings.minDurationMs + 1100) Long.MAX_VALUE
        else now + random.nextLong(settings.minGapMs, settings.maxGapMs + 1)
    }
    fun update(now: Long, curtseyBusy: Boolean): TrainingEvent? {
        if (active && (now >= until || now >= endAt)) {
            active = false; schedule(now); return TrainingEvent.END
        }
        if (!active && now >= next && !curtseyBusy && now < endAt - settings.minDurationMs - 1100) {
            active = true
            until = minOf(now + random.nextLong(settings.minDurationMs, settings.maxDurationMs + 1), endAt - 1100)
            return TrainingEvent.START
        }
        return null
    }
}
