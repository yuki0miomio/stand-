package com.coyotevision.app

import kotlin.math.hypot
import kotlin.math.abs

enum class PreparationPhase { POSITIONING, CAPTURE_CURTSEY, RETURN_TO_NEUTRAL, READY }
enum class PreparationEvent { POSITION_CONFIRMED, CURTSEY_CONFIRMED, CURTSEY_RETRY, POSITION_RESET, START_READY }

/** No session clock or output can start until all three ordered confirmation stages finish. */
class SessionPreparation {
    companion object {
        const val CURTSEY_OCCLUSION_GRACE_MS = 3000L
        const val CURTSEY_PERSON_LOST_MS = 5000L
    }
    var phase = PreparationPhase.POSITIONING; private set
    var progress = 0f; private set
    var captureMessage = "小公主，微微屈膝喵；双手放到你选的提裙位置"; private set
    private var enabled = false
    private var since = -1L
    private var last = -1L
    private var lastPositionGood = -1L
    private var lastPersonGood = -1L
    private var floorChangeSince = -1L
    private var poseChangeSince = -1L
    private var curtseyHeldMs = 0L
    private var previousCurtseyGood = false
    private var lastCurtseyGood = -1L
    private var anchor: Anchor? = null
    var positionReference: FullBody? = null; private set
    var curtseyReference: FullBody? = null; private set
    private val curtseySamples = mutableListOf<Pair<Long, FullBody>>()

    private data class Anchor(val hipX: Float, val hipY: Float, val left: BodyPoint, val right: BodyPoint,
        val span: Float, val torso: Float)

    fun begin() {
        phase = PreparationPhase.POSITIONING; progress = 0f; enabled = true
        since = -1; last = -1; lastPositionGood = -1; lastPersonGood = -1; floorChangeSince = -1; anchor = null
        positionReference = null; curtseyReference = null; curtseySamples.clear()
        clearCurtseyHold()
        captureMessage = "小公主，微微屈膝喵；双手放到你选的提裙位置"
    }
    fun cancel() { begin(); enabled = false }

