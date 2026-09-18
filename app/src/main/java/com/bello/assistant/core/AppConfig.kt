package com.bello.assistant.core

import android.content.Context
import com.bello.assistant.llm.LlmConfig
import org.json.JSONObject
import java.io.File

/**
 * The whole on-device configuration file (`config.json`, pushed with `scripts/push-config.sh`):
 * the providers and their keys, plus where "quel temps fait-il ?" means and which feeds the news
 * comes from. Phase 6 turns this into a settings screen; for now the file is the settings.
 */
data class AppConfig(
    val llm: LlmConfig,
    val city: String = "Grasse",
    val newsFeeds: List<String> = DEFAULT_FEEDS,
    val maxTurns: Int = 10,
    val sessionIdleMs: Long = 10 * 60_000,
) {
    companion object {
        const val FILE_NAME = "config.json"

        val DEFAULT_FEEDS = listOf(
            "https://www.lemonde.fr/rss/une.xml",
            "https://www.francetvinfo.fr/titres.rss",
        )

        fun file(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

        fun load(context: Context): AppConfig {
            val file = file(context)
            if (!file.isFile) {
                FileLog.w(TAG, "no ${file.absolutePath}; free keys: " +
                    LlmConfig.KEY_URLS.entries.joinToString { "${it.key} → ${it.value}" })
                return AppConfig(LlmConfig.EMPTY)
            }
            val text = runCatching { file.readText() }.getOrDefault("")
            val config = parse(text)
            config.llm.problems.forEach { FileLog.w(TAG, "config: $it") }
            return config
        }

        /** Never throws: a broken file leaves Bello with no provider, not without a face. */
        fun parse(json: String): AppConfig {
            val llm = LlmConfig.parse(json)
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return AppConfig(llm)
            val feeds = root.optJSONArray("newsFeeds")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }?.takeIf { it.isNotEmpty() } ?: DEFAULT_FEEDS
            return AppConfig(
                llm = llm,
                city = root.optString("city").ifBlank { "Grasse" },
                newsFeeds = feeds,
                maxTurns = root.optInt("maxTurns", 10),
                sessionIdleMs = root.optLong("sessionIdleMs", 10 * 60_000),
            )
        }

        private const val TAG = "config"
    }
}
