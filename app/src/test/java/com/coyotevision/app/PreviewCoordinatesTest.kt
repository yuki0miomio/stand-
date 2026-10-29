package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class PreviewCoordinatesTest {
    private val body = FullBody(BodyJoint.entries.zip(listOf(
        200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f,
        290f to 400f, 310f to 400f, 230f to 450f, 370f to 450f,
        270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f,
    )).associate { (joint, xy) -> joint to BodyPoint(xy.first, xy.second, .99f) })

    private fun preview(width: Int, height: Int) = body.points.mapValues { (_, p) ->
        p.copy(x = p.x * width / PreviewCoordinates.WIDTH, y = p.y * height / PreviewCoordinates.HEIGHT)
    }

    @Test fun resizingFromSettingsToGamePreservesCalibrationAndMotionScores() {
        val tracker = StandingTracker(); tracker.beginCalibration()
        for (now in 0L..5100 step 100) tracker.observe(PreviewCoordinates.normalize(preview(360, 640), 360, 640),
            PreviewCoordinates.WIDTH, PreviewCoordinates.HEIGHT, now)
        assertTrue(tracker.calibrated)
        for (size in listOf(432 to 768, 540 to 960, 720 to 1280)) {
            val result = tracker.observe(PreviewCoordinates.normalize(preview(size.first, size.second), size.first, size.second),
                PreviewCoordinates.WIDTH, PreviewCoordinates.HEIGHT, 5200)
            assertTrue(result.tracked); assertTrue(result.neutral)
            assertEquals(0f, result.score, .001f); assertEquals(0f, result.hands, .001f)
            assertEquals(0f, result.feet, .001f); assertFalse(result.outside)
        }
    }
    @Test fun realMovementRemainsVisibleAfterResizing() {
        val source = preview(432, 768).mapValues { (_, p) -> p.copy(x = p.x + 72f) }
        val actual = PreviewCoordinates.normalize(source, 432, 768)!!
        assertEquals(body[BodyJoint.LH].x + 120f, actual[BodyJoint.LH].x, .001f)
        assertEquals(.99f, actual[BodyJoint.LH].confidence, .001f)
    }
    @Test fun transientInvalidViewportDoesNotProducePoseEvidence() {
        assertNull(PreviewCoordinates.normalize(body.points, 0, 1280))
        assertNull(PreviewCoordinates.normalize(body.points, 720, 0))
        assertNull(PreviewCoordinates.normalize(body.points, 640, 480))
    }
}
