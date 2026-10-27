package com.nik.carradio.backend.fyt

import com.nik.carradio.core.Band
import com.nik.carradio.core.BandInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyuBandInfoTest {
    @Test fun europeFm() = assertEquals(BandInfo(Band.FM, 87_500, 108_000, 50), Syu.bandInfo(intArrayOf(8750, 10800, 5, 410)))
    @Test fun usaFm() = assertEquals(BandInfo(Band.FM, 87_500, 107_900, 200), Syu.bandInfo(intArrayOf(8750, 10790, 20, 102)))
    @Test fun oirtFm() = assertEquals(BandInfo(Band.FM, 65_000, 74_000, 30), Syu.bandInfo(intArrayOf(6500, 7400, 3, 300)))
    @Test fun europeAm() = assertEquals(BandInfo(Band.AM, 522, 1620, 9), Syu.bandInfo(intArrayOf(522, 1620, 9, 122)))
    @Test fun garbage() {
        assertNull(Syu.bandInfo(intArrayOf(0, 0, 0, 0)))
        assertNull(Syu.bandInfo(intArrayOf(1)))
        assertNull(Syu.bandInfo(null))
    }
    @Test fun areaNames() = assertEquals("Europe", Syu.AREAS[2])
}
