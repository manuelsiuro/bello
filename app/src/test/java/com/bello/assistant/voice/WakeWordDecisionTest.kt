package com.bello.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule against results shaped like the ones the tablet actually produced: real calls start
 * 0.18–0.28 s after the onset of speech and are followed by a pause, false ones sit deep inside a
 * sentence that runs straight on.
 */
class WakeWordDecisionTest {

    private val normal = WakeRule.of(WakeSensitivity.NORMAL)

    /** A Vosk final result: (word, confidence, start on the recogniser's own clock). */
    private fun result(vararg words: Triple<String, Double, Double>): String =
        words.joinToString(",", """{"result":[""", """],"text":"…"}""") { (word, conf, start) ->
            """{"conf":$conf,"start":$start,"end":${start + 0.3},"word":"$word"}"""
        }

    @Test fun `someone calling Bello across the room`() {
        val decision = WakeWordDecision.decide(
            result(Triple("bello", 1.0, 10.20)), onsetSec = 10.0, rule = normal)
        val accept = decision as WakeDecision.Accept
        assertEquals(1.0, accept.conf, 0.001)
        assertEquals(0.20, accept.startSec, 0.001)
    }

    @Test fun `the clock keeps running all day, only the offset matters`() {
        // Vosk timestamps count every sample ever fed; after an hour they are in the thousands.
        val decision = WakeWordDecision.decide(
            result(Triple("bello", 0.99, 3617.16)), onsetSec = 3617.0, rule = normal)
        assertTrue(decision is WakeDecision.Accept)
    }

    @Test fun `the television saying something that sounds like it`() {
        // Heard mid-sentence, with the next word right behind it: nobody is talking to Bello.
        val decision = WakeWordDecision.decide(
            result(Triple("[unk]", 1.0, 5.00), Triple("bello", 1.0, 5.22), Triple("[unk]", 1.0, 5.45)),
            onsetSec = 5.02, rule = normal)
        assertEquals(WakeDecision.Reason.NOT_ISOLATED, (decision as WakeDecision.Reject).reason)
    }

    @Test fun `half-heard is not heard`() {
        val decision = WakeWordDecision.decide(
            result(Triple("bello", 0.45, 1.20)), onsetSec = 1.0, rule = normal)
        assertEquals(WakeDecision.Reason.LOW_CONFIDENCE, (decision as WakeDecision.Reject).reason)
    }

    @Test fun `too early or too late in the utterance`() {
        // Before the onset: the word was already under way when the room got loud.
        val early = WakeWordDecision.decide(
            result(Triple("bello", 1.0, 4.70)), onsetSec = 5.0, rule = normal)
        assertEquals(WakeDecision.Reason.OUTSIDE_WINDOW, (early as WakeDecision.Reject).reason)
        // Deep inside a sentence, where every false wake of SP-03 sat.
        val late = WakeWordDecision.decide(
            result(Triple("bello", 1.0, 5.90)), onsetSec = 5.0, rule = normal)
        assertEquals(WakeDecision.Reason.OUTSIDE_WINDOW, (late as WakeDecision.Reject).reason)
    }

    @Test fun `a rejection says what it saw, so a room can be tuned from the log`() {
        val decision = WakeWordDecision.decide(
            result(Triple("bello", 0.45, 5.60)), onsetSec = 5.0, rule = normal) as WakeDecision.Reject
        assertEquals("conf=0.45 start=0.60 gap=9.99", decision.detail)
    }

    @Test fun `everything heard is reported, whatever the rule thinks of it`() {
        // The sweep that picks the thresholds runs on these, so they must not be filtered first.
        val candidates = WakeWordDecision.candidates(
            result(Triple("bello", 0.30, 5.10), Triple("[unk]", 1.0, 5.40), Triple("bello", 1.0, 7.00)),
            onsetSec = 5.0)
        assertEquals(2, candidates.size)
        assertEquals(0.30, candidates[0].conf, 0.001)
        assertEquals(0.10, candidates[0].startSec, 0.001)
        assertEquals(0.30, candidates[0].gapSec, 0.001)
        // Nothing follows the last one: as isolated as a word can be.
        assertEquals(WakeWordDecision.ALONE_SEC, candidates[1].gapSec, 0.001)
    }

    @Test fun `a second, clearer attempt in the same breath still wakes`() {
        // "Bello… Bello !" — the first one half-swallowed, the second clean.
        val decision = WakeWordDecision.decide(
            result(Triple("bello", 0.40, 5.12), Triple("bello", 1.0, 5.30)), onsetSec = 5.0, rule = normal)
        assertEquals(0.30, (decision as WakeDecision.Accept).startSec, 0.001)
    }

    @Test fun `silence, and nonsense, never wake`() {
        listOf("", "{}", "not json at all", """{"text":""}""", """{"result":[]}""").forEach { json ->
            val decision = WakeWordDecision.decide(json, onsetSec = 0.0, rule = normal)
            assertEquals(json, WakeDecision.Reason.NO_KEYWORD, (decision as WakeDecision.Reject).reason)
        }
    }

    @Test fun `sensitivity widens or narrows the same decision`() {
        // Half-heard, and a shade late: the kind that only the eager setting should take.
        val borderline = result(Triple("bello", 0.60, 5.42))
        assertTrue(WakeWordDecision.decide(borderline, 5.0, WakeRule.of(WakeSensitivity.HIGH))
            is WakeDecision.Accept)
        assertTrue(WakeWordDecision.decide(borderline, 5.0, normal) is WakeDecision.Reject)
        assertTrue(WakeWordDecision.decide(borderline, 5.0, WakeRule.of(WakeSensitivity.LOW))
            is WakeDecision.Reject)
    }

    @Test fun `sensitivity is read from whatever the settings hold`() {
        assertEquals(WakeSensitivity.HIGH, WakeSensitivity.from("high"))
        assertEquals(WakeSensitivity.LOW, WakeSensitivity.from(" LOW "))
        assertEquals(WakeSensitivity.NORMAL, WakeSensitivity.from(null))
        assertEquals(WakeSensitivity.NORMAL, WakeSensitivity.from("whatever"))
    }
}
