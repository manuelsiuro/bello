package com.bello.assistant.assistant

import com.bello.assistant.ui.FaceState

/** Produces an answer for a question. The real one is `llm.LlmGateway` (Phase 3). */
interface Responder {
    /** Runs on a background thread. */
    fun answer(question: String): Answer

    /**
     * @param emotion face expression asked for by the model (FR-FACE-04), shown while speaking.
     * @param source which provider and model answered (FR-LLM-07), for logs and the debug overlay.
     */
    data class Answer(
        val text: String,
        val isError: Boolean = false,
        val emotion: FaceState? = null,
        val source: String? = null,
    )
}

/** Answers without any network, for testing the voice loop (Phase 2) and when no key is set. */
class StubResponder : Responder {
    override fun answer(question: String): Responder.Answer {
        val q = question.trim().lowercase()
        val text = when {
            q.contains("bonjour") || q.contains("salut") || q.contains("bello") ->
                "Bello ! Content de t'entendre."
            q.contains("comment") && q.contains("va") ->
                "Je vais très bien, banana ! Et toi ?"
            else -> "J'ai entendu : $question"
        }
        return Responder.Answer(text, source = "stub")
    }
}
