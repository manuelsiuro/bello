package com.bello.assistant.assistant

/**
 * French numbers, durations and clock times as people say them — and as the recognizer writes
 * them down, which is a mix of digits and words ("dix minutes", "10 minutes", "7 heures 30").
 * Pure, unit tested. Input is expected to have been through
 * [com.bello.assistant.voice.SpeechText.forIntent].
 *
 * Everything works on the token list rather than on one big regular expression: a number sits
 * just before its unit, and what follows the unit ("et demie", "30") refines it.
 */
object FrenchWords {

    const val SECOND = 1_000L
    const val MINUTE = 60 * SECOND
    const val HOUR = 60 * MINUTE

    private val SMALL = mapOf(
        "zero" to 0, "zéro" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4,
        "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11,
        "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16,
        "dix sept" to 17, "dix huit" to 18, "dix neuf" to 19,
    )
    private val TENS = mapOf(
        "vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60,
        "septante" to 70, "octante" to 80, "nonante" to 90,
    )

    fun tokens(text: String): List<String> =
        text.replace('-', ' ').replace('\'', ' ').replace('’', ' ')
            .split(Regex("\\s+")).filter { it.isNotBlank() }

    /** A number in digits or in words, 0–99: "12", "quarante cinq", "vingt et un", "soixante dix". */
    fun number(text: String): Int? = number(tokens(text))

    fun number(words: List<String>): Int? {
        if (words.isEmpty()) return null
        words.singleOrNull()?.toIntOrNull()?.let { return it }
        val joined = words.joinToString(" ")
        SMALL[joined]?.let { return it }
        TENS[joined]?.let { return it }
        // "vingt et un", "soixante dix", "quatre vingt deux", "quatre vingt dix sept"
        var rest = words
        var total = 0
        if (rest.size >= 2 && rest[0] == "quatre" && rest[1] == "vingt") {
            total = 80
            rest = rest.drop(2)
        } else if (TENS.containsKey(rest.first())) {
            total = TENS.getValue(rest.first())
            rest = rest.drop(1)
        } else {
            return null
        }
        if (rest.isEmpty() || rest == listOf("s")) return total
        if (rest.first() == "et") rest = rest.drop(1)
        val remainder = SMALL[rest.joinToString(" ")] ?: return null
        val sum = total + remainder
        return if (sum <= 99) sum else null
    }

