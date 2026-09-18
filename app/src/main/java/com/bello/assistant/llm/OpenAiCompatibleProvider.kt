package com.bello.assistant.llm

import com.bello.assistant.core.FileLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Chat completions in the OpenAI dialect — which every free tier we use speaks, Gemini included
 * through its compatibility endpoint (FR-LLM-02). One instance per configured provider.
 */
class OpenAiCompatibleProvider(
    private val config: ProviderConfig,
    private val client: OkHttpClient,
) : LlmProvider {

    override val id get() = config.id
    override val model get() = config.model

    override fun complete(request: LlmRequest): LlmResult {
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages(request))
            .put("max_tokens", request.maxTokens)
            .put("temperature", 0.7)
            .put("stream", false)
            .withExtra(config.extra)
            .toString()
        val http = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val call = client.newBuilder()
            .callTimeout(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(http)
        val started = System.nanoTime()
        return try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                val ms = (System.nanoTime() - started) / 1_000_000
                if (!response.isSuccessful) {
                    failure(response.code, text, response.header("Retry-After"))
                } else {
                    val answer = OpenAiParse.content(text)
                    if (answer.isNullOrBlank()) LlmResult.Failed(FailureKind.EMPTY, shorten(text))
                    else LlmResult.Ok(answer, config.model, ms)
                }
            }
        } catch (t: Throwable) {
            FileLog.w(TAG, "${config.id} call failed", t)
            LlmResult.Failed(kindOf(t), "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Provider-specific fields from the config, e.g. how much a model may think before answering. */
    private fun JSONObject.withExtra(extra: String): JSONObject {
        if (extra.isBlank()) return this
        val parsed = runCatching { JSONObject(extra) }.getOrElse {
            FileLog.w(TAG, "${config.id}: ignoring unreadable extra fields: $extra")
            return this
        }
        parsed.keys().forEach { put(it, parsed.get(it)) }
        return this
    }

    private fun messages(request: LlmRequest) = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", request.system))
        request.messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
    }

    private fun failure(code: Int, body: String, retryAfter: String?): LlmResult.Failed {
        val kind = when {
            code == 429 -> FailureKind.RATE_LIMIT
            code == 401 || code == 403 -> FailureKind.AUTH
            code >= 500 -> FailureKind.SERVER
            else -> FailureKind.BAD_REQUEST
        }
        return LlmResult.Failed(kind, "http=$code ${OpenAiParse.errorMessage(body)}", OpenAiParse.retryAfterMs(retryAfter))
    }

    private fun kindOf(t: Throwable) = when (t) {
        is SocketTimeoutException, is InterruptedIOException -> FailureKind.TIMEOUT
        is SSLException -> FailureKind.NETWORK
        is IOException -> FailureKind.NETWORK
        else -> FailureKind.NETWORK
    }

    private companion object {
        const val TAG = "llm"
        val JSON = "application/json; charset=utf-8".toMediaType()
        fun shorten(s: String) = s.replace(Regex("\\s+"), " ").take(160)
    }
}

/** Parsing kept pure so it can be unit tested against real provider payloads. */
object OpenAiParse {
    fun content(json: String): String? = runCatching {
        val choices = JSONObject(json).optJSONArray("choices") ?: return null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> (0 until content.length())
                .mapNotNull { content.optJSONObject(it)?.optString("text") }
                .joinToString("")
            else -> null
        }
    }.getOrNull()

    fun errorMessage(json: String): String = runCatching {
        val error = JSONObject(json).opt("error")
        when (error) {
            is JSONObject -> error.optString("message").ifEmpty { error.toString() }
            is String -> error
            else -> ""
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { shorten(it) } ?: shorten(json)

    /** `Retry-After` in seconds; providers also send fractional seconds ("7.5"). */
    fun retryAfterMs(header: String?): Long? {
        val value = header?.trim()?.toDoubleOrNull() ?: return null
        return if (value <= 0) null else (value * 1000).toLong()
    }

    private fun shorten(s: String) = s.replace(Regex("\\s+"), " ").take(160)
}
