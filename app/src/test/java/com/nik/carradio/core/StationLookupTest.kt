package com.nik.carradio.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StationLookupTest {
    private fun hit(name: String, country: String = "AE", favicon: String = "https://x/logo.png", ok: Boolean = true, votes: Int = 0, stream: String = "https://s/$name") =
        StationLookup.Hit(name, stream, favicon, country, "MP3", 128, votes, ok)

    @Test fun cleansFrequencies() {
        assertEquals("Virgin Radio", StationLookup.cleanName("Virgin Radio 104.4"))
        assertEquals("City", StationLookup.cleanName("City 101.6 FM"))
        assertEquals("Radio 1", StationLookup.cleanName("Radio 1"))
        assertEquals("Dubai 92", StationLookup.cleanName("Dubai 92"))
    }

    @Test fun prefersWorkingLocalWithLogo() {
        val hits = listOf(hit("Virgin Radio UK", country = "GB", votes = 900), hit("Virgin Radio Dubai", favicon = ""), hit("Virgin Radio Dubai 104.4"))
        assertEquals("Virgin Radio Dubai 104.4", StationLookup.pickBest("Virgin Radio", hits, "AE")!!.name)
    }

    @Test fun brokenStreamLoses() {
        val hits = listOf(hit("Dubai 92", ok = false, votes = 50), hit("Dubai 92 FM", favicon = ""))
        assertEquals("Dubai 92 FM", StationLookup.pickBest("Dubai 92", hits, "AE")!!.name)
    }

    @Test fun unrelatedNamesRejected() {
        assertNull(StationLookup.pickBest("Hum FM", listOf(hit("Humraaz Radio Canada", country = "CA")), "AE"))
        assertNull(StationLookup.pickBest("Rock", listOf(hit("Rock", stream = "")), "AE"))
        assertNull(StationLookup.pickBest("Dubai 92", listOf(hit("Dubai Eye 103.8")), "AE"))
    }

    @Test fun parsesDirectoryJson() {
        val a = JSONArray("""[{"name":" Kadak FM ","url":"http://a","url_resolved":"https://b","favicon":"https://f","countrycode":"AE","codec":"AAC","bitrate":64,"votes":7,"lastcheckok":1}]""")
        assertEquals(StationLookup.Hit("Kadak FM", "https://b", "https://f", "AE", "AAC", 64, 7, true), StationLookup.parse(a).single())
    }
}
