package com.nik.carradio.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent, crash-safe log.
 *
 * Every line is appended to a file synchronously, so if a vendor native library kills the
 * process the last line in the file shows exactly which step was running. The log screen and
 * the "Share report" button read from here.
 */
object AppLog {
    private const val TAG = "CarRadio"
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_MEMORY_LINES = 800

    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun init(context: Context) {
        val f = File(context.filesDir, "carradio.log")
        if (f.exists() && f.length() > MAX_FILE_BYTES) {
            val old = File(context.filesDir, "carradio.old.log")
            old.delete()
            f.renameTo(old)
        }
        file = f
        val previous = runCatching { f.readLines().takeLast(MAX_MEMORY_LINES) }.getOrDefault(emptyList())
        _lines.value = previous
    }

    fun i(msg: String) = write("I", msg, null)
    fun w(msg: String, t: Throwable? = null) = write("W", msg, t)
    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)

    /** Marks a risky step. If the app dies, this is the last line in the file. */
    fun step(msg: String) = write(">", msg, null)

    @Synchronized
    private fun write(level: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(fmt.format(Date())).append(' ').append(level).append(' ').append(msg)
            if (t != null) append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
        }
        when (level) {
            "E" -> Log.e(TAG, msg, t)
            "W" -> Log.w(TAG, msg, t)
            else -> Log.i(TAG, msg)
        }
        runCatching { file?.appendText(line + "\n") }
        if (t != null && level == "E") {
            runCatching { file?.appendText(stack(t) + "\n") }
        }
        val next = (_lines.value + line)
        _lines.value = if (next.size > MAX_MEMORY_LINES) next.takeLast(MAX_MEMORY_LINES) else next
    }

    fun clear() {
        runCatching { file?.writeText("") }
        _lines.value = emptyList()
        i("Log cleared")
    }

    fun fullText(): String = runCatching { file?.readText() }.getOrNull() ?: _lines.value.joinToString("\n")

    fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
