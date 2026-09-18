package com.bello.assistant.voice

import org.json.JSONObject
import java.util.Locale

/** How eager the wake word is (FR-WAKE-03). */
enum class WakeSensitivity { LOW, NORMAL, HIGH;

    companion object {
        fun from(name: String?): WakeSensitivity =
            values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: NORMAL
    }
}

/**
 * The rule that turns a Vosk result into a wake, from SP-03 and re-measured in Phase 5.
 *
 * Hearing "bello" is not enough: the model hears it in "bel", "belote" and in half of what a
 * television says. Three things separate the real ones:
 *
 * - **Confidence.** Across a room a real one comes back at 0.79–1.00; the loudest false one that
 *   landed inside the window reached 0.62.
 * - **When it starts, counted from the moment the room got loud.** Someone calling Bello says it
 *   first, so a real one starts within about half a second of the onset. False ones sit deep
 *   inside a sentence, past 1.5 s, or were already under way before the room got loud.
 * - **Silence after it.** A person says "Bello", then pauses before asking. Speech that merely
 *   contains the sound runs straight on.
 */
data class WakeRule(
    val minConf: Double,
    val minStartSec: Double,
    val maxStartSec: Double,
    val minGapSec: Double,
) {
    companion object {
        /**
         * Measured again in P5-4 on material the rule had never seen — twenty utterances in four
         * unused voices, and twenty minutes of that day's news read aloud:
         *
         * - **The confidence threshold from SP-03 was far too strict.** Across a room, a real
         *   "Bello" comes back at 0.79–1.00, not the 0.99–1.00 of the spike's close trials, so
         *   0.99 threw away three quarters of them. The loudest false one inside the window
         *   reached 0.62, which is what leaves room for 0.70.
         * - **The window needed widening at both ends**, to [0.05, 0.60] s. The word does not always
         *   start where the room got loud — a breath or a chair can open the utterance, and once
         *   the gate lifts a quiet voice the onset trips sooner. Of eighteen false candidates in
         *   twenty-one minutes of speech, not one reached 0.70 anywhere inside that window; they
         *   sit past 1.5 s, deep in a sentence. The lower bound still excludes a word that was
         *   already under way before the room got loud, which is where SP-03's false wakes were.
         */
        fun of(sensitivity: WakeSensitivity): WakeRule = when (sensitivity) {
            WakeSensitivity.LOW -> WakeRule(0.85, 0.12, 0.40, 0.60)
            WakeSensitivity.NORMAL -> WakeRule(0.70, 0.05, 0.60, 0.50)
            WakeSensitivity.HIGH -> WakeRule(0.55, 0.05, 0.80, 0.35)
        }
    }
}

sealed class WakeDecision {
    /** [startSec] is counted from the onset of speech, not from the start of the audio stream. */
    data class Accept(val conf: Double, val startSec: Double) : WakeDecision()

    /** [detail] is written to the log so a room can be tuned from real misses. */
    data class Reject(val reason: Reason, val detail: String) : WakeDecision()

    enum class Reason { NO_KEYWORD, LOW_CONFIDENCE, OUTSIDE_WINDOW, NOT_ISOLATED }
}

/**
 * Pure, so the rule can be tested against recorded results instead of against a room.
 * Vosk counts word timestamps from all the audio ever fed to the recognizer, which is why the
 * caller passes [onsetSec]: where the current utterance began on that same clock.
 */
object WakeWordDecision {

    const val KEYWORD = "bello"

    fun decide(resultJson: String, onsetSec: Double, rule: WakeRule): WakeDecision {
        val candidates = candidates(resultJson, onsetSec)
        if (candidates.isEmpty()) return WakeDecision.Reject(WakeDecision.Reason.NO_KEYWORD, "")

        var best: WakeDecision.Reject? = null
        for (candidate in candidates) {
            val reason = rejection(candidate, rule)
                ?: return WakeDecision.Accept(candidate.conf, candidate.startSec)
            best = best ?: WakeDecision.Reject(reason, candidate.toString())
        }
        return best ?: WakeDecision.Reject(WakeDecision.Reason.NO_KEYWORD, "")
    }

    /** Null when the rule accepts it. */
    fun rejection(candidate: Candidate, rule: WakeRule): WakeDecision.Reason? = when {
        candidate.conf < rule.minConf -> WakeDecision.Reason.LOW_CONFIDENCE
        candidate.startSec < rule.minStartSec || candidate.startSec > rule.maxStartSec ->
            WakeDecision.Reason.OUTSIDE_WINDOW
        candidate.gapSec < rule.minGapSec -> WakeDecision.Reason.NOT_ISOLATED
        else -> null
    }

    /**
     * Everything that sounded like the keyword, whatever the rule thinks of it. The device logs
     * these as `WAKE_HEARD`, so thresholds can be scored afterwards against a recording of a real
     * room instead of being guessed (`scripts/wake-test.sh score`).
     */
    fun candidates(resultJson: String, onsetSec: Double): List<Candidate> {
        val words = words(resultJson)
        return words.withIndex().filter { it.value.word == KEYWORD }.map { (index, word) ->
            val next = words.getOrNull(index + 1)
            Candidate(
                conf = word.conf,
                startSec = word.start - onsetSec,
                gapSec = next?.let { it.start - word.start } ?: ALONE_SEC,
            )
        }
    }

    /** A "bello" that was heard: how sure, how long after the onset, and how much silence after. */
    data class Candidate(val conf: Double, val startSec: Double, val gapSec: Double) {
        override fun toString() = "conf=${two(conf)} start=${two(startSec)} gap=${two(gapSec)}"
    }

    /** The keyword was the last word of the utterance: as isolated as it gets. */
    const val ALONE_SEC = 9.99

    /** The tablet runs in French, where "%.2f" writes a comma; logs are parsed on the Mac. */
    fun two(value: Double): String = String.format(Locale.US, "%.2f", value)

    data class Word(val word: String, val conf: Double, val start: Double)

    /** A missing or broken result is silence, never a wake. */
    fun words(resultJson: String): List<Word> {
        val array = runCatching { JSONObject(resultJson).optJSONArray("result") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val word = array.optJSONObject(i) ?: return@mapNotNull null
            Word(word.optString("word"), word.optDouble("conf", 0.0), word.optDouble("start", 0.0))
        }
    }
}
