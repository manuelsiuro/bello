package com.bello.assistant.net

import java.security.SecureRandom
import java.util.Random

/**
 * As much HTTP as a page for the phone needs (FR-PAGE-04, NFR-SEC-03): a GET or HEAD request
 * line, one of two paths, a response that closes the connection. Pure, unit tested; the socket
 * work is in [PageServer].
 */
object PageProtocol {

    const val MAX_LINE = 1024
    const val MAX_HEADER_LINES = 100
    const val ID_LENGTH = 8
    private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    data class Request(val method: String, val path: String)

    class Response(val status: Int, val contentType: String, val body: ByteArray, val headOnly: Boolean = false)

    private val REQUEST_LINE = Regex("^(GET|HEAD) (/\\S*) HTTP/1\\.[01]$")
    private val PAGE_PATH = Regex("^/r/([a-z0-9]{$ID_LENGTH})$")

    /** GET or HEAD only, path without its query; anything else is not a request Bello answers. */
    fun parse(requestLine: String?): Request? {
        val match = REQUEST_LINE.find(requestLine?.trim() ?: return null) ?: return null
        val path = match.groupValues[2].substringBefore('?')
        return Request(match.groupValues[1], path)
    }

    fun pageId(path: String): String? = PAGE_PATH.find(path)?.groupValues?.get(1)

    /**
     * `/` answers with a one-line status (a health check for `curl`); `/r/<id>` with the page;
     * everything else is 404 without a hint, and a line that is not a request is 400.
     */
    fun route(requestLine: String?, page: (String) -> String?, summary: () -> String): Response {
        val request = parse(requestLine) ?: return text(400, "Bad request", headOnly = false)
        val headOnly = request.method == "HEAD"
        if (request.path == "/") return text(200, summary(), headOnly)
        val id = pageId(request.path) ?: return text(404, "Not found", headOnly)
        val html = page(id) ?: return text(404, "Not found", headOnly)
        return Response(200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8), headOnly)
    }

    /** The length counts bytes, not characters — every French page has accents. */
    fun encode(response: Response): ByteArray {
        val reason = when (response.status) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Error"
        }
        val head = "HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Content-Length: ${response.body.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: max-age=7200\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n" +
            "\r\n"
        val headBytes = head.toByteArray(Charsets.ISO_8859_1)
        return if (response.headOnly) headBytes else headBytes + response.body
    }

    /** Eight lower-case letters and digits: 36^8 guesses, and typeable if the code will not scan. */
    fun newId(random: Random = SecureRandom()): String =
        buildString(ID_LENGTH) { repeat(ID_LENGTH) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) } }

    private fun text(status: Int, body: String, headOnly: Boolean) =
        Response(status, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8), headOnly)
}
