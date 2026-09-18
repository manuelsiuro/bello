package com.bello.assistant.assistant

/**
 * What the user asked for, when it is something Bello can do by itself (FR-TOOL-08): the clock,
 * timers, alarms, the weather, the news, the television and its own memory. Everything else goes
 * to a provider.
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

    // The television (docs/sfr-tv-box.md). A channel is a number; the name, when one was said, is
    // only kept to be spoken back.
    data class TvPower(val on: Boolean) : Intent()
    data class TvChannel(val number: Int, val name: String?) : Intent()
    data class TvChannelStep(val up: Boolean) : Intent()
    data class TvVolume(val up: Boolean, val steps: Int) : Intent()
    data class TvMute(val silence: Boolean) : Intent()
    data class TvKey(val key: String) : Intent()
    object TvStatus : Intent()
}

object Intents {

    /** @param tvChannels the household's channel names and numbers, from the config. */
    fun match(text: String, tvChannels: Map<String, Int> = emptyMap()): Intent {
        val flat = deaccent(text.trim())
        if (flat.isEmpty()) return Intent.None

        stop(flat)?.let { return it }
        memory(text, flat)?.let { return it }
        timer(text, flat)?.let { return it }
        alarm(text, flat)?.let { return it }
        tv(text, flat, tvChannels)?.let { return it }
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
        // "arrête" only means "stop talking" when it is not "arrête le minuteur" or "stop la télé".
        if (FrenchWords.tokens(flat).size <= 3 && STOP_WORDS.any { flat.startsWith(it) } &&
            !flat.contains("minuteur") && !flat.contains("alarme") && !TV_WORD.containsMatchIn(flat)) return Intent.Stop
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

    // --- The television ----------------------------------------------------------------------

    private val TV_WORD = Regex("\\b(tele|television|tv|teloche|decodeur|box tv)\\b")
    private val TV_STATUS = Regex("\\b(est|elle est|elle) (allumee|eteinte|en marche|en veille)\\b")
    private val TV_ON = Regex("\\b(allume|allumes|allumer|rallume|mets|met|lance|demarre)\\b")
    private val TV_OFF = Regex("\\b(eteins|eteint|eteindre|eteignez|arrete|arretes|arreter|stop|stoppe|coupe|ferme)\\b")
    private val SOUND = Regex("\\b(son|volume)\\b")
    private val VOL_UP = Regex("\\b(monte|augmente|remonte|plus fort|a fond)\\b")
    private val VOL_DOWN = Regex("\\b(baisse|diminue|descend|moins fort)\\b")
    private val VOL_A_LITTLE = Regex("\\b(un peu|legerement|un poil|un chouia)\\b")
    private val VOL_A_LOT = Regex("\\b(beaucoup|a fond|bien|tres|carrement)\\b")
    private val MUTE = Regex("\\b(coupe|couper|enleve|vire|retire|mute|sans)\\b")
    private val UNMUTE = Regex("\\b(remets|remet|remettre|rends|redonne|reactive)\\b")
    private val ZAP = Regex("\\b(mets|met|mettre|passe|passer|va|vas|aller|zappe|zapper|tourne|change|choisis|reviens|retourne)\\b")
    private val CHANNEL_WORD = Regex("\\b(chaine|chaines|programme|canal)\\b")
    private val CHANNEL_NUMBER = Regex("\\b(?:la|le|chaine|canal|sur|numero)\\s+(\\d{1,3})\\b")
    private val CHANNEL_TAIL = Regex("\\b(?:la|chaine|canal|sur)\\s+([a-z ]+?)\\s*$")
    private val CHANNEL_NEXT = Regex("\\b(suivante?|d apres|prochaine)\\b")
    private val CHANNEL_PREV = Regex("\\b(precedente?|d avant)\\b")
    private val ZAP_ALONE = Regex("^\\s*(zappe|zap)\\s*$")
    /** Said alone in the room, these can only be about the television. */
    private val TV_KEY_ALONE = listOf(
        Regex("^(?:mets? (?:en |sur )?)?pause(?: la (?:tele|tv|television))?$") to "playPause",
        Regex("^(?:lecture|reprends|reprendre|relance|play|continue)(?: la (?:tele|tv|television))?$") to "playPause",
        Regex("^(?:retour|menu|accueil)(?: (?:sur )?la (?:tele|tv|television))?$") to null,
    )
    /** With the television named, the remote's other keys. */
    private val TV_KEY_WITH_WORD = listOf(
        Regex("\\b(pause)\\b") to "playPause",
        Regex("\\b(lecture|reprends|relance|play)\\b") to "playPause",
        Regex("\\b(retour rapide|recule|rembobine)\\b") to "fastBackward",
        Regex("\\b(avance rapide)\\b") to "fastForward",
        Regex("\\b(enregistre|enregistrer)\\b") to "record",
        Regex("\\b(retour|arriere)\\b") to "back",
        Regex("\\b(menu|accueil)\\b") to "home",
        Regex("\\b(ok|valide|valider|entree)\\b") to "ok",
        Regex("\\b(en haut|vers le haut)\\b") to "up",
        Regex("\\b(en bas|vers le bas)\\b") to "down",
        Regex("\\b(a gauche|vers la gauche)\\b") to "left",
        Regex("\\b(a droite|vers la droite)\\b") to "right",
    )
    private val ALONE_KEYS = mapOf("retour" to "back", "menu" to "home", "accueil" to "home")

    private fun tv(text: String, flat: String, channels: Map<String, Int>): Intent? {
        val tvWord = TV_WORD.containsMatchIn(flat)
        if (tvWord && TV_STATUS.containsMatchIn(flat)) return Intent.TvStatus

        if (SOUND.containsMatchIn(flat)) {
            val steps = when {
                VOL_A_LITTLE.containsMatchIn(flat) -> 1
                VOL_A_LOT.containsMatchIn(flat) -> 6
                else -> 3
            }
            when {
                UNMUTE.containsMatchIn(flat) -> return Intent.TvMute(silence = false)
                MUTE.containsMatchIn(flat) -> return Intent.TvMute(silence = true)
                VOL_UP.containsMatchIn(flat) -> return Intent.TvVolume(up = true, steps = steps)
                VOL_DOWN.containsMatchIn(flat) -> return Intent.TvVolume(up = false, steps = steps)
            }
        }

        val channelWord = CHANNEL_WORD.containsMatchIn(flat)
        if (channelWord && CHANNEL_NEXT.containsMatchIn(flat)) return Intent.TvChannelStep(up = true)
        if (channelWord && CHANNEL_PREV.containsMatchIn(flat)) return Intent.TvChannelStep(up = false)
        if (ZAP_ALONE.containsMatchIn(flat)) return Intent.TvChannelStep(up = true)
        if (channelWord || ZAP.containsMatchIn(flat)) {
            CHANNEL_NUMBER.find(flat)?.let { return Intent.TvChannel(it.groupValues[1].toInt(), null) }
            channelByName(flat, channels)?.let { return it }
            CHANNEL_TAIL.find(flat)?.let { m ->
                FrenchWords.number(m.groupValues[1])?.let { return Intent.TvChannel(it, null) }
            }
        }

        if (tvWord) {
            // "mets en pause la télé" is a key, "mets la télé" is the power: keys first.
            TV_KEY_WITH_WORD.firstOrNull { it.first.containsMatchIn(flat) }?.let { return Intent.TvKey(it.second) }
            if (TV_OFF.containsMatchIn(flat)) return Intent.TvPower(on = false)
            if (TV_ON.containsMatchIn(flat)) return Intent.TvPower(on = true)
        }
        TV_KEY_ALONE.forEach { (regex, key) ->
            val m = regex.find(flat) ?: return@forEach
            return Intent.TvKey(key ?: ALONE_KEYS.getValue(FrenchWords.tokens(m.value).first()))
        }
        return null
    }

    /** The longest configured name found in the sentence: "france 2" before "france". */
    private fun channelByName(flat: String, channels: Map<String, Int>): Intent? {
        if (channels.isEmpty()) return null
        val byFlat = channels.entries.associate { deaccent(it.key.lowercase()).trim() to it }
        val pattern = byFlat.keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val m = Regex("(?<![a-z0-9])(?:$pattern)(?![a-z0-9])").find(flat) ?: return null
        val entry = byFlat.getValue(m.value)
        return Intent.TvChannel(entry.value, entry.key)
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
