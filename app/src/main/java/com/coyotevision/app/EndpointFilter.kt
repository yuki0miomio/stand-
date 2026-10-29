package com.coyotevision.app

import kotlin.math.exp
import kotlin.math.hypot

/** Stabilize wrist/ankle centers only; meaningful displacements still follow immediately. */
class EndpointFilter {
    private var previous: FullBody? = null
    private var lastTime = -1L

    fun reset() { previous = null; lastTime = -1 }
    fun seed(body: FullBody, now: Long) { previous = body; lastTime = now }

    fun apply(body: FullBody, shoulderSpan: Float, now: Long): FullBody {
        val before = previous
        if (before == null || now <= lastTime || now - lastTime > 400) {
            seed(body, now); return body
        }
        val alpha = 1f - exp(-(now - lastTime) / 100f)
        val filtered = FullBody(body.points.mapValues { (joint, point) ->
            if (joint !in endpoints) point else {
                val old = before[joint]
                val distance = hypot(point.x - old.x, point.y - old.y)
                when {
                    distance <= shoulderSpan * .025f -> point.copy(x = old.x, y = old.y)
                    distance >= shoulderSpan * .12f -> point
                    else -> point.copy(x = old.x + (point.x - old.x) * alpha,
                        y = old.y + (point.y - old.y) * alpha)
                }
            }
        })
        seed(filtered, now)
        return filtered
    }

    companion object {
        val endpoints = setOf(BodyJoint.LW, BodyJoint.RW, BodyJoint.LK, BodyJoint.RK, BodyJoint.LA, BodyJoint.RA)
    }
}
