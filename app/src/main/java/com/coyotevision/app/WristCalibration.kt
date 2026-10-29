package com.coyotevision.app

import kotlin.math.hypot

/** The final preparation hold depends on current wrists, with a fixed scale from positioning. */
class WristCalibration {
    companion object {
        const val DEFAULT_NEAR_PERCENT = 70
        fun valid(percent: Int) = percent in 5..150
    }
    data class Reading(val heldMs: Long, val good: Boolean, val restarted: Boolean, val distance: Float, val reason: String)
    private var held = 0L
    private var last = -1L
    private var lastGood = -1L
    private var previousGood = false
    private var anchor: Pair<BodyPoint, BodyPoint>? = null
    fun reset() { held = 0; last = -1; lastGood = -1; previousGood = false; anchor = null }

    fun update(now: Long, left: BodyPoint?, right: BodyPoint?, span: Float, shoulderY: Float,
        nearPercent: Int, returned: Boolean): Reading {
        val previousTime = last
        val gap = last >= 0 && now - last > 400
        last = now
        val reliable = listOf(left, right).all { it != null && it.x.isFinite() && it.y.isFinite() &&
            it.confidence.isFinite() && it.confidence >= .5f }
        val distance = if (reliable) hypot(left!!.x - right!!.x, left.y - right.y) else Float.NaN
        val validScale = span.isFinite() && span > 1 && shoulderY.isFinite() && valid(nearPercent)
        val close = validScale && distance <= span * nearPercent / 100f
        val lowered = reliable && validScale && left!!.y >= shoulderY + span * .35f && right!!.y >= shoulderY + span * .35f
        val good = reliable && close && lowered
        val moved = good && anchor?.let { (a, b) ->
            hypot(left!!.x - a.x, left.y - a.y) > span * .12f || hypot(right!!.x - b.x, right.y - b.y) > span * .12f
        } == true
        val restart = gap || moved || (!previousGood && lastGood >= 0 && now - lastGood > 300)
        if (restart) { held = 0; anchor = null; lastGood = -1; previousGood = false }
        if (good) {
            if (previousGood && previousTime >= 0 && now > previousTime) held += now - previousTime
            if (anchor == null) anchor = left!! to right!!
            lastGood = now
        }
        previousGood = good
        val reason = when {
            !validScale -> "等待定位时记录的肩宽参考"
            !reliable -> "双手腕暂不清晰，暂停本次手位记录"
            !returned -> "小公主先起身喵，恢复站姿后再收好双手"
            !lowered -> "双手放下喵，停在你选的腹前位置"
            !close -> "双手再靠近一点喵，或在设置中调大手腕靠近距离"
            moved -> "手位移动啦喵，停稳后重新保持 5 秒"
            gap -> "画面间隔过长，重新记录连续手位"
            else -> "双手靠近啦喵，稳住现在的手位"
        }
        return Reading(held, good, restart, distance, reason)
    }
}
