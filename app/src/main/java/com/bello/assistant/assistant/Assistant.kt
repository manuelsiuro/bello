package com.bello.assistant.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.Prefs
import com.bello.assistant.tools.Ringer
import com.bello.assistant.ui.FaceState
import com.bello.assistant.ui.FaceView
import com.bello.assistant.voice.SpeechInput
import com.bello.assistant.voice.SpeechOutput
import com.bello.assistant.voice.SpeechText
import com.bello.assistant.voice.WakeSensitivity
import com.bello.assistant.voice.WakeWord

/**
 * The conversation: tap or text in, spoken and written answer out (FR-CONV-01..10).
 * Runs on the main thread; answers are produced on a background thread.
 */
class Assistant(
    context: Context,
    private val face: FaceView,
    private var responder: Responder = StubResponder(),
) : SpeechInput.Listener, SpeechOutput.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    private val stt = SpeechInput(context, this)
    private val ringer = Ringer(context)
    private val tts = SpeechOutput(context, this, prefs.ttsPitch, prefs.ttsRate)
    private val wake = WakeWord(context, prefs) { onWakeWord() }

    private var turn = Turn.IDLE
    private var currentUtterance: String? = null
    private var lastAnswerWasError = false
    private var retriedBusy = false
    /** True while listening because of a wake word Bello has not yet had confirmed by speech. */
    private var provisionalWake = false
    /** Bumped whenever a question is dropped, so a late answer from the network is ignored. */
    private var askSeq = 0

    private val backToIdle = Runnable { setTurn(Turn.IDLE) }

    val currentTurn get() = turn

    init {
        // Installing the app creates the activity twice in a row, so the microphone may still be
        // held by the instance being destroyed.
        main.postDelayed({ wake.start() }, WAKE_START_DELAY_MS)
    }

    // --- Input ---------------------------------------------------------------------------------

    fun onTap() {
        if (ringer.isRinging) {
            stopRinging("tap")
            return
        }
        when (ConversationPolicy.onTap(turn)) {
            ConversationPolicy.TapAction.START_LISTENING -> {
                provisionalWake = false
                startListening(Turn.LISTENING)
            }
            ConversationPolicy.TapAction.STOP_SPEAKING -> {
                FileLog.i(TAG, "barge-in: stopping speech")
                tts.stop()
                setTurn(Turn.IDLE)
            }
            ConversationPolicy.TapAction.CANCEL -> cancel()
        }
    }

    fun onUserText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        provisionalWake = false
        askSeq++
        cancelTimers()
        stt.cancel()
        face.showUser(clean)
        face.showAnswer("")
        think(clean)
    }

    /**
     * "Bello" heard across the room (FR-WAKE-01). The wake is provisional: the recogniser is
     * opened, and if no speech follows, Bello goes quietly back to idle without an error.
     */
    private fun onWakeWord() {
        if (ringer.isRinging) {
            stopRinging("wake word")
            return
        }
        if (turn != Turn.IDLE) {
            FileLog.i(TAG, "wake word ignored in turn=$turn")
            return
        }
        provisionalWake = true
        face.showUser("")
        startListening(Turn.LISTENING, WAKE_LISTEN_DELAY_MS)
    }

    /** `scripts/wake.sh`: on, off, low, normal, high, status. */
    fun wakeCommand(command: String) {
        when (command.lowercase()) {
            "on" -> wake.setEnabled(true)
            "off" -> wake.setEnabled(false)
            "status" -> {}
            else -> wake.setSensitivity(WakeSensitivity.from(command))
        }
        FileLog.i(TAG, "WAKE_STATUS ${wake.status()}")
    }

    fun wakeStatus(): String = wake.status()

    /** Swaps the answer source, e.g. after the config file is reloaded. */
    fun setResponder(next: Responder) {
        responder = next
    }

    fun setVoiceParams(pitch: Float, rate: Float) {
        prefs.ttsPitch = pitch
        prefs.ttsRate = rate
        tts.setVoiceParams(pitch, rate)
        FileLog.i(TAG, "voice pitch=$pitch rate=$rate")
    }

    /** A timer or an alarm went off (FR-TOOL-04): ring, look alert, say what it was about. */
    fun ring(text: String) {
        cancelTimers()
        wake.pause("ringing")
        stt.cancel()
        tts.stop()
        ringer.start()
        face.showUser("")
        face.setState(FaceState.ALERT)
        // As an expression rather than a state, the alert stays visible while Bello speaks and
        // then listens for "stop" (FR-TOOL-04).
        face.setEmotion(FaceState.ALERT)
        lastAnswerWasError = false
        currentUtterance = tts.speak(SpeechText.forSpeech(text))
        face.showAnswer(text)
        turn = Turn.SPEAKING
        FileLog.i(TAG, "ringing: $text")
    }

    fun stopRinging(reason: String) {
        if (!ringer.isRinging) return
        ringer.stop(reason)
        tts.stop()
        face.setEmotion(null)
        setTurn(Turn.IDLE)
    }

    /** Speaks a sentence without a question, for testing the voice from the Mac. */
    fun speakNow(text: String) {
        face.showUser("")
        say(text, isError = false)
    }

    fun cancel() {
        askSeq++
        cancelTimers()
        stt.cancel()
        tts.stop()
        setTurn(Turn.IDLE)
    }

    fun release() {
        wake.release()
        ringer.stop("release")
        cancelTimers()
        stt.release()
        tts.shutdown()
    }

    private fun startListening(next: Turn, delayMs: Long = 0) {
        if (!stt.isAvailable()) {
            say("La reconnaissance vocale n'est pas disponible sur cette tablette.", isError = true)
            return
        }
        cancelTimers()
        tts.stop()
        if (next == Turn.LISTENING) retriedBusy = false
        setTurn(next)
        // A follow-up keeps the answer on screen; a new question starts from a clean slate.
        if (next != Turn.FOLLOW_UP) face.showAnswer("")
        // The recognizer reports BUSY if it is started in the same breath as the previous session
        // (or right after speaking), so leave it a moment.
        if (delayMs > 0) main.postDelayed({ if (turn == next) stt.start() }, delayMs)
        else stt.start()
    }

    // --- Speech recognition callbacks -----------------------------------------------------------

    override fun onListeningStarted() {
        face.setState(FaceState.LISTENING)
    }

    override fun onPartial(text: String) {
        face.showUser(text)
    }

    override fun onFinal(text: String, confidence: Float?) {
        // Speech followed the wake word, so it was a real one.
        provisionalWake = false
        face.showUser(text)
        think(text)
    }

    override fun onFailure(failure: SpeechInput.Failure) {
        // The recognizer reports the cancellation we asked for. If we have moved on — a typed
        // question, an alarm, a barge-in — that is not a failure to tell the user about.
        if (turn != Turn.LISTENING && turn != Turn.FOLLOW_UP) {
            FileLog.i(TAG, "ignoring $failure in turn=$turn")
            return
        }
        if (failure == SpeechInput.Failure.BUSY && turn == Turn.FOLLOW_UP && !retriedBusy) {
            retriedBusy = true
            FileLog.i(TAG, "recognizer busy, retrying follow-up once")
            startListening(Turn.FOLLOW_UP, RECOGNIZER_RETRY_MS)
            return
        }
        if (ConversationPolicy.silentOnNoSpeech(turn, provisionalWake) &&
            (failure == SpeechInput.Failure.NO_SPEECH || failure == SpeechInput.Failure.BUSY)) {
            FileLog.i(TAG, if (provisionalWake) "false wake: nobody spoke, back to idle" else "no follow-up, back to idle")
            provisionalWake = false
            setTurn(Turn.IDLE)
            return
        }
        val message = when (failure) {
            SpeechInput.Failure.NO_SPEECH -> "Je n'ai rien entendu. Touche mon visage et réessaie !"
            SpeechInput.Failure.NETWORK -> "Je n'arrive pas à joindre le réseau pour t'écouter."
            SpeechInput.Failure.PERMISSION -> "Je n'ai pas l'autorisation d'utiliser le micro."
            SpeechInput.Failure.AUDIO, SpeechInput.Failure.BUSY -> "Mon micro est occupé, réessaie dans un instant."
            SpeechInput.Failure.UNAVAILABLE -> "La reconnaissance vocale n'est pas disponible."
            SpeechInput.Failure.OTHER -> "Oups, je n'ai pas compris."
        }
        face.setState(FaceState.CONFUSED)
        say(message, isError = true)
    }

    // --- Thinking and answering ------------------------------------------------------------------

    private fun think(question: String) {
        setTurn(Turn.THINKING)
        val forIntent = SpeechText.forIntent(question)
        FileLog.i(TAG, "question chars=${question.length} normalised=\"$forIntent\"")
        val asked = ++askSeq
        val startedAt = android.os.SystemClock.elapsedRealtime()
        Thread({
            val answer = runCatching { responder.answer(question) }
                .getOrElse {
                    FileLog.w(TAG, "responder threw", it)
                    Responder.Answer("Je n'ai pas réussi à répondre.", isError = true, emotion = FaceState.SAD)
                }
            val ms = android.os.SystemClock.elapsedRealtime() - startedAt
            main.post {
                if (asked != askSeq) {
                    FileLog.i(TAG, "answer dropped (question cancelled) ms=$ms")
                    return@post
                }
                FileLog.i(TAG, "answer from=${answer.source ?: "none"} ms=$ms chars=${answer.text.length}")
                say(answer.text, answer.isError, answer.emotion)
            }
        }, "responder").start()
    }

    private fun say(text: String, isError: Boolean, emotion: FaceState? = null) {
        lastAnswerWasError = isError
        // "Stop" and friends are obeyed, not commented on.
        if (text.isBlank()) {
            setTurn(Turn.IDLE)
            return
        }
        val spoken = SpeechText.forSpeech(text)
        face.setEmotion(emotion)
        face.showAnswer(text)
        val id = tts.speak(spoken)
        if (id == null) {
            FileLog.w(TAG, "no speech output; showing text only")
            face.setState(if (isError) FaceState.CONFUSED else FaceState.HAPPY)
            main.postDelayed(backToIdle, TEXT_ONLY_PAUSE_MS)
            return
        }
        currentUtterance = id
        setTurn(Turn.SPEAKING)
    }

    // --- Speech output callbacks --------------------------------------------------------------

    // TTS callbacks arrive on a binder thread.
    override fun onSpeakingStarted(utteranceId: String) {
        main.post { if (utteranceId == currentUtterance) face.setState(FaceState.SPEAKING) }
    }

    override fun onSpeakingDone(utteranceId: String) {
        main.post {
            if (utteranceId != currentUtterance) return@post
            if (ringer.isRinging) {
                FileLog.i(TAG, "still ringing, waiting for a tap or \"stop\"")
                startListening(Turn.FOLLOW_UP, AFTER_SPEECH_PAUSE_MS)
            } else if (ConversationPolicy.shouldFollowUp(turn, lastAnswerWasError, prefs.followUpMs)) {
                FileLog.i(TAG, "follow-up window ${prefs.followUpMs} ms")
                startListening(Turn.FOLLOW_UP, AFTER_SPEECH_PAUSE_MS)
                main.postDelayed(followUpTimeout, prefs.followUpMs.toLong())
            } else {
                setTurn(Turn.IDLE)
            }
        }
    }

    override fun onSpeakingFailed(utteranceId: String) {
        main.post {
            FileLog.w(TAG, "speech failed id=$utteranceId")
            setTurn(Turn.IDLE)
        }
    }

    private val followUpTimeout = Runnable {
        if (turn == Turn.FOLLOW_UP) {
            FileLog.i(TAG, "follow-up window closed")
            stt.cancel()
            setTurn(Turn.IDLE)
        }
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun setTurn(next: Turn) {
        // The microphone is exclusive: the wake word listens only between conversations
        // (FR-WAKE-04, 05).
        if (next == Turn.IDLE) wake.resume("idle") else wake.pause(next.name.lowercase())
        if (turn == next) return
        if (next == Turn.LISTENING || next == Turn.THINKING) face.setEmotion(null)
        // Back to idle: let the answer's expression linger a moment, then go neutral.
        if (next == Turn.IDLE) main.postDelayed({ if (turn == Turn.IDLE) face.setEmotion(null) }, EMOTION_LINGER_MS)
        turn = next
        face.setState(ConversationPolicy.face(next))
        FileLog.i(TAG, "turn=$next")
    }

    private fun cancelTimers() = main.removeCallbacksAndMessages(null)

    private companion object {
        const val TAG = "assistant"
        const val TEXT_ONLY_PAUSE_MS = 3_000L
        const val AFTER_SPEECH_PAUSE_MS = 400L
        const val RECOGNIZER_RETRY_MS = 600L
        const val EMOTION_LINGER_MS = 4_000L
        const val WAKE_START_DELAY_MS = 1_500L
        /** The wake word holds the microphone until it hears one; the recogniser needs it free. */
        const val WAKE_LISTEN_DELAY_MS = 150L
    }
}
