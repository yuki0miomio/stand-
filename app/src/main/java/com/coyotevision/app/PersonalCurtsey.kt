package com.coyotevision.app

import kotlin.math.*

data class PersonalPoseMatch(val reference: FullBody, val current: FullBody, val radius: Float,
    val offsets: Map<BodyJoint, Float>, val matched: Boolean, val reason: String)

/** A per-session pose taught in fixed preview coordinates, independent of elbow/finger visibility. */
class PersonalCurtsey(val reference: FullBody) {
    fun distinctFrom(standing: FullBody): Boolean {
        if (!reliable(reference) || !reliable(standing)) return false
        return hipDrop(reference, standing) >= minimumDrop(standing)
    }

    fun bendProgress(body: FullBody, standing: FullBody): Float {
        if (!reliable(body) || !reliable(standing) || !reliable(reference)) return Float.NaN
        val taughtDrop = hipDrop(reference, standing)
        if (taughtDrop < minimumDrop(standing)) return Float.NaN
        return hipDrop(body, standing) / taughtDrop
    }

    fun matches(body: FullBody, standing: FullBody, range: Float = DEFAULT_RANGE): Boolean =
        assess(body, standing, range).matched

    fun assess(body: FullBody, standing: FullBody, range: Float = DEFAULT_RANGE): PersonalPoseMatch =
        assessTarget(body, standing, reference, range, bending = true)

    fun assessStanding(body: FullBody, standing: FullBody, range: Float = DEFAULT_RANGE): PersonalPoseMatch =
        assessTarget(body, standing, standing, range, bending = false)

    private fun assessTarget(body: FullBody, standing: FullBody, target: FullBody,
        range: Float, bending: Boolean): PersonalPoseMatch {
        val scale = span(standing)
        val validRange = range.isFinite() && range in MIN_RANGE..MAX_RANGE
        val radius = if (validRange && scale.isFinite()) scale * range else 0f
        val offsets = stableJoints.associateWith { dist(body[it], target[it]) }
        val missing = stableJoints.firstOrNull {
            !offsets.getValue(it).isFinite() || !body[it].confidence.isFinite() ||
                !target[it].confidence.isFinite() || body[it].confidence < .5f || target[it].confidence < .5f
        }
        val outside = offsets.entries.filter { it.value > radius }.maxByOrNull { it.value }
        val progress = bendProgress(body, standing)
        val issue = when {
            !validRange || !scale.isFinite() || scale <= 1 -> "屈膝礼判定范围无效，请返回设置"
            missing != null -> "${missing.label}测量不足，等待清晰画面"
            !distinctFrom(standing) -> "屈膝礼与站姿基准差异不足，请重新校准"
            outside != null -> "${outside.key.label}偏离目标 ${DisplayFormat.one(outside.value)}，允许 ${DisplayFormat.one(radius)}"
            !progress.isFinite() -> "下蹲测量暂不稳定"
            bending && progress < .5f -> "小公主再稍微屈膝一点喵，接近本轮记录的下蹲姿势"
            !bending && progress >= .5f -> "小公主起身站好喵，回到本轮记录的站姿和手位"
            else -> null
        }
        return PersonalPoseMatch(target, body, radius, offsets, issue == null,
            issue ?: if (bending) "小公主的屈膝礼在记录范围内啦喵，稳住哦" else "小公主站回记录范围啦喵，教官看见了")
    }

    companion object {
        const val MIN_RANGE = .10f
        const val MAX_RANGE = .80f
        const val DEFAULT_RANGE = .30f
        val stableJoints = setOf(BodyJoint.LS, BodyJoint.RS, BodyJoint.LH, BodyJoint.RH, BodyJoint.LW, BodyJoint.RW)
        fun span(body: FullBody) = dist(body[BodyJoint.LS], body[BodyJoint.RS])
        fun canTeach(body: FullBody, standing: FullBody) = teachingIssue(body, standing) == null
        private fun reliable(body: FullBody) = stableJoints.all {
            body[it].x.isFinite() && body[it].y.isFinite() && body[it].confidence.isFinite() && body[it].confidence >= .5f
        }
        private fun hipDrop(body: FullBody, standing: FullBody) =
            midpoint(body[BodyJoint.LH], body[BodyJoint.RH]).y - midpoint(standing[BodyJoint.LH], standing[BodyJoint.RH]).y
        private fun minimumDrop(standing: FullBody): Float {
            val torso = dist(midpoint(standing[BodyJoint.LS], standing[BodyJoint.RS]), midpoint(standing[BodyJoint.LH], standing[BodyJoint.RH]))
            return max(span(standing) * .04f, torso * .05f)
        }

        /** Learning captures the user's hand position instead of imposing an ideal skirt rectangle. */
        fun teachingIssue(body: FullBody, standing: FullBody): String? {
            if (!reliable(body) || !reliable(standing))
                return "等待肩、髋和手腕清晰入镜；腿部点不参与行礼判定"
            val scale = span(standing)
            val hip = midpoint(body[BodyJoint.LH], body[BodyJoint.RH])
            val shoulder = midpoint(body[BodyJoint.LS], body[BodyJoint.RS])
            val originalHip = midpoint(standing[BodyJoint.LH], standing[BodyJoint.RH])
            val torso = dist(midpoint(standing[BodyJoint.LS], standing[BodyJoint.RS]), originalHip)
            if (!scale.isFinite() || !torso.isFinite() || scale <= 1 || torso <= 1)
                return "等待有效的站立位置参考"
            val bend = hip.y - originalHip.y >= minimumDrop(standing)
            val left = body[BodyJoint.LW]; val right = body[BodyJoint.RW]
            val handLowerBound = hip.y + torso * 2f
            val hands = dist(left, right) >= scale * .10f && listOf(left, right).all {
                abs(it.x - hip.x) <= scale * 2f &&
                    it.y in (shoulder.y + scale * .25f)..handLowerBound
            }
            return when {
                !bend || !PersonalCurtsey(body).distinctFrom(standing) -> "小公主再微微屈膝一点喵，让教官看清它和站立的区别"
                !hands -> "小公主，双臂放下来喵；双手自然分开提至裙摆位置，不用对准髋部"
                else -> null
            }
        }
        fun average(samples: List<FullBody>): FullBody = FullBody(BodyJoint.entries.mapNotNull { joint ->
            val valid = samples.mapNotNull { it.points[joint] }.filter { it.x.isFinite() && it.y.isFinite() && it.confidence > 0 }
            if (valid.isEmpty()) null else joint to BodyPoint(valid.map { it.x }.average().toFloat(), valid.map { it.y }.average().toFloat(), 1f)
        }.toMap())
        private fun midpoint(a: BodyPoint, b: BodyPoint) = BodyPoint((a.x + b.x) / 2, (a.y + b.y) / 2, 1f)
        private fun dist(a: BodyPoint, b: BodyPoint) = hypot(a.x - b.x, a.y - b.y)
    }
}
