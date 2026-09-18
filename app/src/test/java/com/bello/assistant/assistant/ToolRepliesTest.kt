package com.bello.assistant.assistant

import com.bello.assistant.memory.Fact
import com.bello.assistant.tools.NextOccurrence
import com.bello.assistant.tools.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ToolRepliesTest {

    private fun at(hour: Int, minute: Int, day: Int = 18): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun timer(remainingMs: Long, label: String? = null, now: Long = at(9, 0)) =
        Schedule(1, Schedule.Kind.TIMER, now + remainingMs, label, remainingMs)

    @Test fun `the clock is spoken, not printed`() {
        assertEquals("Il est 9 heures 5.", ToolReplies.time(at(9, 5)))
        assertEquals("Il est 14 heures.", ToolReplies.time(at(14, 0)))
    }

    @Test fun `the day is the French date`() {
        val said = ToolReplies.day(at(9, 0))
        assertTrue(said, said.contains("septembre 2026"))
        assertTrue(said, said.startsWith("Nous sommes "))
    }

    @Test fun `setting a timer confirms the duration and the label`() {
        assertEquals("C'est parti : 10 minutes.", ToolReplies.timerSet(10 * FrenchWords.MINUTE, null))
        assertEquals(
            "C'est parti pour les pâtes : 3 minutes.",
            ToolReplies.timerSet(3 * FrenchWords.MINUTE, "les pâtes")
        )
    }

    @Test fun `listing timers`() {
        val now = at(9, 0)
        assertEquals("Tu n'as aucun minuteur en cours.", ToolReplies.timerList(emptyList(), now))
        assertEquals(
            "Il reste 3 minutes pour les pâtes.",
            ToolReplies.timerList(listOf(timer(3 * FrenchWords.MINUTE, "les pâtes", now)), now)
        )
        val two = ToolReplies.timerList(
            listOf(timer(60_000, null, now), timer(5 * FrenchWords.MINUTE, "le thé", now)), now
        )
        assertTrue(two, two.startsWith("Tu as 2 minuteurs : "))
        assertTrue(two, two.contains("5 minutes pour le thé"))
    }

    @Test fun `cancelling says how many`() {
        assertEquals("Il n'y avait pas de minuteur à annuler.", ToolReplies.timerCancelled(emptyList()))
        assertEquals("J'ai annulé le minuteur.", ToolReplies.timerCancelled(listOf(timer(1000))))
        assertEquals("J'ai annulé les 2 minuteurs.", ToolReplies.timerCancelled(listOf(timer(1), timer(2))))
    }

    @Test fun `alarms say today or tomorrow`() {
        val now = at(9, 0)
        val later = NextOccurrence.after(now, 18, 0)
        assertEquals(
            "Alarme réglée aujourd'hui à 18 heures pour sortir les poubelles.",
            ToolReplies.alarmSet(18, 0, "sortir les poubelles", later, now)
        )
        val tomorrow = NextOccurrence.after(now, 7, 30)
        assertEquals("Alarme réglée demain à 7 heures 30.", ToolReplies.alarmSet(7, 30, null, tomorrow, now))
    }

    @Test fun `the next occurrence is today when it is still ahead, tomorrow otherwise`() {
        val now = at(9, 0)
        assertEquals(at(18, 0), NextOccurrence.after(now, 18, 0))
        assertEquals(at(7, 30, day = 19), NextOccurrence.after(now, 7, 30))
    }

    @Test fun `ringing mentions the label when there is one`() {
        val withLabel = Schedule(1, Schedule.Kind.ALARM, 0, "le rendez-vous chez le dentiste")
        assertTrue(ToolReplies.ringing(withLabel).contains("dentiste"))
        assertTrue(ToolReplies.ringing(Schedule(2, Schedule.Kind.TIMER, 0, null)).contains("minuteur"))
        assertTrue(ToolReplies.ringing(null).isNotEmpty())
    }

    @Test fun `memories are read back, and forgetting is confirmed`() {
        val fact = Fact(1, "mon café préféré est l'espresso", 0)
        assertTrue(ToolReplies.memories(emptyList()).contains("souviens-toi"))
        assertEquals(
            "Je me souviens de 1 chose : mon café préféré est l'espresso.",
            ToolReplies.memories(listOf(fact))
        )
        assertEquals("C'est noté : mon café préféré est l'espresso.", ToolReplies.remembered(fact.text))
        assertEquals("J'ai oublié : ${fact.text}.", ToolReplies.forgotten(listOf(fact), all = false))
        assertEquals("Je ne trouve pas ce souvenir.", ToolReplies.forgotten(emptyList(), all = false))
        assertEquals("J'ai tout oublié.", ToolReplies.forgotten(listOf(fact), all = true))
    }

    @Test fun `headlines have a spoken fallback and a summary prompt`() {
        val titles = listOf("Un titre", "Un autre titre", "Un troisième", "Un quatrième")
        assertTrue(ToolReplies.headlinesFallback(titles).startsWith("Voici les titres : "))
        assertTrue(ToolReplies.headlinesFallback(emptyList()).contains("n'arrive pas"))
        val prompt = ToolReplies.summarisePrompt(titles)
        assertTrue(prompt.contains("- Un titre"))
        assertTrue(prompt.contains("trois phrases"))
    }
}
