package com.bello.assistant.images

import com.bello.assistant.core.FileLog
import com.bello.assistant.llm.FailureKind
import com.bello.assistant.tools.ToolHttp
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Pollinations (docs/free-services.md): a GET whose answer is the JPEG itself. With a free key (no
 * card) it runs FLUX schnell on `gen.pollinations.ai`. Without one it still answers on the old
 * address, but silently swaps in a weak model (`x-model-used: sana`) and stamps its logo — the
 * last resort, not a choice.
 */
class PollinationsImageProvider(
    private val config: ImageProviderConfig,
    private val client: OkHttpClient,
    private val keyedBase: String = "https://gen.pollinations.ai/image/",
    private val anonymousBase: String = "https://image.pollinations.ai/prompt/",
) : ImageProvider {

    override val id get() = config.id
    override val label get() = if (config.key.isBlank()) "${config.id}/anonymous" else config.label

    override fun generate(prompt: String, width: Int, height: Int, timeoutMs: Int): ImageResult {
        val request = Request.Builder()
            .url(url(prompt, width, height, (1..Int.MAX_VALUE).random()))
            .header("User-Agent", ToolHttp.USER_AGENT)
            .apply { if (config.key.isNotBlank()) header("Authorization", "Bearer ${config.key}") }
            .build()
        val call = client.newBuilder()
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
        val started = System.nanoTime()
        return try {
            call.execute().use { response ->
                val bytes = response.body?.byteStream()?.use { ImageHttp.readCapped(it, ImageHttp.MAX_BYTES) }
                    ?: return ImageResult.Failed(FailureKind.EMPTY, "answer too big or empty")
                if (!response.isSuccessful) {
                    return ImageHttp.failure(response.code, String(bytes, Charsets.UTF_8), response.header("Retry-After"))
                }
                if (!ImageHttp.looksLikeImage(bytes)) {
                    return ImageResult.Failed(FailureKind.EMPTY, "not an image: ${response.header("Content-Type")}")
                }
                val model = response.header("x-model-used") ?: config.model
                ImageResult.Ok(bytes, ImageHttp.contentTypeOf(bytes), (System.nanoTime() - started) / 1_000_000, model)
            }
        } catch (t: Throwable) {
            FileLog.w(TAG, "${config.id} call failed: ${t.javaClass.simpleName}: ${t.message}")
            ImageResult.Failed(ImageHttp.kindOf(t), "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** The prompt is one path segment; `enhance=false` because Bello's author already wrote it. */
    fun url(prompt: String, width: Int, height: Int, seed: Int): HttpUrl {
        val keyed = config.key.isNotBlank()
        return (if (keyed) keyedBase else anonymousBase).toHttpUrl().newBuilder()
            .addPathSegment(prompt.take(MAX_PROMPT))
            .apply { if (keyed) addQueryParameter("model", config.model) }
            .addQueryParameter("width", width.toString())
            .addQueryParameter("height", height.toString())
            .addQueryParameter("seed", seed.toString())
            .addQueryParameter("nologo", "true")
            .addQueryParameter("enhance", "false")
            .build()
    }

    private companion object {
        const val TAG = "images"
        /** The prompt is in the URL; servers refuse much longer request lines. */
        const val MAX_PROMPT = 1200
    }
}
