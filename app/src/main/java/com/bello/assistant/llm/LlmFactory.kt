package com.bello.assistant.llm

import android.content.Context
import com.bello.assistant.core.AppConfig
import com.bello.assistant.core.FileLog
import com.bello.assistant.net.HttpClients
import java.io.File

/**
 * Builds the gateway from the on-device config file — the only place API keys live (never in the
 * repository). Push one with `scripts/push-config.sh`; see `config/bello.example.json`.
 */
object LlmFactory {
    private const val TAG = "llm"
    fun configFile(context: Context): File = AppConfig.file(context)

    fun loadConfig(context: Context): LlmConfig = AppConfig.load(context).llm

    /** One gateway per process; the activity attaches the view the hidden web page lives in. */
    fun build(context: Context, config: LlmConfig = loadConfig(context)): LlmGateway {
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
