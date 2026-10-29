package com.coyotevision.app

import kotlin.math.*

enum class BodyJoint(val id: Int) {
    LS(11), RS(12), LE(13), RE(14), LW(15), RW(16),
    LH(23), RH(24), LK(25), RK(26), LA(27), RA(28);
    val label get() = when (this) {
        LS -> "左肩"; RS -> "右肩"; LE -> "左肘"; RE -> "右肘"; LW -> "左腕"; RW -> "右腕"
        LH -> "左髋"; RH -> "右髋"; LK -> "左膝"; RK -> "右膝"; LA -> "左踝"; RA -> "右踝"
    }
}
data class FullBody(val points: Map<BodyJoint, BodyPoint>) {
    operator fun get(joint: BodyJoint) = points[joint] ?: BodyPoint(Float.NaN, Float.NaN, 0f)
}
data class StandingMetrics(
    val tracked: Boolean = false, val standing: Boolean = false,
    val curtsey: Boolean = false, val amplitude: Float = 0f,
    val score: Float = 0f, val calibrated: Boolean = false,
    val calibrationProgress: Float = 0f,
    val hands: Float = 0f, val feet: Float = 0f, val outside: Boolean = false,
    val neutral: Boolean = false, val tPose: Boolean = false,
    val tracking: TrackingSnapshot? = null, val trackingMessage: String = "教官在等小公主全身入镜喵",
    val legsTogether: Boolean = false, val handsTogether: Boolean = false, val handsInReference: Boolean = false,
    val measurementReady: Boolean = tracked,
    val curtseyCalibrated: Boolean = false,
    val curtseyMatch: PersonalPoseMatch? = null,
    val curtseyReturnMatch: PersonalPoseMatch? = null,
    val handMapping: MotionMappingReading? = null,
    val footMapping: MotionMappingReading? = null,
    val curtseyCore: Boolean = false,
    val torsoScore: Float = 0f,
    val torsoMapping: MotionMappingReading? = null,
) {
    val curtseyNeutral get() = curtseyReturnMatch?.matched ?: neutral
}

/** Geometry uses upright preview pixels. It cannot verify a physical grip on fabric. */
class StandingTracker {
    companion object { const val CALIBRATION_HOLD_MS = 5000L }
    private var baseline: FullBody? = null
    private var curtseyTemplate: PersonalCurtsey? = null
    private var positionedStanding: FullBody? = null
    private var calibrationHeldMs = 0L
    private val calibration = mutableListOf<FullBody>()
    private val calibrationTimes = mutableListOf<Long>()
    private val wristCalibration = WristCalibration()
    private var calibrationAnchor: FullBody? = null
    var wristNearPercent = WristCalibration.DEFAULT_NEAR_PERCENT
    private var calibrating = false
    private var lastTime = -1L
    private var smoothed = 0f
    private var noiseFloor = .04f
    private var handNoiseFloor = .10f
    private var footNoiseFloor = .07f
    private val endpointFilter = EndpointFilter()
    private var continuous: ContinuousTracking? = null
    private var observations = 0L
    var sensitivity = 1f
    var zoneRadius = .5f // Shoulder widths, relative to the calibrated hip and ankle midpoints.
    var curtseyRange = PersonalCurtsey.DEFAULT_RANGE
    var motionMapping = MotionMappingSettings()
    val calibrated get() = baseline != null

    fun reset() {
        baseline = null; clearCalibration(); wristCalibration.reset(); calibrationAnchor = null
        curtseyTemplate = null; positionedStanding = null
        calibrating = false; lastTime = -1L; smoothed = 0f; noiseFloor = .04f
        handNoiseFloor = .10f; footNoiseFloor = .07f; endpointFilter.reset()
        continuous = null; observations = 0
    }
    fun beginCalibration(curtseyReference: FullBody? = null, positionReference: FullBody? = null) {
        reset(); calibrating = true
        curtseyTemplate = curtseyReference?.let { PersonalCurtsey(it) }
        positionedStanding = positionReference; calibrationAnchor = positionReference
    }
    val curtseyCalibrated get() = baseline?.let { curtseyTemplate?.distinctFrom(it) } == true

