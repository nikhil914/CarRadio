package com.nik.carradio.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versionOrder() {
        assertTrue(Updater.isNewer("1.1.0", "1.0.2"))
        assertTrue(Updater.isNewer("v1.0.10", "1.0.2"))
        assertTrue(Updater.isNewer("1.1", "1.0.9"))
        assertFalse(Updater.isNewer("1.0.2", "1.0.2"))
        assertFalse(Updater.isNewer("1.0.2", "1.1.0"))
        assertFalse(Updater.isNewer("1.1.0", "1.1"))
    }

    @Test fun picksAppApkNotProxy() {
        val sha = "a".repeat(64)
        val json = JSONObject("""{"tag_name":"v1.1.0","body":"Build\nSHA-256: `$sha`","assets":[
            {"name":"CarRadio-proxy-aospPlatform.apk","browser_download_url":"https://x/p.apk","size":10},
            {"name":"CarRadio-1.1.0.apk","browser_download_url":"https://x/a.apk","size":2048}]}""")
        val r = Updater.parse(json)!!
        assertEquals("1.1.0", r.version)
        assertEquals("CarRadio-1.1.0.apk", r.apkName)
        assertEquals("https://x/a.apk", r.apkUrl)
        assertEquals(2048L, r.sizeBytes)
        assertEquals(sha, r.sha256)
    }

    @Test fun noApkMeansNull() {
        assertNull(Updater.parse(JSONObject("""{"tag_name":"v1","assets":[{"name":"notes.txt"}]}""")))
    }
}
