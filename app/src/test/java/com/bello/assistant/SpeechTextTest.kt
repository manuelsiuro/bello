package com.bello.assistant

import com.bello.assistant.voice.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {

    @Test
    fun speechDropsMarkdownAndLinks() {
        assertEquals(
            "Voici la météo : 24 °C à Grasse.\nSource : Open-Meteo",
            SpeechText.forSpeech("Voici la **météo** : `24 °C` à Grasse.\n\n- [Source](https://open-meteo.com) : Open-Meteo")
        )
    }

    @Test
    fun speechDropsEmojiAndKeepsFrenchPunctuation() {
        assertEquals("Bello ! Ça va ? — oui, très bien.",
            SpeechText.forSpeech("Bello ! 🍌 Ça va ? — oui, très bien. 😀"))
    }

    @Test
    fun intentExpandsRecognizerShorthand() {
        assertEquals("réveille-moi demain à 7 heures 30", SpeechText.forIntent("Réveille-moi demain à 7h30"))
        assertEquals("rendez-vous à 18 heures", SpeechText.forIntent("Rendez-vous à 18h."))
        assertEquals("combien font 12 fois 15", SpeechText.forIntent("Combien font 12 x 15 ?"))
    }

    @Test
    fun intentKeepsAccentsAndApostrophes() {
        assertEquals("quel temps fait-il à grasse aujourd'hui",
            SpeechText.forIntent("Quel temps fait-il à Grasse aujourd’hui ?"))
    }

    @Test
    fun intentLeavesOrdinaryWordsWithHAlone() {
        assertEquals("il y a 2 heures", SpeechText.forIntent("il y a 2h"))
        assertEquals("j'habite ici", SpeechText.forIntent("J'habite ici"))
    }
}
