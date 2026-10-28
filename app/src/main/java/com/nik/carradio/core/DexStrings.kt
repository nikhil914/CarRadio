package com.nik.carradio.core

import java.io.File
import java.util.zip.ZipFile

/**
 * Reads the string table of an APK's dex files: intent actions, property names, class names and
 * log messages of the firmware's radio apps. Put in the test report so the next version can
 * match this exact firmware without guessing. Read-only; the APK files of system apps are
 * world-readable.
 */
object DexStrings {
    /** Strings from every classes*.dex in [apk] containing one of [keywords] (case-insensitive). */
    fun fromApk(apk: File, keywords: List<String>, limit: Int = 400): List<String> {
        val out = linkedSetOf<String>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("""classes\d*\.dex""")) }.forEach { e ->
                if (e.size > 64L * 1024 * 1024) return@forEach
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                for (s in parse(bytes)) {
                    if (out.size >= limit) return@use
                    val l = s.lowercase()
                    if (s.length in 3..160 && keywords.any { l.contains(it) }) out += s
                }
            }
        }
        return out.toList()
    }

    /** All strings of one dex file (header: string_ids_size at 0x38, string_ids_off at 0x3C). */
    fun parse(dex: ByteArray): List<String> {
        if (dex.size < 0x70 || dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() || dex[2] != 'x'.code.toByte()) return emptyList()
        val count = u32(dex, 0x38); val idsOff = u32(dex, 0x3C)
        if (count <= 0 || idsOff <= 0 || idsOff.toLong() + count * 4L > dex.size) return emptyList()
        val list = ArrayList<String>(count)
        for (i in 0 until count) {
            var p = u32(dex, idsOff + i * 4)
            if (p <= 0 || p >= dex.size) continue
            // uleb128 length in UTF-16 units, then MUTF-8 bytes ending in 0.
            var shift = 0
            while (p < dex.size) { val b = dex[p++].toInt(); if (b and 0x80 == 0) break; shift += 7; if (shift > 28) break }
            val start = p
            while (p < dex.size && dex[p] != 0.toByte()) p++
            list += mutf8(dex, start, p)
        }
        return list
    }

    private fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
        ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun mutf8(b: ByteArray, from: Int, to: Int): String {
        val sb = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val c = b[i].toInt() and 0xff
            when {
                c < 0x80 -> { sb.append(c.toChar()); i++ }
                c and 0xe0 == 0xc0 && i + 1 < to -> { sb.append((((c and 0x1f) shl 6) or (b[i + 1].toInt() and 0x3f)).toChar()); i += 2 }
                c and 0xf0 == 0xe0 && i + 2 < to -> {
                    sb.append((((c and 0x0f) shl 12) or ((b[i + 1].toInt() and 0x3f) shl 6) or (b[i + 2].toInt() and 0x3f)).toChar()); i += 3
                }
                else -> { sb.append('?'); i++ }
            }
        }
        return sb.toString()
    }
}
