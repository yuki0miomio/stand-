package com.coyotevision.app

import kotlin.math.hypot

data class HandZone(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(point: BodyPoint) = point.x in left..right && point.y in top..bottom
}
data class JointTravel(val reference: BodyPoint, val current: BodyPoint,
    val displacement: Float, val recentPath: Float, val trail: List<BodyPoint>)
data class TrackingSnapshot(val frame: Long, val sampledAt: Long, val intervalMs: Long,
    val points: Map<BodyJoint, JointTravel>, val handZone: HandZone, val handTarget: BodyPoint,
    val targetRadius: Float, val handGap: Float, val kneeGap: Float, val ankleGap: Float,
    val handsTogether: Boolean, val legsTogether: Boolean, val handsInBox: Boolean, val handsAtTarget: Boolean,
    val fixed: Boolean = true) {
    fun displacement(joints: Set<BodyJoint>) = joints.maxOf { points.getValue(it).displacement }
    fun path(joints: Set<BodyJoint>) = joints.maxOf { points.getValue(it).recentPath }
}

/** Fixed preview coordinates; never subtract hip movement or update the calibration anchors. */
class ContinuousTracking(private val reference: FullBody, val handZone: HandZone,
    val handTarget: BodyPoint, val targetRadius: Float, private val span: Float,
    private val handDead: Float, private val legDead: Float) {
    private data class Step(val at: Long, val point: BodyPoint, val distance: Float)
    private val accepted = points.associateWith { reference[it] }.toMutableMap()
    private val history = points.associateWith { ArrayDeque<Step>() }
    private var lastTime = -1L
    private var frame = 0L

    fun breakContinuity() { lastTime = -1; history.values.forEach { it.clear() } }

    fun observe(body: FullBody, now: Long, handsTogether: Boolean, legsTogether: Boolean,
        handsInBox: Boolean, handsAtTarget: Boolean): TrackingSnapshot {
        val interval = if (lastTime >= 0) now - lastTime else 0
        val continuous = lastTime >= 0 && interval in 1..400
        if (!continuous) history.values.forEach { it.clear() }
        if (now != lastTime) frame++
        val values = points.associateWith { joint ->
            val current = body[joint]
            val previous = accepted.getValue(joint)
            val step = distance(current, previous)
            val dead = maxOf(span * .04f, if (joint in hands) handDead * .5f else legDead * .5f)
            val steps = history.getValue(joint)
            while (steps.firstOrNull()?.let { now - it.at >= 1000 } == true) steps.removeFirst()
            if (!continuous) accepted[joint] = current
            else if (step > dead) {
                val noiseRadius = if (joint in hands) handDead else legDead
                // Bounded calibration jitter must not accumulate into a fictitious long path.
                if (distance(current, reference[joint]) > noiseRadius || distance(previous, reference[joint]) > noiseRadius)
                    steps.addLast(Step(now, current, step))
                accepted[joint] = current
            }
            JointTravel(reference[joint], current, distance(reference[joint], current),
                steps.sumOf { it.distance.toDouble() }.toFloat(), steps.map { it.point } + current)
        }
        lastTime = now
        return TrackingSnapshot(frame, now, interval, values, handZone, handTarget, targetRadius,
            distance(body[BodyJoint.LW], body[BodyJoint.RW]), distance(body[BodyJoint.LK], body[BodyJoint.RK]),
            distance(body[BodyJoint.LA], body[BodyJoint.RA]), handsTogether, legsTogether, handsInBox, handsAtTarget)
    }

    companion object {
        val hands = setOf(BodyJoint.LW, BodyJoint.RW)
        val legs = setOf(BodyJoint.LK, BodyJoint.RK, BodyJoint.LA, BodyJoint.RA)
        val points = hands + legs
        fun distance(a: BodyPoint, b: BodyPoint) = hypot(a.x - b.x, a.y - b.y)
    }
}
