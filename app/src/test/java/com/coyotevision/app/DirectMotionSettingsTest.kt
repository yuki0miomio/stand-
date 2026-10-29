package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class DirectMotionSettingsTest {
    private fun metrics(h: Float = 0f, f: Float = 0f, t: Float = 0f, outside: Boolean = false) =
        StandingMetrics(tracked = true, calibrated = true, measurementReady = true,
            hands = h, feet = f, torsoScore = t, outside = outside)
    private val rules = DirectMotionSettings(normal = 10, limit = 60, handPeak = 30,
        footPeak = 50, torsoPeak = 40, outsidePeak = 55)

    @Test fun absoluteHandPeakMatchesZeroHalfFullAndBeyondFullAmplitude() {
        assertEquals(10, rules.target(metrics()))
        assertEquals(20, rules.target(metrics(h = .5f)))
        assertEquals(30, rules.target(metrics(h = 1f)))
        assertEquals(30, rules.target(metrics(h = 4f)))
    }
    @Test fun simultaneousActionsTakeTheHighestAndCannotReachTheCapEarly() {
        assertEquals(30, rules.target(metrics(h = .5f, f = .5f, t = .5f)))
        assertEquals(50, rules.target(metrics(h = 1f, f = 1f, t = 1f)))
        assertEquals(55, rules.target(metrics(h = 1f, f = 1f, t = 1f, outside = true)))
    }
    @Test fun peaksAreIndependentAndZeroReallyDisablesAnAction() {
        val disabled = rules.copy(handPeak = 0, torsoPeak = 0, outsidePeak = 0)
        assertEquals(10, disabled.target(metrics(h = 1f, t = 1f, outside = true)))
        assertEquals(50, disabled.target(metrics(h = 1f, f = 1f, t = 1f)))
        assertEquals(10, rules.copy(handPeak = 10).target(metrics(h = 1f)))
    }
    @Test fun eachEnabledActionCanReachItsOwnExactPeak() {
        assertEquals(30, rules.target(metrics(h = 1f)))
        assertEquals(50, rules.target(metrics(f = 1f)))
        assertEquals(40, rules.target(metrics(t = 1f)))
        assertEquals(55, rules.target(metrics(outside = true)))
    }
    @Test fun invalidNumbersAndUnusableEvidenceNeverGenerateOutput() {
        for (bad in listOf(rules.copy(normal = -1), rules.copy(normal = 61), rules.copy(limit = 201),
            rules.copy(handPeak = 9), rules.copy(footPeak = 61), rules.copy(torsoPeak = -1))) {
            assertFalse(bad.valid())
            assertEquals(0, bad.target(metrics(1f, 1f, 1f, true)))
        }
        assertEquals(0, rules.target(metrics().copy(tracked = false)))
        assertEquals(0, rules.target(metrics().copy(measurementReady = false)))
        assertEquals(0, rules.target(metrics().copy(calibrated = false)))
        assertEquals(10, rules.target(metrics(Float.NaN, Float.POSITIVE_INFINITY, -.5f)))
        assertTrue(DirectMotionSettings().valid())
        assertEquals(0, DirectMotionSettings().target(metrics(1f, 1f, 1f, true)))
    }
    @Test fun trainingKeepsBasePlusTheSameMovementHeadroomUnderGlobalCap() {
        val training = rules.copy(outsidePeak = 0)
        assertEquals(25, training.target(metrics(), trainingBase = 25))
        assertEquals(35, training.target(metrics(h = .5f), trainingBase = 25))
        assertEquals(45, training.target(metrics(h = 1f), trainingBase = 25))
        assertEquals(60, training.target(metrics(f = 1f), trainingBase = 25))
        assertEquals(60, training.trainingMaximum(25))
        assertEquals(50, training.trainingMaximum(10))
        assertEquals(60, training.target(metrics(h = 1f, f = 1f, t = 1f), trainingBase = 25))
    }
    @Test fun legacyIncrementsConvertToAbsolutePeaksWithoutStackingOrRaisingTheirOldTargets() {
        assertEquals(30, DirectMotionSettings.legacyPeak(10, 20, 60))
        assertEquals(60, DirectMotionSettings.legacyPeak(10, 100, 60))
        assertEquals(0, DirectMotionSettings.legacyPeak(10, 0, 60))
        val old = MotionWeights(20, 30, 40)
        val migrated = DirectMotionSettings(10, 60, 30, 40, 0, 50)
        for (h in 0..10) for (f in 0..10) for (outside in listOf(false, true)) {
            val body = metrics(h / 10f, f / 10f, outside = outside)
            assertTrue(migrated.target(body) <= old.target(body, 60, 10))
        }
    }
    @Test fun newTargetsStillRespectOrdinaryRiseAndFiniteVirtualLease() {
        val limiter = MotionIntensity()
        val output = VirtualOutput()
        var emitted = 0
        for (at in 0L..6000L step 100) {
            val target = rules.target(metrics(h = 1f))
            emitted = CurrentRules.withMotionFloor(10, target, 60, limiter, at, 4)
            assertTrue(emitted in 10..30)
            if (at == 1000L) assertEquals(14, emitted)
            if (at % 500 == 0L) assertTrue(output.submit(EffectPreset(strength = emitted), 60, at))
        }
        assertEquals(30, emitted)
        assertNull(output.sample(7000))
    }
    @Test fun trainingBaseAndMotionTogetherStillRiseAtFixedThirty() {
        val limiter = MotionIntensity()
        for (at in 0L..1000L step 100) {
            val emitted = CurrentRules.withMotionFloor(0, rules.target(metrics(h = 1f), 25), 60,
                limiter, at, MotionRiseSpeed.forMode(200, true))
            if (at == 1000L) assertEquals(30, emitted)
        }
    }

    private fun body() = FullBody(BodyJoint.entries.zip(listOf(
        200f to 250f, 400f to 250f, 180f to 350f, 420f to 350f, 290f to 400f, 310f to 400f,
        230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f
    )).associate { (j, xy) -> j to BodyPoint(xy.first, xy.second, .99f) })
    private fun tracker(hand: Int = 20, foot: Int = 20, torso: Int = 20) = StandingTracker().apply {
        motionMapping = MotionMappingSettings(hand, foot, torso)
        beginCalibration()
        for (at in 0L..5100L step 100) observe(body(), 720, 1280, at)
        assertTrue(calibrated)
    }
    private fun moved(joints: Set<BodyJoint>, dx: Float) = FullBody(body().points.mapValues { (j, p) ->
        if (j in joints) p.copy(x = p.x + dx) else p
    })

    @Test fun calibratedCoordinatesMapExactHalfAndFullHandAmplitudeToAbsoluteStrength() {
        for ((dx, expected) in listOf(20f to 10, 40f to 20, 60f to 30, 100f to 30)) {
            val measured = tracker().observe(moved(setOf(BodyJoint.LW), dx), 720, 1280, 5200)
            assertEquals(0f, measured.torsoScore, .001f)
            assertEquals(expected, rules.target(measured))
        }
    }
    @Test fun changingAmplitudeControlsWhenTheSameFootPeakIsReached() {
        val moved = moved(setOf(BodyJoint.LA), 54f)
        assertEquals(50, rules.target(tracker(foot = 20).observe(moved, 720, 1280, 5200)))
        assertEquals(30, rules.target(tracker(foot = 40).observe(moved, 720, 1280, 5200)))
    }
    @Test fun trunkHasItsOwnAmplitudeAndDoesNotIncludeHandsOrFeet() {
        val trunk = setOf(BodyJoint.LS, BodyJoint.RS, BodyJoint.LH, BodyJoint.RH)
        // Learned trunk dead zone is 4% shoulder width = 8 px; extra full range 20% = 40 px.
        for ((dx, expected) in listOf(8f to 10, 28f to 25, 48f to 40)) {
            val measured = tracker().observe(moved(trunk, dx), 720, 1280, 5200)
            assertEquals(expected, rules.target(measured))
        }
        val limbs = moved(setOf(BodyJoint.LW, BodyJoint.RW, BodyJoint.LA, BodyJoint.RA), 100f)
        assertEquals(0f, tracker().observe(limbs, 720, 1280, 5200).torsoScore, .001f)
    }
    @Test fun newTrunkRangeCannotBypassInvalidSettingsOrRecalibration() {
        val tracker = tracker()
        tracker.motionMapping = MotionMappingSettings(20, 20, 0)
        val result = tracker.observe(moved(setOf(BodyJoint.LH), 60f), 720, 1280, 5200)
        assertEquals(0f, result.torsoScore, .001f)
        assertNull(result.torsoMapping)
        tracker.reset()
        assertEquals(0, rules.target(tracker.observe(body(), 720, 1280, 5300)))
    }
}
