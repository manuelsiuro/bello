package com.bello.assistant.llm

import com.bello.assistant.ui.FaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {

    @Test fun `emotion tag becomes a face state and leaves the text clean`() {
        val tagged = Persona.split("[happy] Bello ! Il fait vingt degrés.")
        assertEquals(FaceState.HAPPY, tagged.emotion)
        assertEquals("Bello ! Il fait vingt degrés.", tagged.text)
    }

    @Test fun `french tag words are understood`() {
        assertEquals(FaceState.SAD, Persona.split("[triste] Je ne sais pas.").emotion)
        assertEquals(FaceState.CONFUSED, Persona.split("(confus) Hein ?").emotion)
    }

    @Test fun `neutral removes the tag without an expression`() {
        val tagged = Persona.split("[neutral] Il est midi.")
        assertNull(tagged.emotion)
        assertEquals("Il est midi.", tagged.text)
    }

    @Test fun `an unknown bracketed word is left in the answer`() {
        val tagged = Persona.split("[Le Monde] titre ce matin.")
        assertNull(tagged.emotion)
        assertEquals("[Le Monde] titre ce matin.", tagged.text)
    }

    @Test fun `no tag at all`() {
        val tagged = Persona.split("  Bonjour !  ")
        assertNull(tagged.emotion)
        assertEquals("Bonjour !", tagged.text)
    }

    @Test fun `the system prompt carries the date and the details rule`() {
        val prompt = Persona.system("Sois bref.", "jeudi 18 septembre 2026, 14h05")
        assertTrue(prompt.startsWith("Sois bref."))
        assertTrue(prompt.contains("jeudi 18 septembre 2026, 14h05"))
        assertTrue(prompt.contains("[détails]"))
    }

    @Test fun `a trailing details tag is removed and remembered`() {
        val tagged = Persona.split("[happy] Il te faut des œufs, du lait et de la farine. [détails]")
        assertEquals(FaceState.HAPPY, tagged.emotion)
        assertEquals("Il te faut des œufs, du lait et de la farine.", tagged.text)
        assertTrue(tagged.details)
        assertTrue(Persona.split("Trois étapes suffisent.\n(Details)").details)
        assertEquals("Trois étapes suffisent.", Persona.split("Trois étapes suffisent.\n(Details)").text)
    }

    @Test fun `details in the middle of a sentence is just a word`() {
        val tagged = Persona.split("[neutral] Les [détails] comptent, dit-on.")
        assertFalse(tagged.details)
        assertEquals("Les [détails] comptent, dit-on.", tagged.text)
    }
}
