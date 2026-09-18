package com.bello.assistant.tools

import android.content.Context
import com.bello.assistant.core.FileLog

/**
 * Headlines from RSS feeds (FR-TOOL-06). The titles are fetched here and handed to a provider to
 * be turned into two or three spoken sentences; if no provider answers, the titles are read out.
 */
class News(private val context: Context) {

    fun headlines(feeds: List<String>, max: Int = 6): List<String> {
        val titles = mutableListOf<String>()
        for (feed in feeds) {
            val body = ToolHttp.getText(context, feed, TAG) ?: continue
            titles += RssTitles.parse(body).take(max)
            if (titles.size >= max) break
        }
        FileLog.i(TAG, "${titles.size} headline(s) from ${feeds.size} feed(s)")
        return titles.distinct().take(max)
    }


    private companion object { const val TAG = "news" }
}

/** Titles out of an RSS document, without an XML parser. Pure, unit tested. */
object RssTitles {

    private val ITEM = Regex("<item[\\s>][\\s\\S]*?</item>", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
    private val CDATA = Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>")
    private val TAGS = Regex("<[^>]+>")

    fun parse(xml: String): List<String> =
        ITEM.findAll(xml)
            .mapNotNull { item -> TITLE.find(item.value)?.groupValues?.get(1) }
            .map { clean(it) }
            .filter { it.isNotBlank() }
            .toList()

    private fun clean(raw: String): String = raw
        .let { CDATA.find(it)?.groupValues?.get(1) ?: it }
        .replace(TAGS, " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#39;", "'").replace("&laquo;", "«").replace("&raquo;", "»")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
