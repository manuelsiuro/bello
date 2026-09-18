package com.bello.assistant.llm

/** One message of the prompt. Phase 4 fills [LlmRequest.messages] with the conversation memory. */
data class LlmMessage(val role: String, val content: String) {
    companion object {
        fun user(text: String) = LlmMessage("user", text)
        fun assistant(text: String) = LlmMessage("assistant", text)
    }
}

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    val timeoutMs: Int = 20_000,
    // Reasoning models (Groq's gpt-oss) spend part of this budget thinking before answering.
    val maxTokens: Int = 512,
)

/** Why a call failed. The gateway always tries the next provider; the kind sets the cooldown. */
enum class FailureKind {
    RATE_LIMIT,   // 429 — free-tier quota; respect Retry-After
    SERVER,       // 5xx
    TIMEOUT,
    NETWORK,      // DNS, TLS, connection reset
    AUTH,         // 401/403 — missing or wrong key, nothing to retry soon
    BAD_REQUEST,  // 400/404 — wrong model or base URL
    BLOCKED,      // Gemini Web: captcha / "unusual traffic"
    EMPTY,        // answered, but with nothing speakable
}

sealed class LlmResult {
    data class Ok(val text: String, val model: String, val latencyMs: Long) : LlmResult()
    data class Failed(
        val kind: FailureKind,
        val detail: String,
        val retryAfterMs: Long? = null,
    ) : LlmResult()
}

/** A source of answers: an HTTP API (FR-LLM-01) or the Gemini web page (FR-GWEB-01). */
interface LlmProvider {
    val id: String
    val model: String

    /** Called on a background thread. Must not throw. */
    fun complete(request: LlmRequest): LlmResult

    /** False when the provider knows it cannot serve right now (e.g. Gemini Web not loaded). */
    fun isReady(): Boolean = true

    fun close() {}
}
