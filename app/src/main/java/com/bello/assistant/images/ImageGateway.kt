package com.bello.assistant.images

import com.bello.assistant.core.FileLog
import com.bello.assistant.llm.GatewayState

/**
 * The picture services in order, the way `LlmGateway` does it for answers (FR-PAGE-07): the next
 * one after a failure, a cooldown for the one that failed, and a budget for the whole chain — a
 * page is published without its picture rather than kept waiting. No Android here: unit tested.
 */
class ImageGateway(
    private val providers: List<ImageProvider>,
    private val config: ImageConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val state: GatewayState = GatewayState(baseCooldownMs = 60_000),
) {
    class Picture(val bytes: ByteArray, val contentType: String, val source: String, val latencyMs: Long)

    val isEmpty: Boolean get() = providers.isEmpty()

    /** Null when no service could draw it in time; why is in the log. */
    fun generate(prompt: String, budgetMs: Int = config.budgetMs): Picture? {
        if (providers.isEmpty()) return null
        val deadline = clock() + budgetMs
        val skipped = mutableListOf<String>()
        for (provider in providers) {
            val now = clock()
            val left = deadline - now
            if (left < MIN_CALL_MS) {
                skipped += "${provider.id} (budget spent)"
                continue
            }
            if (!state.available(provider.id, now)) {
                skipped += "${provider.id} (${state.whyUnavailable(provider.id, now)})"
                continue
            }
            when (val result = provider.generate(prompt, config.width, config.height, left.toInt())) {
                is ImageResult.Ok -> {
                    state.onSuccess(provider.id, clock(), result.latencyMs)
                    val source = "${provider.id}/${result.model}"
                    FileLog.i(TAG, "IMAGE_OK provider=$source ms=${result.latencyMs} bytes=${result.bytes.size} " +
                        "used=${state.status(provider.id).usedToday}" +
                        if (skipped.isEmpty()) "" else " skipped=${skipped.joinToString()}")
                    return Picture(result.bytes, result.contentType, source, result.latencyMs)
                }
                is ImageResult.Failed -> {
                    state.onFailure(provider.id, clock(), result.failure)
                    FileLog.w(TAG, "IMAGE_FAIL provider=${provider.id} kind=${result.failure.kind} " +
                        "detail=${result.failure.detail} cooldown=${state.whyUnavailable(provider.id, clock())}")
                }
            }
        }
        FileLog.w(TAG, "IMAGE_NONE providers=${providers.size}" +
            if (skipped.isEmpty()) "" else " skipped=${skipped.joinToString()}")
        return null
    }

    /** One line per service, for `scripts/page.sh images`. */
    fun statusLines(): List<String> {
        val now = clock()
        return providers.map { p ->
            val s = state.status(p.id)
            "${p.label} ok=${s.ok} fail=${s.failed} today=${s.usedToday} state=${state.whyUnavailable(p.id, now)}" +
                (s.lastError?.let { " last=$it" } ?: "")
        }
    }

    private companion object {
        const val TAG = "images"
        /** Below this a call cannot succeed: FLUX schnell alone takes a couple of seconds. */
        const val MIN_CALL_MS = 3_000L
    }
}
