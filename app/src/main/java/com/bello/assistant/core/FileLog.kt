package com.bello.assistant.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App logging: logcat (tag `Bello/<area>`) plus a rotating file in the app external files dir.
 * Logcat is unreliable on the target device (camera HAL floods the buffer), so the file is the
 * source of truth — retrieve it with `scripts/pull-logs.sh`.
 */
object FileLog {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 3
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var writer: RotatingFileWriter? = null

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        writer = RotatingFileWriter(dir, "bello", MAX_BYTES, MAX_FILES)
    }

    fun i(area: String, msg: String) = write(Log.INFO, area, msg, null)
    fun w(area: String, msg: String, t: Throwable? = null) = write(Log.WARN, area, msg, t)
    fun e(area: String, msg: String, t: Throwable? = null) = write(Log.ERROR, area, msg, t)

    private fun write(level: Int, area: String, msg: String, t: Throwable?) {
        val full = if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg
        Log.println(level, "Bello/$area", full)
        val tag = when (level) { Log.WARN -> "W"; Log.ERROR -> "E"; else -> "I" }
        val line = synchronized(time) { time.format(Date()) } + " $tag/$area: $full"
        runCatching { writer?.append(line) }
    }
}
