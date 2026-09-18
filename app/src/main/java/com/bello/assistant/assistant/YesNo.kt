package com.bello.assistant.assistant

/**
 * Was that a yes, a no, or something else? Consulted only while a question is pending
 * (FR-PAGE-02), on the deaccented text. The utterance must be made of nothing but yes and no
 * words — anything longer or unknown is "something else" and is handled as usual, so "non, mets
 * un minuteur" still sets the timer and "stop" still stops. A no anywhere wins ("oui mais non").
 */
object YesNo {

    enum class Reply { YES, NO, OTHER }

    private val FILLERS = listOf(
        "s il te plait", "s il vous plait", "bello", "merci", "stp", "svp", "mais", "euh", "bah", "ben", "alors",
    )
    private const val MAX_TOKENS = 6
    private const val MAX_PHRASE = 3

    private val YES = setOf(
        "oui", "ouais", "ouaip", "ok", "okay", "yes", "d accord", "dac", "vas y", "allez", "allons y",
        "volontiers", "avec plaisir", "bien sur", "carrement", "je veux bien", "ca marche",
        "pourquoi pas", "evidemment", "parfait", "envoie", "affiche", "montre", "montre moi",
    )
    private val NO = setOf(
        "non", "nan", "nope", "no", "pas besoin", "pas la peine", "laisse tomber", "ca ira",
        "ca va aller", "c est bon", "pas maintenant", "plus tard", "inutile", "sans facon",
    )

    fun parse(flat: String): Reply {
        var text = " " + flat.trim() + " "
        for (filler in FILLERS) text = text.replace(" $filler ", " ")
        val tokens = text.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return Reply.OTHER
        var i = 0
        var sawNo = false
        while (i < tokens.size) {
            var consumed = 0
            for (length in minOf(MAX_PHRASE, tokens.size - i) downTo 1) {
                val phrase = tokens.subList(i, i + length).joinToString(" ")
                if (phrase in NO) { sawNo = true; consumed = length; break }
                if (phrase in YES) { consumed = length; break }
            }
            if (consumed == 0) return Reply.OTHER
            i += consumed
        }
        return if (sawNo) Reply.NO else Reply.YES
    }
}
