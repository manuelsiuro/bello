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

    // --- The television (docs/sfr-tv-box.md) --------------------------------------------------

    private val channels = mapOf("TF1" to 1, "France 2" to 2, "Arte" to 7, "L'Équipe" to 21, "Chérie 25" to 25)
    private fun tv(said: String): Intent = Intents.match(SpeechText.forIntent(said), channels)

    @Test fun `the television is switched on and off`() {
        assertEquals(Intent.TvPower(on = true), tv("Allume la télé"))
        assertEquals(Intent.TvPower(on = true), tv("Bello, mets la télévision"))
        assertEquals(Intent.TvPower(on = false), tv("Éteins la télé"))
        assertEquals(Intent.TvPower(on = false), tv("arrête la télé"))
        assertEquals(Intent.TvPower(on = false), tv("stop la télé"))
        assertEquals(Intent.Stop, tv("stop"))
        assertEquals(Intent.Stop, tv("arrête"))
        assertEquals(Intent.TvStatus, tv("La télé est allumée ?"))
        assertEquals(Intent.TvStatus, tv("est-ce que la télé est éteinte"))
    }

    @Test fun `channels by number, by word and by name`() {
        assertEquals(Intent.TvChannel(3, null), tv("Mets la 3"))
        assertEquals(Intent.TvChannel(12, null), tv("mets la chaîne 12"))
        assertEquals(Intent.TvChannel(5, null), tv("va sur la 5"))
        assertEquals(Intent.TvChannel(3, null), tv("mets-moi la trois"))
        assertEquals(Intent.TvChannel(1, null), tv("mets la une"))
        assertEquals(Intent.TvChannel(1, "TF1"), tv("Mets TF1"))
        assertEquals(Intent.TvChannel(2, "France 2"), tv("passe sur France 2"))
        assertEquals(Intent.TvChannel(7, "Arte"), tv("zappe sur Arte"))
        assertEquals(Intent.TvChannel(21, "L'Équipe"), tv("mets l'équipe"))
        assertEquals(Intent.TvChannel(25, "Chérie 25"), tv("mets chérie 25"))
        assertEquals(Intent.TvChannelStep(up = true), tv("chaîne suivante"))
        assertEquals(Intent.TvChannelStep(up = true), tv("mets la chaîne d'après"))
        assertEquals(Intent.TvChannelStep(up = false), tv("chaîne précédente"))
        assertEquals(Intent.TvChannelStep(up = true), tv("zappe"))
    }

    @Test fun `sound`() {
        assertEquals(Intent.TvVolume(up = true, steps = 3), tv("Monte le son"))
        assertEquals(Intent.TvVolume(up = true, steps = 1), tv("monte un peu le son de la télé"))
        assertEquals(Intent.TvVolume(up = true, steps = 6), tv("mets le son à fond"))
        assertEquals(Intent.TvVolume(up = false, steps = 3), tv("baisse le son"))
        assertEquals(Intent.TvMute(silence = true), tv("coupe le son"))
        assertEquals(Intent.TvMute(silence = false), tv("remets le son"))
    }

    @Test fun `the other keys, alone or with the television named`() {
        assertEquals(Intent.TvKey("playPause"), tv("pause"))
        assertEquals(Intent.TvKey("playPause"), tv("mets en pause la télé"))
        assertEquals(Intent.TvKey("playPause"), tv("lecture"))
        assertEquals(Intent.TvKey("back"), tv("retour"))
        assertEquals(Intent.TvKey("home"), tv("menu de la télé"))
        assertEquals(Intent.TvKey("ok"), tv("ok sur la télé"))
        assertEquals(Intent.TvKey("fastForward"), tv("avance rapide sur la télé"))
    }

    @Test fun `what is not the television stays with the timers, the alarms or the model`() {
        assertTrue(tv("Mets un minuteur de 3 minutes") is Intent.TimerSet)
        assertTrue(tv("Mets une alarme à 7 heures") is Intent.AlarmSet)
        assertEquals(Intent.None, tv("mets la table"))
        assertEquals(Intent.None, tv("qui présente le 20 heures de TF1 ?"))
        assertEquals(Intent.None, tv("ok"))
        assertEquals(Intent.None, tv("c'est quoi une télé 4K ?"))
        assertEquals(Intent.None, Intents.match("mets TF1"))  // no channel table, no guess
    }
}
