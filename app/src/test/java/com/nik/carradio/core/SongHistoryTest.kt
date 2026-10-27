package com.nik.carradio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SongHistoryTest {
    private fun e(text: String, station: String = "Radio Mirchi", t: Long = 0) = SongHistory.Entry(t, station, text)

    @Test fun repeatsAndNoiseIgnored() {
        val l = SongHistory.add(emptyList(), e("Kesariya - Arijit Singh"))!!
        assertNull(SongHistory.add(l, e("KESARIYA – Arijit Singh ")))      // same line, other spacing/case
        assertNull(SongHistory.add(l, e("RM")))                             // too short
        assertNull(SongHistory.add(l, e("Radio Mirchi")))                   // just the station name
        assertNotNull(SongHistory.add(l, e("Kesariya - Arijit Singh", station = "City 1016")))  // other station
    }

    @Test fun keepsAtMost150() {
        var l = emptyList<SongHistory.Entry>()
        repeat(200) { l = SongHistory.add(l, e("Song number $it"))!! }
        assertEquals(150, l.size)
        assertEquals("Song number 199", l.last().text)
    }

    @Test fun jsonRoundTrip() {
        val l = listOf(e("A song", t = 5), e("Another", station = "Big FM", t = 6))
        assertEquals(l, SongHistory.parse(SongHistory.toJson(l)))
    }
}
