package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class PoseCoordinateMapperTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private fun point(x: Float, y: Float, confidence: Float = .99f) = PreviewLandmark(PreviewPosition(x, y), confidence)

    @Test fun identityKeepsPositionsAndAnatomicalJointIds() {
        val raw = mapOf(15 to point(100f, 200f), 16 to point(300f, 220f, .7f))
        val actual = PoseCoordinateMapper.map(raw, identity)!!
        assertEquals(raw, actual.landmarks)
        assertEquals(.7f, actual.getPoseLandmark(16)!!.inFrameLikelihood, .001f)
    }

    @Test fun frontCameraMovesWithMirroredVideoRatherThanOppositeToIt() {
        // Upright 480x640 buffer fitted into a 720x1280 mirrored preview; 160 px top margin.
        val transform = floatArrayOf(-1.5f, 0f, 720f, 0f, 1.5f, 160f, 0f, 0f, 1f)
        val first = PoseCoordinateMapper.map(mapOf(0 to point(100f, 180f)), transform)!!.getPoseLandmark(0)!!
        val moved = PoseCoordinateMapper.map(mapOf(0 to point(120f, 180f)), transform)!!.getPoseLandmark(0)!!
        assertEquals(570f, first.position.x, .001f)
        assertEquals(430f, first.position.y, .001f)
        assertEquals(540f, moved.position.x, .001f)
        assertTrue(moved.position.x < first.position.x)
    }

    @Test fun backCameraPreservesDirectionAndAppliesPreviewFitOffsets() {
        val transform = floatArrayOf(1.5f, 0f, 0f, 0f, 1.5f, 160f, 0f, 0f, 1f)
        val actual = PoseCoordinateMapper.map(mapOf(15 to point(120f, 180f)), transform)!!.getPoseLandmark(15)!!
        assertEquals(180f, actual.position.x, .001f)
        assertEquals(430f, actual.position.y, .001f)
    }

    @Test fun cameraMatrixRotationIsAppliedExactlyOnce() {
        val source = mapOf(0 to point(100f, 200f))
        val transforms = listOf(
            floatArrayOf(0f, -1f, 640f, 1f, 0f, 0f, 0f, 0f, 1f) to PreviewPosition(440f, 100f),
            floatArrayOf(-1f, 0f, 480f, 0f, -1f, 640f, 0f, 0f, 1f) to PreviewPosition(380f, 440f),
            floatArrayOf(0f, 1f, 0f, -1f, 0f, 480f, 0f, 0f, 1f) to PreviewPosition(200f, 380f)
        )
        for ((matrix, expected) in transforms) {
            assertEquals(expected, PoseCoordinateMapper.map(source, matrix)!!.getPoseLandmark(0)!!.position)
        }
    }

    @Test fun faceCoverSkeletonAndGameUseTheSameMappedPointsAfterResizing() {
        val source = mapOf(0 to point(140f, 120f), 7 to point(110f, 120f), 8 to point(170f, 120f),
            11 to point(80f, 200f), 12 to point(200f, 200f), 15 to point(130f, 310f), 16 to point(150f, 310f))
        val small = PoseCoordinateMapper.map(source, floatArrayOf(-1f, 0f, 360f, 0f, 1f, 0f, 0f, 0f, 1f))!!
        val big = PoseCoordinateMapper.map(source, floatArrayOf(-2f, 0f, 720f, 0f, 2f, 0f, 0f, 0f, 1f))!!
        fun head(pose: PreviewPose) = HeadCoverGeometry.locate(pose.landmarks.mapValues { (_, p) ->
            HeadCoverGeometry.Point(p.position.x, p.position.y, p.inFrameLikelihood)
        })!!
        assertEquals(head(small).x * 2, head(big).x, .001f)
        assertEquals(head(small).y * 2, head(big).y, .001f)
        assertEquals(head(small).radius * 2, head(big).radius, .001f)
        fun normalized(pose: PreviewPose, width: Int, height: Int): FullBody = PreviewCoordinates.normalize(
            BodyJoint.entries.mapNotNull { joint -> pose.getPoseLandmark(joint.id)?.let {
                joint to BodyPoint(it.position.x, it.position.y, it.inFrameLikelihood)
            } }.toMap(), width, height)!!
        assertEquals(normalized(small, 360, 640).points, normalized(big, 720, 1280).points)
    }

    @Test fun invalidTransformsCannotBecomeGameEvidenceAndMissingPointsStayMissing() {
        val source = mapOf(0 to point(100f, 200f))
        assertNull(PoseCoordinateMapper.map(source, FloatArray(9)))
        assertNull(PoseCoordinateMapper.map(source, FloatArray(8)))
        assertNull(PoseCoordinateMapper.map(source, identity.copyOf().also { it[0] = Float.NaN }))
        assertNull(PoseCoordinateMapper.map(source, identity.copyOf().also { it[8] = Float.POSITIVE_INFINITY }))
        assertTrue(PoseCoordinateMapper.map(emptyMap(), identity)!!.landmarks.isEmpty())
        val invalidPoint = source + (15 to point(Float.NaN, 50f))
        assertNull(PoseCoordinateMapper.map(invalidPoint, identity)!!.getPoseLandmark(15))
        assertNull(PoseCoordinateMapper.map(source, identity)!!.getPoseLandmark(27))
    }

    @Test fun pointsOutsideVideoRemainOutsideInsteadOfBeingClampedIntoView() {
        val actual = PoseCoordinateMapper.map(mapOf(15 to point(-100f, 2000f)), identity)!!.getPoseLandmark(15)!!
        assertEquals(-100f, actual.position.x, .001f)
        assertEquals(2000f, actual.position.y, .001f)
    }
}
