package com.bello.assistant.assistant

import com.bello.assistant.ui.FaceState

/** Where the conversation is. Drives the face and what a tap does. */
enum class Turn { IDLE, LISTENING, THINKING, SPEAKING, FOLLOW_UP }

/** Pure conversation rules, unit tested. */
object ConversationPolicy {

    /** At night an idle Bello dozes rather than stares (FR-ON-06); everything else looks the same. */
    fun face(turn: Turn, night: Boolean = false): FaceState = when (turn) {
        Turn.IDLE -> if (night) FaceState.SLEEPY else FaceState.IDLE
        Turn.LISTENING, Turn.FOLLOW_UP -> FaceState.LISTENING
        Turn.THINKING -> FaceState.THINKING
        Turn.SPEAKING -> FaceState.SPEAKING
    }

    /** What a tap on the face means in each turn (FR-CONV-02, FR-CONV-06). */
    enum class TapAction { START_LISTENING, STOP_SPEAKING, CANCEL }

    fun onTap(turn: Turn): TapAction = when (turn) {
        Turn.IDLE -> TapAction.START_LISTENING
        Turn.SPEAKING -> TapAction.STOP_SPEAKING
        Turn.LISTENING, Turn.FOLLOW_UP, Turn.THINKING -> TapAction.CANCEL
    }

    /**
     * After an answer the assistant listens again for a moment (FR-CONV-07), but not after an
     * error message and not when the follow-up window is disabled.
     */
    fun shouldFollowUp(turn: Turn, wasError: Boolean, followUpMs: Int): Boolean =
        turn == Turn.SPEAKING && !wasError && followUpMs > 0

    /**
     * A failed recognition ends the exchange silently (no "I didn't hear") in a follow-up window,
     * and after a wake word Bello was not sure about: a false wake then costs a listening face for
     * a second and nothing else (FR-WAKE-01).
     */
    fun silentOnNoSpeech(turn: Turn, provisional: Boolean = false): Boolean =
        turn == Turn.FOLLOW_UP || (provisional && turn == Turn.LISTENING)
}
