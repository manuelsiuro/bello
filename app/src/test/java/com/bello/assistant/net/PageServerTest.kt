package com.bello.assistant.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.util.concurrent.TimeUnit

/** The real server on an ephemeral port, talked to by OkHttp and by raw sockets. */
class PageServerTest {

    private val store = PageStore()
    private val server = PageServer(store, portSetting = { 0 })
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Before fun start() { assertTrue(server.start()); assertTrue(server.boundPort > 0) }
    @After fun stop() { server.stop() }

    private fun url(path: String) = "http://127.0.0.1:${server.boundPort}$path"
    private fun get(path: String) = client.newCall(Request.Builder().url(url(path)).build()).execute()

    @Test fun `a published page is served with its type and its accents`() {
        val id = store.publish("<!doctype html><p>Crêpes à l'ancienne</p>", System.currentTimeMillis())
        get("/r/$id").use { response ->
            assertEquals(200, response.code)
            assertEquals("text/html; charset=utf-8", response.header("Content-Type"))
            assertEquals("close", response.header("Connection"))
            assertEquals("<!doctype html><p>Crêpes à l'ancienne</p>", response.body!!.string())
        }
        get("/").use { assertEquals(200, it.code); assertTrue(it.body!!.string().contains("up port=")) }
    }

    @Test fun `an unknown page, a bad method and garbage do not kill the server`() {
        get("/r/zzzzzzzz").use { assertEquals(404, it.code) }
        get("/etc/passwd").use { assertEquals(404, it.code) }
        client.newCall(Request.Builder().url(url("/r/zzzzzzzz")).post(okhttp3.RequestBody.create(null, "x")).build())
            .execute().use { assertEquals(400, it.code) }
        Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.getOutputStream().write("GARBAGE\r\n\r\n".toByteArray())
            val reply = socket.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 400 Bad Request", reply)
        }
        // A TLS hello (HTTPS-first browsers) or anything not starting like a method is closed without a word.
        for (probe in listOf(byteArrayOf(0x16, 0x03, 0x01), "garbage\r\n\r\n".toByteArray())) {
            Socket("127.0.0.1", server.boundPort).use { socket ->
                socket.getOutputStream().write(probe)
                assertEquals(-1, socket.getInputStream().read())
            }
        }
        val id = store.publish("<p>ok</p>", System.currentTimeMillis())
        get("/r/$id").use { assertEquals(200, it.code) }
    }

    @Test fun `an idle connection does not block the next client`() {
        val idle = Socket("127.0.0.1", server.boundPort)
        val id = store.publish("<p>ok</p>", System.currentTimeMillis())
        val started = System.currentTimeMillis()
        get("/r/$id").use { assertEquals(200, it.code) }
        assertTrue(System.currentTimeMillis() - started < 2_000)
        idle.close()
    }

    @Test fun `stop closes the port`() {
        val port = server.boundPort
        server.stop()
        assertFalse(server.isRunning)
        assertEquals(-1, server.boundPort)
        try {
            Socket("127.0.0.1", port).close()
            throw AssertionError("still accepting")
        } catch (expected: IOException) { /* refused */ }
        assertTrue(server.status().startsWith("down"))
    }
}
