package com.coyotevision.app

/** User-facing peaks are absolute protocol strengths. Simultaneous motion never stacks peaks. */
data class DirectMotionSettings(val normal: Int = 0, val limit: Int = 0, val handPeak: Int = 0,
    val footPeak: Int = 0, val torsoPeak: Int = 0, val outsidePeak: Int = 0) {
    fun valid() = limit in 0..200 && normal in 0..limit &&
        listOf(handPeak, footPeak, torsoPeak, outsidePeak).all { it == 0 || it in normal..limit }

    /** 0 disables an action. Training adds the SAME ordinary movement headroom to its own base. */
    fun increments() = MotionWeights(delta(handPeak), delta(footPeak), delta(outsidePeak))
    fun delta(peak: Int) = if (peak == 0) 0 else (peak - normal).coerceAtLeast(0)
    fun target(body: StandingMetrics, trainingBase: Int? = null): Int {
        if (!valid() || !body.tracked || !body.measurementReady || !body.calibrated) return 0
        val base = (trainingBase ?: normal).coerceIn(0, limit)
        fun scaled(score: Float, peak: Int) = if (score.isFinite()) score.coerceIn(0f, 1f) * delta(peak) else 0f
        val extra = maxOf(scaled(body.hands, handPeak), scaled(body.feet, footPeak),
            scaled(body.torsoScore, torsoPeak), if (body.outside) delta(outsidePeak).toFloat() else 0f)
        return (base + extra).toInt().coerceIn(0, limit)
    }
    fun trainingMaximum(base: Int) = (base.coerceIn(0, limit.coerceIn(0, 200)) +
        maxOf(delta(handPeak), delta(footPeak), delta(torsoPeak), delta(outsidePeak))).coerceAtMost(limit.coerceIn(0, 200))

    companion object {
        fun legacyPeak(normal: Int, increment: Int, limit: Int): Int =
            if (increment <= 0) 0 else (normal.coerceIn(0, 200) + increment.coerceIn(0, 200))
                .coerceAtMost(limit.coerceIn(0, 200))
    }
}
