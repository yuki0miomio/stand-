package com.coyotevision.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Coordinates are explicitly transformed into PreviewView coordinates by PreviewPoseDetector. */
internal class PoseOverlay(context: Context) : View(context) {
    var pose: PreviewPose? = null
        set(value) { field = value; invalidate() }
    var tracking: TrackingSnapshot? = null
        set(value) { field = value; invalidate() }
    var curtseyTarget: PersonalPoseMatch? = null
        set(value) { field = value; invalidate() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 240, 194); strokeWidth = 3 * resources.displayMetrics.density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val endpoint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 210, 120) }
    private val referencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 200, 200, 210); style = Paint.Style.STROKE; strokeWidth = 2 * resources.displayMetrics.density
    }
    private val travelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 100, 210, 255); strokeWidth = 2 * resources.displayMetrics.density
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12 * resources.displayMetrics.scaledDensity
    }
    private val edges = arrayOf(
        11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
        11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28,
    )
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val body = pose ?: return
        for ((a, b) in edges) {
            val start = body.getPoseLandmark(a) ?: continue
            val end = body.getPoseLandmark(b) ?: continue
            if (start.inFrameLikelihood < .75f || end.inFrameLikelihood < .75f) continue
            canvas.drawLine(start.position.x, start.position.y, end.position.x, end.position.y, line)
        }
        for (joint in BodyJoint.entries) {
            val landmark = body.getPoseLandmark(joint.id) ?: continue
            if (landmark.inFrameLikelihood < .75f) continue
            val focus = joint in EndpointFilter.endpoints
            canvas.drawCircle(landmark.position.x, landmark.position.y,
                (if (focus) 6 else 3) * resources.displayMetrics.density, if (focus) endpoint else dot)
        }
        tracking?.takeIf { curtseyTarget == null }?.let { snapshot ->
            val sx = width.toFloat() / PreviewCoordinates.WIDTH
            val sy = height.toFloat() / PreviewCoordinates.HEIGHT
            val box = snapshot.handZone
            referencePaint.color = if (snapshot.handsInBox && snapshot.handsAtTarget) Color.rgb(110, 240, 194) else Color.rgb(255, 150, 157)
            canvas.drawRect(box.left * sx, box.top * sy, box.right * sx, box.bottom * sy, referencePaint)
            canvas.drawCircle(snapshot.handTarget.x * sx, snapshot.handTarget.y * sy, snapshot.targetRadius * sx, referencePaint)
            canvas.drawText(if (snapshot.fixed) "小公主的手位 / 固定基准" else "双手收好喵 · 静止 5 秒后固定",
                box.left * sx, box.top * sy - 6 * resources.displayMetrics.density, caption)
            referencePaint.color = Color.argb(210, 200, 200, 210)
            for ((_, point) in snapshot.points) {
                if (snapshot.fixed) {
                    canvas.drawCircle(point.reference.x * sx, point.reference.y * sy, 6 * resources.displayMetrics.density, referencePaint)
                    canvas.drawLine(point.reference.x * sx, point.reference.y * sy, point.current.x * sx, point.current.y * sy, travelPaint)
                }
                point.trail.zipWithNext().forEach { (a, b) -> canvas.drawLine(a.x * sx, a.y * sy, b.x * sx, b.y * sy, travelPaint) }
                canvas.drawCircle(point.current.x * sx, point.current.y * sy, 5 * resources.displayMetrics.density, endpoint)
            }
        }
        curtseyTarget?.let { target ->
            val sx = width.toFloat() / PreviewCoordinates.WIDTH
            val sy = height.toFloat() / PreviewCoordinates.HEIGHT
            val paint = referencePaint
            paint.color = Color.argb(170, 245, 175, 225)
            val bones = listOf(BodyJoint.LS to BodyJoint.RS, BodyJoint.LS to BodyJoint.LH,
                BodyJoint.RS to BodyJoint.RH, BodyJoint.LH to BodyJoint.RH,
                BodyJoint.LS to BodyJoint.LW, BodyJoint.RS to BodyJoint.RW)
            for ((a, b) in bones) {
                val start = target.reference[a]; val end = target.reference[b]
                if (start.x.isFinite() && start.y.isFinite() && end.x.isFinite() && end.y.isFinite())
                    canvas.drawLine(start.x * sx, start.y * sy, end.x * sx, end.y * sy, paint)
            }
            target.offsets.forEach { (joint, offset) ->
                val point = target.reference[joint]
                if (point.x.isFinite() && point.y.isFinite() && target.radius > 0) {
                    paint.color = if (offset <= target.radius) Color.argb(110, 110, 240, 194) else Color.argb(140, 255, 150, 157)
                    canvas.drawOval((point.x - target.radius) * sx, (point.y - target.radius) * sy,
                        (point.x + target.radius) * sx, (point.y + target.radius) * sy, paint)
                    canvas.drawCircle(point.x * sx, point.y * sy, 4 * resources.displayMetrics.density, paint)
                }
            }
        }
    }
}
