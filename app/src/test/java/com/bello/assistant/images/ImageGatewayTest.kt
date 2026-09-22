package com.bello.assistant.images

import com.bello.assistant.llm.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageGatewayTest {

    private var now = 1_000_000L

    private class Fake(
        override val id: String,
        private val answers: MutableList<ImageResult>,
        private val takesMs: Long = 0,
        private val clock: (Long) -> Unit = {},
    ) : ImageProvider {
        override val label get() = id
        var calls = 0
        var lastTimeoutMs = 0
        override fun generate(prompt: String, width: Int, height: Int, timeoutMs: Int): ImageResult {
            calls++
            lastTimeoutMs = timeoutMs
            clock(takesMs)
            return answers.removeAt(0)
        }
    }

    private fun ok(tag: String) = ImageResult.Ok(tag.toByteArray(), "image/jpeg", 10, "flux")
    private fun failed(kind: FailureKind) = ImageResult.Failed(kind, "test")

    private fun gateway(vararg providers: ImageProvider, budgetMs: Int = 25_000) =
        ImageGateway(providers.toList(), ImageConfig(budgetMs = budgetMs), clock = { now })

    @Test fun `the first service answers`() {
        val first = Fake("cloudflare", mutableListOf(ok("cf")))
        val second = Fake("pollinations", mutableListOf(ok("po")))
        val picture = gateway(first, second).generate("p")!!
        assertEquals("cf", String(picture.bytes))
        assertEquals("cloudflare/flux", picture.source)
        assertEquals(0, second.calls)
    }

    @Test fun `a failed service hands over, and cools down for the next page`() {
        val first = Fake("cloudflare", mutableListOf(failed(FailureKind.RATE_LIMIT), ok("cf")))
        val second = Fake("pollinations", mutableListOf(ok("po1"), ok("po2")))
        val images = gateway(first, second)
        assertEquals("po1", String(images.generate("p")!!.bytes))
        now += 10_000
        assertEquals("po2", String(images.generate("p")!!.bytes))
        assertEquals(1, first.calls)
        now += 120_000
        assertEquals("cf", String(images.generate("p")!!.bytes))
    }

    @Test fun `nobody answers - no picture, never an exception`() {
        val first = Fake("cloudflare", mutableListOf(failed(FailureKind.AUTH)))
        val second = Fake("pollinations", mutableListOf(failed(FailureKind.TIMEOUT)))
        assertNull(gateway(first, second).generate("p"))
        assertNull(gateway().generate("p"))
    }

    @Test fun `the budget is shared - the second service gets what is left, or nothing`() {
        val slow = Fake("cloudflare", mutableListOf(failed(FailureKind.TIMEOUT)), takesMs = 20_000) { now += it }
        val next = Fake("pollinations", mutableListOf(ok("po")))
        assertEquals("po", String(gateway(slow, next).generate("p")!!.bytes))
        assertEquals(25_000, slow.lastTimeoutMs)
        assertEquals(5_000, next.lastTimeoutMs)

        val slower = Fake("cloudflare", mutableListOf(failed(FailureKind.TIMEOUT)), takesMs = 23_000) { now += it }
        val skipped = Fake("pollinations", mutableListOf(ok("po")))
        assertNull(gateway(slower, skipped).generate("p"))
        assertEquals(0, skipped.calls)
    }
}
