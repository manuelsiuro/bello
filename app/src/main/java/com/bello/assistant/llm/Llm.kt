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
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    companion object { const val DEFAULT_MAX_TOKENS = 512 }
}

/**
 * How one call may differ from a spoken answer (FR-PAGE-03): a page wants another author, more
 * room and more time. Everything else — the provider order, fallback and cooldowns — is the same.
 */
data class AskOptions(
    /** Replaces the persona and its date line; the facts block, when given, is still appended. */
    val system: String? = null,
    val maxTokens: Int = LlmRequest.DEFAULT_MAX_TOKENS,
    /** Per provider call; null is the configured timeout. */
    val timeoutMs: Int? = null,
    /** The whole chain; null is the configured budget. */
    val budgetMs: Int? = null,
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
    /** @param truncated the provider stopped for lack of room (`finish_reason: length`). */
    data class Ok(val text: String, val model: String, val latencyMs: Long, val truncated: Boolean = false) : LlmResult()
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
