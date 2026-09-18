package com.bello.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmConfigTest {

    @Test fun `preset fills base url and default model`() {
        val config = LlmConfig.parse("""{"providers":[{"preset":"groq","key":"gsk_x"}]}""")
        val groq = config.providers.single()
        assertEquals("groq", groq.id)
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl)
        assertEquals("openai/gpt-oss-20b", groq.model)
        assertEquals("gsk_x", groq.apiKey)
        assertTrue(groq.enabled)
    }

    @Test fun `file order is the provider order`() {
        val config = LlmConfig.parse(
            """{"providers":[{"preset":"gemini","key":"a"},{"preset":"groq","key":"b"}]}"""
        )
        assertEquals(listOf("gemini", "groq"), config.providers.map { it.id })
    }

    @Test fun `provider without a key is dropped and reported`() {
        val config = LlmConfig.parse("""{"providers":[{"preset":"gemini"},{"preset":"groq","key":"b"}]}""")
        assertEquals(listOf("groq"), config.providers.map { it.id })
        assertTrue(config.problems.single().contains("no API key"))
    }

    @Test fun `gemini web needs no key and is off unless enabled`() {
        val config = LlmConfig.parse("""{"providers":[{"preset":"geminiweb"}]}""")
        val web = config.providers.single()
        assertEquals(ProviderConfig.Type.GEMINI_WEB, web.type)
        assertEquals(false, web.enabled)
    }

    @Test fun `custom provider with base url and trailing slash`() {
        val config = LlmConfig.parse(
            """{"providers":[{"id":"local","baseUrl":"http://127.0.0.1:8099/v1/","key":"x","model":"m"}]}"""
        )
        val local = config.providers.single()
        assertEquals("http://127.0.0.1:8099/v1", local.baseUrl)
        assertEquals("m", local.model)
    }

    @Test fun `unknown preset without base url is a problem, not a crash`() {
        val config = LlmConfig.parse("""{"providers":[{"preset":"nope","key":"x"}]}""")
        assertTrue(config.providers.isEmpty())
        assertTrue(config.problems.single().contains("unknown preset"))
    }

    @Test fun `duplicate ids are refused`() {
        val config = LlmConfig.parse(
            """{"providers":[{"preset":"groq","key":"a"},{"preset":"groq","key":"b"}]}"""
        )
        assertEquals(1, config.providers.size)
        assertTrue(config.problems.single().contains("duplicate"))
    }

    @Test fun `broken json yields an empty config with an explanation`() {
        val config = LlmConfig.parse("{oops")
        assertTrue(config.providers.isEmpty())
        assertTrue(config.problems.single().contains("not valid JSON"))
    }

    @Test fun `presets carry their extra request fields, and the file can override them`() {
        val preset = LlmConfig.parse("""{"providers":[{"preset":"gemini","key":"a"}]}""")
        assertTrue(preset.providers.single().extra.contains("reasoning_effort"))

        val custom = LlmConfig.parse(
            """{"providers":[{"preset":"gemini","key":"a","extra":{"temperature":0.1}}]}"""
        )
        assertTrue(custom.providers.single().extra.contains("temperature"))
    }

    @Test fun `timeouts and persona have defaults and can be overridden`() {
        val defaults = LlmConfig.parse("{}")
        assertEquals(20_000, defaults.timeoutMs)
        assertEquals(60_000, defaults.cooldownMs)
        assertEquals(Persona.DEFAULT, defaults.persona)

        val custom = LlmConfig.parse("""{"timeoutMs":5000,"cooldownMs":1000,"persona":"Sois bref."}""")
        assertEquals(5_000, custom.timeoutMs)
        assertEquals(1_000, custom.cooldownMs)
        assertEquals("Sois bref.", custom.persona)
    }
}
