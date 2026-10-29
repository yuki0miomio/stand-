package com.coyotevision.app

import android.content.Context
import android.graphics.Color
import android.widget.FrameLayout
import kotlin.math.min

/** Keeps the same video aspect ratio when moved from the settings page into the game. */
class CameraStage(context: Context) : FrameLayout(context) {
    val video = FrameLayout(context)
    var fillSpace = false
        set(value) { field = value; requestLayout() }
    var viewportChanged: (() -> Unit)? = null
    private var previousWidth = 0
    private var previousHeight = 0
    init { setBackgroundColor(Color.BLACK); addView(video) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val naturalHeight = (width * 16f / 9f).toInt()
        val height = if (fillSpace) MeasureSpec.getSize(heightMeasureSpec) else
            resolveSize(naturalHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
        val videoWidth = min(width, (height * 9f / 16f).toInt())
        val videoHeight = (videoWidth * 16f / 9f).toInt()
        video.measure(MeasureSpec.makeMeasureSpec(videoWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(videoHeight, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = video.measuredWidth; val height = video.measuredHeight
        val x = ((right - left) - width) / 2; val y = ((bottom - top) - height) / 2
        video.layout(x, y, x + width, y + height)
        if (width != previousWidth || height != previousHeight) {
            previousWidth = width; previousHeight = height
            viewportChanged?.invoke()
        }
    }
}
