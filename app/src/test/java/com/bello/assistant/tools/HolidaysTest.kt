package com.bello.assistant.tools

import com.bello.assistant.core.FrenchDates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixtures are the answers of the two government services on 2026-09-18. */
class HolidaysTest {

    private val feries2026 = """
        {"2026-01-01": "1er janvier", "2026-04-06": "Lundi de Pâques", "2026-05-01": "1er mai",
         "2026-05-08": "8 mai", "2026-05-14": "Ascension", "2026-05-25": "Lundi de Pentecôte",
         "2026-07-14": "14 juillet", "2026-08-15": "Assomption", "2026-11-01": "Toussaint",
         "2026-11-11": "11 novembre", "2026-12-25": "Jour de Noël"}
    """.trimIndent()

    private val vacancesNice = """
        {"total_count": 13, "results": [
          {"description": "Vacances de la Toussaint", "start_date": "2026-10-16T22:00:00+00:00",
           "end_date": "2026-11-01T23:00:00+00:00", "population": "-"},
          {"description": "Vacances de Noël", "start_date": "2026-12-18T23:00:00+00:00",
           "end_date": "2027-01-03T23:00:00+00:00", "population": "-"},
          {"description": "Vacances d'Hiver", "start_date": "2027-02-19T23:00:00+00:00",
           "end_date": "2027-03-07T23:00:00+00:00", "population": "-"},
          {"description": "Pont de l'Ascension", "start_date": "2027-05-06T22:00:00+00:00",
           "end_date": "2027-05-06T22:00:00+00:00", "population": "-"},
          {"description": "Vacances d'Été", "start_date": "2027-07-02T22:00:00+00:00",
           "end_date": "2027-09-01T22:00:00+00:00", "population": "Élèves"},
          {"description": "Vacances d'Été", "start_date": "2027-07-02T22:00:00+00:00",
           "end_date": "2027-08-23T22:00:00+00:00", "population": "Enseignants"}
        ]}
    """.trimIndent()

    private val noon = FrenchDates.parseDay("2026-09-18")!! + 12 * 3_600_000

    @Test fun `the year's public holidays are read in order`() {
        val holidays = HolidayData.publicHolidays(feries2026)
        assertEquals(11, holidays.size)
        assertEquals("1er janvier", holidays.first().name)
        assertEquals("Jour de Noël", holidays.last().name)
        assertTrue(holidays.zipWithNext().all { (a, b) -> a.day < b.day })
        assertEquals(FrenchDates.parseDay("2026-11-01"), holidays.first { it.name == "Toussaint" }.day)
        assertTrue(HolidayData.publicHolidays("not json").isEmpty())
    }

    @Test fun `the next public holiday after today, today included`() {
        val holidays = HolidayData.publicHolidays(feries2026)
        val today = FrenchDates.midnight(noon)
        assertEquals("Toussaint", holidays.first { it.day >= today }.name)
        // On the day itself it is still the answer, not the one after.
        val toussaint = FrenchDates.parseDay("2026-11-01")!!
        assertEquals("Toussaint", holidays.first { it.day >= toussaint }.name)
        assertNull(holidays.firstOrNull { it.day == FrenchDates.parseDay("2026-09-18") })
    }

    @Test fun `the school breaks drop the teachers' summer and keep the children's`() {
        val breaks = HolidayData.schoolBreaks(vacancesNice)
        assertEquals(5, breaks.size)
        assertEquals(
            listOf("Vacances de la Toussaint", "Vacances de Noël", "Vacances d'Hiver",
                "Pont de l'Ascension", "Vacances d'Été"),
            breaks.map { it.description },
        )
        val summer = breaks.last()
        assertEquals(FrenchDates.parseDay("2027-09-02"), summer.resume)
    }

    @Test fun `a break starts the morning after the last class and ends the morning of the first`() {
        val toussaint = HolidayData.schoolBreaks(vacancesNice).first()
        assertEquals(FrenchDates.parseDay("2026-10-17"), toussaint.start)
        assertEquals(FrenchDates.parseDay("2026-11-02"), toussaint.resume)
        assertEquals(FrenchDates.parseDay("2026-11-01"), toussaint.lastDay)
    }

    @Test fun `a bridge lasts one day, whatever the dataset repeats`() {
        val bridge = HolidayData.schoolBreaks(vacancesNice).first { it.description.startsWith("Pont") }
        assertEquals(FrenchDates.parseDay("2027-05-07"), bridge.start)
        assertEquals(FrenchDates.parseDay("2027-05-08"), bridge.resume)
        assertEquals(1, FrenchDates.daysBetween(bridge.start, bridge.resume))
    }

    @Test fun `what we are in, and what comes next`() {
        val breaks = HolidayData.schoolBreaks(vacancesNice)
        assertNull(HolidayData.currentBreak(breaks, noon))
        assertEquals("Vacances de la Toussaint", HolidayData.nextBreak(breaks, noon)!!.description)

        val duringToussaint = FrenchDates.parseDay("2026-10-20")!! + 9 * 3_600_000
        assertEquals("Vacances de la Toussaint", HolidayData.currentBreak(breaks, duringToussaint)!!.description)
        assertEquals("Vacances de Noël", HolidayData.nextBreak(breaks, duringToussaint)!!.description)

        // The morning classes resume is no longer a holiday.
        val backToSchool = FrenchDates.parseDay("2026-11-02")!! + 8 * 3_600_000
        assertNull(HolidayData.currentBreak(breaks, backToSchool))
        assertNotNull(HolidayData.nextBreak(breaks, backToSchool))
    }
}
