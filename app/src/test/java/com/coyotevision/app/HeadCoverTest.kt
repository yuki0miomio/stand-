package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class HeadCoverTest {
    private fun point(x: Float, y: Float, confidence: Float = 1f) = HeadCoverGeometry.Point(x, y, confidence)
    private fun subject() = mapOf(0 to point(200f, 180f), 2 to point(180f, 170f),
        5 to point(220f, 170f), 7 to point(160f, 180f), 8 to point(240f, 180f),
        9 to point(190f, 210f), 10 to point(210f, 210f),
        11 to point(120f, 300f), 12 to point(280f, 300f))

    @Test fun faceCircleCoversLandmarksWithForeheadAndChinMargin() {
        val points = subject()
        val circle = HeadCoverGeometry.locate(points)!!
        points.filterKeys { it <= 10 }.values.forEach {
            assertTrue(hypot(it.x - circle.x, it.y - circle.y) < circle.radius)
        }
        assertTrue(circle.y - circle.radius < 130f)
        assertTrue(circle.y + circle.radius > 230f)
    }

    @Test fun frontCameraReflectionUsesAlreadyTransformedCoordinates() {
        val normal = HeadCoverGeometry.locate(subject())!!
        val reflected = HeadCoverGeometry.locate(subject().mapValues { (_, p) -> p.copy(x = 720f - p.x) })!!
        assertEquals(720f - normal.x, reflected.x, .001f)
        assertEquals(normal.y, reflected.y, .001f)
        assertEquals(normal.radius, reflected.radius, .001f)
    }

    @Test fun movementAndPreviewScalingMoveCircleImmediately() {
        val tracking = HeadCoverTracking()
        val normal = tracking.update(subject(), 1000)!!
        val moved = tracking.update(subject().mapValues { (_, p) -> p.copy(x = p.x * 2 + 90, y = p.y * 2 + 60) }, 1010)!!
        assertEquals(normal.x * 2 + 90, moved.x, .001f)
        assertEquals(normal.y * 2 + 60, moved.y, .001f)
        assertEquals(normal.radius * 2, moved.radius, .001f)
    }

    @Test fun hiddenFacialPointsUseShouldersWithoutHandsHipsOrLegs() {
        val shoulders = subject().filterKeys { it >= 11 }
        val circle = HeadCoverGeometry.locate(shoulders)!!
        assertEquals(200f, circle.x, .001f)
        assertTrue(circle.y < 250f)
        assertTrue(circle.radius > 100f)
        val mirrored = HeadCoverGeometry.locate(shoulders.mapValues { (_, p) -> p.copy(x = 720f - p.x) })!!
        assertEquals(720f - circle.x, mirrored.x, .001f)
        assertEquals(circle.y, mirrored.y, .001f)
    }

    @Test fun reliableFacialPointsDoNotRequireShoulders() {
        val points = subject().filterKeys { it <= 10 }
        assertNotNull(HeadCoverGeometry.locate(points))
        val lowShoulders = subject().mapValues { (id, p) -> if (id >= 11) p.copy(confidence = .1f) else p }
        assertEquals(HeadCoverGeometry.locate(points), HeadCoverGeometry.locate(lowShoulders))
    }

    @Test fun InvalidOrMissingEvidenceCannotMakeAnArbitraryCircle() {
        assertNull(HeadCoverGeometry.locate(emptyMap()))
        assertNull(HeadCoverGeometry.locate(subject().mapValues { (_, p) -> p.copy(confidence = .1f) }))
        assertNull(HeadCoverGeometry.locate(subject().mapValues { (_, p) -> p.copy(x = Float.NaN) }))
        assertNull(HeadCoverGeometry.locate(mapOf(0 to point(200f, 200f))))
        assertNull(HeadCoverGeometry.locate(mapOf(11 to point(200f, 300f), 12 to point(200f, 300f))))
        assertNull(HeadCoverGeometry.locate(mapOf(11 to point(Float.POSITIVE_INFINITY, 300f), 12 to point(280f, 300f))))
    }

    @Test fun briefFlickerDoesNotRefreshTheExpiryOrKeepAnOldCircleIndefinitely() {
        val tracking = HeadCoverTracking()
        val first = tracking.update(subject(), 1000)
        assertEquals(first, tracking.update(emptyMap(), 1400))
        assertEquals(201L, tracking.expiresIn(1400))
        assertEquals(first, tracking.current(1600))
        assertNull(tracking.current(1601))
        assertEquals(0L, tracking.expiresIn(1601))
        assertNull(tracking.update(emptyMap(), 2000))
    }

    @Test fun closingOrChangingCameraClearsOldCoordinatesAndNewPoseReacquires() {
        val tracking = HeadCoverTracking()
        tracking.update(subject(), 1000)
        tracking.clear()
        assertNull(tracking.current(1001))
        assertNull(tracking.update(emptyMap(), 1002))
        val next = subject().mapValues { (_, p) -> p.copy(y = p.y + 100) }
        assertEquals(HeadCoverGeometry.locate(next), tracking.update(next, 1003))
        assertNull(tracking.current(900))
    }

    @Test fun defaultDisplayOvalFocusesOnEyesNoseAndMouth() {
        val raw = HeadCoverGeometry.locate(subject())!!
        val display = FaceCoverSettings().place(raw, 720, 1280)!!
        assertEquals(raw.x, display.x, .001f)
        assertEquals(raw.y, display.y, .001f)
        assertEquals(raw.radius * .8f, display.radiusX, .001f)
        assertEquals(raw.radius * .6f, display.radiusY, .001f)
        assertTrue(display.radiusY < display.radiusX)
        for (id in listOf(0, 2, 5, 9, 10)) {
            val p = subject().getValue(id)
            val dx = (p.x - display.x) / display.radiusX
            val dy = (p.y - display.y) / display.radiusY
            assertTrue("Eyes/nose/mouth point $id must be inside default oval", dx * dx + dy * dy <= 1f)
        }
    }

    @Test fun manualAdjustmentsUseScreenDirectionsAfterMirroring() {
        val raw = HeadCoverGeometry.Circle(200f, 180f, 60f)
        val adjusted = FaceCoverSettings(150, 10, -5).place(raw, 720, 1280)!!
        assertEquals(272f, adjusted.x, .001f)
        assertEquals(116f, adjusted.y, .001f)
        assertEquals(90f, adjusted.radiusX, .001f)
        assertEquals(67.5f, adjusted.radiusY, .001f)
        val mirrored = raw.copy(x = 720f - raw.x)
        val other = FaceCoverSettings(150, 10, -5).place(mirrored, 720, 1280)!!
        assertEquals(mirrored.x + 72f, other.x, .001f)
    }

    @Test fun displayAdjustmentKeepsAlignmentWhenPreviewSizeChanges() {
        val raw = HeadCoverGeometry.Circle(200f, 180f, 60f)
        val settings = FaceCoverSettings(130, -8, 12)
        val small = settings.place(raw, 720, 1280)!!
        val large = settings.place(raw.copy(x = raw.x * 2, y = raw.y * 2, radius = raw.radius * 2), 1440, 2560)!!
        assertEquals(small.x * 2, large.x, .001f)
        assertEquals(small.y * 2, large.y, .001f)
        assertEquals(small.radiusX * 2, large.radiusX, .001f)
        assertEquals(small.radiusY * 2, large.radiusY, .001f)
    }

    @Test fun invalidDisplayParametersAndGeometryAreRejected() {
        val raw = HeadCoverGeometry.Circle(200f, 180f, 60f)
        for (settings in listOf(FaceCoverSettings(19), FaceCoverSettings(401), FaceCoverSettings(xPercent = -51),
            FaceCoverSettings(xPercent = 51), FaceCoverSettings(yPercent = -51), FaceCoverSettings(yPercent = 51),
            FaceCoverSettings(widthPercent = 19), FaceCoverSettings(heightPercent = 301))) {
            assertFalse(settings.valid())
            assertNull(settings.place(raw, 720, 1280))
        }
        assertNull(FaceCoverSettings().place(raw, 0, 1280))
        assertNull(FaceCoverSettings().place(raw.copy(radius = -1f), 720, 1280))
        assertNull(FaceCoverSettings().place(raw.copy(x = Float.NaN), 720, 1280))
    }
    @Test fun ovalWidthAndHeightCanBeAdjustedIndependentlyAcrossExpandedRanges() {
        val raw = HeadCoverGeometry.Circle(200f, 180f, 60f)
        val flat = FaceCoverSettings(100, widthPercent = 200, heightPercent = 50).place(raw, 720, 1280)!!
        assertEquals(120f, flat.radiusX, .001f)
        assertEquals(30f, flat.radiusY, .001f)
        val tall = FaceCoverSettings(100, widthPercent = 50, heightPercent = 200).place(raw, 720, 1280)!!
        assertEquals(30f, tall.radiusX, .001f)
        assertEquals(120f, tall.radiusY, .001f)
        assertEquals(flat.x, tall.x, .001f)
        assertEquals(flat.y, tall.y, .001f)
        val edge = FaceCoverSettings(400, -50, 50, 300, 20).place(raw, 720, 1280)!!
        assertEquals(-160f, edge.x, .001f)
        assertEquals(820f, edge.y, .001f)
        assertEquals(720f, edge.radiusX, .001f)
        assertEquals(48f, edge.radiusY, .001f)
    }

}
