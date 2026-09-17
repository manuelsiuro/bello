package com.bello.assistant.ui

/** Face expressions understood by assets/face/face.js (FR-FACE-02). */
enum class FaceState(val js: String) {
    IDLE("idle"),
    LISTENING("listening"),
    THINKING("thinking"),
    SPEAKING("speaking"),
    HAPPY("happy"),
    CONFUSED("confused"),
    SAD("sad"),
    ALERT("alert"),
    SLEEPY("sleepy");

    companion object {
        fun fromJs(value: String?): FaceState? = entries.firstOrNull { it.js == value }
    }
}

/** Builds JavaScript calls into the face page with safely quoted string arguments. Pure, unit tested. */
object FaceScript {
    fun call(function: String, vararg args: String): String =
        "bello.$function(${args.joinToString(",") { quote(it) }});"

    /** JSON-style string literal; also escapes U+2028/U+2029, which break JS string literals. */
    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            ' ' -> append("\\u2028")
            ' ' -> append("\\u2029")
            '<' -> append("\\u003c")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
        append('"')
    }
}
