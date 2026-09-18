package com.bello.assistant

import com.bello.assistant.assistant.ConversationPolicy
import com.bello.assistant.assistant.ToolReplies
import com.bello.assistant.assistant.Turn
import com.bello.assistant.core.NightMode
import com.bello.assistant.presence.PresenceRule
import com.bello.assistant.ui.FaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {

    private val elevenPm = 23 * 60
    private val sevenAm = 7 * 60

    @Test fun `the night runs across midnight`() {
        assertTrue(NightMode.isNight(23 * 60 + 30, elevenPm, sevenAm))
        assertTrue(NightMode.isNight(3 * 60, elevenPm, sevenAm))
        assertTrue(NightMode.isNight(elevenPm, elevenPm, sevenAm))      // the minute it starts
        assertFalse(NightMode.isNight(sevenAm, elevenPm, sevenAm))      // the minute it ends
        assertFalse(NightMode.isNight(12 * 60, elevenPm, sevenAm))
    }

    @Test fun `a daytime nap is a window like any other`() {
        assertTrue(NightMode.isNight(14 * 60, 13 * 60, 15 * 60))
        assertFalse(NightMode.isNight(16 * 60, 13 * 60, 15 * 60))
    }

    @Test fun `equal times mean the screen never dims`() {
        assertFalse(NightMode.isNight(0, elevenPm, elevenPm))
        assertFalse(NightMode.isNight(23 * 60 + 59, elevenPm, elevenPm))
    }

    @Test fun `times are read the way people write them`() {
        assertEquals(23 * 60, NightMode.parse("23:00"))
        assertEquals(7 * 60 + 30, NightMode.parse(" 7h30 "))
        assertEquals(0, NightMode.parse("00:00"))
        listOf(null, "", "bonsoir", "25:00", "07:61", "7").forEach { assertNull(it, NightMode.parse(it)) }
    }

    @Test fun `and written back the same way`() {
        assertEquals("23:00", NightMode.format(23 * 60))
        assertEquals("07:05", NightMode.format(7 * 60 + 5))
    }

    @Test fun `how long the night has left`() {
        assertEquals(8 * 60, NightMode.minutesUntilEnd(elevenPm, elevenPm, sevenAm))
        assertEquals(60, NightMode.minutesUntilEnd(6 * 60, elevenPm, sevenAm))
        assertEquals(0, NightMode.minutesUntilEnd(12 * 60, elevenPm, sevenAm))
    }

    @Test fun `an idle Bello dozes at night and stares by day`() {
        assertEquals(FaceState.SLEEPY, ConversationPolicy.face(Turn.IDLE, night = true))
        assertEquals(FaceState.IDLE, ConversationPolicy.face(Turn.IDLE, night = false))
        // Being spoken to looks the same whatever the hour.
        assertEquals(FaceState.LISTENING, ConversationPolicy.face(Turn.LISTENING, night = true))
    }
}

class PresenceRuleTest {

    private val t0 = 1_000_000L
    private val minute = 60_000L

    private fun rule() = PresenceRule(absentAfterMs = minute, greetAfterMs = 5 * minute, startedAt = t0)

    @Test fun `somebody walks in after a long absence`() {
        val rule = rule()
        assertEquals(PresenceRule.Change.ARRIVED_AND_MISSED, rule.update(true, t0 + 6 * minute))
        assertTrue(rule.isPresent)
    }

    @Test fun `walking in right after Bello starts is not a reunion`() {
        val rule = rule()
        assertEquals(PresenceRule.Change.ARRIVED, rule.update(true, t0 + minute))
    }

    @Test fun `looking away does not empty the room`() {
        val rule = rule()
        rule.update(true, t0 + 6 * minute)
        // The detector only sees faces looking straight at it, so gaps are the normal case.
        assertNull(rule.update(false, t0 + 6 * minute + 20_000))
        assertNull(rule.update(false, t0 + 6 * minute + 50_000))
        assertTrue(rule.isPresent)
        assertNull(rule.update(true, t0 + 7 * minute))
    }

    @Test fun `a minute of nothing means the room is empty`() {
        val rule = rule()
        rule.update(true, t0 + 6 * minute)
        assertEquals(PresenceRule.Change.LEFT, rule.update(false, t0 + 7 * minute + 1))
        assertFalse(rule.isPresent)
    }

    @Test fun `stepping out for a moment earns no greeting, five minutes does`() {
        val rule = rule()
        rule.update(true, t0 + 6 * minute)
        rule.update(false, t0 + 7 * minute + 1)                       // left
        assertEquals(PresenceRule.Change.ARRIVED, rule.update(true, t0 + 9 * minute))
        rule.update(false, t0 + 10 * minute + 1)                      // left again
        assertEquals(PresenceRule.Change.ARRIVED_AND_MISSED, rule.update(true, t0 + 20 * minute))
    }
}

class GreetingTest {

    @Test fun `the greeting suits the hour`() {
        assertEquals("Bello ! Bonjour !", ToolReplies.greeting(9))
        assertEquals("Bello ! Bonsoir !", ToolReplies.greeting(20))
        assertTrue(ToolReplies.greeting(3).isNotBlank())
    }
}
