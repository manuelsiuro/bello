package com.bello.assistant.assistant

import android.os.Handler
import android.os.Looper
import com.bello.assistant.core.FileLog
import com.bello.assistant.ui.FaceState
import com.bello.assistant.ui.FaceView

/**
 * Conversation front end. Phase 1: typed text is echoed back with the face going through
 * thinking → happy → idle. Voice (Phase 2) and the LLM gateway (Phase 3) plug in here.
 */
class Assistant(private val face: FaceView) {
    private val main = Handler(Looper.getMainLooper())
    private val toIdle = Runnable { face.setState(FaceState.IDLE) }

    fun onUserText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        FileLog.i(TAG, "user text chars=${clean.length}")
        main.removeCallbacksAndMessages(null)
        face.showUser(clean)
        face.showAnswer("")
        face.setState(FaceState.THINKING)
        main.postDelayed({
            face.showAnswer("Bello ! Tu as écrit : « $clean »")
            face.setState(FaceState.HAPPY)
            main.postDelayed(toIdle, 3_000)
        }, 800)
    }

    fun onTap() {
        FileLog.i(TAG, "face tapped")
        main.removeCallbacksAndMessages(null)
        face.setState(FaceState.LISTENING)
        main.postDelayed({
            face.showAnswer("La voix arrive bientôt ! En attendant, écris-moi (bouton Aa)")
            face.setState(FaceState.CONFUSED)
            main.postDelayed(toIdle, 3_000)
        }, 2_000)
    }

    private companion object { const val TAG = "assistant" }
}
