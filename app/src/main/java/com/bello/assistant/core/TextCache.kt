package com.bello.assistant.core

import java.io.File

/**
 * The small answers that are worth keeping: the public holidays of a year never change, the school
 * calendar once a year. Keeping them also answers the question when the network is gone
 * (NFR-REL-02), which is the whole point of a tool that works by itself.
 *
 * The time of the fetch is written in the file rather than read from the filesystem, so the cache
 * can be tested with a clock in hand.
 */
class TextCache(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    /** The cached text, when it was fetched less than [maxAgeMs] ago. */
    fun fresh(name: String, maxAgeMs: Long): String? {
        val (at, text) = read(name) ?: return null
        return text.takeIf { clock() - at <= maxAgeMs }
    }

    /** The cached text whatever its age. */
    fun any(name: String): String? = read(name)?.second

    fun put(name: String, text: String) {
        runCatching {
            dir.mkdirs()
            File(dir, fileName(name)).writeText("${clock()}\n$text")
        }.onFailure { FileLog.w(TAG, "cannot keep $name", it) }
    }

    /**
     * The cached text while it is young enough, what [fetch] brings back otherwise — and the old
     * copy when the fetch fails, because last week's holiday list still answers the question.
     */
    fun text(name: String, maxAgeMs: Long, fetch: () -> String?): String? {
        fresh(name, maxAgeMs)?.let { return it }
        val fetched = fetch()?.takeIf { it.isNotBlank() }
        if (fetched != null) {
            put(name, fetched)
            return fetched
        }
        return any(name)?.also { FileLog.i(TAG, "$name: answering from the kept copy") }
    }

    private fun read(name: String): Pair<Long, String>? = runCatching {
        val file = File(dir, fileName(name))
        if (!file.isFile) return null
        val text = file.readText()
        val cut = text.indexOf('\n')
        if (cut <= 0) return null
        val at = text.substring(0, cut).trim().toLongOrNull() ?: return null
        at to text.substring(cut + 1)
    }.getOrNull()

    private fun fileName(name: String) = name.replace(Regex("[^A-Za-z0-9_-]+"), "-") + ".json"

    private companion object { const val TAG = "cache" }
}
