package com.bello.assistant.llm

import com.bello.assistant.ui.FaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fallback, cooldown and the persona tag, with fake providers — no network, no device. */
class LlmGatewayTest {

    private class Fake(
        override val id: String,
        private val results: MutableList<LlmResult>,
        private val ready: Boolean = true,
    ) : LlmProvider {
        override val model = "fake-model"
        var calls = 0
        override fun isReady() = ready
        override fun complete(request: LlmRequest): LlmResult {
            calls++
            return if (results.size > 1) results.removeAt(0) else results.first()
        }
    }

    private fun ok(text: String) = LlmResult.Ok(text, "fake-model", 42)
    private fun rateLimited() = LlmResult.Failed(FailureKind.RATE_LIMIT, "http=429", 60_000)

    private var now = 1_700_000_000_000L

    private fun gateway(vararg providers: LlmProvider, config: LlmConfig = LlmConfig.EMPTY) =
        LlmGateway(providers.toList(), config, clock = { now }, nowLabel = { "jeudi" })

    @Test fun `the first provider answers and is recorded`() {
        val first = Fake("gemini", mutableListOf(ok("[happy] Bello !")))
        val second = Fake("groq", mutableListOf(ok("jamais")))
        val answer = gateway(first, second).answer("Ça va ?")

        assertEquals("Bello !", answer.text)
        assertEquals(FaceState.HAPPY, answer.emotion)
        assertEquals("gemini/fake-model", answer.source)
        assertEquals(1, first.calls)
        assertEquals(0, second.calls)
    }

    @Test fun `a 429 falls through to the next provider`() {
        val first = Fake("gemini", mutableListOf(rateLimited()))
        val second = Fake("groq", mutableListOf(ok("Voilà.")))
        val gateway = gateway(first, second)

        val answer = gateway.answer("Quelle heure est-il ?")
        assertEquals("Voilà.", answer.text)
        assertEquals("groq/fake-model", answer.source)
        assertEquals(false, answer.isError)
    }

    @Test fun `the rate-limited provider is skipped while it is on cooldown`() {
        val first = Fake("gemini", mutableListOf(rateLimited(), ok("de retour")))
        val second = Fake("groq", mutableListOf(ok("Voilà.")))
        val gateway = gateway(first, second, config = LlmConfig.EMPTY.copy(cooldownMs = 60_000))

        gateway.answer("une")
        now += 30_000
        gateway.answer("deux")
        assertEquals("gemini must not be called again during its cooldown", 1, first.calls)
        assertEquals(2, second.calls)

        now += 40_000 // cooldown over
        val answer = gateway.answer("trois")
        assertEquals("de retour", answer.text)
        assertEquals(2, first.calls)
    }

    @Test fun `a provider that is not ready is skipped`() {
        val web = Fake("geminiweb", mutableListOf(ok("jamais")), ready = false)
        val api = Fake("groq", mutableListOf(ok("Voilà.")))
        assertEquals("Voilà.", gateway(web, api).answer("q").text)
        assertEquals(0, web.calls)
    }

    @Test fun `when everything fails Bello says so and looks sad`() {
        val first = Fake("gemini", mutableListOf(rateLimited()))
        val second = Fake("groq", mutableListOf(LlmResult.Failed(FailureKind.NETWORK, "no route")))
        val answer = gateway(first, second).answer("q")

        assertEquals(LlmGateway.ALL_FAILED, answer.text)
        assertTrue(answer.isError)
        assertEquals(FaceState.SAD, answer.emotion)
        assertNull(answer.source)
    }

    @Test fun `with no provider configured the answer explains what is missing`() {
        val answer = LlmGateway(emptyList(), LlmConfig.EMPTY, clock = { now }).answer("q")
        assertTrue(answer.isError)
        assertTrue(answer.text.contains("clé"))
    }

    @Test fun `the time budget stops the chain instead of leaving Bello silent`() {
        val slow = object : LlmProvider {
            override val id = "slow"
            override val model = "fake-model"
            var calls = 0
            override fun complete(request: LlmRequest): LlmResult {
                calls++
                now += 40_000 // as if the call took 40 s
                return LlmResult.Failed(FailureKind.TIMEOUT, "timeout")
            }
        }
        val last = Fake("groq", mutableListOf(ok("trop tard")))
        val answer = gateway(slow, last, config = LlmConfig.EMPTY.copy(totalBudgetMs = 30_000)).answer("q")

        assertEquals(1, slow.calls)
        assertEquals("the budget was spent, groq must not be tried", 0, last.calls)
        assertEquals(LlmGateway.ALL_FAILED, answer.text)
    }

    @Test fun `status lines report counters for the overlay`() {
        val first = Fake("gemini", mutableListOf(ok("oui")))
        val gateway = gateway(first)
        gateway.answer("q")
        val line = gateway.statusLines().single()
        assertTrue(line.contains("gemini"))
        assertTrue(line.contains("ok=1"))
        assertTrue(line.contains("today=1"))
        assertEquals("gemini/fake-model", gateway.lastSource)
    }
}
