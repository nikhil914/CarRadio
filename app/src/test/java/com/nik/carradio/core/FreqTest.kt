package com.nik.carradio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreqTest {
    @Test fun decodesVendorUnits() {
        assertEquals(98_100, Freq.decodeInt(9810))      // FYT FM: 10 kHz units
        assertEquals(1_041, Freq.decodeInt(1041))       // MCU: 500..1800 is always AM kHz
        assertEquals(87_500, Freq.decodeInt(87_500))    // kHz
        assertEquals(1_152, Freq.decodeInt(1152))
        assertEquals(999, Freq.decodeInt(999))
        assertEquals(98_100, Freq.decodeMhz(98.1f))
        assertNull(Freq.decodeInt(42))
    }

    @Test fun decodesFmOnlySources() {
        assertEquals(104_100, Freq.decodeFm(104.1f))
        assertEquals(104_100, Freq.decodeFm(1041f))
        assertEquals(98_100, Freq.decodeFm(9810f))
        assertEquals(87_500, Freq.decodeFm(87_500f))
        assertNull(Freq.decodeFm(0f))
    }

    @Test fun parsesUserInput() {
        assertEquals(104_100, Freq.parseUser("104.1"))
        assertEquals(98_100, Freq.parseUser("98,1"))
        assertEquals(87_500, Freq.parseUser("87.5"))
        assertEquals(1_602, Freq.parseUser("1602"))
        assertEquals(981, Freq.parseUser("981"))        // whole number in AM range = AM kHz
        assertNull(Freq.parseUser("abc"))
        assertNull(Freq.parseUser("150"))
    }

    @Test fun formats() {
        assertEquals("98.1", Freq.format(Band.FM, 98_100))
        assertEquals("98.15", Freq.format(Band.FM, 98_150))
        assertEquals("100.0", Freq.format(Band.FM, 100_000))
        assertEquals("1152", Freq.format(Band.AM, 1152))
    }
}