    fun observe(input: FullBody?, width: Int, height: Int, now: Long, curtseyCore: Boolean = false): StandingMetrics {
        observations++
        if (calibrating) return observeRestCalibration(input, width, height, now)
        val previousTime = lastTime
        val gap = previousTime >= 0 && now - previousTime > 400
        lastTime = now
        val torsoPoints = setOf(BodyJoint.LS, BodyJoint.RS, BodyJoint.LH, BodyJoint.RH)
        fun invalid(joint: BodyJoint, threshold: Float): Boolean {
                val p = input?.points?.get(joint)
                return p == null || !p.x.isFinite() || !p.y.isFinite() || !p.confidence.isFinite() ||
                    p.confidence < threshold || p.x !in 0f..width.toFloat() || p.y !in 0f..height.toFloat()
        }
        val missing = torsoPoints.firstOrNull { invalid(it, .5f) }
        if (input == null || width <= 0 || height <= 0 || missing != null) {
            clearCalibration(); smoothed = 0f
            endpointFilter.reset(); continuous?.breakContinuity()
            return StandingMetrics(calibrated = calibrated, trackingMessage = if (input == null) "还没看到小公主喵，请全身回到镜头里" else "关键点 ${missing?.label ?: "画面尺寸"} 缺失、低置信度或出界")
        }
        val requiredLimbs = if (curtseyCore) ContinuousTracking.hands else ContinuousTracking.points
        val limb = requiredLimbs.firstOrNull { invalid(it, if (it in ContinuousTracking.legs) .35f else .5f) }
        if (limb != null) {
            clearCalibration(); smoothed = 0f
            endpointFilter.reset(); continuous?.breakContinuity()
            return StandingMetrics(tracked = true, calibrated = calibrated, measurementReady = false,
                trackingMessage = "人物仍在画面；${limb.label}暂不清晰，保留原校准，等待测量恢复")
        }
        if (curtseyCore) {
            // Do not use missing legs or their previous coordinates to judge the current bow.
            endpointFilter.reset(); continuous?.breakContinuity()
            val match = baseline?.let { curtseyTemplate?.assess(input, it, curtseyRange) }
            val returned = baseline?.let { curtseyTemplate?.assessStanding(input, it, curtseyRange) }
            return StandingMetrics(tracked = true, calibrated = calibrated, measurementReady = true,
                curtsey = match?.matched == true, neutral = returned?.matched == true,
                curtseyCalibrated = curtseyCalibrated, curtseyMatch = match, curtseyReturnMatch = returned,
                curtseyCore = true, trackingMessage = "肩、髋、双手测量清晰 · 腿部点不参与本次行礼保持判定")
        }
        // Validate current raw evidence first. Filtering never hides missing/out-of-frame joints.
        val inputSpan = distance(input[BodyJoint.LS], input[BodyJoint.RS])
        val body = if (calibrated && !calibrating) endpointFilter.apply(input, inputSpan, now) else input
        val span = distance(body[BodyJoint.LS], body[BodyJoint.RS])
        val shoulder = midpoint(body[BodyJoint.LS], body[BodyJoint.RS])
        val hip = midpoint(body[BodyJoint.LH], body[BodyJoint.RH])
        val ankle = midpoint(body[BodyJoint.LA], body[BodyJoint.RA])
        val torso = distance(shoulder, hip)
        val leftKnee = angle(body[BodyJoint.LH], body[BodyJoint.LK], body[BodyJoint.LA])
        val rightKnee = angle(body[BodyJoint.RH], body[BodyJoint.RK], body[BodyJoint.RA])
        val upright = span >= width * .075f && torso >= span * .7f &&
            abs(shoulder.y - body[BodyJoint.LS].y) < span * .25f &&
            abs(shoulder.x - hip.x) < span * .5f && shoulder.y < hip.y &&
            body[BodyJoint.LH].y < body[BodyJoint.LK].y && body[BodyJoint.RH].y < body[BodyJoint.RK].y &&
            body[BodyJoint.LK].y < body[BodyJoint.LA].y && body[BodyJoint.RK].y < body[BodyJoint.RA].y
        if (span <= 1f || torso <= 1f || !leftKnee.isFinite() || !rightKnee.isFinite()) {
            clearCalibration(); smoothed = 0f
            endpointFilter.reset()
            continuous?.breakContinuity()
            return StandingMetrics(tracked = true, calibrated = calibrated, measurementReady = false,
                trackingMessage = "人物仍在画面；腿部关节点重合或几何暂不稳定，保留原校准")
        }
        var standing = upright && leftKnee >= 150f && rightKnee >= 150f
        // The user teaches the resting hand position; no estimated navel/abdomen target is used.
        val handsLowered = ContinuousTracking.hands.all { body[it].y >= shoulder.y + span * .35f }
        val referenceHandGap = baseline?.let { distance(it[BodyJoint.LW], it[BodyJoint.RW]) }
        val together = referenceHandGap?.let { distance(body[BodyJoint.LW], body[BodyJoint.RW]) <= it + span * .15f }
            ?: handsLowered
        val legsTogether = distance(body[BodyJoint.LA], body[BodyJoint.RA]) <= span * .45f &&
            distance(body[BodyJoint.LK], body[BodyJoint.RK]) <= span * .55f
        val handCenter = midpoint(body[BodyJoint.LW], body[BodyJoint.RW])
        val currentBox = handZone(body, span)
        val guide = TrackingSnapshot(observations, now, if (previousTime < 0) 0 else now - previousTime,
            ContinuousTracking.points.associateWith { JointTravel(body[it], body[it], 0f, 0f, emptyList()) },
            currentBox, handCenter, span * .12f,
            distance(body[BodyJoint.LW], body[BodyJoint.RW]), distance(body[BodyJoint.LK], body[BodyJoint.RK]),
            distance(body[BodyJoint.LA], body[BodyJoint.RA]), together, legsTogether, handsLowered, handsLowered, fixed = false)
        // Wrist evidence is already validated above. Elbow visibility/angle is not a start requirement.
        val wristsAtShoulders = abs(body[BodyJoint.LW].y - body[BodyJoint.LS].y) <= span * .45f &&
            abs(body[BodyJoint.RW].y - body[BodyJoint.RS].y) <= span * .45f
        val armsOpen = abs(body[BodyJoint.LW].x - body[BodyJoint.RW].x) >= span * 1.8f &&
            (body[BodyJoint.LW].x - body[BodyJoint.LS].x) * (body[BodyJoint.LS].x - body[BodyJoint.RS].x) > 0 &&
            (body[BodyJoint.RW].x - body[BodyJoint.RS].x) * (body[BodyJoint.RS].x - body[BodyJoint.LS].x) > 0
        val tPose = standing && wristsAtShoulders && armsOpen
        val positioningMessage = when {
            !standing -> "小公主先站直喵，让肩、髋、膝和脚踝都进到镜头里"
            !wristsAtShoulders -> "小公主，把两只手腕抬到肩膀附近喵；手肘自然弯一点也可以"
            !armsOpen -> "双臂往两侧展开喵，两只手都要伸到肩膀外侧"
            else -> "小公主平举的姿势对啦喵，稳住 2 秒确认位置"
        }
        val reference = baseline ?: return StandingMetrics(true, standing,
            neutral = standing && handsLowered && legsTogether, tPose = tPose, tracking = guide, trackingMessage = positioningMessage,
            legsTogether = legsTogether, handsTogether = together, handsInReference = false)
        val baseSpan = distance(reference[BodyJoint.LS], reference[BodyJoint.RS])
        val baseHip = midpoint(reference[BodyJoint.LH], reference[BodyJoint.RH])
        val baseAnkle = midpoint(reference[BodyJoint.LA], reference[BodyJoint.RA])
        val baseTorso = distance(midpoint(reference[BodyJoint.LS], reference[BodyJoint.RS]), baseHip)
        // A changed posture/shoulder width is movement evidence, not disappearance.
        val hipDrop = (hip.y - baseHip.y) / baseTorso
        standing = standing && hipDrop < .08f
        val feetStill = listOf(BodyJoint.LA, BodyJoint.RA).all { distance(body[it], reference[it]) < baseSpan * .20f }
        val curtseyMatch = curtseyTemplate?.assess(body, reference, curtseyRange)
        val curtseyReturn = curtseyTemplate?.assessStanding(body, reference, curtseyRange)
        val curtsey = curtseyMatch?.matched == true
        val raw = rms(body, reference, baseSpan)
        val dt = if (previousTime >= 0) ((now - previousTime) / 1000f).coerceIn(0f, .4f) else .1f
        if (gap) smoothed = 0f
        smoothed += (raw - smoothed) * (1f - exp(-dt / .25f))
        val score = ((smoothed - noiseFloor) / (.50f - noiseFloor) * sensitivity).coerceIn(0f, 1f)
        // Compare every selected joint to its original background coordinate.
        val snapshot = continuous!!.observe(body, now, together, legsTogether,
            ContinuousTracking.hands.all { continuous!!.handZone.contains(body[it]) },
            distance(handCenter, continuous!!.handTarget) <= continuous!!.targetRadius)
        val handDeparture = snapshot.displacement(ContinuousTracking.hands) / baseSpan
        val separation = (distance(body[BodyJoint.LW], body[BodyJoint.RW]) -
            distance(reference[BodyJoint.LW], reference[BodyJoint.RW])) / baseSpan
        val footDeparture = ContinuousTracking.legs.maxOf { joint ->
            distance(body[joint], reference[joint]) / baseSpan
        }
        val handPath = snapshot.path(ContinuousTracking.hands) / baseSpan
        val legPath = snapshot.path(ContinuousTracking.legs) / baseSpan
        val legSpread = maxOf((snapshot.ankleGap - baseSpan * .45f), (snapshot.kneeGap - baseSpan * .55f)).coerceAtLeast(0f) / baseSpan
        fun reading(values: Map<String, Float>, threshold: Float, percent: Int): MotionMappingReading? {
            if (!motionMapping.valid()) return null
            val strongest = values.maxBy { it.value }
            return MotionMapping.evaluate(strongest.value * baseSpan, threshold * baseSpan,
                baseSpan * percent / 100f, strongest.key)
        }
        val handMapping = reading(linkedMapOf("固定坐标偏移" to handDeparture,
            "双手分离" to separation, "近1秒路程" to handPath), handNoiseFloor, motionMapping.handRangePercent)
        val footMapping = reading(linkedMapOf("固定坐标偏移" to footDeparture,
            "近1秒路程" to legPath, "双腿张开" to legSpread), footNoiseFloor, motionMapping.footRangePercent)
        // Independent trunk movement: hand/leg motion cannot secretly activate this peak.
        val torsoDeparture = listOf(BodyJoint.LS, BodyJoint.RS, BodyJoint.LH, BodyJoint.RH).maxOf {
            distance(body[it], reference[it]) / baseSpan
        }
        val torsoMapping = reading(mapOf("肩髋固定坐标偏移" to torsoDeparture), noiseFloor, motionMapping.torsoRangePercent)
        val handScore = handMapping?.score ?: 0f
        val legScore = footMapping?.score ?: 0f
        val outside = distance(hip, baseHip) / baseSpan > zoneRadius &&
            distance(ankle, baseAnkle) / baseSpan > zoneRadius
        return StandingMetrics(true, standing, curtsey, smoothed, maxOf(score, handScore * .5f, legScore * .5f), true,
            hands = handScore, feet = legScore, outside = outside,
            neutral = standing && legsTogether && together && snapshot.handsInBox && snapshot.handsAtTarget &&
                snapshot.displacement(ContinuousTracking.hands) <= baseSpan * .12f &&
                snapshot.displacement(ContinuousTracking.legs) <= baseSpan * .12f && feetStill && !outside,
            tPose = tPose, tracking = snapshot, trackingMessage = "骨架连续跟踪中 · 第 ${snapshot.frame} 帧",
            legsTogether = legsTogether, handsTogether = together, handsInReference = snapshot.handsInBox,
            curtseyCalibrated = curtseyCalibrated,
            curtseyMatch = curtseyMatch, curtseyReturnMatch = curtseyReturn,
            handMapping = handMapping, footMapping = footMapping,
            torsoScore = torsoMapping?.score ?: 0f, torsoMapping = torsoMapping)
    }

