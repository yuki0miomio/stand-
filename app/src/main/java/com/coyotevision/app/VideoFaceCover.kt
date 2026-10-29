package com.coyotevision.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View

/** Draws directly over the video PreviewView, independently of the skeleton and game UI. */
internal class VideoFaceCover(private val preview: View) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tracking = HeadCoverTracking()
    private var enabled = false
    var settings = FaceCoverSettings()
        set(value) { require(value.valid()); field = value; cover.invalidateSelf() }
    private var expiryScheduled = false
    private val expiry = object : Runnable {
        override fun run() {
            expiryScheduled = false
            cover.invalidateSelf()
            if (tracking.current(SystemClock.elapsedRealtime()) != null) scheduleExpiry()
        }
    }
    private val cover = object : Drawable() {
        override fun draw(canvas: Canvas) {
            if (!enabled) return
            tracking.current(SystemClock.elapsedRealtime())?.let { raw ->
                settings.place(raw, preview.width, preview.height)?.let {
                    val saved = canvas.save()
                    canvas.clipRect(0, 0, preview.width, preview.height)
                    canvas.drawOval(it.x - it.radiusX, it.y - it.radiusY, it.x + it.radiusX, it.y + it.radiusY, paint)
                    canvas.restoreToCount(saved)
                }
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Drawable compatibility")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    init {
        preview.overlay.add(cover)
        preview.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            cover.setBounds(0, 0, right - left, bottom - top)
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) clear()
        }
        preview.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { clear() }
        })
    }

    fun setEnabled(value: Boolean) { enabled = value; clear() }

    fun update(pose: PreviewPose?) {
        if (!enabled) return
        // PreviewPoseDetector explicitly mapped these points to this PreviewView, including mirroring.
        val points = (0..12).mapNotNull { id -> pose?.getPoseLandmark(id)?.let {
            id to HeadCoverGeometry.Point(it.position.x, it.position.y, it.inFrameLikelihood)
        } }.toMap()
        if (tracking.update(points, SystemClock.elapsedRealtime()) != null) scheduleExpiry()
        cover.invalidateSelf()
    }

    private fun scheduleExpiry() {
        if (!expiryScheduled) {
            val delay = tracking.expiresIn(SystemClock.elapsedRealtime())
            if (delay <= 0L) return
            expiryScheduled = true
            preview.postDelayed(expiry, delay)
        }
    }
    fun clear() {
        preview.removeCallbacks(expiry); expiryScheduled = false
        tracking.clear(); cover.invalidateSelf()
    }
}
