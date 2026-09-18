package com.bello.assistant.assistant

import com.bello.assistant.core.FrenchDates
import com.bello.assistant.tools.PublicHoliday
import com.bello.assistant.tools.SchoolBreak
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

    @Test fun `the page offer, its confirmations and its refusals are spoken French`() {
        assertTrue(ToolReplies.pageOffer().contains("code QR"))
        assertTrue(ToolReplies.pagePreparing().contains("regarde l'écran"))
        assertEquals("C'est prêt : Crêpes. Scanne le code QR avec ton téléphone.", ToolReplies.pageReady("Crêpes"))
        assertTrue(ToolReplies.pageReady(null).startsWith("C'est prêt !"))
        assertTrue(ToolReplies.pageDeclined().contains("pas de code QR"))
        assertTrue(ToolReplies.pageNotOnWifi().contains("Wi-Fi"))
        assertTrue(ToolReplies.pageFailed().contains("Réessaie"))
        assertEquals("Crêpes", ToolReplies.qrCaption("Crêpes"))
        assertEquals("Le détail de la réponse", ToolReplies.qrCaption(null))
    }

    @Test fun `the page prompt carries the question, the spoken answer and the structure`() {
        val prompt = ToolReplies.pagePrompt("la recette des crêpes", "Il te faut des œufs.")
        assertTrue(prompt.contains("« la recette des crêpes »"))
        assertTrue(prompt.contains("« Il te faut des œufs. »"))
        listOf("Markdown", "## Ingrédients", "## Préparation", "## Étapes", "## Liste", "pas d'adresse web")
            .forEach { assertTrue(it, ToolReplies.PAGE_SYSTEM.contains(it)) }
    }

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

    // --- The television -----------------------------------------------------------------------

    @Test fun `television replies are short and say what was done`() {
        assertEquals("J'allume la télé.", ToolReplies.tvPower(on = true, already = false))
        assertEquals("La télé est déjà allumée.", ToolReplies.tvPower(on = true, already = true))
        assertEquals("J'éteins la télé.", ToolReplies.tvPower(on = false, already = false))
        assertEquals("Je mets la 2, France 2.", ToolReplies.tvChannel(2, "France 2"))
        assertEquals("Je mets la 12.", ToolReplies.tvChannel(12, null))
        assertEquals("Chaîne suivante.", ToolReplies.tvChannelStep(up = true))
        assertEquals("Chut.", ToolReplies.tvMute(silence = true))
        assertEquals("La télé est éteinte.", ToolReplies.tvStatus(on = false))
        assertEquals("Je n'arrive pas à joindre le décodeur télé.", ToolReplies.tvUnreachable())
    }

    // --- Holidays -----------------------------------------------------------------------------

    private val noon = FrenchDates.parseDay("2026-09-18")!! + 12 * 3_600_000
    private fun day(text: String) = FrenchDates.parseDay(text)!!

    @Test fun `a holiday is named with its article, and the first of the month is a word`() {
        assertEquals("la Toussaint", ToolReplies.holidayName("Toussaint"))
        assertEquals("l'Ascension", ToolReplies.holidayName("Ascension"))
        assertEquals("l'Assomption", ToolReplies.holidayName("Assomption"))
        assertEquals("le premier mai", ToolReplies.holidayName("1er mai"))
        assertEquals("le 8 mai", ToolReplies.holidayName("8 mai"))
        assertEquals("le Jour de Noël", ToolReplies.holidayName("Jour de Noël"))
        assertEquals("le Lundi de Pâques", ToolReplies.holidayName("Lundi de Pâques"))
    }

    @Test fun `the next public holiday, near and far`() {
        assertEquals(
            "Le prochain jour férié est la Toussaint, dimanche premier novembre, dans 44 jours.",
            ToolReplies.publicHolidayNext(PublicHoliday("Toussaint", day("2026-11-01")), noon),
        )
        // A holiday whose name is its date says the date once, and gives the weekday.
        assertEquals(
            "Le prochain jour férié est le 11 novembre, un mercredi, dans 10 jours.",
            ToolReplies.publicHolidayNext(PublicHoliday("11 novembre", day("2026-11-11")), day("2026-11-01")),
        )
        assertEquals(
            "Aujourd'hui, c'est férié : la Toussaint.",
            ToolReplies.publicHolidayNext(PublicHoliday("Toussaint", day("2026-11-01")), day("2026-11-01") + 3_600_000),
        )
        assertEquals(
            "Demain, c'est férié : la Toussaint.",
            ToolReplies.publicHolidayNext(PublicHoliday("Toussaint", day("2026-11-01")), day("2026-10-31")),
        )
    }

    @Test fun `is it a holiday today or tomorrow`() {
        val toussaint = PublicHoliday("Toussaint", day("2026-11-01"))
        assertEquals(
            "Oui, aujourd'hui c'est férié : la Toussaint.",
            ToolReplies.publicHolidayOn(0, toussaint, null, day("2026-11-01")),
        )
        assertEquals(
            "Non, demain n'est pas férié. Le prochain jour férié est la Toussaint, dimanche premier novembre, dans 44 jours.",
            ToolReplies.publicHolidayOn(1, null, toussaint, noon),
        )
        assertEquals("Non, aujourd'hui n'est pas férié.", ToolReplies.publicHolidayOn(0, null, null, noon))
    }

    @Test fun `the school breaks, the one to come and the one we are in`() {
        val toussaint = SchoolBreak("Vacances de la Toussaint", day("2026-10-17"), day("2026-11-02"))
        assertEquals(
            "Les vacances de la Toussaint commencent samedi 17 octobre, dans 29 jours. " +
                "Les cours reprennent lundi 2 novembre.",
            ToolReplies.schoolBreak(null, toussaint, noon),
        )
        assertEquals(
            "On est en vacances de la Toussaint. Les cours reprennent lundi 2 novembre.",
            ToolReplies.schoolBreak(toussaint, null, day("2026-10-20")),
        )
        // A bridge is one day: when classes resume goes without saying.
        val bridge = SchoolBreak("Pont de l'Ascension", day("2027-05-07"), day("2027-05-08"))
        assertEquals(
            "Le Pont de l'Ascension, c'est vendredi 7 mai, demain.",
            ToolReplies.schoolBreak(null, bridge, day("2027-05-06")),
        )
        // "On est en vacances ?" deserves a yes or a no before the date.
        assertEquals(
            "Non, pas encore. Les vacances de la Toussaint commencent samedi 17 octobre, dans 29 jours. " +
                "Les cours reprennent lundi 2 novembre.",
            ToolReplies.schoolBreak(null, toussaint, noon, askingNow = true),
        )
        assertEquals(
            "On est en vacances de la Toussaint. Les cours reprennent lundi 2 novembre.",
            ToolReplies.schoolBreak(toussaint, null, day("2026-10-20"), askingNow = true),
        )
        assertEquals(ToolReplies.schoolBreaksUnknown(), ToolReplies.schoolBreak(null, null, noon))
    }
}
