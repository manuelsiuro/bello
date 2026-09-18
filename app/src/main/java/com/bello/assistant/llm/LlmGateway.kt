package com.bello.assistant.llm

import com.bello.assistant.assistant.Responder
import android.view.ViewGroup
import com.bello.assistant.core.FileLog
import com.bello.assistant.ui.FaceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Asks the configured providers in order until one answers (FR-LLM-03, 04). A provider that fails
 * goes on cooldown (FR-LLM-05); when they all fail, Bello says so and looks sad (FR-LLM-10).
 */
class LlmGateway(
    private val providers: List<LlmProvider>,
    private val config: LlmConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nowLabel: () -> String = { dateLabel(Date()) },
) : Responder {

    private val state = GatewayState(config.cooldownMs.toLong(), config.dailyLimit)

    /** Last answer's source and latency, for the debug overlay (FR-LLM-07, FR-DIAG-02). */
    @Volatile var lastSource: String? = null
        private set
    @Volatile var lastLatencyMs: Long = 0
        private set

    override fun answer(question: String): Responder.Answer = ask(question, emptyList(), "")

    /**
     * @param history earlier turns of the session (FR-MEM-01)
     * @param extraSystem facts the user asked Bello to remember (FR-MEM-03), added to the persona
     */
    fun ask(question: String, history: List<LlmMessage>, extraSystem: String): Responder.Answer {
        if (providers.isEmpty()) return Responder.Answer(noProvidersMessage(), isError = true, emotion = FaceState.CONFUSED)
        val system = Persona.system(config.persona, nowLabel()) +
            if (extraSystem.isBlank()) "" else "\n$extraSystem"
        val request = LlmRequest(
            system = system,
            messages = history + LlmMessage.user(question),
            timeoutMs = config.timeoutMs,
        )
        val skipped = mutableListOf<String>()
        val deadline = clock() + config.totalBudgetMs
        for (provider in providers) {
            val now = clock()
            if (now >= deadline) {
                skipped += "${provider.id} (budget spent)"
                continue
            }
            if (!state.available(provider.id, now)) {
                skipped += "${provider.id} (${state.whyUnavailable(provider.id, now)})"
                continue
            }
            if (!provider.isReady()) {
                skipped += "${provider.id} (not ready)"
                continue
            }
            val left = (deadline - clock()).coerceAtMost(config.timeoutMs.toLong())
            when (val result = provider.complete(request.copy(timeoutMs = left.toInt()))) {
                is LlmResult.Ok -> {
                    state.onSuccess(provider.id, clock(), result.latencyMs)
                    val tagged = Persona.split(result.text)
                    lastSource = "${provider.id}/${result.model}"
                    lastLatencyMs = result.latencyMs
                    FileLog.i(TAG, "LLM_OK provider=${provider.id} model=${result.model} " +
                        "ms=${result.latencyMs} chars=${tagged.text.length} emotion=${tagged.emotion} " +
                        "used=${state.status(provider.id).usedToday}" +
                        if (skipped.isEmpty()) "" else " skipped=${skipped.joinToString()}")
                    return Responder.Answer(
                        text = tagged.text,
                        isError = false,
                        emotion = tagged.emotion,
                        source = lastSource,
                    )
                }
                is LlmResult.Failed -> {
                    state.onFailure(provider.id, clock(), result)
                    FileLog.w(TAG, "LLM_FAIL provider=${provider.id} kind=${result.kind} " +
                        "detail=${result.detail} cooldown=${state.whyUnavailable(provider.id, clock())}")
                }
            }
        }
        FileLog.w(TAG, "LLM_NONE all ${providers.size} providers unavailable; skipped=${skipped.joinToString()}")
        lastSource = null
        return Responder.Answer(ALL_FAILED, isError = true, emotion = FaceState.SAD)
    }

    /** One line per provider for the overlay and for `scripts/llm.sh status`. */
    fun statusLines(): List<String> {
        val now = clock()
        return providers.map { p ->
            val s = state.status(p.id)
            "${p.id} model=${p.model} ok=${s.ok} fail=${s.failed} today=${s.usedToday} " +
                "state=${state.whyUnavailable(p.id, now)}" + (s.lastError?.let { " last=$it" } ?: "")
        }
    }

    fun close() = providers.forEach { runCatching { it.close() } }

    /** The hidden Gemini page lives behind the face, so it follows the activity. */
    fun attachWebHost(host: ViewGroup?) =
        web().forEach { it.attach(host, checkNow = providers.firstOrNull() === it) }

    /** FR-GWEB-10: forwarded from the activity so the hidden web page can be released. */
    fun onTrimMemory(level: Int) = web().forEach { it.onTrimMemory(level) }

    private fun web() = providers.filterIsInstance<GeminiWebProvider>()

    private fun noProvidersMessage(): String {
        val problems = config.problems
        FileLog.w(TAG, "no provider configured; problems=${problems.joinToString()}")
        return "Je n'ai pas encore de cerveau branché. Ajoute une clé Gemini ou Groq dans ma configuration."
    }

    companion object {
        private const val TAG = "llm"
        const val ALL_FAILED =
            "Je n'arrive à joindre aucun de mes cerveaux pour le moment. Réessaie dans un petit instant !"

        private val DATE = SimpleDateFormat("EEEE d MMMM yyyy, HH'h'mm", Locale.FRENCH)

        fun dateLabel(date: Date): String = synchronized(DATE) { DATE.format(date) }
    }
}
