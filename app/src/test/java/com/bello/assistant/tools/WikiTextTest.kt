package com.bello.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extracts are the ones French Wikipedia returned on 2026-09-18. */
class WikiTextTest {

    @Test fun `the pronunciation, the local spelling and the nested parentheses go`() {
        val extract = "Grasse — prononcé [gʁas] — (Grassa (normes classique et mistralienne) " +
            "[ɡʀˈasa] dans le dialecte local) est une commune française située dans le département " +
            "des Alpes-Maritimes en région Provence-Alpes-Côte d'Azur.\nSous-préfecture des " +
            "Alpes-Maritimes. Grasse est la cinquième ville du département en matière de population."
        assertEquals(
            "Grasse est une commune française située dans le département des Alpes-Maritimes " +
                "en région Provence-Alpes-Côte d'Azur. Sous-préfecture des Alpes-Maritimes.",
            WikiText.speakable(extract),
        )
    }

    @Test fun `a long first sentence is better alone than cut in half`() {
        val extract = "Marie Curie (ou Marie Skłodowska-Curie), née le 7 novembre 1867 à Varsovie " +
            "(royaume de Pologne, sous domination russe) et morte le 4 juillet 1934 à Passy " +
            "(Haute-Savoie), dans le sanatorium de Sancellemoz, est une physicienne et chimiste " +
            "polonaise, naturalisée française par son mariage avec le physicien Pierre Curie en 1895. " +
            "Scientifique d'exception, elle est la première femme à avoir reçu le prix Nobel et la " +
            "seule femme à en avoir reçu deux."
        val spoken = WikiText.speakable(extract)
        assertTrue(spoken.startsWith("Marie Curie, née le 7 novembre 1867 à Varsovie et morte le 4 juillet 1934"))
        assertTrue(spoken.endsWith("Pierre Curie en 1895."))
        assertTrue(spoken.length <= WikiText.MAX_CHARS)
    }

    @Test fun `the brackets of the tower go too`() {
        val extract = "La tour Eiffel [tuʁɛfɛl]  est une tour autoportante de fer puddlé de 330 m " +
            "de hauteur (avec antennes) située à Paris. Son adresse officielle est 5, avenue Anatole-France."
        assertEquals(
            "La tour Eiffel est une tour autoportante de fer puddlé de 330 m de hauteur située à " +
                "Paris. Son adresse officielle est 5, avenue Anatole-France.",
            WikiText.speakable(extract),
        )
    }

    @Test fun `the page that the search kept`() {
        val json = """
            {"batchcomplete":true,"query":{"pages":[{"pageid":18952,"title":"Napoléon Ier",
             "extract":"Napoléon Bonaparte, né le 15 août 1769 à Ajaccio en Corse et mort le 5 mai 1821 à Longwood, est un militaire et homme d'État français. Il est le premier empereur des Français."}]}}
        """.trimIndent()
        val article = WikiText.article(json)!!
        assertEquals("Napoléon Ier", article.title)
        assertTrue(article.summary.startsWith("Napoléon Bonaparte, né le 15 août 1769"))
        assertNull(WikiText.article("""{"batchcomplete":true}"""))
        assertNull(WikiText.article("not json"))
    }

    @Test fun `what happened on this day`() {
        val json = """
            {"selected":[
              {"text":"formation de l'ICANN.","year":1998},
              {"text":"loi sur l'abolition de la peine de mort en France votée à l'Assemblée nationale.","year":1981},
              {"text":"première publication du quotidien The New York Times.","year":1851}]}
        """.trimIndent()
        val events = WikiText.events(json)
        assertEquals(3, events.size)
        assertEquals(1998, events.first().year)
        assertEquals("formation de l'ICANN", events.first().text)
        assertTrue(WikiText.events("{}").isEmpty())
    }
}
