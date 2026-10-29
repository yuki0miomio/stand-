package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class SessionPreparationTest {
    private fun body(tPose: Boolean = false, dx: Float = 0f): FullBody {
        val xy = listOf(200f to 250f, 400f to 250f,
            (if (tPose) 120f to 250f else 180f to 350f),
            (if (tPose) 480f to 250f else 420f to 350f),
            (if (tPose) 40f to 250f else 290f to 400f),
            (if (tPose) 560f to 250f else 310f to 400f),
            230f to 450f, 370f to 450f, 270f to 650f, 330f to 650f, 270f to 850f, 330f to 850f)
        return FullBody(BodyJoint.entries.zip(xy).associate { (joint, p) -> joint to BodyPoint(p.first + dx, p.second, .99f) })
    }
    private fun bow(drop: Float = 30f) = FullBody(body().points + mapOf(
        BodyJoint.LH to BodyPoint(230f, 450f + drop, .99f), BodyJoint.RH to BodyPoint(370f, 450f + drop, .99f),
        BodyJoint.LW to BodyPoint(150f, 460f, .99f), BodyJoint.RW to BodyPoint(450f, 460f, .99f)))
    private class Flow {
        val tracker = StandingTracker()
        val preparation = SessionPreparation().apply { begin() }
        var startedAt: Long? = null
        fun tick(now: Long, body: FullBody?): Pair<StandingMetrics, PreparationEvent?> {
            val metrics = tracker.observe(body, 720, 1280, now, preparation.phase == PreparationPhase.CAPTURE_CURTSEY)
            val event = preparation.update(now, metrics, body)
            when (event) {
                PreparationEvent.POSITION_CONFIRMED, PreparationEvent.POSITION_RESET, PreparationEvent.CURTSEY_RETRY -> tracker.reset()
                PreparationEvent.CURTSEY_CONFIRMED -> tracker.beginCalibration(preparation.curtseyReference, preparation.positionReference)
                PreparationEvent.START_READY -> startedAt = now
                null -> Unit
            }
            return metrics to event
        }
    }
    private fun feed(flow: Flow, from: Long, to: Long, body: FullBody?) =
        (from..to step 100).mapNotNull { flow.tick(it, body).second }
    private fun confirm(flow: Flow) = assertEquals(listOf(PreparationEvent.POSITION_CONFIRMED), feed(flow, 0, 2000, body(tPose = true)))
    private fun teach(flow: Flow, pose: FullBody = bow()) =
        assertEquals(listOf(PreparationEvent.CURTSEY_CONFIRMED), feed(flow, 2100, 5100, pose))

    @Test fun startRequiresOrderedTwoSecondGestureThreeSecondBowThenFiveSecondStanding() {
        val flow = Flow(); confirm(flow)
        assertTrue(feed(flow, 2100, 5000, bow()).isEmpty()); assertNull(flow.startedAt)
        assertEquals(PreparationEvent.CURTSEY_CONFIRMED, flow.tick(5100, bow()).second)
        assertFalse(flow.tracker.calibrated)
        assertTrue(feed(flow, 5200, 10_100, body()).isEmpty()); assertNull(flow.startedAt)
        val (metrics, event) = flow.tick(10_200, body())
        assertEquals(PreparationEvent.START_READY, event); assertTrue(metrics.curtseyCalibrated)
        assertEquals(10_200L, flow.startedAt)
        assertTrue(feed(flow, 10_300, 11_000, body()).isEmpty())
    }
    @Test fun standingCannotSkipEitherThePositionGestureOrTheTaughtBow() {
        val flow = Flow(); assertTrue(feed(flow, 0, 6000, body()).isEmpty())
        assertFalse(flow.tracker.calibrated); assertNull(flow.startedAt)
        val next = Flow(); confirm(next)
        assertTrue(feed(next, 2100, 12_000, body()).isEmpty())
        assertEquals(PreparationPhase.CAPTURE_CURTSEY, next.preparation.phase)
        assertNull(next.preparation.curtseyReference); assertFalse(next.tracker.calibrated)
    }
    @Test fun missingElbowsCannotBlockAnyOfTheThreeStages() {
        val flow = Flow()
        fun hidden(value: FullBody) = FullBody(value.points - setOf(BodyJoint.LE, BodyJoint.RE))
        assertEquals(listOf(PreparationEvent.POSITION_CONFIRMED), feed(flow, 0, 2000, hidden(body(true))))
        assertEquals(listOf(PreparationEvent.CURTSEY_CONFIRMED), feed(flow, 2100, 5100, hidden(bow())))
        assertTrue(feed(flow, 5200, 10_100, hidden(body())).isEmpty())
        assertEquals(PreparationEvent.START_READY, flow.tick(10_200, hidden(body())).second)
    }
    @Test fun naturallyBentArmsAndModeratelyLowerWristsCanConfirmPosition() {
        val flow = Flow()
        val relaxed = FullBody(body(true).points + mapOf(
            BodyJoint.LE to BodyPoint(140f, 310f, .99f), BodyJoint.RE to BodyPoint(460f, 310f, .99f),
            BodyJoint.LW to BodyPoint(80f, 315f, .99f), BodyJoint.RW to BodyPoint(520f, 315f, .99f)))
        assertEquals(listOf(PreparationEvent.POSITION_CONFIRMED), feed(flow, 0, 2000, relaxed))
        assertEquals(PreparationPhase.CAPTURE_CURTSEY, flow.preparation.phase); assertNull(flow.startedAt)
    }
    @Test fun actualHandRestAndProjectedKneesAreStillLearnedAfterTheBow() {
        val flow = Flow(); confirm(flow); teach(flow)
        val neutral = FullBody(body().points + mapOf(
            BodyJoint.LW to BodyPoint(260f, 500f, .99f), BodyJoint.RW to BodyPoint(340f, 500f, .99f),
            BodyJoint.LK to BodyPoint(290f, 650f, .99f), BodyJoint.RK to BodyPoint(310f, 650f, .99f)))
        assertTrue(feed(flow, 5200, 10_100, neutral).isEmpty())
        assertEquals(PreparationEvent.START_READY, flow.tick(10_200, neutral).second)
        assertEquals(500f, flow.tracker.observe(neutral, 720, 1280, 10_300).tracking!!.handTarget.y, .001f)
    }
    @Test fun closingFeetAfterConfirmationAndVerticalHipTravelDoNotResetPosition() {
        for (drop in listOf(30f, 60f)) {
            val flow = Flow(); val points = body(true).points.toMutableMap()
            for (j in listOf(BodyJoint.LK, BodyJoint.LA)) points[j] = points.getValue(j).copy(x = 230f)
            for (j in listOf(BodyJoint.RK, BodyJoint.RA)) points[j] = points.getValue(j).copy(x = 370f)
            assertEquals(listOf(PreparationEvent.POSITION_CONFIRMED), feed(flow, 0, 2000, FullBody(points)))
            teach(flow, bow(drop))
            assertTrue(feed(flow, 5200, 10_100, body()).isEmpty())
            assertEquals(PreparationEvent.START_READY, flow.tick(10_200, body()).second)
        }
    }
    @Test fun continuingToHoldTheBowCannotBeRecordedAsTheStandingPose() {
        val flow = Flow(); confirm(flow); teach(flow)
        assertTrue(feed(flow, 5200, 12_000, bow()).isEmpty())
        assertFalse(flow.tracker.calibrated); assertNull(flow.startedAt)
        assertTrue(feed(flow, 12_100, 17_000, body()).isEmpty())
        assertEquals(PreparationEvent.START_READY, flow.tick(17_100, body()).second)
    }
    @Test fun movingWhileHoldingArmsRestartsTheTwoSecondConfirmation() {
        val flow = Flow(); feed(flow, 0, 1000, body(true))
        assertTrue(feed(flow, 1100, 3000, body(true, 60f)).isEmpty())
        assertEquals(PreparationEvent.POSITION_CONFIRMED, flow.tick(3100, body(true, 60f)).second)
    }
    @Test fun horizontalDepartureAfterConfirmationRequiresANewGesture() {
        val flow = Flow(); confirm(flow)
        assertTrue(feed(flow, 2100, 2600, body(dx = 100f)).isEmpty())
        assertEquals(PreparationEvent.POSITION_RESET, flow.tick(2700, body(dx = 100f)).second)
        assertNull(flow.preparation.curtseyReference); assertNull(flow.preparation.positionReference)
        assertTrue(feed(flow, 2800, 9000, body(dx = 100f)).isEmpty()); assertNull(flow.startedAt)
    }
    @Test fun lossAfterTeachingDiscardsBothReferencesAndRequiresTheWholeSequenceAgain() {
        val flow = Flow(); confirm(flow); teach(flow)
        assertTrue(feed(flow, 5200, 6000, null).isEmpty())
        assertEquals(PreparationEvent.POSITION_RESET, flow.tick(6100, null).second)
        assertNull(flow.preparation.curtseyReference); assertFalse(flow.tracker.calibrated)
        assertEquals(listOf(PreparationEvent.POSITION_CONFIRMED), feed(flow, 6200, 8200, body(true)))
        assertEquals(listOf(PreparationEvent.CURTSEY_CONFIRMED), feed(flow, 8300, 11_300, bow()))
        assertTrue(feed(flow, 11_400, 16_300, body()).isEmpty())
        assertEquals(PreparationEvent.START_READY, flow.tick(16_400, body()).second)
    }
    @Test fun bowMovementRestartsTheThreeSecondCaptureWithoutMixingTheOldPose() {
        val flow = Flow(); confirm(flow); feed(flow, 2100, 4000, bow())
        assertTrue(feed(flow, 4100, 7400, bow(60f)).isEmpty())
        assertEquals(PreparationEvent.CURTSEY_CONFIRMED, flow.tick(7500, bow(60f)).second)
        assertEquals(510f, flow.preparation.curtseyReference!![BodyJoint.LH].y, .001f)
    }
    @Test fun insufficientDifferenceFromTheFinalStandingPoseRequiresANewBowInsteadOfStarting() {
        val flow = Flow(); confirm(flow); teach(flow, bow(12f))
        val almostSame = FullBody(body().points + mapOf(
            BodyJoint.LH to BodyPoint(230f, 454f, .99f), BodyJoint.RH to BodyPoint(370f, 454f, .99f)))
        assertTrue(feed(flow, 5200, 10_100, almostSame).isEmpty())
        assertEquals(PreparationEvent.CURTSEY_RETRY, flow.tick(10_200, almostSame).second)
        assertEquals(PreparationPhase.CAPTURE_CURTSEY, flow.preparation.phase)
        assertNull(flow.preparation.curtseyReference); assertNull(flow.startedAt)
    }
    @Test fun frameGapsAndCancellationCannotBypassTheOrderedStart() {
        val flow = Flow(); feed(flow, 0, 1900, body(true))
        flow.tick(2500, body(true)); assertEquals(0f, flow.preparation.progress, .001f)
        feed(flow, 2600, 4500, body(true))
        assertEquals(PreparationPhase.CAPTURE_CURTSEY, flow.preparation.phase)
        assertNull(flow.tick(5100, bow()).second)
        assertEquals(PreparationPhase.CAPTURE_CURTSEY, flow.preparation.phase)
        assertEquals(0f, flow.preparation.progress, .001f)
        flow.preparation.cancel(); assertTrue(feed(flow, 5200, 9000, body(true)).isEmpty())
        assertNull(flow.startedAt)
    }

    @Test fun personalNarrowAndLowSkirtHandsCanCompleteAllThreeStages() {
        for (wrists in listOf(285f to 315f, 270f to 335f)) {
            val flow = Flow(); confirm(flow)
            val personal = FullBody(bow().points + mapOf(
                BodyJoint.LW to BodyPoint(wrists.first, 620f, .99f),
                BodyJoint.RW to BodyPoint(wrists.second, 620f, .99f)))
            teach(flow, personal)
            assertTrue(feed(flow, 5200, 10_100, body()).isEmpty())
            assertEquals(PreparationEvent.START_READY, flow.tick(10_200, body()).second)
            assertTrue(flow.tracker.observe(personal, 720, 1280, 10_300).curtsey)
        }
    }
    @Test fun briefPoseWobblePausesAndDoesNotCountAsThreeValidSeconds() {
        val flow = Flow(); confirm(flow); feed(flow, 2100, 3500, bow())
        val progress = flow.preparation.progress
        assertNull(flow.tick(3600, body()).second)
        assertEquals(progress, flow.preparation.progress, .001f)
        assertTrue(flow.preparation.captureMessage.contains("屈膝"))
        assertTrue(feed(flow, 3700, 5200, bow()).isEmpty())
        assertEquals(PreparationEvent.CURTSEY_CONFIRMED, flow.tick(5300, bow()).second)
    }
    @Test fun wristJitterOutsideTheFormerToleranceDoesNotEraseTheCapture() {
        val flow = Flow(); confirm(flow)
        val jitter = FullBody(bow().points + (BodyJoint.LW to bow()[BodyJoint.LW].copy(x = 180f)))
        for (t in 2100L..5000 step 100) {
            assertNull(flow.tick(t, if (t % 200 == 0L) jitter else bow()).second)
        }
        assertEquals(PreparationEvent.CURTSEY_CONFIRMED, flow.tick(5100, bow()).second)
    }
    @Test fun shortOcclusionPreservesPositionAndProgressButNeverCountsMissingFrames() {
        val flow = Flow(); confirm(flow); feed(flow, 2100, 3500, bow())
        val hidden = FullBody(bow().points - BodyJoint.LW)
        val progress = flow.preparation.progress
        assertNull(flow.tick(3600, hidden).second); assertNull(flow.tick(3700, hidden).second)
        assertEquals(PreparationPhase.CAPTURE_CURTSEY, flow.preparation.phase)
        assertEquals(progress, flow.preparation.progress, .001f)
        assertTrue(flow.preparation.captureMessage.contains("左腕"))
        assertTrue(feed(flow, 3800, 5300, bow()).isEmpty())
        assertEquals(PreparationEvent.CURTSEY_CONFIRMED, flow.tick(5400, bow()).second)
        assertNull(flow.startedAt)
    }
    @Test fun extendedWristOcclusionRestartsCaptureWithoutLosingConfirmedFloorPosition() {
        val flow = Flow(); confirm(flow); feed(flow, 2100, 3500, bow())
        assertTrue(feed(flow, 3600, 6700, FullBody(bow().points - BodyJoint.LW)).isEmpty())
        assertEquals(PreparationPhase.CAPTURE_CURTSEY, flow.preparation.phase)
        assertEquals(0f, flow.preparation.progress, .001f)
        assertTrue(feed(flow, 6800, 9700, bow()).isEmpty())
        assertEquals(PreparationEvent.CURTSEY_CONFIRMED, flow.tick(9800, bow()).second)
    }

    @Test fun clothingHidingBothKneesDoesNotPauseOrResetCoreCapture() {
        val flow = Flow(); confirm(flow); feed(flow, 2100, 3500, bow())
        val hidden = FullBody(bow().points - setOf(BodyJoint.LK, BodyJoint.RK, BodyJoint.LA, BodyJoint.RA))
        assertTrue(feed(flow, 3600, 5000, hidden).isEmpty())
        assertEquals(PreparationPhase.CAPTURE_CURTSEY, flow.preparation.phase)
        assertTrue(flow.preparation.progress > .9f)
        assertEquals(PreparationEvent.CURTSEY_CONFIRMED, flow.tick(5100, hidden).second)
        assertNull(flow.startedAt)
    }

    @Test fun lowConfidenceKneesDoNotBlockStableHandsAndHipCapture() {
        val flow = Flow(); confirm(flow)
        val uncertain = FullBody(bow().points.mapValues { (joint, point) ->
            if (joint in setOf(BodyJoint.LK, BodyJoint.RK)) point.copy(confidence = .2f) else point
        })
        assertTrue(feed(flow, 2100, 5000, uncertain).isEmpty())
        assertEquals(PreparationEvent.CURTSEY_CONFIRMED, flow.tick(5100, uncertain).second)
        assertEquals(480f, flow.preparation.curtseyReference!![BodyJoint.LH].y, .001f)
        assertNull(flow.startedAt)
    }

    @Test fun shortWholePoseLossKeepsTheStageButFiveSecondsOfLossRequiresRepositioning() {
        val flow = Flow(); confirm(flow); feed(flow, 2100, 3500, bow())
        val progress = flow.preparation.progress
        assertTrue(feed(flow, 3600, 5900, null).isEmpty())
        assertEquals(progress, flow.preparation.progress, .001f)
        assertEquals(PreparationPhase.CAPTURE_CURTSEY, flow.preparation.phase)
        assertNull(flow.tick(6000, bow()).second)
        assertEquals(progress, flow.preparation.progress, .001f)
        assertTrue(feed(flow, 6100, 10_900, null).isEmpty())
        assertEquals(PreparationEvent.POSITION_RESET, flow.tick(11_000, null).second)
        assertNull(flow.preparation.positionReference); assertNull(flow.startedAt)
    }

    @Test fun theFinalStableSecondDefinesTheTemplateAndIsUsedByTheGameTracker() {
        val flow = Flow(); confirm(flow)
        assertTrue(feed(flow, 2100, 3900, bow(30f)).isEmpty())
        assertTrue(feed(flow, 4000, 5000, bow(48f)).isEmpty())
        assertEquals(PreparationEvent.CURTSEY_CONFIRMED, flow.tick(5100, bow(48f)).second)
        assertEquals(498f, flow.preparation.curtseyReference!![BodyJoint.LH].y, .001f)
        assertTrue(feed(flow, 5200, 10_100, body()).isEmpty())
        assertEquals(PreparationEvent.START_READY, flow.tick(10_200, body()).second)
        assertTrue(flow.tracker.observe(bow(48f), 720, 1280, 10_300).curtsey)
    }

    @Test fun aShortBadFloorEstimateDoesNotDiscardTheConfirmedLocation() {
        val flow = Flow(); confirm(flow); feed(flow, 2100, 3500, bow())
        assertTrue(feed(flow, 3600, 3900, body(dx = 100f)).isEmpty())
        assertEquals(PreparationPhase.CAPTURE_CURTSEY, flow.preparation.phase)
        assertNull(flow.tick(4000, bow()).second)
        assertNotNull(flow.preparation.positionReference)
        assertNull(flow.startedAt)
    }

    @Test fun aCameraGapWithinTheGracePeriodNeverCountsAsHeldTime() {
        val flow = Flow(); confirm(flow); feed(flow, 2100, 3500, bow())
        val progress = flow.preparation.progress
        assertNull(flow.tick(5800, bow()).second)
        assertEquals(progress, flow.preparation.progress, .001f)
        assertTrue(feed(flow, 5900, 7300, bow()).isEmpty())
        assertEquals(PreparationEvent.CURTSEY_CONFIRMED, flow.tick(7400, bow()).second)
    }
}
