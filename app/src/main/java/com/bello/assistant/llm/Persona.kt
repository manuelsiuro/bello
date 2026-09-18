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

    /** The system prompt actually sent: persona + the one fact a model can never guess. */
    fun system(persona: String, nowLabel: String): String = "$persona\nNous sommes le $nowLabel."

    data class Tagged(val emotion: FaceState?, val text: String)

    private val LEADING_TAG = Regex("^\\s*[\\[(]\\s*([\\p{L}]{3,12})\\s*[])]\\s*[:,-]?\\s*")

    /**
     * Splits "[happy] Bello !" into the face state and the spoken text. A known tag is removed
     * (neutral leaves no expression); anything else is left in place — it is probably real text.
     */
    fun split(raw: String): Tagged {
        val match = LEADING_TAG.find(raw) ?: return Tagged(null, raw.trim())
        val word = match.groupValues[1].lowercase()
        if (word !in EMOTIONS) return Tagged(null, raw.trim())
        return Tagged(EMOTIONS[word], raw.substring(match.range.last + 1).trim())
    }

    private val EMOTIONS: Map<String, FaceState?> = mapOf(
        "happy" to FaceState.HAPPY, "joyeux" to FaceState.HAPPY, "content" to FaceState.HAPPY,
        "sad" to FaceState.SAD, "triste" to FaceState.SAD,
        "confused" to FaceState.CONFUSED, "confus" to FaceState.CONFUSED, "perplexe" to FaceState.CONFUSED,
        "alert" to FaceState.ALERT, "alerte" to FaceState.ALERT, "surpris" to FaceState.ALERT,
        "neutral" to null, "neutre" to null,
    )
}
