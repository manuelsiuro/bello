package com.bello.assistant.memory

import com.bello.assistant.core.AppConfig
import com.bello.assistant.tools.RssTitles
import com.bello.assistant.tools.WmoCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoryTest {

    private val t0 = 1_700_000_000_000L

    @Test fun `the conversation is replayed oldest first`() {
        val memory = SessionMemory()
        memory.add("Qui a peint la Joconde ?", "Léonard de Vinci.", t0)
        memory.add("Et sa hauteur ?", "77 centimètres.", t0 + 5_000)
        val history = memory.history(t0 + 6_000)
        assertEquals(4, history.size)
        assertEquals("user", history[0].role)
        assertEquals("Qui a peint la Joconde ?", history[0].content)
        assertEquals("assistant", history[1].role)
        assertEquals("Et sa hauteur ?", history[2].content)
    }

    @Test fun `only the last turns are kept`() {
        val memory = SessionMemory(maxTurns = 2)
        repeat(5) { memory.add("q$it", "a$it", t0 + it * 1000L) }
        val history = memory.history(t0 + 5_000)
        assertEquals(4, history.size)
        assertEquals("q3", history[0].content)
        assertEquals("q4", history[2].content)
    }

    @Test fun `a quiet spell starts a new session`() {
        val memory = SessionMemory(idleResetMs = 10 * 60_000)
        memory.add("Qui a peint la Joconde ?", "Léonard de Vinci.", t0)
        assertEquals(2, memory.history(t0 + 9 * 60_000).size)
        assertTrue(memory.history(t0 + 11 * 60_000).isEmpty())
        assertEquals(0, memory.size)
    }
}

class FactMatchTest {

    private val facts = listOf(
        Fact(1, "mon café préféré est l'espresso", 0),
        Fact(2, "ma fille s'appelle Louise", 0),
        Fact(3, "je suis allergique aux arachides", 0),
    )

    @Test fun `forgetting picks the memory the user meant`() {
        assertEquals(listOf(1L), FactMatch.matches(facts, "mon café préféré").map { it.id })
        assertEquals(listOf(2L), FactMatch.matches(facts, "ma fille").map { it.id })
        assertEquals(listOf(1L), FactMatch.matches(facts, "l'espresso").map { it.id })
    }

    @Test fun `accents and case do not matter`() {
        assertEquals(listOf(1L), FactMatch.matches(facts, "CAFE prefere").map { it.id })
    }

    @Test fun `an unrelated request deletes nothing`() {
        assertTrue(FactMatch.matches(facts, "la voiture rouge").isEmpty())
        assertTrue(FactMatch.matches(facts, "mon").isEmpty())
        assertTrue(FactMatch.matches(facts, "").isEmpty())
    }
}

class RssTitlesTest {

    @Test fun `titles are read out of a feed, channel title excluded`() {
        val xml = """
            <rss><channel>
              <title>Le Monde</title>
              <item><title><![CDATA[Un accord trouvé à Bruxelles]]></title></item>
              <item><title>Grève des transports &amp; perturbations</title></item>
            </channel></rss>
        """.trimIndent()
        assertEquals(
            listOf("Un accord trouvé à Bruxelles", "Grève des transports & perturbations"),
            RssTitles.parse(xml)
        )
    }

    @Test fun `a broken feed yields nothing instead of throwing`() {
        assertTrue(RssTitles.parse("").isEmpty())
        assertTrue(RssTitles.parse("<html>not a feed</html>").isEmpty())
    }
}

class WeatherWordsTest {

    @Test fun `weather codes are spoken in French`() {
        assertEquals("ciel dégagé", WmoCodes.french(0))
        assertEquals("de la pluie", WmoCodes.french(61))
        assertEquals("de l'orage", WmoCodes.french(95))
        assertTrue(WmoCodes.french(1234).isNotEmpty())
    }
}

class AppConfigTest {

    @Test fun `defaults when the file says nothing`() {
        val config = AppConfig.parse("{}")
        assertEquals("Grasse", config.city)
        assertEquals(AppConfig.DEFAULT_FEEDS, config.newsFeeds)
        assertEquals(10, config.maxTurns)
    }

    @Test fun `city and feeds come from the file`() {
        val config = AppConfig.parse(
            """{"city":"Lyon","newsFeeds":["https://example.com/a.xml"],"maxTurns":4}"""
        )
        assertEquals("Lyon", config.city)
        assertEquals(listOf("https://example.com/a.xml"), config.newsFeeds)
        assertEquals(4, config.maxTurns)
    }

    @Test fun `a broken file still yields a usable config`() {
        val config = AppConfig.parse("{oops")
        assertEquals("Grasse", config.city)
        assertTrue(config.llm.providers.isEmpty())
    }
}
