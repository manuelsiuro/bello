package com.bello.assistant.tools

import android.content.Context
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.TextCache
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** What French Wikipedia says about something, short enough to be spoken. */
data class Article(val title: String, val summary: String)

/** One thing that happened on this day, as the encyclopedia's own feed tells it. */
data class PastEvent(val year: Int, val text: String)

/**
 * Turning an encyclopedia page into something a voice can read. Pure and unit tested on the real
 * answers of 2026-09-18, pronunciation asides and all.
 */
object WikiText {

    /** How long an answer may run before it stops being an answer and becomes a lecture. */
    const val MAX_CHARS = 320

    /**
     * The intro of an article carries what print can afford and a voice cannot: the pronunciation
     * between brackets, the local spelling between dashes, a nest of parentheses. All of it goes.
     */
    fun speakable(extract: String, maxSentences: Int = 2): String {
        val flat = extract.replace('\n', ' ').replace(' ', ' ')
        val withoutBrackets = Regex("\\[[^\\]]*]").replace(flat, "")
        val withoutAsides = Regex("[—–][^—–]*[—–]").replace(withoutBrackets, " ")
        val withoutParentheses = dropParentheses(withoutAsides)
        val tidy = withoutParentheses
            .replace(Regex("\\s+([,;:.!?])"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
        return firstSentences(tidy, maxSentences)
    }

    /** Parentheses nest — "(Grassa (normes classique et mistralienne) dans le dialecte local)". */
    private fun dropParentheses(text: String): String = buildString {
        var depth = 0
        for (c in text) {
            when (c) {
                '(' -> depth++
                ')' -> if (depth > 0) depth-- else append(c)
                else -> if (depth == 0) append(c)
            }
        }
    }

    /** Abbreviations end in a dot without ending a sentence: "av. J.-C.", "M. Curie". */
    private val SENTENCE_END = Regex("(?<![A-ZÀ-Ý])(?<!\\bav)(?<!\\bJ\\.-C)(?<!\\bM)(?<!\\bMme)\\.\\s+(?=[A-ZÀ-Ý])")

    fun firstSentences(text: String, maxSentences: Int): String {
        if (text.isBlank()) return ""
        val cuts = SENTENCE_END.findAll(text).map { it.range.first + 1 }.toList()
        val end = cuts.getOrNull(maxSentences - 1) ?: text.length
        var result = text.substring(0, end).trim()
        // One long sentence beats two cut in half: shorten only on a sentence that is already over.
        if (result.length > MAX_CHARS && cuts.isNotEmpty()) {
            result = text.substring(0, cuts.first()).trim()
        }
        return result
    }

    /** The one page the search generator kept, with its title and its intro. */
    fun article(json: String): Article? = runCatching {
        val pages = JSONObject(json).optJSONObject("query")?.optJSONArray("pages") ?: return null
        val page = pages.optJSONObject(0) ?: return null
        val title = page.optString("title").takeIf { it.isNotBlank() } ?: return null
        val summary = speakable(page.optString("extract")).takeIf { it.isNotBlank() } ?: return null
        Article(title, summary)
    }.getOrNull()

    fun events(json: String): List<PastEvent> = runCatching {
        val selected = JSONObject(json).optJSONArray("selected") ?: return emptyList()
        (0 until selected.length()).mapNotNull { i ->
            val row = selected.optJSONObject(i) ?: return@mapNotNull null
            val text = row.optString("text").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val year = row.optInt("year", 0).takeIf { it != 0 } ?: return@mapNotNull null
            PastEvent(year, text.trimEnd('.'))
        }
    }.getOrDefault(emptyList())
}

/**
 * French Wikipedia, for the questions where being right matters more than being chatty: who
 * somebody is, what a place is, what happened on a day. No key, no quota worth counting — the
 * policy asks for a real `User-Agent`, which `ToolHttp` sends.
 *
 * One request does both jobs: the search generator finds the page and the same answer carries its
 * intro, so "Napoléon" comes back as "Napoléon Ier" in under a second.
 */
class Wikipedia(
    context: Context,
    cacheDir: File = File(
        context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir,
        "cache",
    ),
) {

    private val app = context.applicationContext
    private val cache = TextCache(cacheDir)

    fun about(subject: String): Article? {
        val query = subject.trim().takeIf { it.length >= 2 } ?: return null
        val url = "$API?action=query&generator=search&gsrsearch=${encode(query)}&gsrlimit=1" +
            "&prop=extracts&exintro=1&explaintext=1&exsentences=3&format=json&formatversion=2"
        val json = ToolHttp.getText(app, url, TAG) ?: return null
        return WikiText.article(json).also {
            FileLog.i(TAG, if (it == null) "nothing for \"$query\"" else "\"$query\" → ${it.title}")
        }
    }

    /** What happened on that day of the year. The feed does not change, so it is kept for a day. */
    fun onThisDay(monthOfYear: Int, dayOfMonth: Int): List<PastEvent> {
        val month = String.format("%02d", monthOfYear)
        val day = String.format("%02d", dayOfMonth)
        val json = cache.text("wikipedia-$month-$day", A_DAY_MS) {
            ToolHttp.getText(app, "$FEED/onthisday/selected/$month/$day", TAG, timeoutSeconds = 15)
        } ?: return emptyList()
        return WikiText.events(json)
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    private companion object {
        const val TAG = "wikipedia"
        const val API = "https://fr.wikipedia.org/w/api.php"
        const val FEED = "https://fr.wikipedia.org/api/rest_v1/feed"
        const val A_DAY_MS = 24 * 60 * 60 * 1000L
    }
}
