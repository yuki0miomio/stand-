package com.coyotevision.app

/** A local finite output lease. This object has no device or network access. */
class VirtualOutput {
    data class Sample(val preset: EffectPreset, val frame: String, val remainingMs: Long)
    private var effect: EffectPreset? = null
    private var startedAt = 0L
    fun submit(preset: EffectPreset, limit: Int, now: Long): Boolean {
        if (!preset.validFor(limit)) return false
        effect = preset; startedAt = now; return true
    }
    fun clear() { effect = null }
    fun sample(now: Long): Sample? {
        val preset = effect ?: return null
        val elapsed = now - startedAt
        if (elapsed !in 0 until preset.durationSeconds * 1000L) { clear(); return null }
        val frames = preset.shape.frames
        val frame = frames[((preset.frameOffset.toLong() + elapsed / 100) % frames.size).toInt()]
        return Sample(preset, frame, preset.durationSeconds * 1000L - elapsed)
    }
}
