package com.bello.assistant

import com.bello.assistant.service.KioskPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskPolicyTest {
    @Test
    fun relaunchesAfterGraceWhenHidden() {
        assertTrue(KioskPolicy.shouldRelaunch(true, false, lastVisibleAtMs = 0, nowMs = 60_000))
    }

    @Test
    fun waitsDuringGrace() {
        assertFalse(KioskPolicy.shouldRelaunch(true, false, lastVisibleAtMs = 10_000, nowMs = 60_000))
    }

    @Test
    fun neverRelaunchesWhenVisibleOrDisabled() {
        assertFalse(KioskPolicy.shouldRelaunch(true, true, 0, 600_000))
        assertFalse(KioskPolicy.shouldRelaunch(false, false, 0, 600_000))
    }
}
