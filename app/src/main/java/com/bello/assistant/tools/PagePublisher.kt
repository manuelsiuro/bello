package com.bello.assistant.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.bello.assistant.core.FileLog
import com.bello.assistant.llm.LlmGateway
import com.bello.assistant.net.LocalAddress
import com.bello.assistant.net.PageServer
import com.bello.assistant.net.PageStore
import java.io.File
import java.util.Date

/**
 * Turns a Markdown answer into a page the phone can open (FR-PAGE-03/04): renders it, serves it
 * from the tablet, and returns the URL with its QR code. Owned by `BelloApp` so a config reload
 * keeps the pages and the port. The listener is open only while a page exists. Never throws.
 */
class PagePublisher(
    context: Context,
    portSetting: () -> Int,
    private val address: () -> String? = LocalAddress::wifiIpv4,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Published(val id: String, val url: String, val title: String?, val qrRows: List<String>)

    private val app = context.applicationContext
    private val store = PageStore()
    private val server = PageServer(store, portSetting, clock)
    private val main = Handler(Looper.getMainLooper())
    private val sweep = Runnable { sweepNow() }

    /** The tablet's address on the Wi-Fi, or null when it has none. */
    fun address(): String? = address.invoke()

    /** Not for the main thread. Null when nothing could be served; the reason is in the log. */
    fun publish(markdown: String, host: String, truncated: Boolean = false): Published? {
        try {
            if (!server.start()) return null
            val title = MarkdownLite.title(markdown)
            val html = PageHtml.render(
                template(), title ?: "Bello", MarkdownLite.toHtml(markdown, truncated), LlmGateway.dateLabel(Date()),
            )
            val id = store.publish(html, clock())
            val url = "http://$host:${server.boundPort}/r/$id"
            val rows = QrCode.modules(url)
            main.post { scheduleSweep() }
            FileLog.i(TAG, "PAGE_PUBLISHED id=$id url=$url chars=${html.length} title=${title ?: "-"}")
            return Published(id, url, title, rows)
        } catch (t: Throwable) {
            FileLog.w(TAG, "PAGE_PUBLISH_FAILED ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
    }

    fun status(): String = server.status()

    /** Not for the main thread. */
    fun stop() {
        main.removeCallbacks(sweep)
        server.stop()
    }

    // Main thread: the next expiry is when the port may close.
    private fun scheduleSweep() {
        main.removeCallbacks(sweep)
        val at = store.nextExpiry() ?: return
        main.postDelayed(sweep, (at - clock()).coerceAtLeast(1_000L))
    }

    private fun sweepNow() {
        val dropped = store.sweep(clock())
        if (dropped > 0) FileLog.i(TAG, "PAGE_EXPIRED count=$dropped left=${store.size()}")
        if (store.isEmpty()) Thread({ server.stop() }, "page-off").start() else scheduleSweep()
    }

    /** A file pushed next to the config wins over the shipped asset, like `gemini.js` does. */
    private fun template(): String {
        val override = File(app.getExternalFilesDir(null), TEMPLATE_FILE)
        if (override.isFile) runCatching { override.readText() }.getOrNull()?.let { return it }
        return runCatching { app.assets.open("page/$TEMPLATE_FILE").bufferedReader().use { it.readText() } }
            .getOrElse { PageHtml.MINIMAL }
    }

    private companion object {
        const val TAG = "pages"
        const val TEMPLATE_FILE = "page.html"
    }
}
