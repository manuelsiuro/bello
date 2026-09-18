package com.bello.assistant.tools

import android.content.Context
import com.bello.assistant.core.FileLog
import com.bello.assistant.net.HttpClients
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetching for the tools. Free public services hiccup — Open-Meteo answered 503 once in testing —
 * and losing an answer to a single blip is not acceptable when someone is waiting for it, so a
 * server error or a dropped connection is tried once more.
 *
 * Every request says who is calling: Wikimedia's policy asks for a real name and a contact and
 * blocks the default agent of an HTTP library, and Open Food Facts and Radio Browser ask the same.
 */
object ToolHttp {

    const val USER_AGENT = "Bello/1.0 (+https://github.com/manuelsiuro/bello)"

    private const val RETRY_DELAY_MS = 700L

    fun getText(context: Context, url: String, tag: String, timeoutSeconds: Long = 12): String? {
        repeat(2) { attempt ->
            val body = attempt(context, url, tag, timeoutSeconds, attempt)
            if (body != null) return body
            if (attempt == 0) Thread.sleep(RETRY_DELAY_MS)
        }
        return null
    }

    private fun attempt(
        context: Context,
        url: String,
        tag: String,
        timeoutSeconds: Long,
        attempt: Int,
    ): String? = runCatching {
        val client = HttpClients.base(context).newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) return@runCatching response.body?.string()
            FileLog.w(tag, "http=${response.code} (try ${attempt + 1}) for ${url.take(120)}")
            null
        }
    }.getOrElse {
        FileLog.w(tag, "request failed (try ${attempt + 1}): ${url.take(120)}", it)
        null
    }
}
