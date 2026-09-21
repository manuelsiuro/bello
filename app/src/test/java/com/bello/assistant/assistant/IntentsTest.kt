package com.bello.assistant.assistant

import com.bello.assistant.voice.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Questions are written as the recognizer delivers them, then normalised like the app does. */
class IntentsTest {

    private fun match(said: String): Intent = Intents.match(SpeechText.forIntent(said), raw = said)

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
        assertEquals(Intent.None, match("Pourquoi le ciel est bleu ?"))
        assertEquals(Intent.None, match("Combien de temps vit une tortue ?"))
    }

    // --- The television (docs/sfr-tv-box.md) --------------------------------------------------

    private val channels = mapOf("TF1" to 1, "France 2" to 2, "Arte" to 7, "L'Équipe" to 21, "Chérie 25" to 25)
    private fun tv(said: String): Intent = Intents.match(SpeechText.forIntent(said), channels, raw = said)

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

    @Test fun `a channel number said in words, after chaîne or sur, is still the television`() {
        assertEquals(Intent.TvChannel(2, null), tv("mets la chaîne deux"))
        assertEquals(Intent.TvChannel(2, null), tv("passe sur la deux"))
        assertEquals(Intent.TvChannel(23, null), tv("mets la chaîne vingt-trois"))
        assertEquals(Intent.TvChannel(11, null), tv("va sur la chaîne onze"))
        assertEquals(Intent.TvChannel(2, "France 2"), tv("mets France deux"))
        assertEquals(Intent.TvChannel(25, "Chérie 25"), tv("mets chérie vingt-cinq"))
        assertTrue(tv("Mets un minuteur de 3 minutes pour la sauce") is Intent.TimerSet)
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

    // --- Holidays -----------------------------------------------------------------------------

    @Test fun `public holidays`() {
        assertEquals(Intent.PublicHolidays(null), match("C'est quand le prochain jour férié ?"))
        assertEquals(Intent.PublicHolidays(null), match("le prochain jour férié"))
        assertEquals(Intent.PublicHolidays(null), match("Il y a un jour férié bientôt ?"))
        assertEquals(Intent.PublicHolidays(1), match("C'est férié demain ?"))
        assertEquals(Intent.PublicHolidays(1), match("Demain c'est férié ?"))
        assertEquals(Intent.PublicHolidays(0), match("C'est férié aujourd'hui ?"))
        assertEquals(Intent.PublicHolidays(0), match("C'est férié ?"))
    }

    @Test fun `school holidays`() {
        assertEquals(Intent.SchoolHolidays(null), match("C'est quand les vacances ?"))
        assertEquals(Intent.SchoolHolidays(null), match("Les vacances scolaires"))
        assertEquals(Intent.SchoolHolidays(null, askingNow = true), match("On est en vacances ?"))
        assertEquals(Intent.SchoolHolidays(null, askingNow = true), match("C'est les vacances ?"))
        assertEquals(Intent.SchoolHolidays(null), match("vacances"))
        assertEquals(Intent.SchoolHolidays("noel"), match("C'est quand les vacances de Noël ?"))
        assertEquals(Intent.SchoolHolidays("hiver"), match("Les vacances de février, c'est quand ?"))
        assertEquals(Intent.SchoolHolidays("printemps"), match("Quand sont les vacances de Pâques ?"))
        assertEquals(Intent.SchoolHolidays("ete"), match("Les vacances d'été commencent quand ?"))
    }

    @Test fun `a holiday told about is not a holiday asked about`() {
        assertEquals(Intent.None, match("J'ai passé de bonnes vacances à la montagne"))
        assertEquals(Intent.None, match("Raconte-moi tes plus belles vacances au bord de la mer"))
    }

    // --- Fuel, jokes, the encyclopedia ---------------------------------------------------------

    @Test fun `the price of fuel, with the fuel and the town when they are said`() {
        assertEquals(Intent.Fuel("gazole", null), match("Où est le gazole le moins cher ?"))
        assertEquals(Intent.Fuel(null, null), match("Le carburant le moins cher ?"))
        assertEquals(Intent.Fuel("sp98", null), match("C'est combien le sans plomb 98 ?"))
        assertEquals(Intent.Fuel("e85", null), match("Le prix de l'E85"))
        assertEquals(Intent.Fuel("gazole", "cannes"), match("Le gazole le moins cher à Cannes"))
        assertEquals(Intent.Fuel(null, null), match("Où faire le plein le moins cher ?"))
        // The word alone is not a question about a price.
        assertEquals(Intent.None, match("C'est quoi l'essence de la vie ?"))
    }

    @Test fun `jokes`() {
        assertEquals(Intent.Joke, match("Raconte-moi une blague"))
        assertEquals(Intent.Joke, match("une blague !"))
        assertEquals(Intent.Joke, match("Tu connais une blague ?"))
        assertEquals(Intent.Joke, match("Fais-moi rire"))
    }

    @Test fun `the encyclopedia answers about names, and only about names`() {
        assertEquals(Intent.Encyclopedia("Marie Curie"), match("Qui est Marie Curie ?"))
        assertEquals(Intent.Encyclopedia("Grasse"), match("C'est quoi Grasse ?"))
        assertEquals(Intent.Encyclopedia("Tour Eiffel"), match("C'est quoi la Tour Eiffel ?"))
        assertEquals(Intent.Encyclopedia("Napoléon"), match("Parle-moi de Napoléon"))
        assertEquals(Intent.Encyclopedia("Victor Hugo"), match("Qui était Victor Hugo ?"))
        // A common noun, an office, a whole question: a provider answers those better.
        assertEquals(Intent.None, match("Qui est le président de la République ?"))
        assertEquals(Intent.None, match("C'est quoi la photosynthèse ?"))
        assertEquals(Intent.None, match("Qui est là ?"))
        assertEquals(Intent.None, match("Qui est le meilleur joueur de football de tous les temps ?"))
    }

    @Test fun `what happened on a day`() {
        assertEquals(Intent.OnThisDay(null, null), match("Que s'est-il passé aujourd'hui dans l'histoire ?"))
        assertEquals(Intent.OnThisDay(9, 18), match("Que s'est-il passé un 18 septembre ?"))
        assertEquals(Intent.OnThisDay(5, 1), match("Il s'est passé quoi un premier mai ?"))
        assertEquals(Intent.OnThisDay(null, null), match("Quel événement du jour ?"))
    }
}
