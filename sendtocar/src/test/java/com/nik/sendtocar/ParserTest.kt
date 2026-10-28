package com.nik.sendtocar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {

    private fun found(text: String): Place {
        val r = Parser.parse(text)
        assertTrue("expected Found for: $text but got $r", r is Parsed.Found)
        return (r as Parsed.Found).place
    }

    @Test fun geoUri() {
        val p = found("geo:24.4539,54.3773?q=24.4539,54.3773(Louvre)")
        assertEquals(24.4539, p.lat!!, 1e-6)
        assertEquals(54.3773, p.lon!!, 1e-6)
    }

    @Test fun geoUriWithAddressOnly() {
        val p = found("geo:0,0?q=Yas+Mall,+Abu+Dhabi")
        assertNull(p.lat)
        assertEquals("Yas Mall, Abu Dhabi", p.query)
    }

    @Test fun googlePinBeatsMapCentre() {
        val p = found("https://www.google.com/maps/place/Emirates+Palace/@24.46,54.31,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d24.4615!4d54.3172")
        assertEquals(24.4615, p.lat!!, 1e-6)
        assertEquals(54.3172, p.lon!!, 1e-6)
        assertEquals("Emirates Palace", p.name)
    }

    @Test fun whatsappLocation() {
        val p = found("https://maps.google.com/?q=24.4764,54.3226")
        assertEquals(24.4764, p.lat!!, 1e-6)
    }

    @Test fun encodedComma() {
        val p = found("https://www.google.com/maps/search/?api=1&query=24.4764%2C54.3226")
        assertEquals(54.3226, p.lon!!, 1e-6)
    }

    @Test fun wazeLl() {
        val p = found("Meet me here https://waze.com/ul?ll=24.45390000%2C54.37730000&navigate=yes")
        assertEquals(24.4539, p.lat!!, 1e-6)
    }

    @Test fun wazeGeohash() {
        val p = found("https://waze.com/ul/hu4pruydqqvj")
        assertEquals(57.64911, p.lat!!, 1e-4)
        assertEquals(10.40744, p.lon!!, 1e-4)
    }

    @Test fun googleShortLinkNeedsResolve() {
        val r = Parser.parse("Emirates Palace\nWest Corniche Road, Abu Dhabi\nhttps://maps.app.goo.gl/AbCdEf123")
        assertTrue(r is Parsed.NeedsResolve)
        assertEquals("Emirates Palace", (r as Parsed.NeedsResolve).name)
    }

    @Test fun plainCoordinates() {
        val p = found("Parking 24.476400, 54.322600")
        assertEquals(24.4764, p.lat!!, 1e-6)
        assertEquals("Parking", p.name)
    }

    @Test fun plainAddressBecomesSearch() {
        val p = found("Yas Mall\nAbu Dhabi")
        assertNull(p.lat)
        assertEquals("Yas Mall, Abu Dhabi", p.query)
    }

    @Test fun placeNameOnlyLinkBecomesSearch() {
        val p = found("https://www.google.com/maps/place/Yas+Mall/data=!4m2")
        assertNull(p.lat)
        assertEquals("Yas Mall", p.query)
    }

    @Test fun blank() {
        assertTrue(Parser.parse("   ") is Parsed.Nothing)
    }
}