    /**
     * The same words with every number in figures, longest run first: "france deux" → "france 2",
     * "cherie vingt cinq" → "cherie 25". Tokens are rejoined with single spaces.
     */
    fun digits(text: String): String {
        val words = tokens(text)
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val run = (minOf(4, words.size - i) downTo 1).firstOrNull { number(words.subList(i, i + it)) != null }
            if (run == null) {
                out += words[i]
                i++
            } else {
                out += number(words.subList(i, i + run)).toString()
                i += run
            }
        }
        return out.joinToString(" ")
    }

    /** The number written just before position [index], longest match first ("vingt et un"). */
    private fun numberBefore(words: List<String>, index: Int): Int? {
        for (size in 4 downTo 1) {
            val start = index - size
            if (start < 0) continue
            number(words.subList(start, index))?.let { return it }
        }
        return null
    }

    private fun unitOf(word: String): Long? = when (word) {
        "heure", "heures", "h" -> HOUR
        "minute", "minutes", "min", "mn" -> MINUTE
        "seconde", "secondes", "sec", "s" -> SECOND
        else -> null
    }

    /** "10 minutes", "un quart d heure", "1 heure 30", "deux minutes trente" → milliseconds. */
    fun duration(text: String): Long? {
        val words = tokens(text)
        var total = 0L
        var found = false
        for ((index, word) in words.withIndex()) {
            val unit = unitOf(word) ?: continue
            // "un quart d heure" / "une demi heure": the fraction sits before the unit.
            val previous = words.getOrNull(index - 1)
            if (unit == HOUR && (previous == "quart" || previous == "d" && words.getOrNull(index - 2) == "quart")) {
                total += 15 * MINUTE; found = true; continue
            }
            if (unit == HOUR && (previous == "demi" || previous == "demie")) {
                total += 30 * MINUTE; found = true; continue
            }
            val count = numberBefore(words, index) ?: continue
            total += count * unit
            found = true
            total += fractionAfter(words, index, unit)
        }
        return if (found && total > 0) total else null
    }

    /** What follows a unit: "et demie", "et quart", or a bare number one unit smaller. */
    private fun fractionAfter(words: List<String>, index: Int, unit: Long): Long {
        var next = index + 1
        if (words.getOrNull(next) == "et") next++
        val word = words.getOrNull(next) ?: return 0
        if (unit == SECOND) return 0
        val smaller = if (unit == HOUR) MINUTE else SECOND
        return when {
            word == "demi" || word == "demie" -> 30 * smaller
            word == "quart" -> 15 * smaller
            // Not a bare number if it carries its own unit ("1 heure 30 minutes" is handled there).
            unitOf(words.getOrNull(next + 1) ?: "") != null -> 0
            else -> (number(listOf(word)) ?: 0) * smaller
        }
    }

    data class Clock(val hour: Int, val minute: Int)

    /** A spoken clock time: "7 heures 30", "midi et demi", "8 heures moins le quart". */
    fun clock(text: String): Clock? {
        val words = tokens(text)
        val index = words.indexOfFirst { unitOf(it) == HOUR }
        val (hour, after) = when {
            index >= 0 -> (numberBefore(words, index) ?: return null) to index + 1
            words.contains("midi") -> 12 to words.indexOf("midi") + 1
            words.contains("minuit") -> 0 to words.indexOf("minuit") + 1
            else -> return null
        }
        if (hour !in 0..23) return null
        var next = after
        if (words.getOrNull(next) == "moins") {
            next++
            if (words.getOrNull(next) == "le") next++
            val back = if (words.getOrNull(next) == "quart") 15 else number(listOf(words.getOrNull(next) ?: "")) ?: 0
            if (back == 0) return Clock(hour, 0)
            return Clock((hour + 23) % 24, (60 - back + 60) % 60).let { evening(words, it) }
        }
        if (words.getOrNull(next) == "et") next++
        val word = words.getOrNull(next)
        val minute = when {
            word == "demi" || word == "demie" -> 30
            word == "quart" -> 15
            word != null -> number(listOf(word)) ?: 0
            else -> 0
        }
        if (minute !in 0..59) return null
        return evening(words, Clock(hour, minute))
    }

    /** "sept heures du soir" is 19:00. */
    private fun evening(words: List<String>, clock: Clock): Clock {
        val text = words.joinToString(" ")
        val pm = text.contains("du soir") || text.contains("de l apres midi") ||
            text.contains("de l après midi") || text.contains("ce soir")
        return if (pm && clock.hour in 1..11) clock.copy(hour = clock.hour + 12) else clock
    }

    /** Speaks a duration back: 90_000 → "1 minute et 30 secondes". */
    fun sayDuration(ms: Long): String {
        val total = (ms + 500) / 1000
        val parts = mutableListOf<String>()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        if (hours > 0) parts += if (hours == 1L) "1 heure" else "$hours heures"
        if (minutes > 0) parts += if (minutes == 1L) "1 minute" else "$minutes minutes"
        if (seconds > 0 && hours == 0L) parts += if (seconds == 1L) "1 seconde" else "$seconds secondes"
        return parts.joinToString(" et ").ifEmpty { "0 seconde" }
    }

    /** Speaks a clock time back the French way: 7:30 → "7 heures 30", 8:00 → "8 heures". */
    fun sayClock(hour: Int, minute: Int): String =
        if (minute == 0) "$hour heures" else "$hour heures $minute"

    /** "2 euros 25", "1 euro", "99 centimes" — a price as the radio reads it. */
    fun sayPrice(euros: Double): String {
        val cents = Math.round(euros * 100).toInt()
        if (cents < 100) return "$cents centimes"
        val whole = cents / 100
        val rest = cents % 100
        val unit = if (whole == 1) "euro" else "euros"
        return if (rest == 0) "$whole $unit" else "$whole $unit ${String.format("%02d", rest)}"
    }

    /** "800 mètres", "1,4 kilomètre", "7 kilomètres". */
    fun sayDistance(metres: Int): String = when {
        metres < 1000 -> "${(Math.round(metres / 100.0) * 100).toInt().coerceAtLeast(100)} mètres"
        metres < 10_000 -> {
            val km = Math.round(metres / 100.0) / 10.0
            val text = if (km == Math.floor(km)) km.toInt().toString() else km.toString().replace('.', ',')
            text + if (km < 2) " kilomètre" else " kilomètres"
        }
        else -> "${Math.round(metres / 1000.0).toInt()} kilomètres"
    }
}
