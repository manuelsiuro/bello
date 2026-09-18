package com.bello.assistant.assistant

import com.bello.assistant.ui.FaceState

/** Produces an answer for a question. The real one is `llm.LlmGateway` (Phase 3). */
interface Responder {
    /** Runs on a background thread. */
    fun answer(question: String): Answer

    /** The exchange was interrupted (a ring, a cancel): forget any question left pending. */
    fun reset() {}

    /**
     * @param emotion face expression asked for by the model (FR-FACE-04), shown while speaking.
     * @param source which provider and model answered (FR-LLM-07), for logs and the debug overlay.
     * @param offersPage the model thinks the full answer deserves a page on the phone (FR-PAGE-01).
     * @param followUpMs how long to listen again after this answer: null is the setting, 0 is not
     *   at all, more than the setting when Bello has just asked a question (FR-PAGE-02).
     */
    data class Answer(
        val text: String,
        val isError: Boolean = false,
        val emotion: FaceState? = null,
        val source: String? = null,
        val offersPage: Boolean = false,
        val followUpMs: Int? = null,
        /** The model ran out of room (FR-PAGE-03): a page says so instead of ending mid-step. */
        val truncated: Boolean = false,
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
