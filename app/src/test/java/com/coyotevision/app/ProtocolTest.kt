package com.coyotevision.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.net.URI
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

class ProtocolTest {
    @Test fun validatesServerAddressRatherThanRepositoryUrl() {
        assertTrue(V3Protocol.validServer("ws://192.168.1.10:9999"))
        assertTrue(V3Protocol.validServer("wss://relay.example.com/v3"))
        assertFalse(V3Protocol.validServer("https://github.com/example"))
        assertFalse(V3Protocol.validServer("ws://"))
        assertFalse(V3Protocol.validServer("ws://user:secret@example.com"))
        assertFalse(V3Protocol.validServer("ws://example.com:99999"))
        assertFalse(V3Protocol.validServer("ws://example.com?tid=someone"))
    }
    @Test fun handshakeRequiresCorrectControllerAndRealPeer() {
        assertEquals(RelayEvent.Assigned("controller"), V3Protocol.parse(
            """{"type":"bind","clientId":"controller","targetId":"","message":"targetId"}""", ""))
        assertEquals(RelayEvent.Paired("app"), V3Protocol.parse(
            """{"type":"bind","clientId":"controller","targetId":"app","message":"200"}""", "controller"))
        assertEquals(RelayEvent.Ignored, V3Protocol.parse(
            """{"type":"bind","clientId":"stranger","targetId":"app","message":"200"}""", "controller"))
        assertEquals(RelayEvent.Ignored, V3Protocol.parse(
            """{"type":"bind","clientId":"controller","targetId":"controller","message":"200"}""", "controller"))
    }
    @Test fun v4AndMalformedMessagesFailExplicitly() {
        assertTrue(V3Protocol.parse("{", "controller") is RelayEvent.Failure)
        assertTrue(V3Protocol.parse("""{"type":"hello","clientId":"123"}""", "") is RelayEvent.Failure)
        assertTrue(V3Protocol.parse("""{"type":"break","message":"209"}""", "controller") is RelayEvent.Failure)
    }
    @Test fun strengthUsesAbsoluteSettingAndStopUsesClear() {
        val strength = JSONObject(V3Protocol.strength("controller", "app", "A", 8))
        assertEquals(3, strength.getInt("type"))
        assertEquals(8, strength.getInt("strength"))
        assertEquals("", strength.getString("message"))
        assertEquals("controller", strength.getString("clientId"))
        assertEquals("app", strength.getString("targetId"))
        val clear = JSONObject(V3Protocol.clear("controller", "app", "B"))
        assertEquals(4, clear.getInt("type"))
        assertEquals("clear", clear.getString("message"))
        assertEquals("B", clear.getString("channel"))
    }
    @Test fun pulseHasFiniteDurationAndCorrectChannel() {
        val packet = JSONObject(V3Protocol.pulse("controller", "app", EffectPreset("B", 8, 2)))
        assertEquals(2, packet.getInt("time"))
        assertEquals(20, org.json.JSONArray(packet.getString("message").substringAfter(':')).length())
        assertEquals("clientMsg", packet.getString("type"))
    }
    @Test fun pairingLinkUsesLegacyRawSocketAddressAndConnectionId() {
        val link = V3Protocol.pairingLink("wss://relay.example.com/v3/", "controller")
        assertTrue(link.startsWith("https://www.dungeon-lab.com/app-download.php#DGLAB-SOCKET#"))
        assertEquals("wss://relay.example.com/v3/controller", URLDecoder.decode(link.substringAfter("#DGLAB-SOCKET#"), "UTF-8"))
        assertEquals("wss", URI(link.split('#')[2]).scheme)
    }

    @Test fun generatedQrDecodesToTheLegacyDglabSocketPayload() {
        val expected = V3Protocol.pairingLink("ws://192.168.1.8:9999", "controller-uuid")
        val matrix = MultiFormatWriter().encode(expected, BarcodeFormat.QR_CODE, 600, 600)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        val decoded = QRCodeReader().decode(bitmap).text
        assertEquals(expected, decoded)
        assertEquals(3, decoded.split('#').size)
        assertEquals("ws://192.168.1.8:9999/controller-uuid", decoded.split('#')[2])
    }
}
