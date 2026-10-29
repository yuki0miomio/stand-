package com.coyotevision.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V4DeliveryTest {
    private var now = 1000L
    private val packets = mutableListOf<JSONObject>()
    private val reports = mutableListOf<JSONObject>()
    private val failures = mutableListOf<String>()
    private val bridge = V4AppPeer({ packets.add(it) }, {}, { failures.add(it) }, telemetry = { reports.add(it) }, clock = { now })
    private fun connect() {
        bridge.receive(JSONObject().put("t", "ev").put("ev", "devices.snapshot").put("devices", JSONArray().put(
            JSONObject().put("slotId", "slot").put("type", "COYOTE_030")
                .put("props", JSONObject().put("intensityA", 0).put("channelAStatus", 1))
                .put("slotState", JSONObject().put("hasDevice", true).put("channelA",
                    JSONObject().put("isMuted", true).put("intensityMax", 12))))))
    }
    private fun request(index: Int) = packets[index].getJSONObject("data")
    private fun ack(index: Int, error: String? = null) {
        val response = JSONObject().put("t", "resp").put("reqId", request(index).getString("reqId"))
        if (error == null) response.put("result", JSONObject().put("reason", "completed")) else response.put("error", error)
        bridge.receive(response)
    }
    private fun pulse(channel: String = "A", shape: PulseShape = PulseShape.RAMP) {
        bridge.forward("pulse-$channel:" + JSONArray(EffectPreset(shape = shape).pulseFrames()))
    }
    @Test fun clearMustFinishBeforeZeroIsSentAndBothMustFinishBeforeNewOutput() {
        connect()
        bridge.forward("clear-1"); bridge.forward("strength-1+2+0")
        bridge.forward("strength-1+2+18"); pulse()
        assertEquals(1, packets.size) // No zero may be queued for the clear to cancel.
        now += 60; ack(0)
        assertEquals(2, packets.size)
        assertEquals(7, request(1).getJSONObject("data").getInt("t"))
        now += 60; ack(1)
        assertEquals(4, packets.size)
        val intensity = request(2).getJSONObject("data")
        val frames = request(3).getJSONObject("data")
        assertEquals(18, intensity.getInt("v")); assertEquals(4, intensity.getInt("t"))
        assertEquals(880, intensity.getInt("d")); assertEquals(880, frames.getInt("d"))
        assertEquals(0, frames.getInt("t")); assertEquals(3, frames.getInt("ver"))
        assertTrue(failures.isEmpty())
    }
    @Test fun repeatedClearCoalescesAndStopRemovesTheWaitingPulse() {
        connect(); bridge.forward("clear-1"); bridge.forward("strength-1+2+20"); pulse()
        bridge.forward("clear-1"); bridge.forward("strength-1+2+0")
        assertEquals(1, packets.size)
        ack(0); ack(1)
        assertEquals(2, packets.size)
        assertEquals(0, reports.last().getInt("queuedPulses"))
    }
    @Test fun delayedClearCannotReplayAnExpiredPulseLease() {
        connect(); bridge.forward("clear-1"); bridge.forward("strength-1+2+20"); pulse()
        now += 1001; ack(0)
        assertEquals(1, packets.size)
        pulse(); assertEquals(3, packets.size) // Only a fresh explicit request may start output.
    }
    @Test fun latestWaitingPulseWinsAndItsRemainingLeaseIsNotExtended() {
        connect(); bridge.forward("clear-1"); bridge.forward("strength-1+2+10"); pulse()
        now += 500; bridge.forward("strength-1+2+15"); pulse(shape = PulseShape.STEADY)
        now += 200; ack(0)
        assertEquals(15, request(1).getJSONObject("data").getInt("v"))
        val pulse = request(2).getJSONObject("data")
        assertEquals(800, pulse.getInt("d"))
        assertEquals(PulseShape.STEADY.frame, pulse.getJSONArray("v").getString(0))
    }
    @Test fun channelBCanRunWhileChannelACleanupWaitsForConfirmation() {
        connect(); bridge.forward("clear-1"); bridge.forward("strength-1+2+18"); pulse()
        bridge.forward("strength-2+2+19"); pulse("B")
        assertEquals(3, packets.size)
        assertEquals(1, request(1).getJSONObject("data").getInt("c"))
        ack(0); assertEquals(5, packets.size)
        assertEquals(0, request(3).getJSONObject("data").getInt("c"))
    }
    @Test fun rejectedCleanupStopsInsteadOfSendingTheQueuedOutput() {
        connect(); bridge.forward("clear-1"); bridge.forward("strength-1+2+20"); pulse()
        ack(0, "invalid_params")
        assertEquals(1, failures.size); assertEquals(1, packets.size)
        pulse(); assertEquals(1, packets.size)
    }
    @Test fun partialAppUpdatesPreserveMuteAndLimitAndMissingValuesStayUnknown() {
        connect()
        bridge.receive(JSONObject().put("t", "ev").put("ev", "slots.patch").put("slots", JSONArray().put(
            JSONObject().put("slotId", "slot").put("slotState", JSONObject().put("channelA", JSONObject().put("warmUpScale", .8))))))
        val report = DeviceOutputReport.parse(reports.last())!!
        assertTrue(report.details().contains("A 静音：是 · App 上限 12.0"))
        assertTrue(report.summary().contains("B 未上报"))
        assertTrue(report.details().contains("未形成回路"))
    }
    @Test fun successfulRpcDoesNotFabricateADeviceIntensityReading() {
        connect(); bridge.forward("strength-1+2+18"); pulse(); ack(0)
        val report = DeviceOutputReport.parse(reports.last())!!
        assertTrue(report.summary().contains("A 0.0"))
        assertTrue(report.rpc.contains("completed"))
        assertFalse(report.summary().contains("18"))
    }
    @Test fun stateEnvelopeRequiresOurControllerIdentityAndValidSupportedDevice() {
        connect()
        val envelope = JSONObject().put("type", "device-state").put("clientId", "us").put("targetId", "app").put("data", reports.last())
        assertTrue(V3Protocol.parse(envelope.toString(), "us") is RelayEvent.DeviceState)
        envelope.put("clientId", "someone-else")
        assertEquals(RelayEvent.Ignored, V3Protocol.parse(envelope.toString(), "us"))
        assertNull(DeviceOutputReport.parse(JSONObject()))
    }
    @Test fun nullErrorFieldOnASuccessfulClearDoesNotRejectTheCommand() {
        connect(); bridge.forward("clear-1"); bridge.forward("strength-1+2+18"); pulse()
        bridge.receive(JSONObject().put("t", "resp").put("reqId", request(0).getString("reqId"))
            .put("result", JSONObject()).put("error", JSONObject.NULL))
        assertTrue(failures.isEmpty()); assertEquals(3, packets.size)
    }
    @Test fun cancelledZeroTaskDoesNotReleaseWaitingPositiveOutput() {
        connect(); bridge.forward("strength-1+2+0"); bridge.forward("strength-1+2+18"); pulse()
        bridge.receive(JSONObject().put("t", "resp").put("reqId", request(0).getString("reqId"))
            .put("result", JSONObject().put("reason", "cancelled")))
        assertTrue(failures.isEmpty()); assertEquals(2, packets.size)
        assertEquals(7, request(1).getJSONObject("data").getInt("t"))
        ack(1)
        assertEquals(4, packets.size)
        assertEquals(18, request(2).getJSONObject("data").getInt("v"))
    }
    @Test fun veryLateClearCannotOverflowAnExpiredLeaseBackToAPositiveDuration() {
        connect(); bridge.forward("clear-1"); bridge.forward("strength-1+2+18"); pulse()
        now += 4_294_968_000L; ack(0)
        assertEquals(1, packets.size)
    }
    @Test fun clearArrivingDuringZeroWaitsInsteadOfCancellingTheZero() {
        connect(); bridge.forward("strength-1+2+0"); bridge.forward("clear-1")
        bridge.forward("strength-1+2+18"); pulse()
        assertEquals(1, packets.size)
        ack(0); assertEquals(2, packets.size)
        assertEquals("device.op.clear", request(1).getString("m"))
        ack(1); assertEquals(4, packets.size)
        assertTrue(failures.isEmpty())
    }
    @Test fun repeatedlyCancelledZeroHasABoundedRetryAndCannotStartOutput() {
        connect(); bridge.forward("strength-1+2+0"); bridge.forward("strength-1+2+18"); pulse()
        for (index in 0..2) bridge.receive(JSONObject().put("t", "resp").put("reqId", request(index).getString("reqId"))
            .put("result", JSONObject().put("reason", "cleared")))
        assertEquals(1, failures.size); assertEquals(3, packets.size)
        assertTrue(packets.all { it.getJSONObject("data").getJSONObject("data").getInt("t") == 7 })
    }
    @Test fun cancelledZeroRetriesDoNotRestartTheCleanupTimeout() {
        connect(); bridge.forward("strength-1+2+0")
        now += 4900
        bridge.receive(JSONObject().put("t", "resp").put("reqId", request(0).getString("reqId"))
            .put("result", JSONObject().put("reason", "replaced")))
        now += 101; bridge.checkTimeout(now)
        assertEquals(1, failures.size)
    }
    @Test fun confirmedClearRetiresOnlyTheOlderChannelTasksWhoseTerminalRepliesAreMissing() {
        connect(); bridge.forward("strength-1+2+18"); pulse()
        bridge.forward("clear-1"); ack(2)
        bridge.checkTimeout(now + 6000)
        assertTrue(failures.isEmpty())
    }
    @Test fun unconfirmedClearCannotHideMissingTaskResponses() {
        connect(); bridge.forward("strength-1+2+18"); pulse()
        bridge.forward("clear-1"); bridge.checkTimeout(now + 6000)
        assertEquals(1, failures.size)
    }
    @Test fun clearingADoesNotRetireUnconfirmedTasksOnB() {
        connect(); bridge.forward("strength-1+2+18"); pulse()
        bridge.forward("strength-2+2+19"); pulse("B")
        bridge.forward("clear-1"); ack(4)
        bridge.checkTimeout(now + 6000)
        assertEquals(1, failures.size)
    }
    @Test fun closingTransportFencesQueuedOutputButStillAllowsBestEffortClearAndZero() {
        connect(); bridge.forward("clear-1"); bridge.forward("strength-1+2+18"); pulse()
        bridge.closeOutput()
        bridge.forward("clear-1"); bridge.forward("strength-1+2+0")
        assertEquals(3, packets.size)
        assertEquals(7, request(2).getJSONObject("data").getInt("t"))
        ack(0); pulse()
        assertEquals(3, packets.size)
    }
}
