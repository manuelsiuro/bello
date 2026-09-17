package com.bello.assistant.assistant

/** Produces an answer for a question. Replaced by the LLM gateway in Phase 3. */
interface Responder {
    /** Runs on a background thread. */
    fun answer(question: String): Answer

    data class Answer(val text: String, val isError: Boolean = false)
}

/** Phase 2 stand-in: proves the voice loop without any network call. */
class StubResponder : Responder {
    override fun answer(question: String): Responder.Answer {
        val q = question.trim().lowercase()
        val text = when {
            q.contains("bonjour") || q.contains("salut") || q.contains("bello") ->
                "Bello ! Content de t'entendre. Bientôt je pourrai vraiment répondre."
            q.contains("comment") && q.contains("va") ->
                "Je vais très bien, banana ! Et toi ?"
            q.endsWith("?") || q.startsWith("qui") || q.startsWith("quoi") || q.startsWith("pourquoi") ->
                "Bonne question ! Je ne sais pas encore répondre, mais j'ai bien entendu : $question"
            else -> "J'ai entendu : $question"
        }
        return Responder.Answer(text)
    }
}
