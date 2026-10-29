package com.coyotevision.app

import kotlin.math.hypot

/** Display-only geometry in PreviewView coordinates; never feeds the game or calibration. */
internal object HeadCoverGeometry {
    data class Point(val x: Float, val y: Float, val confidence: Float = 1f) {
        fun reliable() = x.isFinite() && y.isFinite() && confidence.isFinite() && confidence >= .5f
    }
    data class Circle(val x: Float, val y: Float, val radius: Float)

    fun locate(points: Map<Int, Point>): Circle? {
        val left = points[11]?.takeIf { it.reliable() }
        val right = points[12]?.takeIf { it.reliable() }
        val shoulderSpan = if (left != null && right != null)
            hypot(left.x - right.x, left.y - right.y).takeIf { it.isFinite() && it >= 8f } else null
        val face = (0..10).mapNotNull { points[it]?.takeIf { point -> point.reliable() } }
        if (face.size >= 2) {
            val minX = face.minOf { it.x }; val maxX = face.maxOf { it.x }
            val minY = face.minOf { it.y }; val maxY = face.maxOf { it.y }
            val span = maxOf(maxX - minX, maxY - minY)
            if (span.isFinite() && span >= 8f) {
                val x = (minX + maxX) / 2f
                // Center on eyes/nose/mouth rather than extending the center toward the forehead.
                val y = (minY + maxY) / 2f
                val radius = maxOf(span * .9f, (shoulderSpan ?: 0f) * .48f,
                    face.maxOf { hypot(it.x - x, it.y - y) } * 1.3f)
                return Circle(x, y, radius).takeIf { it.x.isFinite() && it.y.isFinite() && it.radius.isFinite() }
            }
        }
        // Side views / hidden facial points: estimate above the shoulders without another detector.
        if (shoulderSpan == null || left == null || right == null) return null
        val dx = right.x - left.x; val dy = right.y - left.y
        val upwardSign = if (dx >= 0f) 1f else -1f
        return Circle((left.x + right.x) / 2f + dy * upwardSign * .6f,
            (left.y + right.y) / 2f - dx * upwardSign * .6f, shoulderSpan * .72f)
            .takeIf { it.x.isFinite() && it.y.isFinite() && it.radius.isFinite() }
    }
}

/** Brief landmark flicker keeps the last circle; stale positions cannot survive a new camera. */
internal class HeadCoverTracking {
    companion object { const val HOLD_MS = 600L }
    private var circle: HeadCoverGeometry.Circle? = null
    private var updatedAt = 0L
    fun update(points: Map<Int, HeadCoverGeometry.Point>, now: Long): HeadCoverGeometry.Circle? {
        HeadCoverGeometry.locate(points)?.let { circle = it; updatedAt = now }
        return current(now)
    }
    fun current(now: Long): HeadCoverGeometry.Circle? {
        if (now - updatedAt !in 0..HOLD_MS) clear()
        return circle
    }
    fun expiresIn(now: Long): Long = if (current(now) != null) HOLD_MS - (now - updatedAt) + 1 else 0L
    fun clear() { circle = null; updatedAt = 0L }
}
