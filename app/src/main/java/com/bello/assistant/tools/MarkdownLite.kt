package com.bello.assistant.tools

/**
 * The little Markdown a page needs (FR-PAGE-03): headings, bullets, numbered steps, paragraphs,
 * bold and italic. Everything is escaped first — the page is served to a browser and the text
 * comes from a model. Pure, unit tested.
 */
object MarkdownLite {

    private val HEADING = Regex("^(#{1,3})\\s+(.+?)\\s*#*$")
    private val BULLET = Regex("^[-*•]\\s+(.+)$")
    private val NUMBERED = Regex("^\\d{1,3}[.)]\\s+(.+)$")
    private val FENCE = Regex("^```.*$")
    private val LONE_TAG = Regex("^[\\[(]\\s*[\\p{L}]{3,12}\\s*[\\])]$")
    private val BOLD = Regex("\\*\\*(\\S(?:[^*]*?\\S)?)\\*\\*")
    private val STAR_EM = Regex("(?<![*\\w])\\*(\\S(?:[^*]*?\\S)?)\\*(?![*\\w])")
    private val UNDERSCORE_EM = Regex("(?<!\\w)_(\\S(?:[^_]*?\\S)?)_(?!\\w)")
    private val MARKS = Regex("[*_`]")

    const val CUT_NOTE = "<p class=\"cut\">(la suite a été coupée)</p>"

    fun escape(text: String): String = buildString(text.length + 16) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    /** The first level-one heading, plain: the page title, and what Bello says is ready. */
    fun title(markdown: String): String? = markdown.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("# ") }
        ?.let { HEADING.find(it)?.groupValues?.get(2) }
        ?.replace(MARKS, "")?.trim()
        ?.takeIf { it.isNotBlank() }

    /** @param truncated the model stopped for lack of room: say so rather than end mid-step. */
    fun toHtml(markdown: String, truncated: Boolean = false): String {
        val out = StringBuilder(markdown.length * 2)
        var list: String? = null
        val paragraph = mutableListOf<String>()

        fun closeParagraph() {
            if (paragraph.isEmpty()) return
            out.append("<p>").append(paragraph.joinToString(" ")).append("</p>\n")
            paragraph.clear()
        }
        fun closeList() {
            list?.let { out.append("</").append(it).append(">\n") }
            list = null
        }
        fun openList(kind: String) {
            if (list == kind) return
            closeList()
            out.append("<").append(kind).append(">\n")
            list = kind
        }

        for (raw in markdown.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> { closeParagraph(); closeList() }
                FENCE.matches(line) || LONE_TAG.matches(line) -> Unit
                HEADING.matches(line) -> {
                    closeParagraph(); closeList()
                    val (hashes, text) = HEADING.find(line)!!.destructured
                    val level = hashes.length
                    out.append("<h").append(level).append(">").append(inline(text)).append("</h").append(level).append(">\n")
                }
                BULLET.matches(line) -> {
                    closeParagraph(); openList("ul")
                    out.append("<li>").append(inline(BULLET.find(line)!!.groupValues[1])).append("</li>\n")
                }
                NUMBERED.matches(line) -> {
                    closeParagraph(); openList("ol")
                    out.append("<li>").append(inline(NUMBERED.find(line)!!.groupValues[1])).append("</li>\n")
                }
                else -> { closeList(); paragraph += inline(line) }
            }
        }
        closeParagraph(); closeList()
        if (truncated) out.append(CUT_NOTE).append("\n")
        return out.toString()
    }

    private fun inline(text: String): String = escape(text)
        .replace(BOLD, "<strong>$1</strong>")
        .replace(STAR_EM, "<em>$1</em>")
        .replace(UNDERSCORE_EM, "<em>$1</em>")
}
