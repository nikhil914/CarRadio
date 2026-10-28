package com.nik.carradio.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneSpecAndDexTest {
    @Test fun rendersTemplates() {
        val r = TuneSpec(13, "1,{v10}").render(Band.FM, 98_100, 87_500, 108_000, 50)
        assertArrayEquals(intArrayOf(1, 9810), r.ints)
        assertArrayEquals(intArrayOf(0, 212), TuneSpec(13, "0,{idx}").render(Band.FM, 98_100, 87_500, 108_000, 50).ints)
        assertArrayEquals(intArrayOf(2, 33886), TuneSpec(13, "2,{ratio}").render(Band.FM, 98_100, 87_500, 108_000, 50).ints)
        assertArrayEquals(intArrayOf(981, 98, 98100), TuneSpec(13, "{v100},{mhz},{khz}").render(Band.FM, 98_100, 87_500, 108_000, 50).ints)
        val f = TuneSpec(13, "1", floats = "{fmhz}", strings = "{fmhz}").render(Band.FM, 98_100, 87_500, 108_000, 50)
        assertEquals(98.1f, f.floats!![0], 0.001f)
        assertEquals("98.1", f.strings!![0])
        assertNull(TuneSpec(3, "").render(Band.FM, 98_100, 87_500, 108_000, 50).ints)
        assertArrayEquals(intArrayOf(3, 999), TuneSpec(13, "3,{v10}").render(Band.AM, 999, 522, 1620, 9).ints)
    }

    @Test fun encodeDecodeRoundTrip() {
        val s = TuneSpec(13, "1,{v10}", "", "", claimSource = true)
        assertEquals("13|1,{v10}|||src", s.encode())
        assertEquals(s, TuneSpec.decode(s.encode()))
        assertNull(TuneSpec.decode("garbage"))
        assertNull(TuneSpec.decode(""))
    }

    /** A minimal dex: header with the string table pointers, then two MUTF-8 strings. */
    @Test fun parsesDexStringTable() {
        val strings = listOf("com.syu.radio.ACTION_X", "héllo")
        val dex = ByteArray(0x100)
        "dex\n035\u0000".forEachIndexed { i, c -> dex[i] = c.code.toByte() }
        fun put32(o: Int, v: Int) { for (k in 0..3) dex[o + k] = (v shr (8 * k)).toByte() }
        put32(0x38, strings.size); put32(0x3C, 0x70)
        var data = 0x80
        strings.forEachIndexed { i, s ->
            put32(0x70 + i * 4, data)
            val bytes = s.toByteArray(Charsets.UTF_8)
            dex[data++] = s.length.toByte()
            bytes.forEach { dex[data++] = it }
            dex[data++] = 0
        }
        assertEquals(strings, DexStrings.parse(dex))
        assertTrue(DexStrings.parse(ByteArray(10)).isEmpty())
    }
}
