package com.bello.assistant.assistant

import com.bello.assistant.core.FrenchDates
import com.bello.assistant.memory.Fact
import com.bello.assistant.tools.PublicHoliday
import com.bello.assistant.tools.SchoolBreak
import com.bello.assistant.tools.Schedule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What Bello says when it answers by itself (FR-TOOL-09). Pure and unit tested: these sentences
 * are read out loud, so they must stay short, French, and free of anything a voice cannot read.
 */
object ToolReplies {

    /**
     * No network (NFR-REL-02). Worth saying what still works: the things Bello does by itself are
     * exactly the ones somebody is most likely to ask for.
     */
    fun offline(): String =
        "Je n'ai plus de réseau. Je peux quand même te donner l'heure, " +
            "mettre un minuteur ou te réveiller."

    /** Somebody has come back into the room after a while (FR-PRES-02). */
    fun greeting(hour: Int): String = when (hour) {
        in 0..4 -> "Oh ! Encore debout ?"
        in 5..17 -> "Bello ! Bonjour !"
        else -> "Bello ! Bonsoir !"
    }

    private val TIME = SimpleDateFormat("H'#'mm", Locale.FRANCE)
    private val DAY = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE)

    fun time(now: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return "Il est ${FrenchWords.sayClock(hour, minute)}."
    }

    fun day(now: Long): String = "Nous sommes ${DAY.format(Date(now))}."

    // --- Timers -------------------------------------------------------------------------------

    fun timerSet(duration: Long, label: String?): String {
        val forWhat = label?.let { " pour $it" } ?: ""
        return "C'est parti$forWhat : ${FrenchWords.sayDuration(duration)}."
    }

    fun timerList(timers: List<Schedule>, now: Long): String = when {
        timers.isEmpty() -> "Tu n'as aucun minuteur en cours."
        timers.size == 1 -> {
            val timer = timers.first()
            "Il reste ${FrenchWords.sayDuration(timer.remainingMs(now))}" +
                (timer.label?.let { " pour $it" } ?: "") + "."
        }
        else -> "Tu as ${timers.size} minuteurs : " + timers.joinToString(", ") {
            FrenchWords.sayDuration(it.remainingMs(now)) + (it.label?.let { l -> " pour $l" } ?: "")
        } + "."
    }

    fun timerCancelled(cancelled: List<Schedule>): String = when {
        cancelled.isEmpty() -> "Il n'y avait pas de minuteur à annuler."
        cancelled.size == 1 -> "J'ai annulé le minuteur."
        else -> "J'ai annulé les ${cancelled.size} minuteurs."
    }

    // --- Alarms -------------------------------------------------------------------------------

    fun alarmSet(hour: Int, minute: Int, label: String?, dueAt: Long, now: Long): String {
        val when_ = if (isTomorrow(dueAt, now)) "demain" else "aujourd'hui"
        val forWhat = label?.let { " pour $it" } ?: ""
        return "Alarme réglée $when_ à ${FrenchWords.sayClock(hour, minute)}$forWhat."
    }

    fun alarmList(alarms: List<Schedule>, now: Long): String = when {
        alarms.isEmpty() -> "Tu n'as aucune alarme."
        else -> "Tu as ${alarms.size} " + (if (alarms.size == 1) "alarme : " else "alarmes : ") +
            alarms.joinToString(", ") { alarm ->
                val calendar = Calendar.getInstance().apply { timeInMillis = alarm.dueAt }
                FrenchWords.sayClock(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)) +
                    (alarm.label?.let { " pour $it" } ?: "")
            } + "."
    }

    fun alarmCancelled(cancelled: List<Schedule>): String = when {
        cancelled.isEmpty() -> "Il n'y avait pas d'alarme à annuler."
        cancelled.size == 1 -> "J'ai annulé l'alarme."
        else -> "J'ai annulé les ${cancelled.size} alarmes."
    }

    fun ringing(schedule: Schedule?): String {
        val label = schedule?.label
        return when {
            label != null -> "Ding ding ! C'est l'heure : $label !"
            schedule?.kind == Schedule.Kind.ALARM -> "Ding ding ! C'est l'heure de ton alarme !"
            else -> "Ding ding ! Ton minuteur est terminé !"
        }
    }

    // --- Memory -------------------------------------------------------------------------------

    fun remembered(fact: String): String = "C'est noté : $fact."

    fun forgotten(forgotten: List<Fact>, all: Boolean): String = when {
        all -> if (forgotten.isEmpty()) "Je n'avais rien en mémoire." else "J'ai tout oublié."
        forgotten.isEmpty() -> "Je ne trouve pas ce souvenir."
        forgotten.size == 1 -> "J'ai oublié : ${forgotten.first().text}."
        else -> "J'ai oublié ${forgotten.size} souvenirs."
    }

    fun memories(facts: List<Fact>): String = when {
        facts.isEmpty() -> "Je ne sais encore rien sur toi. Dis-moi « souviens-toi que… »."
        else -> "Je me souviens de " + facts.size + (if (facts.size == 1) " chose : " else " choses : ") +
            facts.joinToString(", ") { it.text } + "."
    }

    // --- News ---------------------------------------------------------------------------------

    fun headlinesFallback(titles: List<String>): String = when {
        titles.isEmpty() -> "Je n'arrive pas à récupérer les informations pour le moment."
        else -> "Voici les titres : " + titles.take(3).joinToString(". ") + "."
    }

    fun summarisePrompt(titles: List<String>): String =
        "Voici les titres de l'actualité française du moment :\n" +
            titles.joinToString("\n") { "- $it" } +
            "\n\nRésume-les en trois phrases maximum, à lire à voix haute, sans liste et sans citer de source."

    // --- The television (docs/sfr-tv-box.md) ---------------------------------------------------

    fun tvPower(on: Boolean, already: Boolean): String = when {
        on && already -> "La télé est déjà allumée."
        on -> "J'allume la télé."
        already -> "La télé est déjà éteinte."
        else -> "J'éteins la télé."
    }

    fun tvChannel(number: Int, name: String?): String =
        "Je mets la $number" + (name?.let { ", $it" } ?: "") + "."

    fun tvChannelStep(up: Boolean): String = if (up) "Chaîne suivante." else "Chaîne précédente."

    fun tvVolume(up: Boolean): String = if (up) "Je monte le son." else "Je baisse le son."

    fun tvMute(silence: Boolean): String = if (silence) "Chut." else "Et voilà le son."

    fun tvKey(key: String): String = when (key) {
        "playPause" -> "Voilà."
        "stop" -> "Stop."
        "back" -> "Retour."
        "home" -> "Le menu."
        "record" -> "J'enregistre."
        else -> "Voilà."
    }

    fun tvStatus(on: Boolean): String = if (on) "La télé est allumée." else "La télé est éteinte."

    fun tvUnreachable(): String = "Je n'arrive pas à joindre le décodeur télé."

    fun tvNotConfigured(): String = "Je n'ai pas de télé à commander. Il faut l'ajouter dans la configuration."

    // --- Holidays, public and school -----------------------------------------------------------

    /**
     * The government's file names a holiday the way a calendar prints it — "Toussaint", "1er mai",
     * "Jour de Noël" — and a sentence needs an article in front of it. "1er" is written out: the
     * voice reads the word, not the abbreviation.
     */
    fun holidayName(name: String): String {
        val spoken = name.replace(Regex("^1er\\b"), "premier")
        val flat = Intents.deaccent(spoken.lowercase())
        return when {
            spoken.first().isDigit() -> "le $spoken"
            flat.startsWith("premier") -> "le $spoken"
            flat.first() in "aeiouy" || flat.startsWith("h") -> "l'$spoken"
            FEMININE_HOLIDAYS.any { flat.startsWith(it) } -> "la $spoken"
            else -> "le $spoken"
        }
    }

    fun publicHolidayNext(holiday: PublicHoliday, now: Long): String {
        val days = FrenchDates.daysBetween(now, holiday.day)
        val what = holidayName(holiday.name)
        return when {
            days <= 0 -> "Aujourd'hui, c'est férié : $what."
            days == 1 -> "Demain, c'est férié : $what."
            // "Le prochain jour férié est le 14 juillet, mardi 14 juillet" says it twice.
            holiday.name.first().isDigit() ->
                "Le prochain jour férié est $what, un ${FrenchDates.weekday(holiday.day)}, dans $days jours."
            else ->
                "Le prochain jour férié est $what, ${FrenchDates.say(holiday.day)}, dans $days jours."
        }
    }

    /** "C'est férié demain ?" — yes or no first, then the next one when the answer is no. */
    fun publicHolidayOn(
        offsetDays: Int,
        holiday: PublicHoliday?,
        next: PublicHoliday?,
        now: Long,
    ): String {
        val day = if (offsetDays == 1) "demain" else "aujourd'hui"
        if (holiday != null) return "Oui, $day c'est férié : ${holidayName(holiday.name)}."
        val no = "Non, $day n'est pas férié."
        return if (next == null) no else "$no ${publicHolidayNext(next, now)}"
    }

    /**
     * "C'est quand les vacances ?" — the one we are in when there is one, the next otherwise. A
     * bridge lasts a single day, and saying when classes resume after it would be silly.
     */
    fun schoolBreak(
        current: SchoolBreak?,
        next: SchoolBreak?,
        now: Long,
        askingNow: Boolean = false,
    ): String {
        val period = current ?: next ?: return schoolBreaksUnknown()
        val no = if (askingNow && current == null) "Non, pas encore. " else ""
        val what = period.description.trim()
        val holiday = what.startsWith("Vacances", ignoreCase = true)
        val opening = when {
            current != null && holiday -> "On est en vacances ${what.substringAfter(' ').trim()}."
            current != null -> "C'est ${holidayName(what)}."
            holiday -> "Les ${what.replaceFirstChar { it.lowercase() }} commencent " +
                "${FrenchDates.say(period.start)}, ${inDays(FrenchDates.daysBetween(now, period.start))}."
            else -> "${holidayName(what).replaceFirstChar { it.uppercase() }}, c'est " +
                "${FrenchDates.say(period.start)}, ${inDays(FrenchDates.daysBetween(now, period.start))}."
        }
        val oneDay = FrenchDates.daysBetween(period.start, period.resume) <= 1
        val resumes = if (oneDay) "" else " Les cours reprennent ${FrenchDates.say(period.resume)}."
        return "$no$opening$resumes"
    }

    fun publicHolidaysUnknown(): String = "Je n'arrive pas à consulter le calendrier des jours fériés."

    fun schoolBreaksUnknown(): String = "Je n'arrive pas à consulter le calendrier des vacances scolaires."

    private fun inDays(days: Int): String = when {
        days <= 0 -> "aujourd'hui"
        days == 1 -> "demain"
        else -> "dans $days jours"
    }

    private val FEMININE_HOLIDAYS = listOf("toussaint", "pentecote", "abolition")

    // --- A page for the phone (FR-PAGE) ---------------------------------------------------------

    fun pageOffer(): String = "Tu veux le détail sur ton téléphone ? Dis oui et je t'affiche un code QR."

    fun pagePreparing(): String = "Je prépare la page, regarde l'écran dans quelques secondes."

    fun pageReady(title: String?): String =
        (title?.let { "C'est prêt : $it. " } ?: "C'est prêt ! ") + "Scanne le code QR avec ton téléphone."

    fun pageDeclined(): String = "D'accord, pas de code QR."

    fun pageNotOnWifi(): String =
        "Je ne suis pas connecté au Wi-Fi, je ne peux pas t'envoyer la page sur ton téléphone."

    fun pageFailed(): String = "Je n'ai pas réussi à écrire la page. Réessaie dans un petit instant !"

    /** Under the QR code, on the face (FR-PAGE-05). */
    fun qrCaption(title: String?): String = title ?: "Le détail de la réponse"

    /**
     * The author of the page (FR-PAGE-03). It replaces the persona for that one call: Bello's
     * "three spoken sentences, no list" would contradict everything a page is for.
     */
    val PAGE_SYSTEM: String = """
        Tu es un rédacteur précis. Tu écris en français, au format Markdown, la version complète et
        détaillée d'une réponse que l'assistant vocal Bello vient de donner en trois phrases.
        Rien d'autre : pas d'introduction, pas de conclusion, pas d'emoji, pas d'étiquette d'émotion,
        pas d'adresse web, pas de tableau, pas de titre de niveau 3.
        Structure : un titre de niveau 1 (# ), puis des sections de niveau 2 (## ) adaptées au sujet :
        - pour une recette : une ligne avec le nombre de personnes et les temps de préparation et de
          cuisson ; « ## Ingrédients » en liste à puces avec les quantités ; « ## Préparation » en liste
          numérotée, une étape par ligne ; « ## Conseils ».
        - pour un mode d'emploi, une méthode ou un itinéraire : « ## Ce qu'il faut » si nécessaire,
          puis « ## Étapes » en liste numérotée, puis « ## Conseils ».
        - pour une liste : « ## Liste » en puces, avec une courte explication par élément.
        Moins de quatre cents mots. Reste fidèle à ce qui a déjà été dit à voix haute.
    """.trimIndent()

    fun pagePrompt(question: String, spokenAnswer: String): String =
        "La question posée : « $question »\n" +
            "Ce qui a déjà été répondu à voix haute : « $spokenAnswer »\n\n" +
            "Écris maintenant la version complète, pour quatre personnes s'il s'agit d'une recette " +
            "et si la question ne précise pas un autre nombre."

    private fun isTomorrow(dueAt: Long, now: Long): Boolean {
        val today = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
        val due = Calendar.getInstance().apply { timeInMillis = dueAt }.get(Calendar.DAY_OF_YEAR)
        return today != due
    }
}
