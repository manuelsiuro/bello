package com.bello.spikes

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy-gated wake word loop (replaces Vosk SpeechService for SP-03 "gated" mode).
 *
 * - Silence: audio is not fed to Vosk (saves CPU).
 * - Speech onset: recognizer is reset and fed a short pre-roll, so the wake word is the first word.
 * - Early abort: the wake word must start the utterance, so if the partial's first word is not the
 *   keyword after [abortAfterMs], feeding stops until the next silence gap (saves CPU with TV/talk).
 */
class GatedWakeListener(
    private val rec: Recognizer,
    private val onFinal: (String) -> Unit,
    private val onEvent: (String) -> Unit,
    private val keyword: String,
    private val preRollFrames: Int = 2,
    private val onsetDb: Double = 9.0,
    private val resetOnOnset: Boolean = true,
) {
    private val rate = 16000
    private val frame = 1600 // 100 ms
    private val minDbfs = -55.0
    private val hangoverFrames = 7 // 700 ms of silence ends an utterance
    private val abortAfterMs = 1500
    private val maxUtteranceMs = 6000

    @Volatile private var running = false
    private var thread: Thread? = null

    var framesTotal = 0L; private set
    var framesFed = 0L; private set

    fun start() {
        running = true
        thread = Thread(::loop, "gated-wake").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frame * 4))
        val buf = ShortArray(frame)
        val preRoll = ArrayDeque<ShortArray>()
        var noiseDb = -60.0
        var active = false
        var aborted = false
        var silentFrames = 0
        var activeMs = 0
        recorder.startRecording()
        onEvent("GATE_STARTED minBuf=$minBuf preRoll=${preRollFrames * 100}ms onsetDb=$onsetDb")
        try {
            while (running) {
                var read = 0
                while (read < frame && running) {
                    val n = recorder.read(buf, read, frame - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < frame) continue
                framesTotal++
                val db = dbfs(buf)
                val speech = db > minDbfs && db > noiseDb + onsetDb

                if (!active) {
                    // Track noise floor only while idle and quiet.
                    if (!speech) noiseDb = if (db < noiseDb) db else noiseDb * 0.95 + db * 0.05
                    if (speech) {
                        active = true; aborted = false; silentFrames = 0; activeMs = 0
                        if (resetOnOnset) rec.reset()
                        // Vosk timestamps count all audio fed so far; log onset position for relative timing.
                        onEvent("ONSET t=%.2f db=%.1f noise=%.1f".format(framesFed * 0.1, db, noiseDb))
                        preRoll.forEach { feed(it) }
                        feed(buf)
                    } else {
                        preRoll.addLast(buf.copyOf())
                        if (preRoll.size > preRollFrames) preRoll.removeFirst()
                    }
                    continue
                }

                activeMs += 100
                silentFrames = if (speech) 0 else silentFrames + 1
                if (!aborted) {
                    if (feed(buf)) {
                        onFinal(rec.result)
                        active = false; preRoll.clear(); continue
                    }
                    if (activeMs >= abortAfterMs) {
                        val partial = JSONObject(rec.partialResult).optString("partial")
                        val first = partial.split(" ").firstOrNull { it.isNotBlank() && it != "[unk]" }
                        if (first != keyword) {
                            aborted = true
                            onEvent("ABORT partial=\"$partial\"")
                        }
                    }
                }
                if (silentFrames >= hangoverFrames || activeMs >= maxUtteranceMs) {
                    if (!aborted) onFinal(rec.finalResult)
                    active = false; preRoll.clear()
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    private fun feed(b: ShortArray): Boolean {
        framesFed++
        return rec.acceptWaveForm(b, b.size)
    }

    private fun dbfs(b: ShortArray): Double {
        var sum = 0.0
        for (s in b) sum += s.toDouble() * s
        val rms = sqrt(sum / b.size)
        return 20 * log10(max(rms, 1.0) / 32768.0)
    }
}
