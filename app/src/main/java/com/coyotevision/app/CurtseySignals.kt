package com.coyotevision.app

enum class CurtseySignalKind { NONE, CUE_WAITING, CUE, REQUEST, SUCCESS, FAILURE, PAUSED, MOTION_RISE }

data class CurtseySignalFrame(val kind: CurtseySignalKind = CurtseySignalKind.NONE,
    val bright: Boolean = true, val progress: Float? = null)

/** Display-only state. All timing uses the existing shared logical game clock. */
class CurtseySignals {
    private var result = CurtseySignalKind.NONE
    private var resultAt = 0L
    private var resultUntil = 0L

    fun clear() { result = CurtseySignalKind.NONE; resultAt = 0; resultUntil = 0 }

    fun onEvent(event: GameEvent, now: Long, penaltyMs: Long = 0) {
        when (event) {
            GameEvent.SUCCESS -> {
                result = CurtseySignalKind.SUCCESS; resultAt = now; resultUntil = now + CurtseyGame.SUCCESS_RECOVERY_MS
            }
            GameEvent.PENALTY -> {
                result = CurtseySignalKind.FAILURE; resultAt = now
                resultUntil = now + penaltyMs.coerceIn(3000, 30_000)
            }
            GameEvent.CUE, GameEvent.PROMPT, GameEvent.CUE_FAILED,
            GameEvent.TRACKING_LOST, GameEvent.FINISHED -> clear()
        }
    }

    fun sample(now: Long, running: Boolean, paused: Boolean, phase: GamePhase?,
        cueUntil: Long = 0, cueDuration: Long = 3000, responseDeadline: Long = 0,
        responseDuration: Long = 1): CurtseySignalFrame {
        if (paused) return CurtseySignalFrame(CurtseySignalKind.PAUSED)
        if (!running || phase in setOf(GamePhase.FINISHED, GamePhase.STOPPED)) return CurtseySignalFrame()
        if (phase == GamePhase.CUE) {
            if (cueUntil <= 0) return CurtseySignalFrame(CurtseySignalKind.CUE_WAITING)
            val elapsed = (now - (cueUntil - cueDuration)).coerceAtLeast(0)
            return CurtseySignalFrame(CurtseySignalKind.CUE, bright = now < cueUntil && elapsed % 1000 < 450)
        }
        if (phase == GamePhase.REQUEST) {
            val remaining = ((responseDeadline - now).coerceAtLeast(0).toFloat() /
                responseDuration.coerceAtLeast(1)).coerceIn(0f, 1f)
            return CurtseySignalFrame(CurtseySignalKind.REQUEST, progress = remaining)
        }
        if (now >= resultAt && now < resultUntil) {
            return CurtseySignalFrame(result, bright = result != CurtseySignalKind.FAILURE ||
                (now - resultAt) % 1000 < 600)
        }
        return CurtseySignalFrame()
    }
}
