package com.bello.assistant.images

import org.json.JSONObject

/**
 * The picture services of the details page (FR-PAGE-07), from the `images` block of `config.json`.
 * Order is the priority, as for the chat providers. No block, or no usable entry, means no picture:
 * the page is then exactly what it was before. Pure, unit tested.
 */
data class ImageConfig(
    val providers: List<ImageProviderConfig> = emptyList(),
    /** What is asked for; Cloudflare's FLUX answers 1024 x 1024 whatever is asked. */
    val width: Int = 1024,
    val height: Int = 768,
    /** The whole chain: a page is never held longer than this for its picture. */
    val budgetMs: Int = 25_000,
    val problems: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = providers.none { it.enabled }

    companion object {
        const val CLOUDFLARE = "cloudflare"
        const val POLLINATIONS = "pollinations"

        /** Where to get a free key — neither asks for a card. */
        val KEY_URLS = mapOf(
            CLOUDFLARE to "dash.cloudflare.com → AI → Workers AI → Use REST API",
            POLLINATIONS to "enter.pollinations.ai",
        )

        /** Never throws; an unusable entry is dropped and reported. */
        fun parse(root: JSONObject?): ImageConfig {
            if (root == null) return ImageConfig()
            val problems = mutableListOf<String>()
            val array = root.optJSONArray("providers")
            val providers = (0 until (array?.length() ?: 0)).mapNotNull { i ->
                val entry = array!!.optJSONObject(i) ?: return@mapNotNull null
                val preset = entry.optString("preset", entry.optString("id")).lowercase()
                val key = entry.optString("key").ifBlank { entry.optString("apiKey") }.trim()
                val provider = ImageProviderConfig(
                    id = entry.optString("id").ifBlank { preset },
                    preset = preset,
                    key = key,
                    accountId = entry.optString("accountId").trim(),
                    model = entry.optString("model").ifBlank { defaultModel(preset) },
                    enabled = entry.optBoolean("enabled", true),
                )
                when {
                    preset !in setOf(CLOUDFLARE, POLLINATIONS) -> {
                        problems += "images: unknown preset '$preset'"; null
                    }
                    preset == CLOUDFLARE && (key.isBlank() || provider.accountId.isBlank()) -> {
                        problems += "images: cloudflare needs a key and an accountId (${KEY_URLS[CLOUDFLARE]})"; null
                    }
                    else -> provider
                }
            }
            return ImageConfig(
                providers = providers,
                width = root.optInt("width", 1024).coerceIn(256, 1536),
                height = root.optInt("height", 768).coerceIn(256, 1536),
                budgetMs = root.optInt("budgetMs", 25_000).coerceIn(5_000, 60_000),
                problems = problems,
            )
        }

        private fun defaultModel(preset: String) = when (preset) {
            CLOUDFLARE -> "@cf/black-forest-labs/flux-1-schnell"
            POLLINATIONS -> "flux"
            else -> ""
        }
    }
}

/** One picture service. Pollinations without a key still answers, with a weaker model and a logo. */
data class ImageProviderConfig(
    val id: String,
    val preset: String,
    val key: String,
    val accountId: String = "",
    val model: String,
    val enabled: Boolean = true,
) {
    val label: String get() = "$id/$model"
}
