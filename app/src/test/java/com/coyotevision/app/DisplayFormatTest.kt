package com.coyotevision.app

import org.junit.Assert.*
import org.junit.Test

class DisplayFormatTest {
    @Test fun timeAndPositionDisplaysUseOneDecimalAndCorrectMinuteCarry() {
        assertEquals("123.5", DisplayFormat.one(123.456))
        assertEquals("0:01.2", DisplayFormat.duration(1234))
        assertEquals("1:00.0", DisplayFormat.duration(59_999))
        assertEquals("0.2 秒", DisplayFormat.seconds(200))
        assertTrue(DisplayFormat.timestamp(1234).endsWith(".2"))
    }
}
