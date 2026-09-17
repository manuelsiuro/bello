package com.bello.assistant

import com.bello.assistant.assistant.ConversationPolicy
import com.bello.assistant.assistant.ConversationPolicy.TapAction
import com.bello.assistant.assistant.Turn
import com.bello.assistant.ui.FaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPolicyTest {

    @Test
    fun facePerTurn() {
        assertEquals(FaceState.IDLE, ConversationPolicy.face(Turn.IDLE))
        assertEquals(FaceState.LISTENING, ConversationPolicy.face(Turn.LISTENING))
        assertEquals(FaceState.LISTENING, ConversationPolicy.face(Turn.FOLLOW_UP))
        assertEquals(FaceState.THINKING, ConversationPolicy.face(Turn.THINKING))
        assertEquals(FaceState.SPEAKING, ConversationPolicy.face(Turn.SPEAKING))
    }

    @Test
    fun tapStartsListeningWhenIdleAndStopsSpeech() {
        assertEquals(TapAction.START_LISTENING, ConversationPolicy.onTap(Turn.IDLE))
        assertEquals(TapAction.STOP_SPEAKING, ConversationPolicy.onTap(Turn.SPEAKING))
        assertEquals(TapAction.CANCEL, ConversationPolicy.onTap(Turn.LISTENING))
        assertEquals(TapAction.CANCEL, ConversationPolicy.onTap(Turn.THINKING))
    }

    @Test
    fun followUpOnlyAfterASuccessfulAnswer() {
        assertTrue(ConversationPolicy.shouldFollowUp(Turn.SPEAKING, wasError = false, followUpMs = 6000))
        assertFalse(ConversationPolicy.shouldFollowUp(Turn.SPEAKING, wasError = true, followUpMs = 6000))
        assertFalse(ConversationPolicy.shouldFollowUp(Turn.SPEAKING, wasError = false, followUpMs = 0))
        assertFalse(ConversationPolicy.shouldFollowUp(Turn.IDLE, wasError = false, followUpMs = 6000))
    }

    @Test
    fun silenceEndsAFollowUpWithoutComplaining() {
        assertTrue(ConversationPolicy.silentOnNoSpeech(Turn.FOLLOW_UP))
        assertFalse(ConversationPolicy.silentOnNoSpeech(Turn.LISTENING))
    }
}
