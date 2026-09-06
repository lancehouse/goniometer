package com.lancehouse.goniometer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * One-shot voice capture for the movement label ("Left shoulder abduction"),
 * triggered on Start so it can be said hands-free right as the rep begins,
 * and re-triggerable later to correct a mis-heard label.
 * On-device recognition (EXTRA_PREFER_OFFLINE) — clinic wifi may be flaky
 * and the app should keep working without it.
 *
 * Keeps ONE SpeechRecognizer instance alive for the recognizer's lifetime
 * and reuses it via cancel()+startListening() on every retry, rather than
 * destroy()-ing and creating a new instance each time. Destroy+recreate in
 * quick succession is a known Android flakiness trap — the old instance's
 * service connection isn't guaranteed to have unbound before the new one
 * tries to bind, so a rapid retry (e.g. tapping the mic button again right
 * after Start's auto-capture) could silently never call back at all.
 *
 * EXTRA_BIASING_STRINGS has no "strength" knob in the public API — it's just
 * a word list — so leaning harder on the physio vocabulary happens in two
 * other places instead:
 *  1. The biasing list itself includes realistic full phrases (side + region
 *     + movement), not just single words — phrase-level biasing pulls much
 *     harder on the recognizer than isolated words do.
 *  2. We ask for several candidate transcriptions (not just the top one) and
 *     pick whichever scores highest against the vocabulary, then fuzzy-snap
 *     any near-miss word (small edit distance) back to the closest known
 *     term — so on a coin-flip between "options" and "abduction", abduction
 *     wins if that's what's actually in the biasing list.
 */
class VoiceLabelRecognizer(
    private val context: Context,
    private val onResult: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches.isNullOrEmpty()) return
            val best = pickBestCandidate(matches)
            if (best.isNotBlank()) onResult(snapToVocabulary(best))
        }

        override fun onError(error: Int) {
            Log.w("VoiceLabelRecognizer", "recognition error code=$error")
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        recognizer?.let { return it }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(listener)
        recognizer = r
        return r
    }

    /** Starts (or restarts) listening. Safe to call again mid-session — cancels any prior listen first. */
    fun start() {
        val r = ensureRecognizer() ?: return
        r.cancel()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(BIASING_WORDS))
            }
        }
        r.startListening(intent)
    }

    /** Releases the recognizer entirely — call from onPause/onDestroy, not between retries. */
    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    /** Picks whichever ASR candidate has the most vocabulary hits; ties keep the recognizer's own top rank. */
    private fun pickBestCandidate(candidates: List<String>): String {
        if (candidates.size == 1) return candidates[0]
        return candidates.maxByOrNull { candidate ->
            val lower = candidate.lowercase()
            VOCAB_PHRASES.count { lower.contains(it) }
        } ?: candidates.first()
    }

    /** Replaces any word that's a near-miss (small edit distance) of a known term with that term. */
    private fun snapToVocabulary(text: String): String =
        text.split(" ").joinToString(" ") { rawWord ->
            val word = rawWord.trim().trimEnd('.', ',', '?', '!')
            if (word.length < 3) return@joinToString rawWord
            val lower = word.lowercase()
            if (lower in VOCAB_WORDS) return@joinToString rawWord

            val threshold = if (word.length <= 5) 1 else 2
            val closest = VOCAB_WORDS.minByOrNull { levenshtein(lower, it) } ?: return@joinToString rawWord
            if (levenshtein(lower, closest) > threshold) return@joinToString rawWord

            if (word.first().isUpperCase()) closest.replaceFirstChar { it.uppercase() } else closest
        }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }

    companion object {
        private val REGIONS = listOf(
            "shoulder", "elbow", "wrist", "hand", "finger", "thumb",
            "hip", "knee", "ankle", "foot", "toe",
            "cervical", "thoracic", "lumbar", "spine", "neck", "trunk", "pelvis",
            "sacroiliac", "scapula", "clavicle",
        )
        private val CORE_REGIONS = listOf("shoulder", "hip", "knee", "elbow", "wrist", "ankle")
        private val MOVEMENTS = listOf(
            "flexion", "extension", "abduction", "adduction",
            "internal rotation", "external rotation", "rotation",
            "lateral flexion", "medial rotation", "lateral rotation",
            "pronation", "supination", "inversion", "eversion",
            "dorsiflexion", "plantarflexion", "circumduction",
            "protraction", "retraction", "elevation", "depression",
            "opposition", "radial deviation", "ulnar deviation",
        )
        private val CORE_MOVEMENTS = listOf(
            "flexion", "extension", "abduction", "adduction",
            "internal rotation", "external rotation",
        )
        private val SIDES = listOf("left", "right")
        private val QUALIFIERS = listOf("active", "passive", "resisted", "range of motion", "bilateral")

        /**
         * Biases the on-device recognizer toward the vocabulary a physio
         * session actually uses, per RecognizerIntent.EXTRA_BIASING_STRINGS
         * (API 33+; no-ops harmlessly on older devices). Includes full
         * "side + region + movement" phrases, not just isolated words —
         * phrase-level entries bias much more strongly than single words.
         */
        val BIASING_WORDS: List<String> by lazy {
            val phrases = mutableListOf<String>()
            phrases += REGIONS
            phrases += MOVEMENTS
            phrases += SIDES
            phrases += QUALIFIERS
            for (side in SIDES) {
                for (region in CORE_REGIONS) {
                    for (movement in CORE_MOVEMENTS) {
                        phrases += "$side $region $movement"
                    }
                }
            }
            phrases
        }

        /** Lowercased single words used for the fuzzy near-miss correction pass. */
        private val VOCAB_WORDS: Set<String> =
            (REGIONS + MOVEMENTS + SIDES + QUALIFIERS)
                .flatMap { it.split(" ") }
                .map { it.lowercase() }
                .toSet()

        /** Lowercased full phrases (including multi-word ones) used to score whole ASR candidates. */
        private val VOCAB_PHRASES: Set<String> = BIASING_WORDS.map { it.lowercase() }.toSet()
    }
}
