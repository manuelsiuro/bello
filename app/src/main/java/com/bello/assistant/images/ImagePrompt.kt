package com.bello.assistant.images

/**
 * The picture's prompt travels with the page (FR-PAGE-07): the page's author ends its Markdown with
 * one `[image: …]` line, in English, following the style map of `ToolReplies.PAGE_SYSTEM` — no
 * second call to a provider. This takes that line out of the page and makes it safe to send.
 * Pure, unit tested; the prompt rules are in docs/page-images.md.
 */
object ImagePrompt {

    const val MAX_CHARS = 600

    /**
     * Said positively, because FLUX has no negative prompt: every picture is wordless (text comes out
     * garbled) and the page's own title is the only writing on the page.
     */
    const val TAIL = "Clean wordless image, blank surfaces, high detail."

    private val TAG_LINE = Regex("^\\s*[-*]?\\s*\\[\\s*(?:image|illustration|picture)\\s*:\\s*(.*?)\\s*]\\s*$", RegexOption.IGNORE_CASE)
    private val QUOTES = Regex("^[\"'«»“”`\\s]+|[\"'«»“”`\\s]+$")
    private val SPACES = Regex("\\s+")

    data class Split(val markdown: String, val prompt: String?)

    /** The page without its `[image: …]` line, and that line's prompt when there was one. */
    fun split(markdown: String): Split {
        var prompt: String? = null
        val kept = markdown.lines().filter { line ->
            val match = TAG_LINE.find(line) ?: return@filter true
            prompt = match.groupValues[1]
            false
        }
        return Split(kept.joinToString("\n").trimEnd(), prompt?.let(::clean))
    }

    /** One line, no quotes, not too long, the wordless tail added; null when nothing is left. */
    fun clean(prompt: String): String? {
        val flat = prompt.replace(SPACES, " ").replace(QUOTES, "").trim()
        if (flat.length < MIN_CHARS) return null
        val short = if (flat.length <= MAX_CHARS) flat
        else flat.take(MAX_CHARS).substringBeforeLast(' ').trimEnd(',', ';', ' ')
        val ended = if (short.endsWith('.')) short else "$short."
        return "$ended $TAIL"
    }

    /**
     * When the author forgot the line: a calm illustration of the page's title. The title is French
     * — FLUX reads simple French, and a vague picture is better than none.
     */
    fun fallback(title: String?): String? {
        val subject = title?.replace(SPACES, " ")?.trim()?.takeIf { it.length >= 3 } ?: return null
        return clean(
            "Soft flat vector illustration evoking \"$subject\", one clear central subject, " +
                "pastel palette, plain light background, calm and friendly mood",
        )
    }

    private const val MIN_CHARS = 12
}
