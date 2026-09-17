package com.bello.assistant.voice

/**
 * Text clean-up around speech. Pure functions, unit tested.
 *
 * [forIntent] normalises what the recognizer returns (SP-01 showed digits and compact times:
 * "10 minutes", "7h30", "12 x 15"), so later phases can match commands on stable text.
 * [forSpeech] strips markdown, links and emoji so the voice does not read them out.
 */
object SpeechText {

    private val MARKDOWN_LINK = Regex("\\[([^]]+)]\\((?:[^)]*)\\)")
    private val BARE_URL = Regex("https?://\\S+")
    private val MARKDOWN_MARKS = Regex("[*_`#>]+")
    private val BULLET = Regex("(?m)^\\s*[-•]\\s+")
    // Emoji and pictographs only — keep symbols that matter when spoken (° € % + …).
    private val EMOJI = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{200D}\\x{E000}-\\x{F8FF}]"
    )
    private val SPACES = Regex("[ \\t\\u00a0]+")
    private val BLANK_LINES = Regex("\n{2,}")
    private val TIME = Regex("(\\d{1,2})\\s*h\\s*(\\d{2})")
    private val HOUR_ONLY = Regex("(\\d{1,2})\\s*h(?![0-9a-zà-ÿ])")
    private val PUNCT = Regex("[.,;:!?«»\"()\\[\\]…]")

    fun forSpeech(text: String): String = text
        .replace(MARKDOWN_LINK, "$1")
        .replace(BARE_URL, "")
        .replace(BULLET, "")
        .replace(MARKDOWN_MARKS, "")
        .replace(EMOJI, "")
        .replace(SPACES, " ")
        .replace(BLANK_LINES, "\n")
        .trim()

    fun forIntent(text: String): String = text
        .lowercase()
        .replace(TIME, "$1 heures $2")
        .replace(HOUR_ONLY, "$1 heures")
        .replace(Regex("(\\d)\\s*x\\s*(\\d)"), "$1 fois $2")
        .replace("’", "'")
        .replace(PUNCT, " ")
        .replace(SPACES, " ")
        .trim()
}
