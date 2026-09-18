package com.bello.assistant.assistant

/**
 * What the user asked for, when it is something Bello can do by itself (FR-TOOL-08): the clock,
 * timers, alarms, the weather, the news, and its own memory. Everything else goes to a provider.
 *
 * Matching is done on the accent-stripped text so "météo" and "meteo" behave the same, but the
 * text taken *out* of a question (a label, a city, a fact to remember) is cut from the original,
 * accents included — it is going to be spoken back.
 */
sealed class Intent {
    object None : Intent()
    object Time : Intent()
    object Day : Intent()
    object Stop : Intent()
    data class TimerSet(val durationMs: Long, val label: String?) : Intent()
    object TimerList : Intent()
    data class TimerCancel(val all: Boolean) : Intent()
    data class AlarmSet(val hour: Int, val minute: Int, val label: String?) : Intent()
    object AlarmList : Intent()
    data class AlarmCancel(val all: Boolean) : Intent()
    data class Weather(val city: String?, val tomorrow: Boolean) : Intent()
    object News : Intent()
    data class Remember(val fact: String) : Intent()
    data class Forget(val what: String?) : Intent()
    object ListMemories : Intent()
}

object Intents {

    fun match(text: String): Intent {
        val flat = deaccent(text.trim())
        if (flat.isEmpty()) return Intent.None

        stop(flat)?.let { return it }
        memory(text, flat)?.let { return it }
        timer(text, flat)?.let { return it }
        alarm(text, flat)?.let { return it }
        weather(text, flat)?.let { return it }
        if (NEWS.containsMatchIn(flat)) return Intent.News
        if (TIME.containsMatchIn(flat)) return Intent.Time
        if (DAY.containsMatchIn(flat)) return Intent.Day
        return Intent.None
    }

    // --- Stop -------------------------------------------------------------------------------

    private val STOP_WORDS = setOf("stop", "arrete", "arretes", "tais toi", "silence", "chut")

    private fun stop(flat: String): Intent? {
        if (flat in STOP_WORDS) return Intent.Stop
        // "arrête" only means "stop talking" when it is not "arrête le minuteur".
        if (FrenchWords.tokens(flat).size <= 3 && STOP_WORDS.any { flat.startsWith(it) } &&
            !flat.contains("minuteur") && !flat.contains("alarme")) return Intent.Stop
        return null
    }

    // --- Memory -----------------------------------------------------------------------------

    private val REMEMBER = Regex("\\b(?:souviens toi|rappelle toi|retiens|note)\\b(?:\\s+(?:que|de|d|du|des|la|le))?\\s+(.+)")
    // "mon", "ma", "mes" are part of the fact ("oublie mon café préféré"), so they stay.
    private val FORGET = Regex("\\boublie[sz]?\\b(?:\\s+(?:ce que|que))?\\s*(.*)")
    private val LIST_MEMORIES = Regex(
        "(qu est ce que tu sais (de moi|sur moi)|de quoi tu te souviens|tes souvenirs|" +
            "(liste|dis moi) (tes|ce dont tu te) souviens?|qu est ce que tu retiens)"
    )

    private fun memory(text: String, flat: String): Intent? {
        if (LIST_MEMORIES.containsMatchIn(flat)) return Intent.ListMemories
        REMEMBER.find(flat)?.let { m ->
            val fact = original(text, m.groups[1]!!.range).trim()
            return if (fact.length >= 3) Intent.Remember(fact) else null
        }
        FORGET.find(flat)?.let { m ->
            val what = original(text, m.groups[1]!!.range).trim()
            return when {
                what.isEmpty() || what == "tout" -> Intent.Forget(null)
                else -> Intent.Forget(what)
            }
        }
        return null
    }

    // --- Timers -----------------------------------------------------------------------------

    private val TIMER_WORD = Regex("\\b(minuteurs?|minuteries?|timers?|chronos?|chronometres?)\\b")
    private val TIMER_IN = Regex("\\b(?:previens|reveille|appelle|rappelle) moi dans\\b")
    private val CANCEL = Regex("\\b(annule|supprime|efface|enleve|arrete|stoppe)\\b")
    private val LIST = Regex("\\b(liste|quels?|quelles?|combien|reste|restants?|en cours)\\b")
    private val LABEL_FOR = Regex("\\bpour\\s+(.+)")

    private fun timer(text: String, flat: String): Intent? {
        val hasTimerWord = TIMER_WORD.containsMatchIn(flat)
        if (!hasTimerWord && !TIMER_IN.containsMatchIn(flat)) return null
        if (hasTimerWord && CANCEL.containsMatchIn(flat)) {
            return Intent.TimerCancel(all = flat.contains("tous") || flat.contains("minuteurs"))
        }
        if (hasTimerWord && LIST.containsMatchIn(flat) && FrenchWords.duration(flat) == null) {
            return Intent.TimerList
        }
        val duration = FrenchWords.duration(flat) ?: return if (hasTimerWord) Intent.TimerList else null
        val label = LABEL_FOR.find(flat)?.let { original(text, it.groups[1]!!.range).trim() }
        return Intent.TimerSet(duration, label?.takeIf { it.isNotEmpty() })
    }

