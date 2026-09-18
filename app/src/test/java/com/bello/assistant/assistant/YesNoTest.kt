package com.bello.assistant.assistant

import com.bello.assistant.assistant.YesNo.Reply
import com.bello.assistant.voice.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Answers as the recognizer writes them, normalised like the Router does before matching. */
class YesNoTest {

    private fun parse(said: String) = YesNo.parse(Intents.deaccent(SpeechText.forIntent(said)))

    @Test fun `yes, in the ways people say it`() {
        listOf(
            "Oui", "oui !", "Ouais", "OK", "D'accord", "Vas-y", "Volontiers", "Avec plaisir",
            "Oui s'il te plaît", "oui merci", "Oui Bello", "carrément", "oui oui", "ok d'accord",
            "Bien sûr", "Je veux bien", "Oui, je veux bien", "Bello, oui", "montre-moi",
        ).forEach { assertEquals(it, Reply.YES, parse(it)) }
    }

    @Test fun `no, in the ways people say it`() {
        listOf(
            "Non", "Non merci", "Pas besoin", "Laisse tomber", "Ça ira", "Non c'est bon",
            "pas la peine", "Nan", "Non non", "Oui mais non", "non, pas maintenant",
        ).forEach { assertEquals(it, Reply.NO, parse(it)) }
    }

    @Test fun `anything else keeps the conversation going`() {
        listOf(
            "Stop", "Mets un minuteur de 3 minutes", "Oui mais pour six personnes et sans lait",
            "Et pour six personnes ?", "Non, mets un minuteur", "Quelle heure est-il ?", "", "   ",
            "oui oui oui oui oui oui oui",
        ).forEach { assertEquals(it, Reply.OTHER, parse(it)) }
    }
}
