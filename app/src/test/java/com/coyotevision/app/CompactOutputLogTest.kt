package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class CompactOutputLogTest {
    private fun sample(at: Long, strength: Int, hands: Float = 0f, feet: Float = 0f,
        base: Int = 0, context: String = "normal", handWeight: Int = 20, footWeight: Int = 20) =
        CompactOutputLog.Sample(at, strength, base, hands, feet, handWeight, footWeight, context)

    @Test fun constantOutputForNinetyMinutesHasOneEntry() {
        val log = CompactOutputLog()
        var count = 0
        for (at in 0L..5_400_000L step 500) if (log.update(sample(at, 10, .5f)) != null) count++
        assertEquals(1, count)
    }

    @Test fun microChangesAreCoalescedAndEventuallyRecorded() {
        val log = CompactOutputLog()
        assertNotNull(log.update(sample(0, 10)))
        for (at in 500L..9500L step 500) assertNull(log.update(sample(at, 11, .05f)))
        assertNotNull(log.update(sample(10_000, 11, .05f)))
        assertNull(log.update(sample(20_000, 11, .05f)))
    }

    @Test fun handAndFootChangesAreVisibleWithoutIntensityRise() {
        val log = CompactOutputLog()
        log.update(sample(0, 20))
        assertNotNull(log.update(sample(2000, 20, .4f)))
        val change = log.update(sample(4000, 20, .4f, .6f))!!
        assertFalse(change.sharp)
    }

    @Test fun sharpHandRiseAlertsOnceWhileHeld() {
        val log = CompactOutputLog()
        log.update(sample(0, 5))
        val change = log.update(sample(500, 15, .8f))!!
        assertTrue(change.sharp)
        assertEquals("手部", change.source)
        assertEquals(5, change.from)
        for (at in 1000L..5000L step 500) assertFalse(log.update(sample(at, 25, .8f))?.sharp ?: false)
    }

    @Test fun footAlertRearmsAfterMovementAndOutputFall() {
        val log = CompactOutputLog()
        log.update(sample(0, 0))
        assertTrue(log.update(sample(500, 12, feet = .9f))!!.sharp)
        log.update(sample(3000, 0))
        assertTrue(log.update(sample(3500, 14, feet = .9f))!!.sharp)
    }

    @Test fun highScoresWithoutEmittedRiseDoNotAlert() {
        val log = CompactOutputLog()
        log.update(sample(0, 5))
        for (at in 500L..10_000L step 500) assertFalse(log.update(sample(at, 5, 1f, 1f))?.sharp ?: false)
    }

    @Test fun unweightedHandsAndFeetCannotClaimBodyRise() {
        val log = CompactOutputLog()
        log.update(sample(0, 0))
        val change = log.update(sample(2000, 20, 1f, 1f, handWeight = 0, footWeight = 0))!!
        assertFalse(change.sharp)
    }

    @Test fun trainingBaseRampAndPhaseTransitionsDoNotLookLikeMotionAlerts() {
        val log = CompactOutputLog()
        log.update(sample(0, 0))
        assertFalse(log.update(sample(500, 30, 1f, base = 30, context = "training"))!!.sharp)
        assertNull(log.update(sample(1000, 30, 1f, base = 30, context = "training")))
        assertFalse(log.update(sample(1500, 60, context = "penalty"))!!.sharp)
    }

    @Test fun actualMotionExtraDuringTrainingCanAlert() {
        val log = CompactOutputLog()
        log.update(sample(0, 30, base = 30, context = "training"))
        assertTrue(log.update(sample(1000, 40, .9f, base = 30, context = "training"))!!.sharp)
    }

    @Test fun staleOutputAndResetDoNotMakeFalseJumps() {
        val log = CompactOutputLog()
        log.update(sample(0, 0))
        assertFalse(log.update(sample(3000, 20, 1f))!!.sharp)
        log.reset()
        assertFalse(log.update(sample(3500, 40, 1f))!!.sharp)
    }

    @Test fun zeroIsRecordedImmediatelyAndNotRepeated() {
        val log = CompactOutputLog()
        log.update(sample(0, 20))
        assertNotNull(log.update(sample(100, 0)))
        assertNull(log.update(sample(200, 0)))
    }

    @Test fun contextAndChannelChangesAreRecordedEvenAtSameStrength() {
        val log = CompactOutputLog()
        val initial = sample(0, 15)
        log.update(initial)
        assertNotNull(log.update(initial.copy(at = 500, context = "curtsey")))
        assertNotNull(log.update(initial.copy(at = 1000, context = "curtsey", channel = "B")))
    }

    @Test fun minuteGroupsAndImportantErrorsKeepTimesWithoutPerRefreshStamps() {
        val log = SessionLog(3)
        log.add("开始", 120_000)
        log.add("手 20%", 120_500)
        log.add("脚 40%", 121_000)
        assertEquals("脚 40%", log.entries.first())
        log.add("错误：归零任务未完成", 121_500, important = true)
        assertTrue(log.entries.first().endsWith("  错误：归零任务未完成"))
        assertEquals(3, log.entries.size)
        log.add("强度 10", 180_000)
        assertTrue(log.entries.first().endsWith("  强度 10"))
        log.clear()
        assertTrue(log.entries.isEmpty())
        log.add("新一轮", 180_500)
        assertNotEquals("新一轮", log.entries.first())
    }

    @Test fun mirrorModeRequiresCalibrationAndPauseExitPersistsUntilNextCalibration() {
        val mirror = MirrorMode()
        assertFalse(mirror.active)
        mirror.calibrated(false)
        assertFalse(mirror.active)
        mirror.calibrated(true)
        assertTrue(mirror.active)
        mirror.exit() // full loss, partial-measurement suspension, tap, stop or background
        assertFalse(mirror.active)
        mirror.exit()
        assertFalse(mirror.active)
        mirror.calibrated(true)
        assertTrue(mirror.active)
    }

    @Test fun conciseDeviceProblemsExcludeOrdinaryStrengthTelemetry() {
        fun report(props: JSONObject, state: JSONObject = JSONObject()) = DeviceOutputReport.parse(JSONObject()
            .put("device", JSONObject().put("slotId", "one").put("type", "COYOTE_030")
                .put("props", props).put("slotState", state)))!!
        assertEquals("", report(JSONObject().put("intensityA", 30).put("channelAStatus", 2)).outputProblems())
        assertEquals("A 静音、A 未形成回路、B 输出损坏", report(JSONObject().put("channelAStatus", 1)
            .put("channelBStatus", 3), JSONObject().put("channelA", JSONObject().put("isMuted", true))).outputProblems())
    }
}