    // --- Alarms -----------------------------------------------------------------------------

    private val ALARM_WORD = Regex("\\b(alarmes?|reveils?|rappels?)\\b")
    private val ALARM_VERB = Regex("\\b(reveille moi|rappelle moi|previens moi)\\b")
    // "rappelle-moi à 18 heures **de** sortir les poubelles" / "une alarme à 7 heures **pour** le train"
    private val LABEL_DE = Regex("\\b(?:de|pour)\\s+(.+)")

    private fun alarm(text: String, flat: String): Intent? {
        val hasAlarmWord = ALARM_WORD.containsMatchIn(flat)
        val hasVerb = ALARM_VERB.containsMatchIn(flat)
        if (!hasAlarmWord && !hasVerb) return null
        if (hasAlarmWord && CANCEL.containsMatchIn(flat)) {
            return Intent.AlarmCancel(all = flat.contains("toutes") || flat.contains("alarmes"))
        }
        val clock = FrenchWords.clock(flat)
        if (clock == null) {
            return if (hasAlarmWord && LIST.containsMatchIn(flat)) Intent.AlarmList else null
        }
        // "rappelle-moi à 18 heures de sortir les poubelles"
        val after = flat.substringAfter("heures", "").let { if (it.isEmpty()) flat else it }
        val label = LABEL_DE.find(after)?.let {
            val offset = flat.length - after.length
            original(text, (it.groups[1]!!.range.first + offset)..(it.groups[1]!!.range.last + offset)).trim()
        }
        return Intent.AlarmSet(clock.hour, clock.minute, label?.takeIf { it.isNotEmpty() })
    }

    // --- Weather, news, clock ----------------------------------------------------------------

    private val WEATHER = Regex(
        "\\b(meteo|quel temps|le temps (fait|fera|qu il)|il fait (combien|quel temps)|" +
            "temperature|va t il pleuvoir|il va pleuvoir|il pleut|pleuvoir)\\b"
    )
    private val CITY = Regex("\\b(?:a|au|aux|en|sur|pour)\\s+([a-z][a-z' -]{2,30}?)\\s*$")
    private val NOISE = Regex("\\b(aujourd hui|demain|ce soir|ce matin|cet apres midi|maintenant|en ce moment)\\b")
    private val NEWS = Regex("\\b(nouvelles|infos|informations|actualites?|journal|quoi de neuf)\\b")
    private val TIME = Regex("\\b(quelle heure|l heure (qu il est|actuelle)|il est quelle heure)\\b")
    private val DAY = Regex("\\b(quel jour|quelle date|la date (d aujourd hui|du jour)|on est quel jour)\\b")

    private fun weather(text: String, flat: String): Intent? {
        if (!WEATHER.containsMatchIn(flat)) return null
        val tomorrow = flat.contains("demain")
        // Blanked out rather than removed: the city is cut from the original text by index,
        // and deleting characters here would shift every position after it.
        val cleaned = NOISE.replace(flat) { " ".repeat(it.value.length) }
        val city = CITY.find(cleaned)?.let { m ->
            val name = original(text, m.groups[1]!!.range).trim()
            // "il fait quel temps en ce moment" must not become the city "ce moment".
            if (name.length < 3 || STOP_CITY.any { name.equals(it, ignoreCase = true) }) null else name
        }
        return Intent.Weather(city, tomorrow)
    }

    private val STOP_CITY = listOf("ce moment", "la maison", "l instant", "moi", "toi", "exterieur")

    // --- Helpers ----------------------------------------------------------------------------

    /** Same length as the input, so ranges found in the flattened text point at the original. */
    fun deaccent(text: String): String = buildString(text.length) {
        for (c in text.lowercase()) append(ACCENTS[c] ?: c)
    }

    private fun original(text: String, range: IntRange): String =
        text.substring(range.first.coerceAtMost(text.length), (range.last + 1).coerceAtMost(text.length))

    private val ACCENTS: Map<Char, Char> = buildMap {
        "àâäá".forEach { put(it, 'a') }
        "éèêë".forEach { put(it, 'e') }
        "îïí".forEach { put(it, 'i') }
        "ôöó".forEach { put(it, 'o') }
        "ûüùú".forEach { put(it, 'u') }
        put('ç', 'c')
        put('ÿ', 'y')
        // The recognizer writes "réveille-moi" and "qu'est-ce que"; the rules below are written
        // with plain spaces, and both replacements keep the text the same length.
        put('-', ' ')
        put('\'', ' ')
        put('’', ' ')
    }
}
