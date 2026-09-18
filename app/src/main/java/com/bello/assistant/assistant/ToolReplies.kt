package com.bello.assistant.assistant

import com.bello.assistant.memory.Fact
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

    private fun isTomorrow(dueAt: Long, now: Long): Boolean {
        val today = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
        val due = Calendar.getInstance().apply { timeInMillis = dueAt }.get(Calendar.DAY_OF_YEAR)
        return today != due
    }
}
