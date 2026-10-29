package com.coyotevision.app

/** Process output replaces movement output only inside a live curtsey response window. */
object CurtseyOutputRules {
    fun process(phase: GamePhase?, tracked: Boolean, now: Long, responseDeadline: Long,
        sessionEndAt: Long, preset: EffectPreset, limit: Int): EffectPreset? {
        if (phase != GamePhase.REQUEST || !tracked || now >= responseDeadline || sessionEndAt - now < 1100)
            return null
        val bounded = preset.copy(strength = preset.strength.coerceIn(0, limit.coerceIn(0, 200)), durationSeconds = 1)
        return bounded.takeIf { it.validFor(limit) }
    }
}
