package com.coyotevision.app

internal data class PreviewPosition(val x: Float, val y: Float)
internal data class PreviewLandmark(val position: PreviewPosition, val inFrameLikelihood: Float)

/** Coordinates explicitly mapped to the video view, not an untransformed SDK Pose. */
internal data class PreviewPose(val landmarks: Map<Int, PreviewLandmark>) {
    fun getPoseLandmark(id: Int) = landmarks[id]
}

internal object PoseCoordinateMapper {
    /** Android Matrix values: scale/skew/translation, then perspective. Apply exactly once. */
    fun map(raw: Map<Int, PreviewLandmark>, matrix: FloatArray): PreviewPose? {
        if (matrix.size != 9 || matrix.any { !it.isFinite() }) return null
        val m = matrix.map { it.toDouble() }
        val determinant = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (!determinant.isFinite() || determinant == 0.0) return null
        val points = raw.mapNotNull { (id, point) ->
            val x = point.position.x; val y = point.position.y
            if (!x.isFinite() || !y.isFinite() || !point.inFrameLikelihood.isFinite()) return@mapNotNull null
            val divisor = m[6] * x + m[7] * y + m[8]
            if (!divisor.isFinite() || divisor == 0.0) return null
            val viewX = ((m[0] * x + m[1] * y + m[2]) / divisor).toFloat()
            val viewY = ((m[3] * x + m[4] * y + m[5]) / divisor).toFloat()
            if (!viewX.isFinite() || !viewY.isFinite()) return null
            id to point.copy(position = PreviewPosition(viewX, viewY))
        }.toMap()
        return PreviewPose(points)
    }
}
