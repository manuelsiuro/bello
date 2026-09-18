package com.bello.assistant.llm

import android.content.Context
import com.bello.assistant.core.FileLog
import com.bello.assistant.net.HttpClients
import java.io.File

/**
 * Builds the gateway from the on-device config file — the only place API keys live (never in the
 * repository). Push one with `scripts/push-config.sh`; see `config/bello.example.json`.
 */
object LlmFactory {
    private const val TAG = "llm"
    const val CONFIG_NAME = "config.json"

    fun configFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, CONFIG_NAME)

    fun loadConfig(context: Context): LlmConfig {
        val file = configFile(context)
        if (!file.isFile) {
            FileLog.w(TAG, "no ${file.absolutePath}; free keys: " +
                LlmConfig.KEY_URLS.entries.joinToString { "${it.key} → ${it.value}" })
            return LlmConfig.EMPTY
        }
        val config = LlmConfig.parse(runCatching { file.readText() }.getOrDefault(""))
        config.problems.forEach { FileLog.w(TAG, "config: $it") }
        return config
    }

    /** One gateway per process; the activity attaches the view the hidden web page lives in. */
    fun build(context: Context): LlmGateway {
        val config = loadConfig(context)
        val providers = config.providers.filter { it.enabled }.map { cfg ->
            when (cfg.type) {
                ProviderConfig.Type.OPENAI ->
                    OpenAiCompatibleProvider(cfg, HttpClients.base(context))
                ProviderConfig.Type.GEMINI_WEB ->
                    GeminiWebProvider(context, cfg.baseUrl)
            }
        }
        FileLog.i(TAG, "gateway with ${providers.size} provider(s): " +
            config.providers.joinToString { "${it.label}${if (it.enabled) "" else " (off)"}" } +
            " timeout=${config.timeoutMs}ms cooldown=${config.cooldownMs}ms")
        return LlmGateway(providers, config)
    }
}
