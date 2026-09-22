package com.bello.assistant.images

import com.bello.assistant.llm.FailureKind
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The two picture services against a local server that plays their answers. */
class ImageProvidersTest {

    private lateinit var server: MockWebServer

    /** Enough of a JPEG for the magic-number check. */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60) { it.toByte() }

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun cloudflare() = CloudflareImageProvider(
        ImageProviderConfig("cloudflare", "cloudflare", "cf-token", "acc42", "@cf/black-forest-labs/flux-1-schnell"),
        OkHttpClient(),
        baseUrl = server.url("/client/v4").toString().trimEnd('/'),
    )

    private fun pollinations(key: String) = PollinationsImageProvider(
        ImageProviderConfig("pollinations", "pollinations", key, model = "flux"),
        OkHttpClient(),
        keyedBase = server.url("/image/").toString(),
        anonymousBase = server.url("/prompt/").toString(),
    )

    @Test fun `cloudflare - the base64 picture comes back as bytes`() {
        val body = JSONObject().put("result", JSONObject().put("image", jpeg.toByteString().base64()))
            .put("success", true)
        server.enqueue(MockResponse().setBody(body.toString()))
        val result = cloudflare().generate("A calm lake at dawn.", 1024, 768, 3_000)
        assertTrue(result is ImageResult.Ok)
        assertArrayEquals(jpeg, (result as ImageResult.Ok).bytes)
        assertEquals("image/jpeg", result.contentType)
        val request = server.takeRequest()
        assertEquals("/client/v4/accounts/acc42/ai/run/@cf/black-forest-labs/flux-1-schnell", request.path)
        assertEquals("Bearer cf-token", request.getHeader("Authorization"))
        val sent = JSONObject(request.body.readUtf8())
        assertEquals("A calm lake at dawn.", sent.getString("prompt"))
        assertEquals(4, sent.getInt("steps"))
        assertEquals(setOf("prompt", "steps"), sent.keys().asSequence().toSet())
    }

    @Test fun `cloudflare - quota, bad token and an empty result are failures of the right kind`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30")
            .setBody("""{"success":false,"errors":[{"code":4006,"message":"daily free allocation used"}]}"""))
        val quota = cloudflare().generate("p", 1024, 768, 3_000) as ImageResult.Failed
        assertEquals(FailureKind.RATE_LIMIT, quota.failure.kind)
        assertEquals(30_000L, quota.failure.retryAfterMs)
        assertTrue(quota.failure.detail.contains("4006: daily free allocation used"))

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"errors":[]}"""))
        assertEquals(FailureKind.AUTH, (cloudflare().generate("p", 1024, 768, 3_000) as ImageResult.Failed).failure.kind)

        server.enqueue(MockResponse().setBody("""{"result":{},"success":true}"""))
        assertEquals(FailureKind.EMPTY, (cloudflare().generate("p", 1024, 768, 3_000) as ImageResult.Failed).failure.kind)

        server.enqueue(MockResponse().setBody("""{"result":{"image":"PGh0bWw+PC9odG1sPg=="}}"""))
        assertEquals(FailureKind.EMPTY, (cloudflare().generate("p", 1024, 768, 3_000) as ImageResult.Failed).failure.kind)
    }

    @Test fun `pollinations with a key - FLUX on the keyed address, the key in a header`() {
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)).setHeader("x-model-used", "flux"))
        val result = pollinations("pk_secret").generate("A calm lake, at dawn.", 1024, 768, 3_000) as ImageResult.Ok
        assertEquals("flux", result.model)
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals(listOf("image", "A calm lake, at dawn."), url.pathSegments)
        assertEquals("flux", url.queryParameter("model"))
        assertEquals("1024", url.queryParameter("width"))
        assertEquals("false", url.queryParameter("enhance"))
        assertEquals("Bearer pk_secret", request.getHeader("Authorization"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("Bello/"))
        assertTrue(!url.toString().contains("pk_secret"))
    }

    @Test fun `pollinations without a key - the old address, no header`() {
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg)).setHeader("x-model-used", "sana"))
        val result = pollinations("").generate("A calm lake at dawn.", 1024, 768, 3_000) as ImageResult.Ok
        assertEquals("sana", result.model)
        val request = server.takeRequest()
        assertEquals("prompt", request.requestUrl!!.pathSegments.first())
        assertEquals(null, request.getHeader("Authorization"))
    }

    @Test fun `pollinations - an error page is not a picture, even with a 200`() {
        server.enqueue(MockResponse().setBody("<html>Queue full</html>").setHeader("Content-Type", "text/html"))
        assertEquals(FailureKind.EMPTY, (pollinations("k").generate("p", 1024, 768, 3_000) as ImageResult.Failed).failure.kind)
        server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":"not enough pollen"}"""))
        assertEquals(FailureKind.RATE_LIMIT, (pollinations("k").generate("p", 1024, 768, 3_000) as ImageResult.Failed).failure.kind)
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertEquals(FailureKind.SERVER, (pollinations("k").generate("p", 1024, 768, 3_000) as ImageResult.Failed).failure.kind)
    }

    @Test fun `a picture too big for a phone is refused`() {
        server.enqueue(MockResponse().setBody(Buffer().write(jpeg + ByteArray(ImageHttp.MAX_BYTES.toInt()))))
        assertEquals(FailureKind.EMPTY, (pollinations("k").generate("p", 1024, 768, 5_000) as ImageResult.Failed).failure.kind)
    }

    @Test fun `magic numbers tell a picture from an error page`() {
        assertTrue(ImageHttp.looksLikeImage(jpeg))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(12)
        assertTrue(ImageHttp.looksLikeImage(png))
        assertEquals("image/png", ImageHttp.contentTypeOf(png))
        val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()
        assertTrue(ImageHttp.looksLikeImage(webp))
        assertEquals("image/webp", ImageHttp.contentTypeOf(webp))
        assertTrue(!ImageHttp.looksLikeImage("<html></html>".toByteArray()))
    }
}
