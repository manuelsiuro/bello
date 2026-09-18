package com.bello.assistant.net

import com.bello.assistant.core.FileLog
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves the pages of a [PageStore] on the local network (FR-PAGE-04, NFR-SEC-03): one accept
 * thread, two workers (a browser opens a spare connection and sends nothing on it), GET and
 * HEAD only, every connection closed after one answer. Nothing here may ever throw out of its
 * thread — an uncaught exception restarts the whole app (see `BelloApp`). Never call [start] or
 * [stop] on the main thread.
 */
class PageServer(
    private val store: PageStore,
    private val portSetting: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var boundPort: Int = -1
        private set
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val served = AtomicInteger()

    val isRunning: Boolean get() = socket?.isClosed == false

    /** Binds the configured port, or the next few if it is taken. False when nothing could be bound. */
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val wanted = portSetting()
        val last = if (wanted == 0) 0 else wanted + PORT_TRIES
        for (port in wanted..last) {
            val server = try {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port), BACKLOG) }
            } catch (e: IOException) {
                FileLog.w(TAG, "port $port unavailable: ${e.message}")
                continue
            }
            socket = server
            boundPort = server.localPort
            pool = Executors.newFixedThreadPool(WORKERS) { r -> Thread(r, "page-http").apply { isDaemon = true } }
            Thread({ acceptLoop(server) }, "page-server").apply { isDaemon = true }.start()
            FileLog.i(TAG, "PAGE_SERVER_UP port=$boundPort")
            return true
        }
        FileLog.w(TAG, "PAGE_SERVER_FAIL port=$wanted")
        return false
    }

    @Synchronized
    fun stop() {
        val server = socket ?: return
        socket = null
        boundPort = -1
        runCatching { server.close() }
        pool?.shutdownNow()
        pool = null
        FileLog.i(TAG, "PAGE_SERVER_DOWN")
    }

    fun status(): String =
        if (isRunning) "up port=$boundPort pages=${store.size()} served=${served.get()}"
        else "down pages=${store.size()}"

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (server.isClosed) break
                FileLog.w(TAG, "accept failed: ${e.message}")
                runCatching { Thread.sleep(RETRY_MS) }
                continue
            } catch (t: Throwable) {
                FileLog.w(TAG, "PAGE_SERVER_ERROR accept ${t.javaClass.simpleName}: ${t.message}")
                runCatching { Thread.sleep(RETRY_MS) }
                continue
            }
            try {
                pool?.execute { serve(client) } ?: client.close()
            } catch (t: Throwable) {
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            input.mark(1)
            val first = input.read()
            // A browser trying HTTPS first sends a TLS hello (0x16): close at once rather than
            // hold it for the whole read timeout.
            if (first < 'A'.code || first > 'Z'.code) return
            input.reset()
            val requestLine = readLine(input, PageProtocol.MAX_LINE) ?: return
            skipHeaders(input)
            val response = PageProtocol.route(
                requestLine,
                page = { id -> store.get(id, clock()) },
                summary = { "Bello · ${status()}\n" },
            )
            val out = client.getOutputStream()
            out.write(PageProtocol.encode(response))
            out.flush()
            served.incrementAndGet()
            val what = PageProtocol.parse(requestLine)?.path?.take(40) ?: "?"
            FileLog.i(TAG, "PAGE_SERVED path=$what status=${response.status} from=${client.inetAddress?.hostAddress}")
        } catch (e: SocketTimeoutException) {
            // A browser's spare connection, opened in advance and never used: routine, not an error.
            FileLog.i(TAG, "PAGE_IDLE_CLOSED from=${client.inetAddress?.hostAddress}")
        } catch (t: Throwable) {
            FileLog.w(TAG, "PAGE_SERVER_ERROR ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /** One line, without its end; null when the client hangs up or the line is too long. */
    private fun readLine(input: InputStream, max: Int): String? {
        val bytes = ByteArray(max)
        var n = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (n >= max) return null
            bytes[n++] = b.toByte()
        }
        return String(bytes, 0, n, Charsets.ISO_8859_1).trimEnd('\r')
    }

    private fun skipHeaders(input: InputStream) {
        repeat(PageProtocol.MAX_HEADER_LINES) {
            val line = readLine(input, PageProtocol.MAX_LINE) ?: return
            if (line.isEmpty()) return
        }
    }

    private companion object {
        const val TAG = "pages"
        const val PORT_TRIES = 10
        const val BACKLOG = 8
        const val WORKERS = 2
        const val READ_TIMEOUT_MS = 3_000
        const val RETRY_MS = 200L
    }
}
