package com.nik.carradio.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory

/** The proxy-verdict fingerprints must be exactly the keys the proxy APKs are signed with. */
class AospKeysTest {
    private fun sha256Of(pem: String): String {
        val f = listOf(File("../keystore/aosp/$pem"), File("keystore/aosp/$pem")).first { it.exists() }
        val cert = f.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    }

    @Test fun fingerprintsMatchCommittedCertificates() {
        assertEquals("aospTestkey", DeviceInfo.AOSP_KEYS[sha256Of("testkey.x509.pem")])
        assertEquals("aospPlatform", DeviceInfo.AOSP_KEYS[sha256Of("platform.x509.pem")])
    }
}
