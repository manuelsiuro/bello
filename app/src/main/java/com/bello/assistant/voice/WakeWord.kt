package com.bello.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.Prefs
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Bello" heard offline, without a tap (FR-WAKE-01..05).
 *
 * The expensive part of listening all day is the recogniser, so it is only fed when the room makes
 * a noise: silence costs 2.5 % CPU, speech 17 % (SP-03). At the onset of a sound the recogniser is
 * reset and given a 200 ms pre-roll, so the wake word — if there is one — is the first thing it
 * hears; [WakeWordDecision] then decides whether it was really addressed to Bello.
 *
 * The microphone is exclusive on this tablet: the Google recogniser cannot open it while this loop
 * is running, so [pause] releases it for the length of a conversation.
 */
class WakeWord(
    private val context: Context,
    private val prefs: Prefs,
    private val onWake: () -> Unit,
) {
    enum class State { OFF, STARTING, LISTENING, PAUSED, UNAVAILABLE }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var state = State.OFF
    @Volatile private var running = false
    /** What the assistant wants, which a pending [resume] must not contradict. */
    @Volatile private var wantListening = false
    @Volatile private var pauseReason: String? = null
    private var thread: Thread? = null

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    /** Vosk timestamps count every sample ever fed to the recogniser; so does this. */
    @Volatile private var framesFed = 0L
    @Volatile private var framesHeard = 0L
    private var lastWakeAt = 0L
    /** When the room last made a sound, so a wake can say how quickly it followed the word. */
    @Volatile private var lastLoudAt = 0L

    var wakes = 0L; private set
    private val rejects = HashMap<WakeDecision.Reason, Int>()

    val isEnabled get() = prefs.wakeEnabled
    val currentState get() = state

    // --- Control ---------------------------------------------------------------------------------

    fun setEnabled(on: Boolean) {
        prefs.wakeEnabled = on
        FileLog.i(TAG, "WAKE_ENABLED=$on")
        if (on) start() else stop("disabled")
    }

    fun setSensitivity(sensitivity: WakeSensitivity) {
        prefs.wakeSensitivity = sensitivity.name
        FileLog.i(TAG, "WAKE_SENSITIVITY=$sensitivity rule=${WakeRule.of(sensitivity)}")
    }

    /** Starts listening if it is enabled and the model is there; harmless otherwise. */
    fun start() {
        if (!prefs.wakeEnabled || state == State.UNAVAILABLE || running) return
        if (!VoskRuntime.isModelPresent(context)) {
            state = State.UNAVAILABLE
            FileLog.w(TAG, "WAKE_UNAVAILABLE no speech model; run scripts/push-model.sh")
            return
        }
        wantListening = true
        running = true
        state = State.STARTING
        pauseReason = null
        thread = Thread(::loop, "wake-word").apply { priority = Thread.NORM_PRIORITY - 1 }.also { it.start() }
    }

    /** Gives the microphone back. The model stays in memory: reloading it costs 4.5 s. */
    fun pause(reason: String) {
        wantListening = false
        if (!running) return
        stopLoop()
        state = State.PAUSED
        pauseReason = reason
        FileLog.i(TAG, "WAKE_PAUSED reason=$reason")
    }

    /**
     * Back to listening after a conversation. The Google recogniser hands the microphone back a
     * moment after it says it has finished, so the loop waits before opening it.
     */
    fun resume(reason: String, delayMs: Long = RESUME_DELAY_MS) {
        if (!prefs.wakeEnabled || state == State.UNAVAILABLE || running || wantListening) return
        wantListening = true
        main.postDelayed({
            if (wantListening && !running) {
                FileLog.i(TAG, "WAKE_RESUMED reason=$reason")
                start()
            }
        }, delayMs)
    }

    fun release() {
        stop("release")
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        recognizer = null
        model = null
    }

    private fun stop(reason: String) {
        val wasRunning = running
        wantListening = false
        stopLoop()
        state = State.OFF
        if (wasRunning) FileLog.i(TAG, "WAKE_STOPPED reason=$reason wakes=$wakes")
    }

    private fun stopLoop() {
        running = false
        thread?.join(STOP_JOIN_MS)
        thread = null
    }

    /** One line for `scripts/wake.sh status` and the debug overlay. */
    fun status(): String {
        val sensitivity = WakeSensitivity.from(prefs.wakeSensitivity)
        val misses = rejects.entries.sortedByDescending { it.value }
            .joinToString(",") { "${it.key.name.lowercase()}=${it.value}" }
        val heard = if (framesHeard == 0L) 0 else (framesFed * 100 / framesHeard)
        // The reason belongs to a pause, and to nothing else: "off(listening)" reads like a fault.
        val why = pauseReason?.takeIf { state == State.PAUSED }?.let { "($it)" } ?: ""
        return "wake=${if (prefs.wakeEnabled) state.name.lowercase() else "off"}$why " +
            "sensitivity=${sensitivity.name.lowercase()} " +
            "wakes=$wakes fed=$heard% rejected=[$misses]"
    }

    // --- The listening loop ------------------------------------------------------------------------

    private fun loop() {
        val rec = runCatching { recogniser() }.getOrElse {
            state = State.UNAVAILABLE
            running = false
            FileLog.w(TAG, "WAKE_UNAVAILABLE cannot start the recogniser", it)
            return
        }
        val recorder = openMicrophone() ?: run {
            running = false
            state = State.OFF
            return
        }
        val frame = ShortArray(FRAME_SAMPLES)
        val preRoll = ArrayDeque<ShortArray>()
        var noiseDb = -60.0
        var active = false
        var aborted = false
        var silentFrames = 0
        var activeMs = 0
        var onsetSec = 0.0
        state = State.LISTENING
        FileLog.i(TAG, "WAKE_LISTENING sensitivity=${WakeSensitivity.from(prefs.wakeSensitivity).name.lowercase()}")
        try {
            while (running) {
                if (!read(recorder, frame)) continue
                framesHeard++
                val db = dbfs(frame)
                val loud = db > FLOOR_DBFS && db > noiseDb + ONSET_DB
                if (loud) lastLoudAt = SystemClock.elapsedRealtime()

                if (!active) {
                    // The noise floor follows the quiet room, and drops to it at once.
                    if (!loud) noiseDb = if (db < noiseDb) db else noiseDb * 0.95 + db * 0.05
                    if (loud) {
                        active = true; aborted = false; silentFrames = 0; activeMs = 0
                        rec.reset()
                        // Marked before the pre-roll is fed, exactly as in SP-03: the 200 ms of
                        // sound from before the onset shifts the whole utterance by that much, so
                        // a word spoken at the onset lands at 0.20 s — the middle of the window.
                        onsetSec = framesFed * FRAME_SEC
                        preRoll.forEach { feed(rec, it) }
                        feed(rec, frame)
                    } else {
                        preRoll.addLast(frame.copyOf())
                        if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    }
                    continue
                }

                activeMs += FRAME_MS
                silentFrames = if (loud) 0 else silentFrames + 1
                if (!aborted) {
                    if (feed(rec, frame)) {
                        judge(rec.result, onsetSec)
                        active = false; preRoll.clear(); continue
                    }
                    // Someone talking to the room, not to Bello: stop decoding until they pause.
                    if (activeMs >= ABORT_AFTER_MS && !startsWithKeyword(rec.partialResult)) {
                        aborted = true
                    }
                }
                if (silentFrames >= HANGOVER_FRAMES || activeMs >= MAX_UTTERANCE_MS) {
                    if (!aborted) judge(rec.finalResult, onsetSec)
                    active = false; preRoll.clear()
                }
            }
        } catch (e: Throwable) {
            FileLog.w(TAG, "WAKE_ERROR listening stopped", e)
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            if (state == State.LISTENING) state = State.OFF
        }
    }

    private fun judge(resultJson: String, onsetSec: Double) {
        val rule = WakeRule.of(WakeSensitivity.from(prefs.wakeSensitivity))
        // Everything that sounded like the word, before the rule has its say: one pass through a
        // room then yields enough to score thresholds on the Mac (scripts/wake-test.sh).
        val candidates = WakeWordDecision.candidates(resultJson, onsetSec)
        if (candidates.isNotEmpty()) FileLog.i(TAG, "WAKE_HEARD " + candidates.joinToString(" | "))
        when (val decision = WakeWordDecision.decide(resultJson, onsetSec, rule)) {
            is WakeDecision.Accept -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastWakeAt < WAKE_COOLDOWN_MS) return
                lastWakeAt = now
                wakes++
                // NFR-PERF-01 wants the face to react within a second of the word being spoken;
                // most of this is the 700 ms of silence that has to prove the speaker stopped.
                FileLog.i(TAG, "WAKE_OK conf=${WakeWordDecision.two(decision.conf)} " +
                    "start=${WakeWordDecision.two(decision.startSec)} afterSpeechMs=${now - lastLoudAt}")
                main.post(onWake)
            }
            is WakeDecision.Reject -> {
                if (decision.reason == WakeDecision.Reason.NO_KEYWORD) return
                rejects[decision.reason] = (rejects[decision.reason] ?: 0) + 1
                FileLog.i(TAG, "WAKE_REJECT reason=${decision.reason} ${decision.detail}")
            }
        }
    }

    private fun recogniser(): Recognizer {
        recognizer?.let { return it }
        VoskRuntime.load()
        val loadedAt = SystemClock.elapsedRealtime()
        val loaded = model ?: Model(VoskRuntime.modelDir(context).absolutePath).also {
            model = it
            FileLog.i(TAG, "model loaded in ${SystemClock.elapsedRealtime() - loadedAt} ms")
        }
        // A grammar of two entries: the word, and "anything else". Decoy words were tried in SP-03
        // and made it worse — the model hears "Bello" as "bellot" and the decoy swallowed it.
        return Recognizer(loaded, SAMPLE_RATE.toFloat(), GRAMMAR).also {
            it.setWords(true)
            framesFed = 0
            recognizer = it
        }
    }

    private fun openMicrophone(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        repeat(MIC_ATTEMPTS) { attempt ->
            val recorder = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuffer, FRAME_SAMPLES * 4))
            }.getOrNull()
            if (recorder?.state == AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.startRecording() }
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) return recorder
            }
            runCatching { recorder?.release() }
            FileLog.w(TAG, "microphone busy (try ${attempt + 1}/$MIC_ATTEMPTS)")
            if (!running) return null
            Thread.sleep(MIC_RETRY_MS)
        }
        FileLog.w(TAG, "WAKE_NO_MIC giving up until the next conversation ends")
        return null
    }

    private fun read(recorder: AudioRecord, frame: ShortArray): Boolean {
        var read = 0
        while (read < frame.size && running) {
            val n = recorder.read(frame, read, frame.size - read)
            if (n <= 0) return false
            read += n
        }
        return read == frame.size
    }

    private fun feed(rec: Recognizer, frame: ShortArray): Boolean {
        framesFed++
        return rec.acceptWaveForm(frame, frame.size)
    }

    /**
     * After [ABORT_AFTER_MS] the word must already be there, decoded and first. Nothing decoded
     * counts as "not there": a wake word is over in well under a second, so a second and a half of
     * sound with nothing to show for it is a room talking to itself — and decoding the rest of it
     * is what a 2014 processor cannot afford (SP-03 iteration 3).
     */
    private fun startsWithKeyword(partialJson: String): Boolean {
        val partial = runCatching { JSONObject(partialJson).optString("partial") }.getOrDefault("")
        val first = partial.split(" ").firstOrNull { it.isNotBlank() && it != UNKNOWN }
        return first == WakeWordDecision.KEYWORD
    }

    private fun dbfs(frame: ShortArray): Double {
        var sum = 0.0
        for (sample in frame) sum += sample.toDouble() * sample
        return 20 * log10(max(sqrt(sum / frame.size), 1.0) / 32768.0)
    }

    private companion object {
        const val TAG = "wake"
        const val SAMPLE_RATE = 16000
        const val FRAME_MS = 100
        const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS
        const val FRAME_SEC = FRAME_MS / 1000.0
        const val UNKNOWN = "[unk]"
        const val GRAMMAR = """["bello", "[unk]"]"""

        /** Below this, the room is silent whatever the noise floor says. */
        const val FLOOR_DBFS = -55.0
        const val ONSET_DB = 9.0
        const val PRE_ROLL_FRAMES = 2
        const val HANGOVER_FRAMES = 7
        const val ABORT_AFTER_MS = 1500
        const val MAX_UTTERANCE_MS = 6000

        const val WAKE_COOLDOWN_MS = 1500L
        const val RESUME_DELAY_MS = 600L
        const val STOP_JOIN_MS = 1500L
        const val MIC_ATTEMPTS = 3
        const val MIC_RETRY_MS = 500L
    }
}
