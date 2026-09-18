package com.bello.assistant.memory

import com.bello.assistant.llm.LlmMessage

/**
 * Short-term memory (FR-MEM-01/02): the last few exchanges, so "et sa hauteur ?" means something.
 * A session ends by itself after a quiet spell — the next question then starts from nothing.
 * Pure and clock-injected, so it is unit tested.
 */
class SessionMemory(
    private val maxTurns: Int = 10,
    private val idleResetMs: Long = 10 * 60_000,
) {
    private data class Turn(val question: String, val answer: String)

    private val turns = ArrayDeque<Turn>()
    private var lastActivityAt = 0L

    /** The conversation so far, oldest first. Expires the session first if it has gone quiet. */
    fun history(now: Long): List<LlmMessage> {
        expireIfIdle(now)
        return turns.flatMap { listOf(LlmMessage.user(it.question), LlmMessage.assistant(it.answer)) }
    }

    fun add(question: String, answer: String, now: Long) {
        expireIfIdle(now)
        turns.addLast(Turn(question, answer))
        while (turns.size > maxTurns) turns.removeFirst()
        lastActivityAt = now
    }

    fun reset() {
        turns.clear()
        lastActivityAt = 0
    }

    val size get() = turns.size

    /** True when the quiet spell ended the session (used for the log line and for Gemini Web). */
    fun expireIfIdle(now: Long): Boolean {
        if (turns.isEmpty() || now - lastActivityAt <= idleResetMs) return false
        turns.clear()
        return true
    }
}
