package com.coyotevision.app

import kotlin.random.Random

data class GameSettings(val durationMs: Long = 180_000, val minGapMs: Long = 15_000,
    val maxGapMs: Long = 30_000, val responseMs: Long = 8_000,
    val cueMs: Long = 3000, val penaltyMs: Long = 3000, val curtseyHoldMs: Long = 500) {
    fun valid() = durationMs in 60_000..SessionClock.MAX_DURATION_MS && minGapMs in 5_000..300_000 &&
        maxGapMs in minGapMs..300_000 && responseMs in 3_000..30_000 &&
        cueMs in setOf(0L, 3000L) && penaltyMs in 1000..30_000 &&
        curtseyHoldMs in 500..10_000 && curtseyHoldMs + 500 <= responseMs
}
enum class GamePhase { WAITING, CUE, REQUEST, RECOVERY, FINISHED, STOPPED }
enum class GameEvent { CUE, CUE_FAILED, PROMPT, SUCCESS, PENALTY, FINISHED, TRACKING_LOST }
enum class CurtseyStage { STAND_FIRST, BEND_AND_LIFT }

/** Wall-clock changes cannot alter a session; callers supply monotonic milliseconds. */
class CurtseyGame(val settings: GameSettings, start: Long, private val random: Random = Random.Default,
    val endAt: Long = start + settings.durationMs) {
    companion object { const val SUCCESS_RECOVERY_MS = 2000L }
    var phase = GamePhase.WAITING
        private set
    var stage = CurtseyStage.STAND_FIRST
        private set
    var responseDeadline = 0L
        private set
    var cueUntil = 0L
        private set
    private var cueRequestedAt = 0L
    val cueStarted get() = cueUntil > 0L
    var successes = 0
        private set
    var misses = 0
        private set
    var holdProgress = 0f
        private set
    private var nextPrompt = Long.MAX_VALUE
    private var streakStart = -1L
    private var lastFrame = start
    private var lastTracked = start
    var recoveryUntil = 0L; private set
    private var successfulRecovery = false
    val successRecoveryActive get() = phase == GamePhase.RECOVERY && successfulRecovery
    private var observedAt = -1L
    private var cueStandSince = -1L
    private var cueStandConfirmed = false
    val motionAllowed get() = phase == GamePhase.WAITING

    init { require(settings.valid()); schedule(start) }

    fun stop() { phase = GamePhase.STOPPED; streakStart = -1; holdProgress = 0f }

    /** Cancel interrupted work without a miss or replay. Keep the shared deadline and scores. */
    fun resumeAfterCalibration(now: Long) {
        if (now >= endAt || phase == GamePhase.FINISHED) return
        phase = GamePhase.WAITING; stage = CurtseyStage.STAND_FIRST
        responseDeadline = 0; cueUntil = 0; cueRequestedAt = 0; recoveryUntil = 0
        streakStart = -1; holdProgress = 0f; lastFrame = now; lastTracked = now; observedAt = -1
        cueStandSince = -1; cueStandConfirmed = false
        successfulRecovery = false
        schedule(now)
    }

    /** Begin the cue clock only after the caller has submitted the full finite cue preset. */
    fun startCue(now: Long): Boolean {
        if (phase != GamePhase.CUE || cueStarted || now < cueRequestedAt || now >= endAt) return false
        cueUntil = now + settings.cueMs
        return true
    }

    fun update(now: Long, body: StandingMetrics, training: Boolean = false): GameEvent? {
        if (phase in setOf(GamePhase.FINISHED, GamePhase.STOPPED) || now <= observedAt) return null
        observedAt = now
        // The hard deadline wins over task timeout and punishment.
        if (now >= endAt) { phase = GamePhase.FINISHED; return GameEvent.FINISHED }
        val frameGap = now - lastFrame > 400
        lastFrame = now
        if (body.tracked) lastTracked = now
        if (!body.tracked) {
            streakStart = -1; holdProgress = 0f
            if (phase == GamePhase.CUE || now - lastTracked >= 600 || (phase == GamePhase.REQUEST && now >= responseDeadline)) {
                phase = GamePhase.STOPPED; return GameEvent.TRACKING_LOST
            }
            return null
        }
        if (frameGap) { streakStart = -1; holdProgress = 0f }
        when (phase) {
            GamePhase.WAITING -> if (training) { schedule(now) } else if (now >= nextPrompt) {
                if (settings.cueMs > 0) {
                    phase = GamePhase.CUE; cueUntil = 0; cueRequestedAt = now
                    cueStandSince = -1; cueStandConfirmed = false
                    responseDeadline = 0; streakStart = -1; holdProgress = 0f
                    return GameEvent.CUE
                }
                phase = GamePhase.REQUEST
                stage = CurtseyStage.STAND_FIRST
                responseDeadline = now + settings.responseMs
                streakStart = -1; holdProgress = 0f
                return GameEvent.PROMPT
            }
            GamePhase.CUE -> {
                // Standing during the cue already establishes the first step. Do not force a second stand after blue appears.
                if (frameGap || !body.measurementReady || !body.curtseyNeutral) cueStandSince = -1
                else {
                    if (cueStandSince < 0) cueStandSince = now
                    if (now - cueStandSince >= 300) cueStandConfirmed = true
                }
                if (!cueStarted && now - cueRequestedAt >= 2000) {
                    phase = GamePhase.STOPPED; return GameEvent.CUE_FAILED
                }
                if (cueStarted && now >= cueUntil) {
                    phase = GamePhase.REQUEST
                    stage = if (cueStandConfirmed) CurtseyStage.BEND_AND_LIFT else CurtseyStage.STAND_FIRST
                    responseDeadline = now + settings.responseMs; streakStart = -1; holdProgress = 0f
                    return GameEvent.PROMPT
                }
            }
            GamePhase.REQUEST -> {
                if (now >= responseDeadline) {
                    misses++
                    phase = GamePhase.RECOVERY
                    successfulRecovery = false
                    recoveryUntil = now + settings.penaltyMs + 500
                    streakStart = -1
                    return GameEvent.PENALTY
                }
                val matches = when (stage) {
                    CurtseyStage.STAND_FIRST -> body.measurementReady && body.curtseyNeutral
                    CurtseyStage.BEND_AND_LIFT -> body.measurementReady && body.curtsey
                }
                if (!matches) {
                    streakStart = -1
                    if (stage == CurtseyStage.BEND_AND_LIFT) holdProgress = 0f
                }
                else {
                    if (streakStart < 0) streakStart = now
                    val hold = when (stage) {
                        CurtseyStage.STAND_FIRST -> 300L
                        CurtseyStage.BEND_AND_LIFT -> settings.curtseyHoldMs
                    }
                    if (stage == CurtseyStage.BEND_AND_LIFT) holdProgress = ((now - streakStart).toFloat() / hold).coerceIn(0f, 1f)
                    if (now - streakStart >= hold) {
                        streakStart = -1
                        when (stage) {
                            CurtseyStage.STAND_FIRST -> stage = CurtseyStage.BEND_AND_LIFT
                            CurtseyStage.BEND_AND_LIFT -> {
                                successes++
                                phase = GamePhase.RECOVERY
                                successfulRecovery = true
                                recoveryUntil = now + SUCCESS_RECOVERY_MS
                                return GameEvent.SUCCESS
                            }
                        }
                    }
                }
            }
            GamePhase.RECOVERY -> {
                // Recovery is finite; holding a bent pose must not exempt the rest of the game.
                if (now >= recoveryUntil) {
                    phase = GamePhase.WAITING; successfulRecovery = false; streakStart = -1; schedule(now)
                }
            }
            else -> Unit
        }
        return null
    }

    private fun schedule(now: Long) {
        val latest = endAt - settings.cueMs - settings.responseMs - 4000
        val high = minOf(settings.maxGapMs, latest - now)
        nextPrompt = if (high < settings.minGapMs) Long.MAX_VALUE
            else now + random.nextLong(settings.minGapMs, high + 1)
    }
}