    fun update(now: Long, metrics: StandingMetrics, body: FullBody?): PreparationEvent? {
        if (!enabled || phase == PreparationPhase.READY || now <= last) return null
        val previousTime = last
        val gap = last >= 0 && now - last > 400
        last = now
        val position = if (metrics.tracked && metrics.measurementReady) body?.let { reference(it) } else null
        if (phase == PreparationPhase.POSITIONING) {
            if (position == null || !metrics.tPose) { clearHold(); return null }
            if (gap || anchor?.let { !near(it, position) } == true) clearHold()
            if (since < 0) { since = now; anchor = position }
            progress = ((now - since) / 2000f).coerceIn(0f, 1f)
            if (progress >= 1f) {
                phase = PreparationPhase.CAPTURE_CURTSEY
                anchor = position
                positionReference = body
                lastPositionGood = now
                lastPersonGood = now
                clearCurtseyHold()
                return PreparationEvent.POSITION_CONFIRMED
            }
            return null
        }
        val capturing = phase == PreparationPhase.CAPTURE_CURTSEY
        if (metrics.tracked) lastPersonGood = now
        if (position == null) {
            previousCurtseyGood = false
            poseChangeSince = -1; floorChangeSince = -1
            if (capturing) {
                if (lastCurtseyGood >= 0 && now - lastCurtseyGood > CURTSEY_OCCLUSION_GRACE_MS) clearCurtseyHold()
                captureMessage = "局部暂时看不清啦喵，保留位置、暂停采集；看清后再稳住 · ${metrics.trackingMessage}"
                if (!metrics.tracked && now - lastPersonGood >= CURTSEY_PERSON_LOST_MS) {
                    begin(); return PreparationEvent.POSITION_RESET
                }
                return null
            }
            captureMessage = metrics.trackingMessage
            if (now - lastPositionGood > 600) clearCurtseyHold()
            if (!metrics.tracked && now - lastPositionGood >= 1000) {
                begin(); return PreparationEvent.POSITION_RESET
            }
            return null
        }
        if (anchor?.let { !nearFloor(it, position, ignoreLegs = capturing || phase == PreparationPhase.RETURN_TO_NEUTRAL) } != false) {
            if (capturing || phase == PreparationPhase.RETURN_TO_NEUTRAL) {
                previousCurtseyGood = false
                if (floorChangeSince < 0) floorChangeSince = now
                captureMessage = "位置测量有点不稳喵，先停稳，教官再确认一次"
                if (now - floorChangeSince < 600) return null
            }
            begin()
            return PreparationEvent.POSITION_RESET
        }
        floorChangeSince = -1
        lastPositionGood = now
        if (capturing) {
            // A missing interval pauses the hold; it never counts as observed, stable time.
            if (gap) previousCurtseyGood = false
            if (lastCurtseyGood >= 0 && now - lastCurtseyGood > CURTSEY_OCCLUSION_GRACE_MS) clearCurtseyHold()
            val standing = positionReference
            if (body == null || standing == null) return null
            val scale = PersonalCurtsey.span(standing)
            val issue = PersonalCurtsey.teachingIssue(body, standing)
            val moved = curtseySamples.firstOrNull()?.second?.let { first ->
                    PersonalCurtsey.stableJoints.any { joint ->
                        val tolerance = if (joint in ContinuousTracking.hands || joint == BodyJoint.LA || joint == BodyJoint.RA) .20f else .12f
                        hypot(body[joint].x - first[joint].x, body[joint].y - first[joint].y) > scale * tolerance
                    }
                } == true
            if (issue != null) {
                previousCurtseyGood = false
                poseChangeSince = -1
                if (lastCurtseyGood >= 0 && now - lastCurtseyGood > 300) clearCurtseyHold()
                captureMessage = issue
                return null
            }
            if (moved) {
                previousCurtseyGood = false
                if (poseChangeSince < 0) poseChangeSince = now
                if (now - poseChangeSince >= 300) clearCurtseyHold()
                captureMessage = "小公主慢慢调整喵；停稳后，以最后的屈膝礼姿势重新记录"
                return null
            }
            poseChangeSince = -1
            // Only fresh, consecutive valid intervals count; a brief wobble pauses instead of erasing progress.
            if (previousCurtseyGood && previousTime >= 0 && !gap) curtseyHeldMs += now - previousTime
            previousCurtseyGood = true
            lastCurtseyGood = now
            curtseySamples += now to body
            progress = (curtseyHeldMs / 3000f).coerceIn(0f, 1f)
            captureMessage = "教官在记小公主的屈膝礼喵，稳住现在的手位和下蹲姿势"
            if (progress >= 1 && curtseySamples.size >= 8) {
                // The final stable second defines the target, rather than averaging the descent.
                val finalPose = PersonalCurtsey.average(curtseySamples.filter { now - it.first <= 1000 }.map { it.second })
                curtseyReference = FullBody(finalPose.points.filterKeys { it in PersonalCurtsey.stableJoints })
                phase = PreparationPhase.RETURN_TO_NEUTRAL
                clearCurtseyHold()
                return PreparationEvent.CURTSEY_CONFIRMED
            }
            return null
        }
        if (metrics.calibrated && metrics.neutral) {
            if (!metrics.curtseyCalibrated) {
                curtseyReference = null; phase = PreparationPhase.CAPTURE_CURTSEY; clearCurtseyHold()
                return PreparationEvent.CURTSEY_RETRY
            }
            phase = PreparationPhase.READY
            return PreparationEvent.START_READY
        }
        return null
    }

    private fun clearHold() { since = -1; anchor = null; progress = 0f }
    private fun clearCurtseyHold() {
        since = -1; progress = 0f; curtseySamples.clear()
        curtseyHeldMs = 0; previousCurtseyGood = false; lastCurtseyGood = -1; poseChangeSince = -1
    }
    private fun reference(body: FullBody): Anchor? = runCatching {
        val ls = body[BodyJoint.LS]; val rs = body[BodyJoint.RS]
        val lh = body[BodyJoint.LH]; val rh = body[BodyJoint.RH]
        val hipX = (lh.x + rh.x) / 2; val hipY = (lh.y + rh.y) / 2
        val span = hypot(ls.x - rs.x, ls.y - rs.y)
        val torso = hypot((ls.x + rs.x) / 2 - hipX, (ls.y + rs.y) / 2 - hipY)
        require(span.isFinite() && torso.isFinite() && span > 1 && torso > 1)
        Anchor(hipX, hipY, body[BodyJoint.LA], body[BodyJoint.RA], span, torso)
    }.getOrNull()

    private fun near(a: Anchor, b: Anchor): Boolean =
        b.torso / a.torso in .85f..1.15f &&
            hypot(a.hipX - b.hipX, a.hipY - b.hipY) <= a.span * .15f &&
            hypot(a.left.x - b.left.x, a.left.y - b.left.y) <= a.span * .15f &&
            hypot(a.right.x - b.right.x, a.right.y - b.right.y) <= a.span * .15f

    // Vertical hip travel is expected while bowing and standing back up. Keep the floor position instead.
    private fun nearFloor(a: Anchor, b: Anchor, ignoreLegs: Boolean = false): Boolean =
        b.torso / a.torso in .55f..1.65f && abs(a.hipX - b.hipX) <= a.span * .35f &&
            (ignoreLegs || hypot((a.left.x + a.right.x - b.left.x - b.right.x) / 2,
                (a.left.y + a.right.y - b.left.y - b.right.y) / 2) <= a.span * .40f)
}
