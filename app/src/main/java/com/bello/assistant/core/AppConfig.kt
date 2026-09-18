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
    /** Which public holidays apply: "metropole", "alsace-moselle", or an overseas code. */
    val holidayZone: String = "metropole",
    /** The school holiday zone and académie of the house (Grasse is Nice, zone B). */
    val schoolZone: String = "Zone B",
    val schoolAcademy: String = "Nice",
    /** The fuel the house buys, when a question does not name one. */
    val fuel: String = "gazole",
    /** The SFR TV decoder (docs/sfr-tv-box.md); null when `"tvBox": {"enabled": false}`. */
    val tvBox: TvBoxConfig? = TvBoxConfig(),
) {
    /**
     * Where the decoder is and what the household calls its channels. The defaults are the SFR
     * name of the box on the LAN and the TNT numbering in force since 6 June 2025; a `channels`
     * object in the file replaces the whole table, so that a box with its own plan can be described.
     */
    data class TvBoxConfig(
        val host: String = "stb",
        val port: Int = 7682,
        val channels: Map<String, Int> = DEFAULT_CHANNELS,
        /** Some boxes want `ok` after the digits of a channel number; the STB8 is to be checked. */
        val okAfterDigits: Boolean = false,
    ) {
        companion object {
            val DEFAULT_CHANNELS: Map<String, Int> = linkedMapOf(
                "TF1" to 1, "France 2" to 2, "France 3" to 3, "France 4" to 4, "France 5" to 5, "M6" to 6,
                "Arte" to 7, "LCP" to 8, "W9" to 9, "TMC" to 10, "TFX" to 11, "Gulli" to 12,
                "BFM TV" to 13, "BFM" to 13, "CNews" to 14, "C News" to 14, "LCI" to 15,
                "franceinfo" to 16, "France Info" to 16, "CStar" to 17, "C Star" to 17,
                "T18" to 18, "T 18" to 18, "Novo19" to 19, "Novo 19" to 19,
                "TF1 Séries Films" to 20, "TF1 Séries" to 20, "L'Équipe" to 21, "6ter" to 22, "6 ter" to 22,
                "RMC Story" to 23, "RMC Découverte" to 24, "Chérie 25" to 25,
            )

            fun parse(root: JSONObject?): TvBoxConfig? {
                if (root == null) return TvBoxConfig()
                if (!root.optBoolean("enabled", true)) return null
                val channels = root.optJSONObject("channels")?.let { table ->
                    table.keys().asSequence().mapNotNull { name ->
                        val number = table.optInt(name, -1)
                        if (name.isBlank() || number < 0) null else name to number
                    }.toMap(linkedMapOf())
                }?.takeIf { it.isNotEmpty() } ?: DEFAULT_CHANNELS
                return TvBoxConfig(
                    host = root.optString("host").ifBlank { "stb" },
                    port = root.optInt("port", 7682),
                    channels = channels,
                    okAfterDigits = root.optBoolean("okAfterDigits", false),
                )
            }
        }
    }

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
                holidayZone = root.optString("holidayZone").ifBlank { "metropole" },
                schoolZone = root.optString("schoolZone").ifBlank { "Zone B" },
                schoolAcademy = root.optString("schoolAcademy").ifBlank { "Nice" },
                fuel = root.optString("fuel").ifBlank { "gazole" },
                tvBox = TvBoxConfig.parse(root.optJSONObject("tvBox")),
            )
        }

        private const val TAG = "config"
    }
}
