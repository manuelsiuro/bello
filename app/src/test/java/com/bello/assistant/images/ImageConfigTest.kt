package com.bello.assistant.images

import com.bello.assistant.core.AppConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageConfigTest {

    @Test fun `no images block means no picture`() {
        assertTrue(ImageConfig.parse(null).isEmpty)
        assertTrue(AppConfig.parse("""{"providers":[]}""").images.isEmpty)
    }

    @Test fun `the services are read in order, with their defaults`() {
        val config = ImageConfig.parse(JSONObject("""{"providers":[
            {"preset":"cloudflare","accountId":"acc","key":"cf-token"},
            {"preset":"pollinations","apiKey":"pk_x"},
            {"preset":"pollinations","id":"anonymous","enabled":false}]}"""))
        assertEquals(listOf("cloudflare", "pollinations", "anonymous"), config.providers.map { it.id })
        assertEquals("@cf/black-forest-labs/flux-1-schnell", config.providers[0].model)
        assertEquals("pk_x", config.providers[1].key)
        assertEquals("flux", config.providers[1].model)
        assertEquals(false, config.providers[2].enabled)
        assertEquals(1024 to 768, config.width to config.height)
        assertEquals(25_000, config.budgetMs)
        assertTrue(config.problems.isEmpty())
    }

    @Test fun `an unusable entry is dropped and said`() {
        val config = ImageConfig.parse(JSONObject("""{"width":5000,"budgetMs":1,"providers":[
            {"preset":"cloudflare","key":"no-account"},{"preset":"midjourney","key":"x"}]}"""))
        assertTrue(config.isEmpty)
        assertEquals(2, config.problems.size)
        assertTrue(config.problems[0].contains("accountId"))
        assertEquals(1536, config.width)
        assertEquals(5_000, config.budgetMs)
    }

    @Test fun `the example config parses with both services`() {
        val example = AppConfig.parse(File("../config/bello.example.json").readText())
        assertEquals(listOf("cloudflare", "pollinations"), example.images.providers.map { it.preset })
    }
}
