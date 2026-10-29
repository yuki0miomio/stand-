package com.coyotevision.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class V4Test {
    private val packets = mutableListOf<JSONObject>()
    private val ready = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private fun bridge() = V4AppPeer({ packets.add(it) }, { ready.add(it) }, { errors.add(it) }, clock = { 1000L })
    private fun device(id: String = "coyote-a", connected: Boolean = true, type: String = "COYOTE_030") =
        JSONObject().put("slotId", id).put("name", "My Coyote").put("type", type)
            .put("slotState", JSONObject().put("hasDevice", connected))
    private fun snapshot(vararg devices: JSONObject) = JSONObject().put("t", "ev").put("ev", "devices.snapshot")
        .put("devices", JSONArray(devices.toList()))
    @Test fun officialV4QrUsesEncodedUrlAndTidQuery() {
        val link = V4AppPeer.pairingLink("ws://192.168.1.2:9999", "my-controller")
        assertTrue(link.startsWith("https://dungeon-lab.cn/s/?v=1&action=socket&url="))
        val decoded = URLDecoder.decode(URI(link).rawQuery.substringAfter("url="), "UTF-8")
        assertEquals("ws://192.168.1.2:9999?tid=my-controller", decoded)
    }
    @Test fun pairingWaitsForExactlyOneConnectedSupportedCoyote() {
        val bridge = bridge()
        bridge.receive(snapshot()); assertEquals("", bridge.slot)
        bridge.receive(snapshot(device(connected = false))); assertTrue(ready.isEmpty())
        bridge.receive(snapshot(device(type = "OVC_1"))); assertTrue(ready.isEmpty())
        bridge.receive(snapshot(device(), device("second"))); assertTrue(ready.isEmpty())
        bridge.receive(snapshot(device())); assertEquals("coyote-a", bridge.slot); assertEquals(1, ready.size)
        assertTrue(packets.isEmpty()) // Discovery never starts a waveform or adjusts intensity.
    }
    @Test fun coyoteTwoDiscoveryResponseReportsModelAndDoesNotStartOutput() {
        val bridge = bridge(); bridge.requestDevices()
        val request = packets.single().getJSONObject("data")
        bridge.receive(JSONObject().put("t", "resp").put("reqId", request.getString("reqId"))
            .put("result", JSONObject().put("devices", JSONArray().put(device(type = "COYOTE_020").put("name", "")))))
        assertEquals("coyote-a", bridge.slot)
        assertEquals("COYOTE_020", bridge.deviceType)
        assertEquals(listOf("郊狼 2.0"), ready)
        assertEquals(1, packets.size) // Only devices.get; no output on discovery.
        assertTrue(errors.isEmpty())
    }
    @Test fun mixedCoyoteGenerationsMustNotPairUntilOnlyOneIsConnected() {
        val bridge = bridge()
        bridge.receive(snapshot(device(type = "COYOTE_020"), device("three")))
        assertTrue(ready.isEmpty()); assertTrue(packets.isEmpty())
        bridge.receive(snapshot(device(type = "COYOTE_020"), device("three", connected = false)))
        assertEquals("COYOTE_020", bridge.deviceType)
        assertTrue(ready.single().startsWith("郊狼 2.0"))
    }
    @Test fun blankSlotsAndDisconnectedCoyoteTwoCannotPair() {
        val bridge = bridge()
        bridge.receive(snapshot(device(type = "COYOTE_020", connected = false)))
        bridge.receive(snapshot(device(id = "", type = "COYOTE_020")))
        assertTrue(ready.isEmpty()); assertTrue(errors.isEmpty()); assertTrue(packets.isEmpty())
        bridge.receive(snapshot(device().removeName()))
        assertEquals(listOf("郊狼 3.0"), ready)
    }
    private fun JSONObject.removeName() = apply { remove("name") }

    @Test fun coyoteTwoCanAppearAndDisconnectThroughDevicePatches() {
        val bridge = bridge(); bridge.receive(snapshot())
        bridge.receive(JSONObject().put("t", "ev").put("ev", "devices.patch")
            .put("added", JSONArray().put(device(type = "COYOTE_020"))))
        assertEquals("COYOTE_020", bridge.deviceType)
        bridge.receive(JSONObject().put("t", "ev").put("ev", "slots.patch").put("slots", JSONArray().put(
            JSONObject().put("slotId", "coyote-a").put("props", JSONObject().put("power", 80)))))
        assertTrue(errors.isEmpty())
        bridge.receive(JSONObject().put("t", "ev").put("ev", "devices.patch")
            .put("removed", JSONArray().put("coyote-a")))
        assertEquals(1, errors.size)
    }
    @Test fun modelReplacementCannotReuseTheSelectedSlotOrSubmitFurtherPositiveOutput() {
        val bridge = bridge(); bridge.receive(snapshot(device(type = "COYOTE_020")))
        bridge.receive(snapshot(device()))
        assertEquals(1, errors.size)
        bridge.forward("strength-1+2+20")
        bridge.forward("pulse-A:" + JSONArray(List(10) { PulseShape.RAMP.frame }))
        assertTrue(packets.isEmpty())
        // Failure cleanup must still be able to clear and zero the selected slot.
        bridge.forward("clear-1"); bridge.forward("strength-1+2+0")
        assertEquals("device.op.clear", packets[0].getJSONObject("data").getString("m"))
        assertEquals(0, packets[1].getJSONObject("data").getJSONObject("data").getInt("v"))
    }
    @Test fun addingAnotherGenerationDuringCoyoteTwoSessionInvalidatesPairing() {
        val bridge = bridge(); bridge.receive(snapshot(device(type = "COYOTE_020")))
        bridge.receive(snapshot(device(type = "COYOTE_020"), device("three")))
        assertEquals(1, errors.size)
        assertEquals(1, ready.size)
    }
    @Test fun coyoteTwoPreservesEveryWaveformInBothChannelsWithExplicitEncodingAndNoIntensityScaling() {
        val bridge = bridge(); bridge.receive(snapshot(device(type = "COYOTE_020")))
        for ((index, channel) in listOf("A", "B").withIndex()) {
            bridge.forward("strength-${index + 1}+2+18")
            for (shape in PulseShape.entries) {
                val frames = JSONArray(EffectPreset(channel, 18, shape = shape).pulseFrames())
                val start = packets.size
                bridge.forward("pulse-$channel:$frames")
                val intensity = packets[start].getJSONObject("data").getJSONObject("data")
                val pulse = packets[start + 1].getJSONObject("data").getJSONObject("data")
                assertEquals(18, intensity.getInt("v"))
                assertEquals(4, intensity.getInt("t")); assertEquals(1000, intensity.getInt("d"))
                assertEquals(index, intensity.getInt("c")); assertEquals(index, pulse.getInt("c"))
                assertEquals("coyote-a", pulse.getString("s"))
                assertEquals(1000, pulse.getInt("d")); assertEquals(3, pulse.getInt("ver"))
                assertEquals(frames.toString(), pulse.getJSONArray("v").toString())
            }
        }
        assertEquals(packets.size, packets.map { it.getJSONObject("data").getString("reqId") }.toSet().size)
        assertTrue(errors.isEmpty())
    }
    @Test fun deviceRemovalOrBluetoothDisconnectInvalidatesTheSession() {
        for ((index, type) in listOf("COYOTE_020", "COYOTE_030").withIndex()) {
            val bridge = bridge(); bridge.receive(snapshot(device(type = type)))
            bridge.receive(JSONObject().put("t", "ev").put("ev", "slots.patch").put("slots", JSONArray().put(
                JSONObject().put("slotId", "coyote-a").put("slotState", JSONObject().put("hasDevice", false)))))
            assertEquals(index + 1, errors.size)
        }
    }
    @Test fun temporaryIntensityAndPulseHaveAOneSecondLeaseAndUniqueRpcIds() {
        val bridge = bridge(); bridge.receive(snapshot(device()))
        bridge.forward("strength-1+2+18"); assertTrue(packets.isEmpty())
        bridge.forward("pulse-A:" + JSONArray(List(10) { PulseShape.RAMP.frame }))
        assertEquals(2, packets.size)
        val intensity = packets[0].getJSONObject("data")
        val pulse = packets[1].getJSONObject("data")
        assertNotEquals(intensity.getString("reqId"), pulse.getString("reqId"))
        assertEquals("device.op", intensity.getString("m"))
        val operation = intensity.getJSONObject("data")
        assertEquals("coyote-a", operation.getString("s")); assertEquals(0, operation.getInt("c"))
        assertEquals(4, operation.getInt("t")); assertEquals(18, operation.getInt("v"))
        assertEquals(1000, operation.getInt("d")); assertTrue(operation.getBoolean("im"))
        assertEquals(0, pulse.getJSONObject("data").getInt("t"))
        assertEquals(3, pulse.getJSONObject("data").getInt("ver"))
        assertEquals(10, pulse.getJSONObject("data").getJSONArray("v").length())
        bridge.forward("clear-1"); bridge.forward("strength-1+2+0")
        assertEquals(3, packets.size) // Zero waits for clear instead of being cancelled by it.
        bridge.receive(JSONObject().put("t", "resp").put("reqId", packets[2].getJSONObject("data").getString("reqId"))
            .put("result", JSONObject()))
        assertEquals("device.op.clear", packets[2].getJSONObject("data").getString("m"))
        assertEquals(7, packets[3].getJSONObject("data").getJSONObject("data").getInt("t"))
        assertEquals(0, packets[3].getJSONObject("data").getJSONObject("data").getInt("v"))
    }
    @Test fun matchingRpcErrorStopsButUnknownResponseDoesNot() {
        val bridge = bridge(); bridge.requestDevices()
        bridge.receive(JSONObject().put("t", "resp").put("reqId", "unknown").put("error", "bad"))
        assertTrue(errors.isEmpty())
        bridge.receive(JSONObject().put("t", "resp").put("reqId", packets[0].getJSONObject("data").getString("reqId"))
            .put("error", "method_not_found"))
        assertEquals(1, errors.size)
    }
    @Test fun missingRpcResponsesHaveAFiniteTimeout() {
        val bridge = bridge(); bridge.requestDevices(); bridge.checkTimeout(Long.MAX_VALUE)
        assertEquals(1, errors.size)
    }
}
