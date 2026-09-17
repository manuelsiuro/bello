package com.bello.assistant.voice

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import com.bello.assistant.core.FileLog

/**
 * Speech to text through the Google recognizer installed on the tablet (SP-01: 10/10 French
 * phrases, result 50–180 ms after the end of speech). Must be created and used on the main thread.
 */
class SpeechInput(private val context: Context, private val listener: Listener) {

    enum class Failure { NO_SPEECH, NETWORK, BUSY, AUDIO, PERMISSION, UNAVAILABLE, OTHER }

    interface Listener {
        fun onListeningStarted()
        fun onPartial(text: String)
        fun onFinal(text: String, confidence: Float?)
        fun onFailure(failure: Failure)
    }

    private var recognizer: SpeechRecognizer? = null
    private var startedAt = 0L
    var isListening = false
        private set

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String = "fr-FR") {
        if (!isAvailable()) return listener.onFailure(Failure.UNAVAILABLE)
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(callbacks)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        startedAt = SystemClock.elapsedRealtime()
        isListening = true
        rec.cancel()
        rec.startListening(intent)
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        isListening = false
        runCatching { recognizer?.cancel() }
    }

    fun release() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            FileLog.i(TAG, "ready in ${SystemClock.elapsedRealtime() - startedAt} ms")
            listener.onListeningStarted()
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let { if (it.isNotBlank()) listener.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val text = best(results)
            val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
            if (text.isNullOrBlank()) {
                listener.onFailure(Failure.NO_SPEECH)
            } else {
                FileLog.i(TAG, "final chars=${text.length} conf=$confidence")
                listener.onFinal(text, confidence)
            }
        }

        override fun onError(error: Int) {
            isListening = false
            val failure = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Failure.NO_SPEECH
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> Failure.NETWORK
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Failure.BUSY
                SpeechRecognizer.ERROR_AUDIO -> Failure.AUDIO
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Failure.PERMISSION
                else -> Failure.OTHER
            }
            FileLog.w(TAG, "error code=$error -> $failure")
            listener.onFailure(failure)
        }

        private fun best(bundle: Bundle?) =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }

    private companion object { const val TAG = "stt" }
}
