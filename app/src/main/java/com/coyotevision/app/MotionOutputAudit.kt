package com.coyotevision.app

import java.util.Locale

/** Records an emitted extra-strength fragment, rather than a mere recognition or refresh at base. */
object MotionOutputAudit {
    fun describe(body: StandingMetrics, weights: MotionWeights, bodyWeight: Int,
        base: Int, limit: Int, emittedStrength: Int): String? {
        if (!body.tracked) return null
        val extra = emittedStrength - CurrentRules.normal(base, limit)
        if (extra <= 0) return null
        val causes = mutableListOf<String>()
        fun add(name: String, score: Float, weight: Int) {
            val contribution = score.coerceIn(0f, 1f) * weight.coerceIn(0, 200)
            if (contribution > 0) causes += String.format(Locale.ROOT, "%s +%.1f", name, contribution)
        }
        add("手部", body.hands, weights.hands)
        add("脚步", body.feet, weights.feet)
        if (body.outside && weights.outside > 0) causes += "离位 +${weights.outside}"
        add("身体幅度", body.score, bodyWeight)
        if (causes.isEmpty()) return null
        return "${causes.joinToString("、")} → 本片段实际动作加成 +$extra（含缓升与总上限），总强度 $emittedStrength"
    }
}
