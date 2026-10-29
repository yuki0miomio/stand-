package com.coyotevision.app

/** Display adjustments only. Offsets are in screen axes, after camera mirroring. */
internal data class FaceCoverSettings(val sizePercent: Int = 80, val xPercent: Int = 0, val yPercent: Int = 0,
    val widthPercent: Int = 100, val heightPercent: Int = 75) {
    data class Oval(val x: Float, val y: Float, val radiusX: Float, val radiusY: Float)
    companion object {
        val defaults = linkedMapOf("face_cover_size" to 80, "face_cover_x" to 0, "face_cover_y" to 0,
            "face_cover_width" to 100, "face_cover_height" to 75)
    }
    fun valid() = sizePercent in 20..400 && xPercent in -50..50 && yPercent in -50..50 &&
        widthPercent in 20..300 && heightPercent in 20..300
    fun place(circle: HeadCoverGeometry.Circle, width: Int, height: Int): Oval? {
        if (!valid() || width <= 0 || height <= 0 || !circle.x.isFinite() || !circle.y.isFinite() ||
            !circle.radius.isFinite() || circle.radius <= 0f) return null
        val radius = circle.radius * (sizePercent / 100f)
        return Oval(circle.x + width * (xPercent / 100f), circle.y + height * (yPercent / 100f),
            radius * (widthPercent / 100f), radius * (heightPercent / 100f))
            .takeIf { it.x.isFinite() && it.y.isFinite() && it.radiusX.isFinite() && it.radiusY.isFinite() }
    }
}
