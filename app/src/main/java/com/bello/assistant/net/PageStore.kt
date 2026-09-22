package com.bello.assistant.net

import java.security.SecureRandom
import java.util.Random

/**
 * The pages the tablet currently serves (FR-PAGE-04): in memory, a handful, for a couple of
 * hours — long enough to cook from, short enough that the port is not open for ever. A page may
 * carry its picture (FR-PAGE-07), which lives and goes with it. Nothing is ever written to disk.
 * Thread-safe, pure, unit tested.
 */
class PageStore(
    private val ttlMs: Long = TTL_MS,
    private val maxPages: Int = MAX_PAGES,
    private val random: Random = SecureRandom(),
) {
    class Image(val bytes: ByteArray, val contentType: String)

    private class Page(val html: String, val image: Image?, val publishedAt: Long)

    private val pages = LinkedHashMap<String, Page>()

    @Synchronized
    fun publish(html: String, now: Long): String = publish(null, now) { html }

    /** [render] gets the page's id, so the page can point at its own picture. */
    @Synchronized
    fun publish(image: Image?, now: Long, render: (id: String) -> String): String {
        sweep(now)
        while (pages.size >= maxPages) pages.remove(pages.keys.first())
        var id = PageProtocol.newId(random)
        while (id in pages) id = PageProtocol.newId(random)
        pages[id] = Page(render(id), image, now)
        return id
    }

    @Synchronized
    fun get(id: String, now: Long): String? {
        sweep(now)
        return pages[id]?.html
    }

    @Synchronized
    fun image(id: String, now: Long): Image? {
        sweep(now)
        return pages[id]?.image
    }

    /** Drops what has expired; returns how many. */
    @Synchronized
    fun sweep(now: Long): Int {
        val expired = pages.filterValues { now - it.publishedAt >= ttlMs }.keys
        expired.forEach { pages.remove(it) }
        return expired.size
    }

    @Synchronized fun size(): Int = pages.size

    @Synchronized fun isEmpty(): Boolean = pages.isEmpty()

    /** What the pictures weigh in memory, for the status line. */
    @Synchronized fun imageBytes(): Int = pages.values.sumOf { it.image?.bytes?.size ?: 0 }

    /** When the next page expires, so the owner can sweep then — and close the port if empty. */
    @Synchronized
    fun nextExpiry(): Long? = pages.values.minOfOrNull { it.publishedAt + ttlMs }

    companion object {
        const val TTL_MS = 2L * 60 * 60 * 1000
        const val MAX_PAGES = 10
    }
}
