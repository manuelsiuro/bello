package com.bello.assistant.llm

import com.bello.assistant.ui.FaceState

/**
 * Who Bello is when it answers (FR-CONV-08, FR-CONV-11) and how the optional emotion tag the
 * model may put in front of its answer becomes a face expression (FR-FACE-04). Pure, unit tested.
 */
object Persona {

    val DEFAULT = """
        Tu es Bello, un assistant vocal facétieux inspiré des Minions, installé sur une tablette
        posée dans le salon. Tu réponds toujours en français, à voix haute.
        Règles :
        - Trois phrases maximum, parlées, sans listes, sans markdown, sans emoji, sans adresse web.
        - Tu peux glisser de temps en temps un mot de Minion (Bello, banana, poopaye), sans jamais
          nuire à la clarté de la réponse.
        - Les nombres, heures et unités s'écrivent en toutes lettres quand c'est plus naturel à
          l'oral (par exemple "vingt degrés" plutôt que "20 °C").
        - Si tu ne sais pas ou si l'information peut avoir changé récemment, dis-le simplement.
        - Commence chaque réponse par une étiquette d'émotion entre crochets, choisie parmi
          [happy] [sad] [confused] [alert] [neutral], puis la réponse.
    """.trimIndent()

    /**
     * Some answers are really a page (FR-PAGE-01): the model says so with a trailing tag, and Bello
     * offers the details on the phone. Kept apart from [DEFAULT] so a custom persona keeps it.
     */
    val DETAILS_RULE = "- Si la réponse complète demanderait une liste, des étapes ou une recette, " +
        "réponds quand même en trois phrases et termine par l'étiquette [détails]."

    /** The system prompt actually sent: persona + the details rule + the one fact a model can never guess. */
    fun system(persona: String, nowLabel: String): String =
        "$persona\n$DETAILS_RULE\nNous sommes le $nowLabel."

    /** @param details the model thinks the full answer deserves a page (FR-PAGE-01). */
    data class Tagged(val emotion: FaceState?, val text: String, val details: Boolean = false)

    private val LEADING_TAG = Regex("^\\s*[\\[(]\\s*([\\p{L}]{3,12})\\s*[])]\\s*[:,-]?\\s*")
    private val TRAILING_DETAILS = Regex("\\s*[\\[(]\\s*d[ée]tails?\\s*[])]\\s*[.!]?\\s*$", RegexOption.IGNORE_CASE)

    /**
     * Splits "[happy] Bello ! [détails]" into the face state, the spoken text and the page flag.
     * A known tag is removed (neutral leaves no expression); anything else is left in place — it
     * is probably real text.
     */
    fun split(raw: String): Tagged {
        val trailing = TRAILING_DETAILS.find(raw)
        val details = trailing != null
        val body = if (trailing == null) raw else raw.substring(0, trailing.range.first)
        val match = LEADING_TAG.find(body) ?: return Tagged(null, body.trim(), details)
        val word = match.groupValues[1].lowercase()
        if (word !in EMOTIONS) return Tagged(null, body.trim(), details)
        return Tagged(EMOTIONS[word], body.substring(match.range.last + 1).trim(), details)
    }

    private val EMOTIONS: Map<String, FaceState?> = mapOf(
        "happy" to FaceState.HAPPY, "joyeux" to FaceState.HAPPY, "content" to FaceState.HAPPY,
        "sad" to FaceState.SAD, "triste" to FaceState.SAD,
        "confused" to FaceState.CONFUSED, "confus" to FaceState.CONFUSED, "perplexe" to FaceState.CONFUSED,
        "alert" to FaceState.ALERT, "alerte" to FaceState.ALERT, "surpris" to FaceState.ALERT,
        "neutral" to null, "neutre" to null,
    )
}
