package com.bello.assistant.assistant

import com.bello.assistant.assistant.FrenchWords.MINUTE
import com.bello.assistant.assistant.FrenchWords.HOUR
import com.bello.assistant.assistant.FrenchWords.SECOND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrenchWordsTest {

    @Test fun `numbers in digits and in words`() {
        assertEquals(12, FrenchWords.number("12"))
        assertEquals(1, FrenchWords.number("une"))
        assertEquals(16, FrenchWords.number("seize"))
        assertEquals(21, FrenchWords.number("vingt et un"))
        assertEquals(45, FrenchWords.number("quarante cinq"))
        assertEquals(70, FrenchWords.number("soixante dix"))
        assertEquals(97, FrenchWords.number("quatre vingt dix sept"))
        assertNull(FrenchWords.number("banane"))
    }

    @Test fun `durations people actually say`() {
        assertEquals(10 * MINUTE, FrenchWords.duration("mets un minuteur de 10 minutes"))
        assertEquals(2 * MINUTE, FrenchWords.duration("minuteur de deux minutes"))
        assertEquals(30 * SECOND, FrenchWords.duration("30 secondes"))
        assertEquals(HOUR, FrenchWords.duration("une heure"))
        assertEquals(HOUR + 30 * MINUTE, FrenchWords.duration("1 heure 30"))
        assertEquals(HOUR + 30 * MINUTE, FrenchWords.duration("une heure et demie"))
        assertEquals(15 * MINUTE, FrenchWords.duration("un quart d'heure"))
        assertEquals(30 * MINUTE, FrenchWords.duration("une demi heure"))
        assertEquals(2 * MINUTE + 30 * SECOND, FrenchWords.duration("deux minutes trente"))
        assertEquals(HOUR + 20 * MINUTE, FrenchWords.duration("1 heure 20 minutes"))
    }

    @Test fun `not a duration`() {
        assertNull(FrenchWords.duration("quelle heure est-il"))
        assertNull(FrenchWords.duration("mets un minuteur"))
        assertNull(FrenchWords.duration("raconte une blague"))
    }

    @Test fun `clock times`() {
        assertEquals(FrenchWords.Clock(7, 30), FrenchWords.clock("7 heures 30"))
        assertEquals(FrenchWords.Clock(19, 0), FrenchWords.clock("19 heures"))
        assertEquals(FrenchWords.Clock(7, 30), FrenchWords.clock("sept heures et demie"))
        assertEquals(FrenchWords.Clock(8, 15), FrenchWords.clock("huit heures et quart"))
        assertEquals(FrenchWords.Clock(7, 45), FrenchWords.clock("huit heures moins le quart"))
        assertEquals(FrenchWords.Clock(12, 0), FrenchWords.clock("midi"))
        assertEquals(FrenchWords.Clock(0, 30), FrenchWords.clock("minuit et demi"))
        assertEquals(FrenchWords.Clock(20, 0), FrenchWords.clock("8 heures du soir"))
        assertNull(FrenchWords.clock("raconte une blague"))
    }

    @Test fun `speaking durations and times back`() {
        assertEquals("10 minutes", FrenchWords.sayDuration(10 * MINUTE))
        assertEquals("1 minute et 30 secondes", FrenchWords.sayDuration(90 * SECOND))
        assertEquals("1 heure et 5 minutes", FrenchWords.sayDuration(HOUR + 5 * MINUTE))
        assertEquals("45 secondes", FrenchWords.sayDuration(45 * SECOND))
        assertEquals("7 heures 30", FrenchWords.sayClock(7, 30))
        assertEquals("8 heures", FrenchWords.sayClock(8, 0))
    }
}
