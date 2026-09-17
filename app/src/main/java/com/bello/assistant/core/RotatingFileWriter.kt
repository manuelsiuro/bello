package com.bello.assistant.core

import java.io.File

/**
 * Appends lines to `<dir>/<baseName>.log`, rotating to `.1.log`, `.2.log`… when the file
 * exceeds [maxBytes]. Keeps at most [maxFiles] files in total. Pure JVM (unit tested).
 */
class RotatingFileWriter(
    private val dir: File,
    private val baseName: String,
    private val maxBytes: Long,
    private val maxFiles: Int,
) {
    init {
        require(maxFiles >= 1) { "maxFiles must be >= 1" }
        dir.mkdirs()
    }

    val current: File get() = file(0)

    @Synchronized
    fun append(line: String) {
        val f = current
        if (f.exists() && f.length() + line.length + 1 > maxBytes) rotate()
        f.appendText(line + "\n")
    }

    /** All existing log files, oldest first. */
    fun files(): List<File> = (maxFiles - 1 downTo 0).map(::file).filter { it.exists() }

    private fun rotate() {
        file(maxFiles - 1).delete()
        for (i in maxFiles - 2 downTo 0) {
            val from = file(i)
            if (from.exists()) from.renameTo(file(i + 1))
        }
    }

    private fun file(index: Int) = File(dir, if (index == 0) "$baseName.log" else "$baseName.$index.log")
}