    private fun observeRestCalibration(input: FullBody?, width: Int, height: Int, now: Long): StandingMetrics {
        fun valid(j: BodyJoint, threshold: Float): Boolean {
            val p = input?.points?.get(j) ?: return false
            return width > 0 && height > 0 && p.x.isFinite() && p.y.isFinite() && p.confidence.isFinite() &&
                p.confidence >= threshold && p.x in 0f..width.toFloat() && p.y in 0f..height.toFloat()
        }
        val torso = setOf(BodyJoint.LS, BodyJoint.RS, BodyJoint.LH, BodyJoint.RH)
        if (calibrationAnchor == null && valid(BodyJoint.LS, .5f) && valid(BodyJoint.RS, .5f)) calibrationAnchor = input
        val original = calibrationAnchor
        val span = original?.let { distance(it[BodyJoint.LS], it[BodyJoint.RS]) } ?: Float.NaN
        val shoulderY = original?.let { (it[BodyJoint.LS].y + it[BodyJoint.RS].y) / 2 } ?: Float.NaN
        val hipsClear = valid(BodyJoint.LH, .5f) && valid(BodyJoint.RH, .5f)
        val positioned = positionedStanding
        val template = curtseyTemplate
        val returned = if (hipsClear && positioned != null && template != null) {
            val originalHip = (positioned[BodyJoint.LH].y + positioned[BodyJoint.RH].y) / 2
            val taughtDrop = (template.reference[BodyJoint.LH].y + template.reference[BodyJoint.RH].y) / 2 - originalHip
            val currentDrop = (input!![BodyJoint.LH].y + input[BodyJoint.RH].y) / 2 - originalHip
            taughtDrop > 1 && currentDrop / taughtDrop < .45f
        } else true
        val wristsVisible = valid(BodyJoint.LW, .5f) && valid(BodyJoint.RW, .5f)
        val reading = wristCalibration.update(now,
            input?.get(BodyJoint.LW)?.takeIf { wristsVisible }, input?.get(BodyJoint.RW)?.takeIf { wristsVisible },
            span, shoulderY, wristNearPercent, returned)
        calibrationHeldMs = reading.heldMs
        if (reading.restarted) { calibration.clear(); calibrationTimes.clear() }
        while (calibrationTimes.firstOrNull()?.let { now - it > 1200 } == true) {
            calibrationTimes.removeAt(0); calibration.removeAt(0)
        }
        val required = ContinuousTracking.points + torso
        val full = required.all { valid(it, if (it in ContinuousTracking.legs) .35f else .5f) } && input != null &&
            distance(input[BodyJoint.LS], input[BodyJoint.RS]) > 1f &&
            angle(input[BodyJoint.LH], input[BodyJoint.LK], input[BodyJoint.LA]).isFinite() &&
            angle(input[BodyJoint.RH], input[BodyJoint.RK], input[BodyJoint.RA]).isFinite()
        val legsTogether = full && input != null && distance(input[BodyJoint.LA], input[BodyJoint.RA]) <= span * .45f &&
            distance(input[BodyJoint.LK], input[BodyJoint.RK]) <= span * .55f
        if (reading.good && returned && full && legsTogether && calibrationTimes.lastOrNull() != now) {
            calibration += input; calibrationTimes += now
        }
        val progress = (calibrationHeldMs.toFloat() / CALIBRATION_HOLD_MS).coerceIn(0f, 1f)
        if (reading.good && returned && full && legsTogether && progress >= 1f && calibration.size >= 3) {
            val reference = average(calibration)
            val noise = calibration.map { rms(it, reference, span) }.sorted()
            noiseFloor = (noise[(noise.size * .9f).toInt().coerceAtMost(noise.lastIndex)] * 2f).coerceIn(.04f, .16f)
            // Separate noise estimates avoid a restless hand masking a real step (or vice versa).
            fun noiseFor(joints: List<BodyJoint>): Float {
                val values = calibration.map { sample -> joints.maxOf { distance(sample[it], reference[it]) / span } }.sorted()
                return values[(values.size * .9f).toInt().coerceAtMost(values.lastIndex)] * 2f
            }
            handNoiseFloor = noiseFor(listOf(BodyJoint.LW, BodyJoint.RW)).coerceIn(.10f, .18f)
            footNoiseFloor = noiseFor(listOf(BodyJoint.LA, BodyJoint.RA)).coerceIn(.07f, .15f)
            endpointFilter.seed(reference, now)
            val referenceSpan = distance(reference[BodyJoint.LS], reference[BodyJoint.RS])
            continuous = ContinuousTracking(reference,
                handZone(reference, referenceSpan),
                midpoint(reference[BodyJoint.LW], reference[BodyJoint.RW]), referenceSpan * .12f,
                referenceSpan, handNoiseFloor * referenceSpan, footNoiseFloor * referenceSpan).also {
                    it.observe(reference, now, true, true, true, true)
                }
            baseline = reference; calibrating = false; calibration.clear(); calibrationTimes.clear(); smoothed = 0f
        }
        val body = input
        val guide = if (body != null && full && span.isFinite() && span > 1) {
            val center = midpoint(body[BodyJoint.LW], body[BodyJoint.RW])
            TrackingSnapshot(observations, now, 0,
                ContinuousTracking.points.associateWith { JointTravel(body[it], body[it], 0f, 0f, emptyList()) },
                handZone(body, span), center, span * .12f, reading.distance,
                distance(body[BodyJoint.LK], body[BodyJoint.RK]), distance(body[BodyJoint.LA], body[BodyJoint.RA]),
                reading.good, legsTogether, reading.good, reading.good, fixed = false)
        } else null
        val missing = required.firstOrNull { !valid(it, if (it in ContinuousTracking.legs) .35f else .5f) }
        val status = if (!reading.good || !returned) reading.reason else if (!full || !legsTogether || (progress >= 1f && !calibrated))
            "手位进度已保留喵，等最近的身体坐标看清再完成 · ${missing?.label ?: if (!legsTogether) "双脚并拢" else "采集基准"}"
            else reading.reason
        val distanceText = if (reading.distance.isFinite() && span.isFinite() && span > 1)
            "双腕间距 ${DisplayFormat.one(reading.distance / span * 100)}% / 允许 $wristNearPercent% 肩宽" else "等待双手腕"
        lastTime = now
        return StandingMetrics(tracked = wristsVisible, standing = returned && full, calibrated = calibrated,
            calibrationProgress = progress, neutral = reading.good && returned && full && legsTogether, measurementReady = full,
            legsTogether = legsTogether, handsTogether = reading.good, handsInReference = reading.good,
            tracking = if (calibrated && body != null) continuous?.observe(body, now, true, legsTogether, true, true) else guide,
            trackingMessage = "$status · $distanceText", curtseyCalibrated = curtseyCalibrated)
    }

