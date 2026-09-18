package com.bello.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayStateTest {

    private val t0 = 1_700_000_000_000L

    @Test fun `a fresh provider is available`() {
        assertTrue(GatewayState().available("groq", t0))
    }

    @Test fun `rate limit puts the provider on cooldown for the configured time`() {
        val state = GatewayState(baseCooldownMs = 60_000)
        state.onFailure("groq", t0, LlmResult.Failed(FailureKind.RATE_LIMIT, "http=429"))
        assertFalse(state.available("groq", t0 + 59_000))
        assertTrue(state.available("groq", t0 + 60_001))
    }

    @Test fun `retry-after wins over the configured cooldown`() {
        val state = GatewayState(baseCooldownMs = 60_000)
        state.onFailure("groq", t0, LlmResult.Failed(FailureKind.RATE_LIMIT, "http=429", retryAfterMs = 5_000))
        assertFalse(state.available("groq", t0 + 4_000))
        assertTrue(state.available("groq", t0 + 5_001))
    }

    @Test fun `a bad key is not retried for half an hour`() {
        val state = GatewayState()
        state.onFailure("gemini", t0, LlmResult.Failed(FailureKind.AUTH, "http=401"))
        assertFalse(state.available("gemini", t0 + 29 * 60_000))
        assertTrue(state.available("gemini", t0 + 31 * 60_000))
    }

    @Test fun `transient failures back off, capped at five minutes`() {
        assertEquals(15_000L, GatewayState.cooldownMs(FailureKind.TIMEOUT, null, 1))
        assertEquals(30_000L, GatewayState.cooldownMs(FailureKind.NETWORK, null, 2))
        assertEquals(5 * 60_000L, GatewayState.cooldownMs(FailureKind.SERVER, null, 9))
    }

    @Test fun `success clears the cooldown and counts the request`() {
        val state = GatewayState()
        state.onFailure("groq", t0, LlmResult.Failed(FailureKind.SERVER, "http=502"))
        state.onSuccess("groq", t0 + 20_000, latencyMs = 900)
        assertTrue(state.available("groq", t0 + 20_001))
        assertEquals(1, state.status("groq").usedToday)
        assertEquals(0, state.status("groq").consecutiveFails)
        assertEquals(900, state.status("groq").lastLatencyMs)
    }

    @Test fun `a rate limit still counts against the day`() {
        val state = GatewayState()
        state.onFailure("groq", t0, LlmResult.Failed(FailureKind.RATE_LIMIT, "http=429"))
        assertEquals(1, state.status("groq").usedToday)
    }

    @Test fun `the daily limit blocks, and the counter resets the next day`() {
        val state = GatewayState(dailyLimit = 2)
        state.onSuccess("groq", t0, 100)
        state.onSuccess("groq", t0, 100)
        assertFalse(state.available("groq", t0))
        assertTrue(state.whyUnavailable("groq", t0).contains("daily limit"))
        assertTrue(state.available("groq", t0 + 86_400_000))
    }
}
