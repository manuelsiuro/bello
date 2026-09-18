package com.bello.assistant.llm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The HTTP half of the gateway, against a local server that plays each provider behaviour. */
class OpenAiCompatibleProviderTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun provider(extra: String = ""): OpenAiCompatibleProvider {
        val config = ProviderConfig(
            id = "test", type = ProviderConfig.Type.OPENAI,
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "secret", model = "test-model", enabled = true, extra = extra,
        )
        return OpenAiCompatibleProvider(config, OkHttpClient())
    }

    @Test fun `extra fields from the config are sent with the request`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        provider("""{"reasoning_effort":"none"}""").complete(
            LlmRequest("s", listOf(LlmMessage.user("q")), timeoutMs = 3_000)
        )
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("none", body.getString("reasoning_effort"))
    }

    @Test fun `unreadable extra fields are ignored, not fatal`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        val result = provider("not json").complete(
            LlmRequest("s", listOf(LlmMessage.user("q")), timeoutMs = 3_000)
        )
        assertTrue(result is LlmResult.Ok)
    }

    private fun ask() = provider().complete(
        LlmRequest("Tu es Bello.", listOf(LlmMessage.user("Ça va ?")), timeoutMs = 3_000)
    )

    @Test fun `a normal answer comes back with the model name`() {
        server.enqueue(MockResponse().setBody(
            """{"choices":[{"message":{"role":"assistant","content":"[happy] Bello !"}}]}"""
        ))
        val result = ask() as LlmResult.Ok
        assertEquals("[happy] Bello !", result.text)
        assertEquals("test-model", result.model)

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals("Bearer secret", sent.getHeader("Authorization"))
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("test-model", body.getString("model"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("Ça va ?", body.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test fun `429 is a rate limit and Retry-After is passed on`() {
        server.enqueue(MockResponse().setResponseCode(429)
            .setHeader("Retry-After", "7")
            .setBody("""{"error":{"message":"Rate limit reached"}}"""))
        val failed = ask() as LlmResult.Failed
        assertEquals(FailureKind.RATE_LIMIT, failed.kind)
        assertEquals(7_000L, failed.retryAfterMs)
        assertTrue(failed.detail.contains("Rate limit reached"))
    }

    @Test fun `401 is an auth failure`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        assertEquals(FailureKind.AUTH, (ask() as LlmResult.Failed).kind)
    }

    @Test fun `5xx is a server failure`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))
        assertEquals(FailureKind.SERVER, (ask() as LlmResult.Failed).kind)
    }

    @Test fun `an unknown model is a bad request`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}"""))
        assertEquals(FailureKind.BAD_REQUEST, (ask() as LlmResult.Failed).kind)
    }

    @Test fun `an answer without content is empty, not a crash`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant"}}]}"""))
        assertEquals(FailureKind.EMPTY, (ask() as LlmResult.Failed).kind)
    }

    @Test fun `a dead endpoint is a network failure`() {
        val port = server.port
        server.shutdown()
        val config = ProviderConfig("test", ProviderConfig.Type.OPENAI,
            "http://127.0.0.1:$port/v1", "secret", "test-model", true)
        val result = OpenAiCompatibleProvider(config, OkHttpClient()).complete(
            LlmRequest("s", listOf(LlmMessage.user("q")), timeoutMs = 2_000)
        )
        assertEquals(FailureKind.NETWORK, (result as LlmResult.Failed).kind)
        server = MockWebServer().also { it.start() } // so @After has something to shut down
    }

    @Test fun `content sent as parts is joined`() {
        assertEquals("un deux", OpenAiParse.content(
            """{"choices":[{"message":{"content":[{"text":"un "},{"text":"deux"}]}}]}"""
        ))
    }

    @Test fun `retry-after may be fractional or missing`() {
        assertEquals(7_500L, OpenAiParse.retryAfterMs("7.5"))
        assertEquals(null, OpenAiParse.retryAfterMs(null))
        assertEquals(null, OpenAiParse.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
    }
}
