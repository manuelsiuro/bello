package com.bello.assistant.assistant

/**
 * "Would the full answer be a page rather than three sentences?" (FR-PAGE-01). Pure, unit tested.
 * The model is asked to tag such answers itself (`[détails]`, see `Persona`); this is the safety
 * net on the question, for the forms people actually use: a recipe, a how-to, steps, a list.
 */
object PageOffer {

    /** What Bello offered and when: the next utterance may be the answer to it (FR-PAGE-02). */
    data class Offer(val question: String, val spokenAnswer: String, val at: Long)

    const val TTL_MS = 90_000L

    private val RECIPE = Regex("\\b(recettes?|ingredients?)\\b")
    private val NOT_A_RECIPE = Regex("\\brecettes? (du bonheur|du succes|fiscales?|de l etat)\\b")
    private val HOW_TO = Regex(
        "\\bcomment (?:on |je |tu |est ce qu on |est ce que je |est ce que tu )?" +
            "(?:fait|faire|prepare|preparer|cuisine|cuisiner|installe|installer|repare|reparer|" +
            "configure|configurer|fabrique|fabriquer|construit|construire|reussir|reussit|aller|" +
            "se rendre|monte|monter|demonte|demonter|nettoie|nettoyer|plante|planter|taille|tailler)\\b"
    )
    private val NOT_A_HOW_TO = Regex("\\bcomment (ca va|tu t appelles|vas tu|allez vous)\\b")
    private val STEPS = Regex(
        "\\b(etapes?|mode d emploi|tutos?|tutoriels?|itineraires?|marche a suivre|procedure|" +
            "explique moi comment|(donne|fais|dresse|prepare) moi (la |une )?liste|la liste (de|des|du))\\b"
    )

    /** @param flat the question after `SpeechText.forIntent` and [Intents.deaccent]. */
    fun wantsPage(flat: String): Boolean {
        if (flat.isBlank()) return false
        if (RECIPE.containsMatchIn(flat) && !NOT_A_RECIPE.containsMatchIn(flat)) return true
        if (HOW_TO.containsMatchIn(flat) && !NOT_A_HOW_TO.containsMatchIn(flat)) return true
        return STEPS.containsMatchIn(flat)
    }

    /** An offer is answered within [ttlMs]; after that "oui" is just a word again. */
    fun stillValid(offer: Offer?, now: Long, ttlMs: Long = TTL_MS): Boolean =
        offer != null && now - offer.at in 0..ttlMs
}