    private fun clearCalibration() {
        calibration.clear(); calibrationTimes.clear(); calibrationHeldMs = 0
    }
    private fun handZone(body: FullBody, span: Float): HandZone {
        val left = body[BodyJoint.LW]; val right = body[BodyJoint.RW]; val margin = span * .12f
        return HandZone(min(left.x, right.x) - margin, min(left.y, right.y) - margin,
            max(left.x, right.x) + margin, max(left.y, right.y) + margin)
    }

    private fun rms(a: FullBody, b: FullBody, scale: Float): Float =
        sqrt(motionWeights.entries.sumOf { (joint, weight) ->
            val dx = (a[joint].x - b[joint].x) / scale
            val dy = (a[joint].y - b[joint].y) / scale
            ((dx * dx + dy * dy) * weight).toDouble()
        }.toFloat())
    private val motionWeights = mapOf(BodyJoint.LW to .20f, BodyJoint.RW to .20f,
        BodyJoint.LA to .20f, BodyJoint.RA to .20f,
        BodyJoint.LH to .075f, BodyJoint.RH to .075f,
        BodyJoint.LS to .025f, BodyJoint.RS to .025f)
    private fun average(samples: List<FullBody>) = PersonalCurtsey.average(samples)
    private fun midpoint(a: BodyPoint, b: BodyPoint) = BodyPoint((a.x + b.x) / 2, (a.y + b.y) / 2, min(a.confidence, b.confidence))
    private fun distance(a: BodyPoint, b: BodyPoint) = hypot(a.x - b.x, a.y - b.y)
    private fun angle(a: BodyPoint, b: BodyPoint, c: BodyPoint): Float {
        val u = distance(a, b); val v = distance(c, b)
        if (u < 1 || v < 1) return Float.NaN
        val cosine = ((a.x - b.x) * (c.x - b.x) + (a.y - b.y) * (c.y - b.y)) / (u * v)
        return Math.toDegrees(acos(cosine.coerceIn(-1f, 1f)).toDouble()).toFloat()
    }
}

/** Absolute intensity with a configurable rise rate; downward changes remain immediate. */
class MotionIntensity {
    private var value = 0f
    private var last = -1L
    fun reset() { value = 0f; last = -1 }
    fun update(score: Float, maximum: Int, now: Long, risePerSecond: Int = MotionRiseSpeed.DEFAULT): Int {
        val target = if (score.isFinite()) score.coerceIn(0f, 1f) * maximum.coerceIn(0, 200) else 0f
        val elapsed = if (last < 0) 0f else ((now - last).coerceIn(0, 400) / 1000f)
        last = now
        val speed = if (MotionRiseSpeed.valid(risePerSecond)) risePerSecond.toFloat() else 0f
        value = if (target <= value) target else min(target, value + elapsed * speed)
        return value.roundToInt().coerceIn(0, maximum.coerceIn(0, 200))
    }
}
