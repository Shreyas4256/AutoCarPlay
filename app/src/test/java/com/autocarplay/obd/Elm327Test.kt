package com.autocarplay.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327Test {

    @Test
    fun gaugeValuesAreDecodedFromModeOneReplies() {
        val rpm = Elm327.pidData("410C1AF8\r\r>", 0x0C)!!
        assertArrayEquals(intArrayOf(0x1A, 0xF8), rpm)
        assertEquals(1726.0, Gauge.RPM.decode(rpm)!!, 0.001)

        // Spaces, the protocol search message and echoed commands are ignored.
        val speed = Elm327.pidData("010D\rSEARCHING...\r41 0D 32 \r\r>", 0x0D)!!
        assertEquals(50.0, Gauge.SPEED.decode(speed)!!, 0.001)

        assertEquals(50.0, Gauge.COOLANT.decode(Elm327.pidData("41055A", 0x05)!!)!!, 0.001)
        assertEquals(100.0, Gauge.THROTTLE.decode(Elm327.pidData("4111FF", 0x11)!!)!!, 0.001)
        assertEquals("1726", Gauge.RPM.format(1726.0))
        assertEquals("12.6", Gauge.BATTERY.format(12.62))
        assertEquals("–", Gauge.SPEED.format(null))
    }

    @Test
    fun unansweredRequestsGiveNoValue() {
        assertNull(Elm327.pidData("NO DATA\r\r>", 0x0C))
        assertEquals("NO DATA", Elm327.error("NO DATA\r\r>"))
        assertEquals("UNABLE TO CONNECT", Elm327.error("SEARCHING...\rUNABLE TO CONNECT\r>"))
        assertNull(Elm327.error("410C1AF8"))
        // A reply to another PID is not taken for this one.
        assertNull(Elm327.pidData("410D32", 0x0C))
        // RPM needs two data bytes.
        assertNull(Gauge.RPM.decode(intArrayOf(0x1A)))
    }

    @Test
    fun batteryVoltageComesFromTheAdapter() {
        assertEquals(12.6, Elm327.voltage("12.6V\r\r>")!!, 0.001)
        assertEquals(14.1, Elm327.voltage("ATRV\r14.1V")!!, 0.001)
        assertNull(Elm327.voltage("?"))
    }

    @Test
    fun supportedPidsAreReadFromTheBitmask() {
        val supported = Elm327.supportedPids("4100BE1FA813\r\r>", 0x00)
        assertEquals(
            setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
            supported,
        )
        // Replies from two control units are combined.
        assertEquals(setOf(0x21, 0x2F), Elm327.supportedPids("412080000000\r412000020000", 0x20))
        assertTrue(Elm327.supportedPids("NO DATA", 0x00).isEmpty())
    }

    @Test
    fun canProtocolsAreRecognised() {
        assertTrue(Elm327.isCan("A6\r\r>"))
        assertTrue(Elm327.isCan("8"))
        assertFalse(Elm327.isCan("A3"))
        assertFalse(Elm327.isCan("5"))
        assertFalse(Elm327.isCan("?"))
    }

    @Test
    fun troubleCodesAreDecoded() {
        assertEquals("P0301", Elm327.decodeTroubleCode("0301"))
        assertEquals("C0123", Elm327.decodeTroubleCode("4123"))
        assertEquals("B0123", Elm327.decodeTroubleCode("8123"))
        assertEquals("U1456", Elm327.decodeTroubleCode("D456"))
        assertNull(Elm327.decodeTroubleCode("XY12"))

        // Older protocols: three codes per line, padded with zeros.
        assertEquals(listOf("P0133"), Elm327.troubleCodes("43 01 33 00 00 00 00\r\r>", can = false))
        // CAN: the first byte is the number of codes.
        assertEquals(listOf("P0133", "U0123"), Elm327.troubleCodes("43 02 01 33 C1 23\r\r>", can = true))
        assertEquals(emptyList<String>(), Elm327.troubleCodes("4300\r\r>", can = true))
        // CAN, long reply split into numbered frames after a length line.
        val framed = "00A\r0: 43 04 01 33 01 34\r1: C1 23 D4 56 00 00 00\r\r>"
        assertEquals(listOf("P0133", "P0134", "U0123", "U1456"), Elm327.troubleCodes(framed, can = true))
    }

    @Test
    fun theChosenAdapterIsStoredAsText() {
        val bluetooth = ObdAdapter.Bluetooth("00:1D:A5:68:98:8B", "OBDII | Car")
        assertEquals(bluetooth, ObdAdapter.decode(bluetooth.encode()))
        val wifi = ObdAdapter.WiFi()
        assertEquals(wifi, ObdAdapter.decode(wifi.encode()))
        assertEquals("192.168.0.10", wifi.host)
        assertNull(ObdAdapter.decode(null))
        assertNull(ObdAdapter.decode("garbage"))
    }
}
