package com.bello.assistant.images

import com.bello.assistant.core.FileLog
import com.bello.assistant.llm.FailureKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * FLUX.1 [schnell] on Cloudflare Workers AI (docs/free-services.md): a free account and a token, no
 * card, 10 000 neurons a day — a couple of hundred pictures. The answer is JSON with the JPEG in
 * base64, always 1024 x 1024: the page crops it with CSS.
 */
class CloudflareImageProvider(
    private val config: ImageProviderConfig,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.cloudflare.com/client/v4",
) : ImageProvider {

    override val id get() = config.id
    override val label get() = config.label

    override fun generate(prompt: String, width: Int, height: Int, timeoutMs: Int): ImageResult {
        val body = JSONObject()
            .put("prompt", prompt.take(MAX_PROMPT))
            .put("steps", STEPS)
            // Nothing else: the model refuses any other field, `seed` included (400, code 5006),
            // although its own documentation shows one.
            .toString()
        val http = Request.Builder()
            .url("$baseUrl/accounts/${config.accountId}/ai/run/${config.model}")
            .addHeader("Authorization", "Bearer ${config.key}")
            .post(body.toRequestBody(JSON))
            .build()
        val call = client.newBuilder()
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(http)
        val started = System.nanoTime()
        return try {
            call.execute().use { response ->
                val raw = response.body?.byteStream()?.use { ImageHttp.readCapped(it, MAX_JSON_BYTES) }
                    ?: return ImageResult.Failed(FailureKind.EMPTY, "answer too big or empty")
                val text = String(raw, Charsets.UTF_8)
                if (!response.isSuccessful) {
                    return ImageHttp.failure(response.code, CloudflareParse.error(text), response.header("Retry-After"))
                }
                val bytes = CloudflareParse.image(text)
                    ?: return ImageResult.Failed(FailureKind.EMPTY, "no image: ${CloudflareParse.error(text)}")
                if (!ImageHttp.looksLikeImage(bytes)) return ImageResult.Failed(FailureKind.EMPTY, "not an image")
                ImageResult.Ok(bytes, ImageHttp.contentTypeOf(bytes), (System.nanoTime() - started) / 1_000_000, config.model)
            }
        } catch (t: Throwable) {
            FileLog.w(TAG, "${config.id} call failed: ${t.javaClass.simpleName}: ${t.message}")
            ImageResult.Failed(ImageHttp.kindOf(t), "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "images"
        /** FLUX schnell is distilled for 1–4 steps; Cloudflare accepts up to 8. */
        const val STEPS = 4
        const val MAX_PROMPT = 2048
        const val MAX_JSON_BYTES = ImageHttp.MAX_BYTES * 4 / 3 + 4096
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Cloudflare's envelope, kept pure so it is tested against real payloads. */
object CloudflareParse {

    /** `{"result":{"image":"<base64>"},"success":true}`; null for anything else. */
    fun image(json: String): ByteArray? = runCatching {
        val encoded = JSONObject(json).optJSONObject("result")?.optString("image").orEmpty()
        encoded.takeIf { it.isNotEmpty() }?.decodeBase64()?.toByteArray()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** `{"errors":[{"code":…,"message":…}]}`, or the start of the body. */
    fun error(json: String): String = runCatching {
        val errors = JSONObject(json).optJSONArray("errors")
        (0 until (errors?.length() ?: 0)).mapNotNull { i ->
            errors!!.optJSONObject(i)?.let { e -> "${e.optInt("code")}: ${e.optString("message")}" }
        }.joinToString("; ")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: ImageHttp.shorten(json)
}
