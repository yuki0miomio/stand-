package com.coyotevision.app

import kotlin.math.abs

/** The preview retains a 9:16 video rectangle in both settings and game views. */
object PreviewCoordinates {
    const val WIDTH = 720
    const val HEIGHT = 1280
    fun normalize(points: Map<BodyJoint, BodyPoint>, width: Int, height: Int): FullBody? {
        if (width <= 0 || height <= 0 || abs(width.toFloat() / height - WIDTH.toFloat() / HEIGHT) > .01f)
            return null
        return FullBody(points.mapValues { (_, point) ->
            point.copy(x = point.x * WIDTH / width, y = point.y * HEIGHT / height)
        })
    }
}
