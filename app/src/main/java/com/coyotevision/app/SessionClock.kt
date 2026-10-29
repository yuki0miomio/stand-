package com.coyotevision.app

/** One active-play timeline shared by all tasks. Recalibration consumes no play time. */
class SessionClock {
    companion object { const val MAX_DURATION_MS = 5_400_000L }
    private var duration = 0L
    private var accumulated = 0L
    private var resumedAt: Long? = null
    val started get() = duration > 0
    val running get() = resumedAt != null

    fun start(now: Long, durationMs: Long) {
        require(durationMs in 1..SessionClock.MAX_DURATION_MS)
        duration = durationMs; accumulated = 0; resumedAt = now
    }
    fun elapsed(now: Long): Long = (accumulated + (resumedAt?.let { (now - it).coerceAtLeast(0) } ?: 0))
        .coerceIn(0, duration)
    fun remaining(now: Long) = duration - elapsed(now)
    fun pause(now: Long) { accumulated = elapsed(now); resumedAt = null }
    fun resume(now: Long): Boolean {
        if (!started || running || remaining(now) == 0L) return false
        resumedAt = now; return true
    }
    fun reset() { duration = 0; accumulated = 0; resumedAt = null }
}
