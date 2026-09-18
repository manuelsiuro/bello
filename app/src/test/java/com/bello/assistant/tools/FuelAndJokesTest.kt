package com.bello.assistant.tools

import com.bello.assistant.assistant.Intents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelAndJokesTest {

    private fun flat(text: String) = Intents.deaccent(text.lowercase())

    @Test fun `the fuel named in a question, the most precise one first`() {
        assertEquals("gazole", FuelData.fuelIn(flat("le gazole le moins cher")))
        assertEquals("gazole", FuelData.fuelIn(flat("le diesel")))
        assertEquals("sp95", FuelData.fuelIn(flat("du sans plomb")))
        assertEquals("sp98", FuelData.fuelIn(flat("le sans plomb 98")))
        assertEquals("sp95", FuelData.fuelIn(flat("le sans plomb 95")))
        assertEquals("e85", FuelData.fuelIn(flat("de l'éthanol")))
        assertEquals("gplc", FuelData.fuelIn(flat("le GPL")))
        assertNull(FuelData.fuelIn(flat("le prix du pain")))
        assertEquals("sans plomb 95", FuelData.spoken("sp95"))
    }

    @Test fun `the cheapest station of the real answer`() {
        val json = """
            {"total_count":19,"results":[
              {"adresse":"QUARTIER MOULIN DE BRUN RD.4","ville":"Grasse","gazole_prix":2.25,"dist":1438.1451453477976},
              {"adresse":"PLAN DE ROQUEFORT","ville":"Roquefort-les-Pins","gazole_prix":2.379,"dist":9476.930652572491},
              {"adresse":"87 Route de la Fènerie","ville":"Pégomas","gazole_prix":2.399,"dist":7345.935896189387}]}
        """.trimIndent()
        val stations = FuelData.stations(json, "gazole")
        assertEquals(3, stations.size)
        val cheapest = stations.first()
        assertEquals(2.25, cheapest.price, 0.0001)
        assertEquals("Grasse", cheapest.town)
        assertEquals(1438, cheapest.metres)
        assertTrue(FuelData.stations(json, "e85").isEmpty())
        assertTrue(FuelData.stations("not json", "gazole").isEmpty())
    }

    @Test fun `a joke arrives in one piece or in two, and the pause is added`() {
        assertEquals(
            32 to "Comment appelle-t-on un chien qui a des lunettes?… Un optichien.",
            JokeBook.parse("""{"category":"Misc","type":"twopart","setup":"Comment appelle-t-on un chien qui a des lunettes?","delivery":"Un optichien.","id":32,"lang":"fr"}"""),
        )
        assertEquals(
            7 to "Un pingouin qui respire par les fesses meurt en s'asseyant.",
            JokeBook.parse("""{"type":"single","joke":"Un pingouin qui respire par les fesses meurt en s'asseyant.","id":7}"""),
        )
        assertNull(JokeBook.parse("""{"error":true,"message":"No matching joke found"}"""))
        assertNull(JokeBook.parse("not json"))
    }

    @Test fun `the safe flag is not taken on trust`() {
        assertFalse(JokeBook.isAcceptable(79, "n'importe quoi"))
        assertFalse(JokeBook.isAcceptable(5, "Une blonde entre dans un bar…"))
        assertFalse(JokeBook.isAcceptable(5, "Hitler et Staline sont dans un bateau"))
        assertTrue(JokeBook.isAcceptable(5, "Quel est le comble pour un électricien ?"))
        // Whole words only: a calculation is not a joke about a backside.
        assertTrue(JokeBook.isAcceptable(5, "Deux plus deux, le calcul est vite fait !"))
        assertTrue(JokeBook.BUNDLED.all { it.contains("…") || it.length > 20 })
        assertTrue(JokeBook.nextBundled(JokeBook.BUNDLED.first()) != JokeBook.BUNDLED.first())
    }
}
