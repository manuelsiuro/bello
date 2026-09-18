package com.bello.assistant.assistant

import com.bello.assistant.voice.SpeechText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Questions as the recognizer writes them, normalised like the Router does before matching. */
class PageOfferTest {

    private fun wantsPage(said: String) = PageOffer.wantsPage(Intents.deaccent(SpeechText.forIntent(said)))

    @Test fun `a recipe, a how-to, steps or a list deserve a page`() {
        listOf(
            "Donne-moi la recette des crêpes",
            "Comment on fait des crêpes ?",
            "Comment préparer un risotto aux champignons",
            "Quels ingrédients pour une tarte tatin ?",
            "Comment installer une tringle à rideaux ?",
            "Quelles sont les étapes pour rempoter un ficus ?",
            "Comment aller de Grasse à Nice en bus ?",
            "Fais-moi la liste des courses pour une raclette",
            "Explique-moi comment changer un pneu",
            "C'est quoi le mode d'emploi de la cocotte-minute ?",
        ).forEach { assertTrue(it, wantsPage(it)) }
    }

    @Test fun `ordinary questions are answered in three sentences and nothing more`() {
        listOf(
            "Comment ça va ?",
            "Comment tu t'appelles ?",
            "Qui a peint la Joconde ?",
            "Quelle heure est-il ?",
            "Raconte-moi une blague",
            "Pourquoi le ciel est bleu ?",
            "La recette du bonheur c'est quoi ?",
            "",
        ).forEach { assertFalse(it, wantsPage(it)) }
    }

    @Test fun `an offer is worth answering for ninety seconds`() {
        val offer = PageOffer.Offer("q", "a", at = 1_000L)
        assertTrue(PageOffer.stillValid(offer, now = 1_000L + 89_000L))
        assertFalse(PageOffer.stillValid(offer, now = 1_000L + 91_000L))
        assertFalse(PageOffer.stillValid(null, now = 1_000L))
        // A clock that went backwards is no reason to accept a stale "oui".
        assertFalse(PageOffer.stillValid(offer, now = 500L))
    }
}
