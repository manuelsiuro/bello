package com.bello.assistant.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PageStoreTest {

    @Test fun `a page is served until it expires`() {
        val store = PageStore(ttlMs = 1_000, random = Random(1))
        val id = store.publish("<p>a</p>", now = 10_000)
        assertEquals("<p>a</p>", store.get(id, now = 10_999))
        assertEquals(10_000L + 1_000L, store.nextExpiry())
        assertNull(store.get(id, now = 11_000))
        assertTrue(store.isEmpty())
        assertNull(store.nextExpiry())
    }

    @Test fun `only the last few pages are kept, the oldest go first`() {
        val store = PageStore(maxPages = 3, random = Random(1))
        val ids = (1..4).map { store.publish("<p>$it</p>", now = it.toLong()) }
        assertEquals(3, store.size())
        assertNull(store.get(ids[0], now = 5))
        assertEquals("<p>4</p>", store.get(ids[3], now = 5))
    }

    @Test fun `an unknown id and a sweep`() {
        val store = PageStore(ttlMs = 100, random = Random(1))
        assertNull(store.get("zzzzzzzz", now = 0))
        store.publish("a", now = 0); store.publish("b", now = 50)
        assertEquals(1, store.sweep(now = 120))
        assertEquals(1, store.size())
    }
}
