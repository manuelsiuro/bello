package com.bello.assistant.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PageProtocolTest {

    @Test fun `only a GET or HEAD request line yields a path, without its query`() {
        assertEquals(PageProtocol.Request("GET", "/r/k3x9q2ab"), PageProtocol.parse("GET /r/k3x9q2ab?x=1 HTTP/1.1"))
        assertEquals(PageProtocol.Request("HEAD", "/"), PageProtocol.parse("HEAD / HTTP/1.0\r"))
        assertNull(PageProtocol.parse("POST /r/k3x9q2ab HTTP/1.1"))
        assertNull(PageProtocol.parse("garbage"))
        assertNull(PageProtocol.parse(null))
        assertNull(PageProtocol.parse("GET r/k3x9q2ab HTTP/1.1"))
    }

    @Test fun `a page id is eight safe characters under slash r`() {
        assertEquals("k3x9q2ab", PageProtocol.pageId("/r/k3x9q2ab"))
        assertNull(PageProtocol.pageId("/r/K3X9Q2AB"))
        assertNull(PageProtocol.pageId("/r/k3x9q2a"))
        assertNull(PageProtocol.pageId("/r/../etc"))
        assertNull(PageProtocol.pageId("/r/k3x9q2ab/"))
        assertNull(PageProtocol.pageId("/favicon.ico"))
    }

    @Test fun `the routes - status, page, not found, bad request`() {
        val page = { id: String -> if (id == "k3x9q2ab") "<p>Crêpes</p>" else null }
        val summary = { "up" }
        assertEquals(200, PageProtocol.route("GET / HTTP/1.1", page, summary).status)
        val found = PageProtocol.route("GET /r/k3x9q2ab HTTP/1.1", page, summary)
        assertEquals(200, found.status)
        assertEquals("text/html; charset=utf-8", found.contentType)
        assertEquals(404, PageProtocol.route("GET /r/zzzzzzzz HTTP/1.1", page, summary).status)
        assertEquals(404, PageProtocol.route("GET /etc/passwd HTTP/1.1", page, summary).status)
        assertEquals(400, PageProtocol.route("POST /r/k3x9q2ab HTTP/1.1", page, summary).status)
        assertEquals(400, PageProtocol.route(null, page, summary).status)
        assertTrue(PageProtocol.route("HEAD /r/k3x9q2ab HTTP/1.1", page, summary).headOnly)
    }

    @Test fun `a response counts its bytes, closes the connection and locks the page down`() {
        val body = "Crêpes"  // 7 bytes in UTF-8, 6 characters
        val bytes = PageProtocol.encode(PageProtocol.Response(200, "text/html; charset=utf-8", body.toByteArray()))
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 7\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.contains("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n"))
        assertTrue(text.endsWith("\r\n\r\nCrêpes"))
        val head = PageProtocol.encode(PageProtocol.Response(200, "text/html; charset=utf-8", body.toByteArray(), headOnly = true))
        assertTrue(String(head).endsWith("\r\n\r\n"))
        assertArrayEquals(bytes.copyOfRange(0, head.size), head)
    }

    @Test fun `ids are eight characters from the safe alphabet`() {
        val id = PageProtocol.newId(Random(42))
        assertEquals(8, id.length)
        assertTrue(id.all { it in 'a'..'z' || it in '0'..'9' })
        assertEquals(id, PageProtocol.pageId("/r/$id"))
    }
}
