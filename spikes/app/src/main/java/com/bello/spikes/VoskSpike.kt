package com.bello.spikes

import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * SP-03: Vosk offline wake word "bello".
 * Model pushed to /sdcard/Android/data/com.bello.spikes/files/vosk-model-small-fr-0.22
 */
class VoskSpike(private val act: MainActivity) : Spike {
    private val id = "sp03"
    private var model: Model? = null
    private var service: SpeechService? = null
    private var gated: GatedWakeListener? = null
    private var useGate = false
    private var preRoll = 2
    private var onsetDb = 9.0
    private var maxStartSec = 1.0
    private var resetOnOnset = true
    private var minGapSec = 0.0
    private var onsetT = 0.0
    private val metrics = Metrics(act, id)
    private var wakeCount = 0
    private var keyword = "bello"
    private var minConf = 0.95

    override fun command(action: String, intent: Intent) {
        when (action) {
            "start" -> {
                minConf = intent.getStringExtra("minConf")?.toDouble() ?: 0.95
                useGate = intent.getBooleanExtra("gated", false)
                preRoll = intent.getIntExtra("preRoll", 2)
                onsetDb = intent.getStringExtra("onsetDb")?.toDouble() ?: 9.0
                maxStartSec = intent.getStringExtra("maxStartSec")?.toDouble() ?: 1.0
                resetOnOnset = intent.getBooleanExtra("reset", true)
                minGapSec = intent.getStringExtra("minGapSec")?.toDouble() ?: 0.0
                start(intent.getStringExtra("grammar"), intent.getStringExtra("keyword"))
            }
            "stop" -> stop()
        }
    }

    private fun start(grammar: String?, kw: String?) {
        stop()
        kw?.let { keyword = it }
        Thread {
            try {
                // Must precede libvosk: provides stdin/stdout/stderr missing from API 21 libc.
                System.loadLibrary("stdiofix")
                LibVosk.setLogLevel(LogLevel.INFO)
                val t0 = SystemClock.elapsedRealtime()
                val m = model ?: Model(File(act.getExternalFilesDir(null), "vosk-model-small-fr-0.22").absolutePath)
                    .also { model = it }
                Report.log(id, "MODEL_LOADED ms=${SystemClock.elapsedRealtime() - t0}")
                val rec = if (grammar.isNullOrBlank()) Recognizer(m, 16000f)
                else Recognizer(m, 16000f, grammar)
                rec.setWords(true)
                act.runOnUiThread {
                    if (useGate) {
                        gated = GatedWakeListener(rec, { handle("FINAL", it) }, { if (it.startsWith("ONSET t=")) onsetT = it.substringAfter("t=").substringBefore(" ").replace(',', '.').toDouble(); Report.log(id, it) }, keyword, preRoll, onsetDb, resetOnOnset)
                            .also { it.start() }
                    } else {
                        val s = SpeechService(rec, 16000f)
                        s.startListening(listener)
                        service = s
                    }
                    wakeCount = 0
                    metrics.start()
                    Report.log(id, "LISTENING grammar=${grammar ?: "free"} keyword=$keyword gated=$useGate")
                }
            } catch (e: Throwable) {
                Report.log(id, "START_ERROR ${android.util.Log.getStackTraceString(e).take(3000)}")
            }
        }.start()
    }

    private fun stop() {
        service?.let {
            it.stop(); it.shutdown()
            Report.log(id, "STOPPED wakeCount=$wakeCount")
        }
        service = null
        gated?.let {
            it.stop()
            Report.log(id, "STOPPED gated wakeCount=$wakeCount fed=${it.framesFed}/${it.framesTotal} frames")
        }
        gated = null
        metrics.stop()
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val partial = runCatching { JSONObject(hypothesis ?: "{}").optString("partial") }.getOrDefault("")
            if (!partialFired && partial.split(" ").contains(keyword)) {
                partialFired = true
                Report.log(id, "HINT text=\"$partial\"")
            }
        }
        override fun onResult(hypothesis: String?) = handle("FINAL", hypothesis)
        override fun onFinalResult(hypothesis: String?) = handle("FINAL", hypothesis)
        override fun onError(exception: Exception?) = Report.log(id, "ERROR ${exception?.message}")
        override fun onTimeout() = Report.log(id, "TIMEOUT")
    }

    private var partialFired = false

    private fun handle(kind: String, hypothesis: String?) {
        partialFired = false
        val json = runCatching { JSONObject(hypothesis ?: "{}") }.getOrNull() ?: return
        val text = json.optString("text")
        if (text.isBlank()) return
        val words = json.optJSONArray("result")
        val confs = buildString {
            if (words != null) for (i in 0 until words.length()) {
                val w = words.getJSONObject(i)
                append("${w.optString("word")}:%.2f@%.2f ".format(w.optDouble("conf"), w.optDouble("start") - (if (useGate) onsetT else 0.0)))
            }
        }.trim()
        // Decision rule. Ungated: keyword must be the first word with conf >= minConf.
        // Gated: the recognizer is reset at speech onset, so the keyword must START within
        // maxStartSec of the onset (leading [unk] noise allowed) with conf >= minConf.
        var hit: JSONObject? = null
        if (words != null) for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            if (w.optString("word") != keyword || w.optDouble("conf") < minConf) continue
            // Isolation: a real wake word is followed by a pause, so no word may start within minGapSec.
            val next = if (i + 1 < words.length()) words.getJSONObject(i + 1) else null
            val isolated = next == null || next.optDouble("start") - w.optDouble("start") >= minGapSec
            val ok = if (useGate) w.optDouble("start") - onsetT <= maxStartSec && isolated else i == 0
            if (ok) { hit = w; break }
        }
        if (hit != null) {
            wakeCount++
            Report.log(id, "WAKE n=$wakeCount text=\"$text\" start=${hit.optDouble("start")} conf=[$confs]")
        } else if (text.split(" ").contains(keyword)) {
            Report.log(id, "CANDIDATE text=\"$text\" conf=[$confs]")
        } else {
            Report.log(id, "$kind text=\"$text\" conf=[$confs]")
        }
    }
}
