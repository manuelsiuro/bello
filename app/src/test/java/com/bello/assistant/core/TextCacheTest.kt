package com.bello.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextCacheTest {

    @get:Rule val folder = TemporaryFolder()

    private var now = 1_000_000L
    private fun cache() = TextCache(folder.root) { now }

    @Test fun `a kept answer is served while it is young enough`() {
        val cache = cache()
        cache.put("feries-metropole-2026", "{\"2026-11-01\": \"Toussaint\"}")
        assertEquals("{\"2026-11-01\": \"Toussaint\"}", cache.fresh("feries-metropole-2026", 1_000))
        now += 2_000
        assertNull(cache.fresh("feries-metropole-2026", 1_000))
        assertEquals("{\"2026-11-01\": \"Toussaint\"}", cache.any("feries-metropole-2026"))
        assertNull(cache.any("jamais-demande"))
    }

    @Test fun `the network is asked only when the copy is too old`() {
        val cache = cache()
        var calls = 0
        val fetch = { calls++; "fresh $calls" }
        assertEquals("fresh 1", cache.text("ecole", 1_000, fetch))
        assertEquals("fresh 1", cache.text("ecole", 1_000, fetch))
        assertEquals(1, calls)
        now += 2_000
        assertEquals("fresh 2", cache.text("ecole", 1_000, fetch))
        assertEquals(2, calls)
    }

    @Test fun `a failed fetch answers with the old copy rather than with nothing`() {
        val cache = cache()
        cache.put("ecole", "last week's list")
        now += 30 * 24 * 3_600_000L
        assertEquals("last week's list", cache.text("ecole", 1_000) { null })
        assertEquals("last week's list", cache.text("ecole", 1_000) { "" })
        assertNull(cache.text("rien", 1_000) { null })
    }
}
