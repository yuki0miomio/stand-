package com.coyotevision.app

/** Brief/moving limb occlusion keeps the fixed calibration; never supplies old limb coordinates. */
class LimbRecovery {
    var paused = false; private set
    private var goodSince = -1L
    private var previousGood = -1L
    fun suspend() { paused = true; goodSince = -1; previousGood = -1 }
    fun canResume(now: Long, ready: Boolean): Boolean {
        if (!paused) return false
        if (!ready) { goodSince = -1; previousGood = -1; return false }
        if (goodSince < 0 || now - previousGood > 500) goodSince = now
        previousGood = now
        return now - goodSince >= 200
    }
    fun reset() { paused = false; goodSince = -1; previousGood = -1 }
}
