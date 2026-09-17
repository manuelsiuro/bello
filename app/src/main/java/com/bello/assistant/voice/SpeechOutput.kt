package com.bello.assistant.voice

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.bello.assistant.core.FileLog
import java.util.Locale

/**
 * Text to speech with the on-device French voice, pitched up for the Minion character
 * (FR-TTS-01..03). Speaking/idle callbacks drive the face mouth.
 */
class SpeechOutput(
    context: Context,
    private val listener: Listener,
    private var pitch: Float = 1.6f,
    private var rate: Float = 1.05f,
) {
    interface Listener {
        fun onSpeakingStarted(utteranceId: String)
        fun onSpeakingDone(utteranceId: String)
        fun onSpeakingFailed(utteranceId: String)
    }

    private var ready = false
    private var counter = 0
    val isReady get() = ready

    private val tts: TextToSpeech

    init {
        // Prefer Google TTS (proven in SP-01). With no engine named, the system may open a store
        // page to install Samsung's voice, which would cover the face on this always-on device.
        val app = context.applicationContext
        val engine = PREFERRED_ENGINES.firstOrNull { pkg ->
            runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
        FileLog.i(TAG, "engine=${engine ?: "system default"}")
        tts = TextToSpeech(app, { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                FileLog.e(TAG, "init failed status=$status")
            } else {
                FileLog.i(TAG, "ready language=${runCatching { tts.language?.toString() }.getOrNull()}")
                applyVoice()
            }
        }, engine)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = listener.onSpeakingStarted(utteranceId)
            override fun onDone(utteranceId: String) = listener.onSpeakingDone(utteranceId)
            @Deprecated("Android < N") override fun onError(utteranceId: String) = listener.onSpeakingFailed(utteranceId)
        })
    }

    fun setVoiceParams(pitch: Float, rate: Float) {
        this.pitch = pitch
        this.rate = rate
        if (ready) applyVoice()
    }

    private fun applyVoice() {
        val result = tts.setLanguage(Locale.FRENCH)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            FileLog.e(TAG, "French voice unavailable (code $result)")
        }
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
    }

    /** Speaks [text]; returns the utterance id, or null when TTS is unavailable or text is empty. */
    fun speak(text: String): String? {
        if (!ready || text.isBlank()) return null
        val id = "u${++counter}"
        @Suppress("DEPRECATION")
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to id))
        }
        if (result != TextToSpeech.SUCCESS) {
            FileLog.w(TAG, "speak rejected result=$result")
            return null
        }
        return id
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }

    private companion object {
        const val TAG = "tts"
        val PREFERRED_ENGINES = listOf("com.google.android.tts", "com.samsung.SMT")
    }
}
