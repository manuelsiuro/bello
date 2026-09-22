package com.bello.assistant.images

import com.bello.assistant.llm.FailureKind
import com.bello.assistant.llm.LlmResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/** A service that turns an English prompt into a picture (FR-PAGE-07). */
interface ImageProvider {
    val id: String
    val label: String

    /** Called on a background thread. Must not throw. */
    fun generate(prompt: String, width: Int, height: Int, timeoutMs: Int): ImageResult
}

sealed class ImageResult {
    class Ok(val bytes: ByteArray, val contentType: String, val latencyMs: Long, val model: String) : ImageResult()

    /** The chat gateway's failure, so its cooldowns ([com.bello.assistant.llm.GatewayState]) apply as they are. */
    data class Failed(val failure: LlmResult.Failed) : ImageResult() {
        constructor(kind: FailureKind, detail: String, retryAfterMs: Long? = null) :
            this(LlmResult.Failed(kind, detail, retryAfterMs))
    }
}

/** What the two services share: status codes, exceptions, and the few bytes a picture may weigh. */
internal object ImageHttp {

    /** A 1024 x 1024 JPEG from FLUX is 100–250 KB; anything past this is not a picture for a phone. */
    const val MAX_BYTES = 3L * 1024 * 1024

    fun failure(code: Int, detail: String, retryAfter: String?): ImageResult.Failed {
        val kind = when {
            code == 429 || code == 402 -> FailureKind.RATE_LIMIT
            code == 401 || code == 403 -> FailureKind.AUTH
            code >= 500 -> FailureKind.SERVER
            else -> FailureKind.BAD_REQUEST
        }
        val retryMs = retryAfter?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        return ImageResult.Failed(kind, "http=$code ${shorten(detail)}", retryMs)
    }

    fun kindOf(t: Throwable) = when (t) {
        is SocketTimeoutException, is InterruptedIOException -> FailureKind.TIMEOUT
        is IOException -> FailureKind.NETWORK
        else -> FailureKind.NETWORK
    }

    /** JPEG, PNG or WebP, told by their first bytes — a service's error page is sometimes sent as 200. */
    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val b = bytes.take(12).map { it.toInt() and 0xFF }
        val jpeg = b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF
        val png = b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
        val webp = b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 &&
            b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50
        return jpeg || png || webp
    }

    fun contentTypeOf(bytes: ByteArray): String = when (bytes.firstOrNull()?.toInt()?.and(0xFF)) {
        0x89 -> "image/png"
        0x52 -> "image/webp"
        else -> "image/jpeg"
    }

    /** The whole body, or null when it is bigger than [max] — read by chunks, never trusted by its header. */
    fun readCapped(input: InputStream, max: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > max) return null
            out.write(buffer, 0, n)
        }
    }

    fun shorten(s: String) = s.replace(Regex("\\s+"), " ").take(160)
}
