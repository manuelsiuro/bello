package com.bello.assistant.assistant

import com.bello.assistant.core.FrenchDates
import com.bello.assistant.tools.FuelData

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

    /** Jours fériés: [offsetDays] is null for "the next one", 0 for today, 1 for tomorrow. */
    data class PublicHolidays(val offsetDays: Int?) : Intent()
    /**
     * School breaks; [named] is the one the question asked for ("noel"), null for the next one.
     * [askingNow] is "on est en vacances ?" rather than "c'est quand les vacances ?": it deserves
     * a yes or a no before the date.
     */
    data class SchoolHolidays(val named: String?, val askingNow: Boolean = false) : Intent()

    /** The cheapest fuel around; both fields fall back to the configuration when null. */
    data class Fuel(val fuel: String?, val city: String?) : Intent()
    object Joke : Intent()
    /** What French Wikipedia says about a name. */
    data class Encyclopedia(val subject: String) : Intent()
    /** What happened on a day of the year; null and null is today. */
    data class OnThisDay(val month: Int?, val day: Int?) : Intent()

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

    /**
     * @param tvChannels the household's channel names and numbers, from the config.
     * @param raw what was really said or typed, before normalisation. Only the encyclopedia looks
     *   at it, and only for its capital letters: they are what tells a name from a common noun,
     *   and `SpeechText.forIntent` has lowercased them by the time the rest of the matching runs.
     */
    fun match(text: String, tvChannels: Map<String, Int> = emptyMap(), raw: String = text): Intent {
        val flat = deaccent(text.trim())
        if (flat.isEmpty()) return Intent.None

        stop(flat)?.let { return it }
        memory(text, flat)?.let { return it }
        timer(text, flat)?.let { return it }
        alarm(text, flat)?.let { return it }
        tv(text, flat, tvChannels)?.let { return it }
        holidays(flat)?.let { return it }
        if (JOKE.containsMatchIn(flat)) return Intent.Joke
        fuel(text, flat)?.let { return it }
        weather(text, flat)?.let { return it }
        if (NEWS.containsMatchIn(flat)) return Intent.News
        onThisDay(flat)?.let { return it }
        if (TIME.containsMatchIn(flat)) return Intent.Time
        if (DAY.containsMatchIn(flat)) return Intent.Day
        // Last, and only for a name: everything else is a better question for a provider.
        encyclopedia(text, flat, raw)?.let { return it }
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
    /** Where a channel said in words can start: "mets la chaîne deux", "passe sur la deux". */
    private val CHANNEL_LEAD = Regex("\\b(?:la|le|chaine|canal|sur|numero)\\s+")
    private val CHANNEL_FILLER = Regex("^(?:(?:la|le|chaine|canal|sur|numero)\\s+)+")
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
            spokenChannel(flat)?.let { return Intent.TvChannel(it, null) }
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

    /**
     * The whole rest of the sentence after a lead word, read as a number. Every lead is tried, not
     * only the first: in "mets la chaîne deux" the first tail is "chaine deux", which is not one.
     */
    private fun spokenChannel(flat: String): Int? {
        CHANNEL_LEAD.findAll(flat).forEach { m ->
            val tail = flat.substring(m.range.last + 1).replace(CHANNEL_FILLER, "").trim()
            FrenchWords.number(tail)?.let { return it }
        }
        return null
    }

    /**
     * The longest configured name found in the sentence: "france 2" before "france". A name said
     * with its number in words ("france deux") is found in the same sentence with figures.
     */
    private fun channelByName(flat: String, channels: Map<String, Int>): Intent? {
        if (channels.isEmpty()) return null
        val byFlat = channels.entries.associate { deaccent(it.key.lowercase()).trim() to it }
        val pattern = byFlat.keys.filter { it.isNotEmpty() }.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        val name = Regex("(?<![a-z0-9])(?:$pattern)(?![a-z0-9])")
        val m = name.find(flat) ?: name.find(FrenchWords.digits(flat)) ?: return null
        val entry = byFlat.getValue(m.value)
        return Intent.TvChannel(entry.value, entry.key)
    }

    // --- Holidays, public and school -----------------------------------------------------------

    private val FERIE = Regex("\\bferie[es]?\\b")
    private val VACANCES = Regex("\\bvacances\\b")
    private val LATER = Regex("\\b(prochain|prochaine|prochains|prochaines|suivant|bientot|quand|date|dates|combien)\\b")
    private val NOW_FORM = Regex("\\b(aujourd hui|on est|nous sommes|c est|ce jour)\\b")
    private val SCHOOL_HINT = Regex("\\b(scolaires?|ecole|college|lycee|enfants|classe|cours)\\b")
    /** What the household calls a break, against what the ministry calls it. */
    private val BREAK_NAMES = listOf(
        Regex("\\btoussaint\\b") to "toussaint",
        Regex("\\bnoel\\b") to "noel",
        Regex("\\b(hiver|fevrier)\\b") to "hiver",
        Regex("\\b(printemps|paques)\\b") to "printemps",
        // "été" alone is the verb as often as the season: it has to be "des vacances d'été".
        Regex("\\b(?:d|de|des|du)\\s+ete\\b") to "ete",
        Regex("\\bascension\\b") to "ascension",
    )

    private fun holidays(flat: String): Intent? {
        if (FERIE.containsMatchIn(flat)) {
            if (flat.contains("demain")) return Intent.PublicHolidays(1)
            if (LATER.containsMatchIn(flat)) return Intent.PublicHolidays(null)
            if (NOW_FORM.containsMatchIn(flat)) return Intent.PublicHolidays(0)
            return Intent.PublicHolidays(null)
        }
        if (!VACANCES.containsMatchIn(flat)) return null
        // "les vacances, c'est quand ?" is a question; "on a passé de bonnes vacances" is not.
        val asking = LATER.containsMatchIn(flat) || SCHOOL_HINT.containsMatchIn(flat) ||
            NOW_FORM.containsMatchIn(flat) || FrenchWords.tokens(flat).size <= 3
        if (!asking) return null
        val named = BREAK_NAMES.firstOrNull { it.first.containsMatchIn(flat) }?.second
        val askingNow = NOW_FORM.containsMatchIn(flat) && !LATER.containsMatchIn(flat)
        return Intent.SchoolHolidays(named, askingNow)
    }

    // --- Fuel, jokes, the encyclopedia ----------------------------------------------------------

    private val JOKE = Regex("\\b(blagues?|histoire drole|fais moi rire|faire rire|un truc drole|devinette)\\b")
    private val FUEL_WORD = Regex("\\b(gazole|gasoil|gazol|diesel|essence|carburants?|sp ?9[58]|sans plomb|e ?10|e ?85|gpl|ethanol|superethanol|plein)\\b")
    private val PRICE_WORD = Regex("\\b(prix|moins cher|cher|combien|coute|coutent|tarif|ou est|ou sont|ou faire|ou je)\\b")

    private fun fuel(text: String, flat: String): Intent? {
        if (!FUEL_WORD.containsMatchIn(flat)) return null
        if (!PRICE_WORD.containsMatchIn(flat)) return null
        val city = CITY.find(NOISE.replace(flat) { " ".repeat(it.value.length) })?.let { m ->
            val name = original(text, m.groups[1]!!.range).trim()
            if (name.length < 3 || STOP_CITY.any { name.equals(it, ignoreCase = true) }) null else name
        }
        return Intent.Fuel(FuelData.fuelIn(flat), city)
    }

    private val PASSED = Regex("\\b(s est passe|s est il passe|est il arrive|evenements?|histoire)\\b")
    private val THAT_DAY = Regex("\\b(aujourd hui|ce jour|du jour|dans l histoire|meme jour|un \\d{1,2})\\b")
    private val SAID_DATE = Regex("\\b(?:un |le )?(\\d{1,2}|premier)\\s+(janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)\\b")

    private fun onThisDay(flat: String): Intent? {
        val saidDate = SAID_DATE.containsMatchIn(flat)
        if (!PASSED.containsMatchIn(flat) || !(THAT_DAY.containsMatchIn(flat) || saidDate)) return null
        val said = SAID_DATE.find(flat) ?: return Intent.OnThisDay(null, null)
        val day = said.groupValues[1].let { if (it == "premier") 1 else it.toIntOrNull() } ?: return Intent.OnThisDay(null, null)
        val month = FrenchDates.monthNumber(said.groupValues[2]) ?: return Intent.OnThisDay(null, null)
        return Intent.OnThisDay(month, day)
    }

    private val ASKS_ABOUT = listOf(
        Regex("\\b(?:qui est|qui etait|qui sont|qui etaient|c est qui)\\s+(.+)$"),
        Regex("\\b(?:c est quoi|qu est ce que|qu est ce qu|qu est ce que c est que)\\s+(.+)$"),
        Regex("\\b(?:parle moi de|parle moi du|parle moi des|parle moi d|raconte moi|dis moi tout sur)\\s+(.+)$"),
    )
    /** Dropped before the name is looked at: they belong to the question, not to the subject. */
    private val LEADING = Regex("^(?:le|la|les|l|un|une|des|du|de la|de l|de|d|ce|cet|cette|mon|ma|mes|ton|ta|tes|son|sa|ses)\\s+", RegexOption.IGNORE_CASE)

    /**
     * Only names. "Qui est Marie Curie" is a question the encyclopedia answers better than a
     * model; "qui est le président de la République" is one it answers worse, because the article
     * describes the office and never names the person. The rule that tells them apart is the
     * capital letter, once the determiners are out of the way — and when in doubt Bello says
     * nothing here and lets the provider answer as usual.
     */
    private fun encyclopedia(text: String, flat: String, raw: String): Intent? {
        for (pattern in ASKS_ABOUT) {
            val m = pattern.find(flat) ?: continue
            var subject = original(text, m.groups[1]!!.range).trim().trimEnd('?', '!', '.', ' ')
            while (true) {
                val shorter = LEADING.replaceFirst(subject, "")
                if (shorter == subject) break
                subject = shorter
            }
            subject = subject.trim().trimStart('\'', '\u2019')
            if (subject.length < 2 || subject.length > 40) return null
            if (FrenchWords.tokens(subject).size > 5) return null
            return Intent.Encyclopedia(capitalisedIn(raw, subject) ?: return null)
        }
        return null
    }

    /** The subject as it was really written, when it was written as a name. */
    private fun capitalisedIn(raw: String, subject: String): String? {
        val at = raw.indexOf(subject, ignoreCase = true)
        if (at < 0) return null
        val written = raw.substring(at, at + subject.length)
        return written.takeIf { it.first().isUpperCase() }
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
