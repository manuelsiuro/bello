package com.bello.assistant.assistant

import com.bello.assistant.voice.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Questions are written as the recognizer delivers them, then normalised like the app does. */
class IntentsTest {

    private fun match(said: String): Intent = Intents.match(SpeechText.forIntent(said))

    @Test fun `the clock and the date are answered locally`() {
        assertEquals(Intent.Time, match("Quelle heure est-il ?"))
        assertEquals(Intent.Time, match("Bello, il est quelle heure ?"))
        assertEquals(Intent.Day, match("On est quel jour ?"))
    }

    @Test fun `timers`() {
        assertEquals(Intent.TimerSet(10 * FrenchWords.MINUTE, null), match("Mets un minuteur de 10 minutes"))
        assertEquals(Intent.TimerSet(2 * FrenchWords.MINUTE, null), match("minuteur de deux minutes"))
        assertEquals(
            Intent.TimerSet(3 * FrenchWords.MINUTE, "les pâtes"),
            match("Mets un minuteur de 3 minutes pour les pâtes")
        )
        assertEquals(Intent.TimerSet(5 * FrenchWords.MINUTE, null), match("Préviens-moi dans 5 minutes"))
        assertEquals(Intent.TimerList, match("Quels sont mes minuteurs ?"))
        assertEquals(Intent.TimerCancel(all = false), match("Annule le minuteur"))
        assertEquals(Intent.TimerCancel(all = true), match("Supprime tous les minuteurs"))
    }

    @Test fun `alarms`() {
        assertEquals(Intent.AlarmSet(7, 30, null), match("Mets une alarme à 7h30"))
        assertEquals(Intent.AlarmSet(7, 0, null), match("Réveille-moi à 7 heures"))
        assertEquals(
            Intent.AlarmSet(18, 0, "sortir les poubelles"),
            match("Rappelle-moi à 18 heures de sortir les poubelles")
        )
        assertEquals(
            Intent.AlarmSet(7, 0, "le train"),
            match("Mets une alarme à 7 heures pour le train")
        )
        assertEquals(Intent.AlarmList, match("Quelles sont mes alarmes ?"))
        assertEquals(Intent.AlarmCancel(all = false), match("Annule l'alarme"))
    }

    @Test fun `a timer is not an alarm`() {
        assertTrue(match("Mets un minuteur de 10 minutes") is Intent.TimerSet)
        assertTrue(match("Mets une alarme à 7 heures") is Intent.AlarmSet)
    }

    @Test fun `weather, with and without a city`() {
        assertEquals(Intent.Weather(null, tomorrow = false), match("Quel temps fait-il ?"))
        assertEquals(Intent.Weather("grasse", tomorrow = false), match("Quel temps fait-il à Grasse ?"))
        assertEquals(Intent.Weather("grasse", tomorrow = true), match("Quelle météo demain à Grasse ?"))
        assertEquals(Intent.Weather(null, tomorrow = false), match("Est-ce qu'il va pleuvoir ?"))
    }

    @Test fun `news and stop`() {
        assertEquals(Intent.News, match("Donne-moi les infos"))
        assertEquals(Intent.News, match("Quoi de neuf ?"))
        assertEquals(Intent.Stop, match("Stop"))
        assertEquals(Intent.Stop, match("Tais-toi !"))
    }

    @Test fun `memory commands keep their accents`() {
        assertEquals(
            Intent.Remember("mon café préféré est l'espresso"),
            match("Souviens-toi que mon café préféré est l'espresso")
        )
        assertEquals(Intent.Forget("mon café préféré"), match("Oublie mon café préféré"))
        assertEquals(Intent.Forget(null), match("Oublie tout"))
        assertEquals(Intent.ListMemories, match("Qu'est-ce que tu sais de moi ?"))
    }

    @Test fun `anything else goes to a provider`() {
        assertEquals(Intent.None, match("Qui a peint la Joconde ?"))
        assertEquals(Intent.None, match("Raconte-moi une blague"))
        assertEquals(Intent.None, match("Pourquoi le ciel est bleu ?"))
        assertEquals(Intent.None, match("Combien de temps vit une tortue ?"))
    }
}
