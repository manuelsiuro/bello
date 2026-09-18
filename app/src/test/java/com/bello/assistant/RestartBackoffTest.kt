package com.bello.assistant

import com.bello.assistant.core.RestartBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The difference between "it crashed" and "it cannot start at all". */
class RestartBackoffTest {

    private val crashedAtStartup = 1_000L

    @Test fun `the first crash comes back quickly`() {
        assertEquals(RestartBackoff.FIRST_MS, RestartBackoff.delayMs(0, crashedAtStartup))
    }

    @Test fun `a crash loop backs off instead of spinning`() {
        val delays = (0..6).map { RestartBackoff.delayMs(it, crashedAtStartup) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), delays)
        // Half an hour of a startup crash costs a handful of restarts, not a thousand.
        var elapsed = 0L
        var count = 0
        var consecutive = 0
        while (elapsed < 30 * 60_000L) {
            elapsed += RestartBackoff.delayMs(consecutive, crashedAtStartup)
            consecutive = RestartBackoff.nextCount(consecutive, crashedAtStartup)
            count++
        }
        assertTrue("restarts in half an hour: $count", count < 40)
    }

    @Test fun `a process that ran for a while starts over from the short delay`() {
        val aliveForHours = 6 * 3600_000L
        assertEquals(RestartBackoff.FIRST_MS, RestartBackoff.delayMs(9, aliveForHours))
        assertEquals(1, RestartBackoff.nextCount(9, aliveForHours))
    }

    @Test fun `the wait never grows past a minute`() {
        assertEquals(RestartBackoff.MAX_MS, RestartBackoff.delayMs(99, crashedAtStartup))
    }
}
