package com.bello.assistant

import com.bello.assistant.core.RotatingFileWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RotatingFileWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun appendsToCurrentFileBelowLimit() {
        val w = RotatingFileWriter(tmp.root, "t", maxBytes = 1000, maxFiles = 3)
        w.append("hello")
        w.append("world")
        assertEquals("hello\nworld\n", w.current.readText())
        assertEquals(1, w.files().size)
    }

    @Test
    fun rotatesAndKeepsAtMostMaxFiles() {
        val w = RotatingFileWriter(tmp.root, "t", maxBytes = 20, maxFiles = 3)
        repeat(10) { w.append("line-$it-xxxxxxxx") } // 18 bytes per line incl. newline -> rotate each time
        val files = w.files()
        assertEquals(3, files.size)
        assertEquals("line-9-xxxxxxxx\n", w.current.readText())
        assertTrue(files.first().readText().startsWith("line-7"))
    }
}
