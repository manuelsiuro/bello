package com.bello.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrenchDatesTest {

    @Test fun `a day is read in the timezone of the house and said in words`() {
        val toussaint = FrenchDates.parseDay("2026-11-01")!!
        assertEquals("dimanche", FrenchDates.weekday(toussaint))
        assertEquals("novembre", FrenchDates.month(toussaint))
        assertEquals("premier", FrenchDates.dayNumber(toussaint))
        assertEquals("dimanche premier novembre", FrenchDates.say(toussaint))
        assertEquals("samedi 17 octobre", FrenchDates.say(FrenchDates.parseDay("2026-10-17")!!))
        assertNull(FrenchDates.parseDay("pas une date"))
    }

    @Test fun `the school calendar writes its days as UTC midnights of Paris`() {
        // Summer time: 22:00 UTC on the 16th is midnight on the 17th in the house.
        val start = FrenchDates.parseInstantUtc("2026-10-16T22:00:00+00:00")!!
        assertEquals(FrenchDates.parseDay("2026-10-17"), start)
        // Winter time: 23:00 UTC on the 1st is midnight on the 2nd.
        val resume = FrenchDates.parseInstantUtc("2026-11-01T23:00:00+00:00")!!
        assertEquals(FrenchDates.parseDay("2026-11-02"), resume)
        assertEquals("lundi 2 novembre", FrenchDates.say(resume))
    }

    @Test fun `days are counted as whole days, the short and long ones included`() {
        val noon = FrenchDates.parseDay("2026-09-18")!! + 12 * 3_600_000
        assertEquals(0, FrenchDates.daysBetween(noon, noon + 3_600_000))
        assertEquals(44, FrenchDates.daysBetween(noon, FrenchDates.parseDay("2026-11-01")!!))
        assertEquals(29, FrenchDates.daysBetween(noon, FrenchDates.parseDay("2026-10-17")!!))
        // The clocks go forward on 29 March 2026: that day is 23 hours long.
        assertEquals(2, FrenchDates.daysBetween(FrenchDates.parseDay("2026-03-28")!!, FrenchDates.parseDay("2026-03-30")!!))
        // And back on 25 October: 25 hours.
        assertEquals(2, FrenchDates.daysBetween(FrenchDates.parseDay("2026-10-24")!!, FrenchDates.parseDay("2026-10-26")!!))
        assertTrue(FrenchDates.sameDay(noon, noon + 3_600_000))
        assertEquals(FrenchDates.parseDay("2026-09-19"), FrenchDates.midnight(FrenchDates.addDays(noon, 1)))
    }
}
