package com.bello.assistant.llm

import org.json.JSONObject

/** One configured provider (FR-LLM-01). Order in the config file is the priority (FR-LLM-03). */
data class ProviderConfig(
    val id: String,
    val type: Type,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val enabled: Boolean,
    /**
     * Extra JSON fields merged into the request body, as a raw object. Needed because thinking
     * models bill their thinking against `max_tokens`: Gemini 3 cut its answers in half until
     * `reasoning_effort` was turned down.
     */
    val extra: String = "",
) {
    enum class Type { OPENAI, GEMINI_WEB }

    /** What shows in logs and in the debug overlay. */
    val label: String get() = if (type == Type.GEMINI_WEB) id else "$id/$model"
}

/** Everything the gateway needs, read from the on-device config file. */
data class LlmConfig(
    val providers: List<ProviderConfig>,
    val timeoutMs: Int = 20_000,
    val totalBudgetMs: Int = 45_000,   // whole question, all providers: silence has a limit
    val cooldownMs: Int = 60_000,
    val dailyLimit: Int = 0,          // 0 = no local cap; free tiers still answer 429
    val persona: String = Persona.DEFAULT,
    val problems: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = LlmConfig(providers = emptyList())

        /**
         * Free-tier presets (FR-LLM-02). Everything speaks the OpenAI chat-completions dialect,
         * including Gemini through its compatibility endpoint, so one client covers them all.
         *
         * Model names go stale — providers retire them and answer 404. `scripts/models.sh <id>`
         * lists what a key can actually use; set `model` in the config file to override.
         */
        val PRESETS: Map<String, ProviderConfig> = listOf(
            ProviderConfig("gemini", ProviderConfig.Type.OPENAI,
                "https://generativelanguage.googleapis.com/v1beta/openai", "", "gemini-3.6-flash", true,
                extra = """{"reasoning_effort":"none"}"""),
            ProviderConfig("groq", ProviderConfig.Type.OPENAI,
                "https://api.groq.com/openai/v1", "", "openai/gpt-oss-20b", true,
                extra = """{"reasoning_effort":"low"}"""),
            ProviderConfig("mistral", ProviderConfig.Type.OPENAI,
                "https://api.mistral.ai/v1", "", "mistral-small-latest", true),
            ProviderConfig("cerebras", ProviderConfig.Type.OPENAI,
                "https://api.cerebras.ai/v1", "", "llama-3.3-70b", true),
            ProviderConfig("openrouter", ProviderConfig.Type.OPENAI,
                "https://openrouter.ai/api/v1", "", "meta-llama/llama-3.3-70b-instruct:free", true),
            ProviderConfig("geminiweb", ProviderConfig.Type.GEMINI_WEB,
                "https://gemini.google.com/app?hl=fr", "", "gemini-web", false),
        ).associateBy { it.id }

        /** Where to get a free key, for the message shown when nothing is configured. */
        val KEY_URLS = mapOf(
            "gemini" to "aistudio.google.com/apikey",
            "groq" to "console.groq.com/keys",
            "mistral" to "console.mistral.ai/api-keys",
            "cerebras" to "cloud.cerebras.ai",
            "openrouter" to "openrouter.ai/keys",
        )

        /**
         * Parses the config file. Never throws: anything unusable is reported in [problems] so the
         * assistant can say what is wrong instead of dying at startup.
         */
        fun parse(json: String): LlmConfig {
            val problems = mutableListOf<String>()
            val root = try {
                JSONObject(json)
            } catch (t: Throwable) {
                return EMPTY.copy(problems = listOf("config.json is not valid JSON: ${t.message}"))
            }
            val providers = mutableListOf<ProviderConfig>()
            val array = root.optJSONArray("providers")
            for (i in 0 until (array?.length() ?: 0)) {
                val o = array!!.optJSONObject(i) ?: continue
                val presetName = o.optString("preset", o.optString("id"))
                val preset = PRESETS[presetName]
                if (preset == null && !o.has("baseUrl")) {
                    problems += "provider #${i + 1}: unknown preset '$presetName' and no baseUrl"
                    continue
                }
                val base = preset ?: ProviderConfig(presetName.ifEmpty { "custom${i + 1}" },
                    ProviderConfig.Type.OPENAI, "", "", "", true)
                val cfg = base.copy(
                    id = o.optString("id").ifEmpty { base.id },
                    type = typeOf(o.optString("type"), base.type),
                    baseUrl = o.optString("baseUrl").ifEmpty { base.baseUrl }.trimEnd('/'),
                    apiKey = o.optString("key").ifEmpty { o.optString("apiKey") }.trim(),
                    model = o.optString("model").ifEmpty { base.model },
                    enabled = o.optBoolean("enabled", base.enabled),
                    extra = o.optJSONObject("extra")?.toString() ?: base.extra,
                )
                if (cfg.type == ProviderConfig.Type.OPENAI && cfg.apiKey.isEmpty()) {
                    problems += "provider '${cfg.id}': no API key, ignored"
                    continue
                }
                if (providers.any { it.id == cfg.id }) {
                    problems += "provider '${cfg.id}': duplicate id, ignored"
                    continue
                }
                providers += cfg
            }
            return LlmConfig(
                providers = providers,
                timeoutMs = root.optInt("timeoutMs", 20_000),
                totalBudgetMs = root.optInt("totalBudgetMs", 45_000),
                cooldownMs = root.optInt("cooldownMs", 60_000),
                dailyLimit = root.optInt("dailyLimit", 0),
                persona = root.optString("persona").ifEmpty { Persona.DEFAULT },
                problems = problems,
            )
        }

        private fun typeOf(value: String, fallback: ProviderConfig.Type) = when (value.lowercase()) {
            "" -> fallback
            "gemini-web", "geminiweb", "web" -> ProviderConfig.Type.GEMINI_WEB
            else -> ProviderConfig.Type.OPENAI
        }
    }
}
