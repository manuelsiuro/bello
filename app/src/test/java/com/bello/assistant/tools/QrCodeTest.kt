package com.bello.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {

    private val url = "http://192.168.1.199:8080/r/k3x9q2ab"

    @Test fun `the modules form a square of twenty-one plus a multiple of four`() {
        val rows = QrCode.modules(url)
        assertTrue(rows.size >= 21)
        assertEquals(0, (rows.size - 21) % 4)
        rows.forEach { assertEquals(rows.size, it.length) }
        rows.forEach { row -> assertTrue(row.all { it == '0' || it == '1' }) }
    }

    @Test fun `the finder patterns sit in three corners`() {
        val rows = QrCode.modules(url)
        val finder = "1111111"
        assertTrue(rows[0].startsWith(finder))
        assertTrue(rows[0].endsWith(finder))
        assertTrue(rows.last().startsWith(finder))
        assertTrue(rows[1].startsWith("1000001"))
    }

    @Test fun `a different address gives a different code`() {
        assertNotEquals(QrCode.modules(url), QrCode.modules("http://192.168.1.199:8080/r/aaaaaaaa"))
    }
}
