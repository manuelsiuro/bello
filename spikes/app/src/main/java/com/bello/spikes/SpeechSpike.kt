package com.bello.spikes

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** SP-01: Android SpeechRecognizer (Google) in French. */
class SpeechSpike(private val act: MainActivity) : Spike {
    private val id = "sp01"
    private var recognizer: SpeechRecognizer? = null
    private var endOfSpeechAt = 0L
    private var startedAt = 0L

    override fun command(action: String, intent: Intent) {
        when (action) {
            "info" -> {
                Report.log(id, "AVAILABLE=${SpeechRecognizer.isRecognitionAvailable(act)}")
                act.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
                    .forEach { Report.log(id, "SERVICE ${it.serviceInfo.packageName}/${it.serviceInfo.name}") }
            }
            "listen" -> listen(intent.getBooleanExtra("offline", false))
            "stop" -> { recognizer?.destroy(); recognizer = null }
        }
    }

    private fun listen(preferOffline: Boolean) {
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(act).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.packageName)
            if (preferOffline) putExtra("android.speech.extra.PREFER_OFFLINE", true)
        }
        startedAt = SystemClock.elapsedRealtime()
        rec.cancel()
        rec.startListening(i)
        Report.log(id, "LISTEN offline=$preferOffline")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) =
            Report.log(id, "READY after=${SystemClock.elapsedRealtime() - startedAt}ms")
        override fun onBeginningOfSpeech() = Report.log(id, "BEGIN")
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            endOfSpeechAt = SystemClock.elapsedRealtime()
            Report.log(id, "END")
        }
        override fun onError(error: Int) = Report.log(id, "ERROR code=$error name=${errorName(error)}")
        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val latency = if (endOfSpeechAt > 0) SystemClock.elapsedRealtime() - endOfSpeechAt else -1
            Report.log(id, "RESULT latencyMs=$latency conf=${conf?.firstOrNull()} text=${texts.firstOrNull()} alts=${texts.drop(1)}")
            endOfSpeechAt = 0
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) Report.log(id, "PARTIAL $t")
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorName(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        else -> "UNKNOWN"
    }
}
