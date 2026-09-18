package com.bello.assistant.core

/**
 * How long to wait before restarting after a crash (NFR-REL-01).
 *
 * A crash that happens *at startup* — a missing permission, a bad configuration — crashes again the
 * moment the app comes back. At a flat two seconds that is a loop: thirty restarts a minute, a hot
 * tablet and a flat battery, for as long as nobody notices. So each crash that follows closely on
 * the last one waits longer, up to a minute, and a process that has been alive a while starts over
 * from the short delay.
 *
 * Pure and clock-injected: the loop is reproduced in a test rather than on the device.
 */
object RestartBackoff {

    const val FIRST_MS = 2_000L
    const val MAX_MS = 60_000L
    /** Survive this long and the crash counts as a fresh one, not part of a loop. */
    const val HEALTHY_MS = 120_000L

    /**
     * @param consecutive how many crashes have already followed each other closely
     * @param aliveMs how long this process ran before it died
     */
    fun delayMs(consecutive: Int, aliveMs: Long): Long {
        if (aliveMs >= HEALTHY_MS) return FIRST_MS
        val doublings = consecutive.coerceIn(0, 10)
        return (FIRST_MS shl doublings).coerceAtMost(MAX_MS)
    }

    /** The count to remember for next time. */
    fun nextCount(consecutive: Int, aliveMs: Long): Int =
        if (aliveMs >= HEALTHY_MS) 1 else (consecutive + 1).coerceAtMost(10)
}
