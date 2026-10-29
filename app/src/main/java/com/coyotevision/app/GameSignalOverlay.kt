package com.coyotevision.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** No visible text, touch handlers, layout changes, camera transforms, or output commands. */
class GameSignalOverlay(context: Context) : View(context) {
    var frame = CurtseySignalFrame()
        set(value) {
            if (field == value) return
            field = value
            contentDescription = when (value.kind) {
                CurtseySignalKind.NONE -> null
                CurtseySignalKind.CUE_WAITING -> "等待三连提示"
                CurtseySignalKind.CUE -> "三连脉冲提示"
                CurtseySignalKind.REQUEST -> "屈膝礼判定中"
                CurtseySignalKind.SUCCESS -> "小公主行礼合格"
                CurtseySignalKind.FAILURE -> "小公主行礼超时"
                CurtseySignalKind.PAUSED -> "检测暂停"
                CurtseySignalKind.MOTION_RISE -> "手脚大幅动作，输出强度明显上升"
            }
            invalidate()
        }
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frame.kind == CurtseySignalKind.NONE || width <= 0 || height <= 0) return
        val stroke = min(14f * density, min(width, height) * .06f)
        val inset = stroke * .75f
        bounds.set(inset, inset, width - inset, height - inset)
        val radius = stroke * 1.4f
        paint.color = Color.argb(220, 0, 0, 0); paint.strokeWidth = stroke * 1.4f
        canvas.drawRoundRect(bounds, radius, radius, paint)
        val color = when (frame.kind) {
            CurtseySignalKind.CUE_WAITING, CurtseySignalKind.CUE -> Color.rgb(255, 220, 0)
            CurtseySignalKind.REQUEST -> Color.rgb(0, 160, 255)
            CurtseySignalKind.SUCCESS -> Color.rgb(0, 255, 100)
            CurtseySignalKind.FAILURE -> Color.rgb(255, 35, 45)
            CurtseySignalKind.MOTION_RISE -> Color.rgb(255, 140, 30)
            else -> Color.WHITE
        }
        paint.color = color
        paint.alpha = if (!frame.bright || frame.kind == CurtseySignalKind.CUE_WAITING) 65 else 255
        paint.strokeWidth = stroke
        canvas.drawRoundRect(bounds, radius, radius, paint)
        // A shrinking light strip inside the bottom edge shows the response budget without text.
        frame.progress?.let { progress ->
            val left = inset + radius; val right = width - inset - radius
            val y = height - inset - stroke * 1.8f
            paint.strokeWidth = stroke * .55f; paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.argb(230, 0, 15, 35)
            canvas.drawLine(left, y, right, y, paint)
            if (progress > 0) {
                paint.color = Color.rgb(150, 225, 255)
                canvas.drawLine(left, y, left + (right - left) * progress, y, paint)
            }
            paint.strokeCap = Paint.Cap.BUTT
        }
    }
}
