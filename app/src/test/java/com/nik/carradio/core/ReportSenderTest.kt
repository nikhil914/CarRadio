package com.nik.carradio.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSenderTest {
    private val sample = buildString {
        repeat(120) { appendLine("10-26 17:52:01.123 I ▶ Starting FYT / Syu MCU tuner [fyt] ✔ – line $it …") }
    }

    @Test fun pagesAreAsciiAndNumbered() {
        val pages = ReportSender.paginate(sample)
        assertTrue(pages.size > 1)
        pages.forEachIndexed { i, p ->
            assertTrue(p.startsWith("CarRadio ${i + 1}/${pages.size}\n"))
            assertTrue("non-ASCII on page $i", p.all { it.code in 32..126 || it == '\n' })
        }
        // Nothing lost apart from the symbol mapping.
        assertEquals(ReportSender.toAscii(sample), pages.joinToString("") { it.substringAfter('\n') })
    }

    @Test fun everyPageFitsOneQrCode() {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2)
        ReportSender.paginate(sample).forEach { page ->
            val m = QRCodeWriter().encode(page, BarcodeFormat.QR_CODE, 600, 600, hints)
            assertTrue(m.width >= 600)
        }
    }
}
