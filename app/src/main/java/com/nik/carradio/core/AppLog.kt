package com.nik.carradio.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Persistent, crash-safe log with little disk I/O.
 *
 * Info lines are buffered and flushed every 2 s. Risky-step markers (">"), warnings and errors
 * are flushed immediately, so if a vendor native library kills the process the last line in
 * the file still shows exactly which step was running.
 */
object AppLog {
    private const val TAG = "CarRadio"
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_MEMORY_LINES = 600

    private var file: File? = null
    private var writer: BufferedWriter? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val memory = ArrayDeque<String>(MAX_MEMORY_LINES)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val flusher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "log-flush").apply { isDaemon = true } }

    fun init(context: Context) {
        val f = File(context.filesDir, "carradio.log")
        if (f.exists() && f.length() > MAX_FILE_BYTES) {
            val old = File(context.filesDir, "carradio.old.log")
            old.delete(); f.renameTo(old)
        }
        file = f
        runCatching { f.useLines { seq -> seq.toList().takeLast(MAX_MEMORY_LINES) } }.getOrNull()?.let { memory.addAll(it) }
        _lines.value = memory.toList()
        writer = runCatching { BufferedWriter(FileWriter(f, true), 8 * 1024) }.getOrNull()
        flusher.scheduleWithFixedDelay({ flush() }, 2, 2, TimeUnit.SECONDS)
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
        runCatching {
            writer?.apply {
                write(line); newLine()
                if (t != null && level == "E") { write(stack(t)); newLine() }
                if (level != "I") flush()
            }
        }
        if (memory.size >= MAX_MEMORY_LINES) memory.removeFirst()
        memory.addLast(line)
        _lines.value = memory.toList()
    }

    @Synchronized
    fun flush() { runCatching { writer?.flush() } }

    @Synchronized
    fun clear() {
        runCatching { writer?.close() }
        runCatching { file?.writeText("") }
        writer = file?.let { runCatching { BufferedWriter(FileWriter(it, true), 8 * 1024) }.getOrNull() }
        memory.clear()
        _lines.value = emptyList()
        i("Log cleared")
    }

    fun fullText(): String { flush(); return runCatching { file?.readText() }.getOrNull() ?: memory.joinToString("\n") }

    fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
