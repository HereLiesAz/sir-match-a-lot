package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How well two tracks mix.
 *
 * Scores are computed only from measured tempo and key. The previous version
 * also scored "progression" and "atmosphere", which were prose strings invented
 * by a language model from the track title — weighting 40% of a compatibility
 * score on generated text was worse than not scoring it at all.
 *
 * @param isComplete false when either track lacks a measured tempo or key, in
 *   which case the scores are provisional and the UI should say so rather than
 *   present a number as fact.
 */
data class MixMatch(
    val trackA: Track,
    val trackB: Track,
    val overallScore: Int,
    val tempoScore: Int,
    val keyScore: Int,
    val tempoAdvice: String,
    val keyAdvice: String,
    val tempoDiffPercent: Double,
    val isHalfTimeDoubleTime: Boolean,
    val canMixWithNudge: Boolean,
    val isComplete: Boolean,
)

object HarmonicEngine {
    private val CHROMATIC_MINOR = listOf("Am", "A#m", "Bm", "Cm", "C#m", "Dm", "D#m", "Em", "Fm", "F#m", "Gm", "G#m")
    private val CAMELOT_MINOR = listOf("8A", "3A", "10A", "5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A")

    private val CHROMATIC_MAJOR = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val CAMELOT_MAJOR = listOf("8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B")

    data class CamelotKey(val number: Int, val mode: Char)

    /**
     * Camelot code for a key identified by root pitch class (0 = C) and mode.
     *
     * This is the bridge from measured audio to the harmonic model: it takes the
     * output of [com.hereliesaz.sirmatchalot.dsp.KeyDetector] and places it on
     * the wheel, so every compatibility score below is computed from a key that
     * was detected in the signal rather than derived from a filename.
     */
    fun camelotFor(rootPitchClass: Int, isMinor: Boolean): String {
        val pitchClass = ((rootPitchClass % 12) + 12) % 12
        return if (isMinor) {
            // CAMELOT_MINOR is indexed from A minor, which is pitch class 9.
            CAMELOT_MINOR[(pitchClass - 9 + 12) % 12]
        } else {
            // CAMELOT_MAJOR is indexed from C major, which is pitch class 0.
            CAMELOT_MAJOR[pitchClass]
        }
    }

    /** Convenience overload for a detected key. */
    fun camelotFor(key: com.hereliesaz.sirmatchalot.dsp.KeyEstimate): String =
        camelotFor(key.rootPitchClass, key.isMinor)

    fun parseCamelotKey(camelot: String): CamelotKey? {
        val trimmed = camelot.trim().uppercase()
        val regex = Regex("^(\\d+)([AB])$")
        val match = regex.find(trimmed) ?: return null
        val num = match.groupValues[1].toIntOrNull() ?: return null
        // The wheel only has positions 1..12 — "13A" matches the regex but names
        // nothing on it, and letting it through handed callers a CamelotKey that
        // would never be found in CAMELOT_MINOR/CAMELOT_MAJOR by lookup.
        if (num !in 1..12) return null
        val mode = match.groupValues[2][0]
        return CamelotKey(num, mode)
    }

    /**
     * Used only by tests, as the round-trip check on [getShortestSemitoneShift]
     * below — applying the shift it returns must land back on the starting key.
     */
    fun transposeCamelotKey(camelotKey: String, semitones: Int): String {
        if (semitones == 0) return camelotKey
        val parsed = parseCamelotKey(camelotKey) ?: return camelotKey

        val list = if (parsed.mode == 'A') CAMELOT_MINOR else CAMELOT_MAJOR
        val idx = list.indexOf(camelotKey.trim().uppercase())
        if (idx == -1) return camelotKey

        val targetIdx = (idx + semitones + 24) % 12
        return list[targetIdx]
    }

    fun getShortestSemitoneShift(keyA: String, keyB: String): Int {
        val parsedA = parseCamelotKey(keyA) ?: return 0
        val parsedB = parseCamelotKey(keyB) ?: return 0
        
        val listA = if (parsedA.mode == 'A') CAMELOT_MINOR else CAMELOT_MAJOR
        val listB = if (parsedB.mode == 'A') CAMELOT_MINOR else CAMELOT_MAJOR

        // Must match how parseCamelotKey normalised these strings above — an
        // untrimmed " 8A" parses fine but never matches a list entry by raw
        // indexOf, so it silently fell through to a 0 shift instead of an
        // actual failure or the intended semitone count.
        val idxA = listA.indexOf(keyA.trim().uppercase())
        val idxB = listB.indexOf(keyB.trim().uppercase())
        if (idxA == -1 || idxB == -1) return 0

        var diff = (idxA - idxB + 12) % 12
        if (diff > 6) diff -= 12
        return diff
    }

    fun calculateKeyCompatibility(camelotA: String, camelotB: String): Pair<Int, String> {
        val cA = parseCamelotKey(camelotA)
        val cB = parseCamelotKey(camelotB)

        if (cA == null || cB == null) {
            return Pair(60, "Unknown key relationship")
        }

        if (cA.number == cB.number && cA.mode == cB.mode) {
            return Pair(100, "Perfect Key Match (In-key blending)")
        }

        if (cA.number == cB.number && cA.mode != cB.mode) {
            return Pair(95, "Relative Major/Minor (Emotional shift, extremely smooth)")
        }

        val diff = abs(cA.number - cB.number)
        val isAdjacent = diff == 1 || diff == 11
        if (isAdjacent && cA.mode == cB.mode) {
            // Clockwise on the wheel is a fifth up, and the wheel wraps both
            // ways: 12 -> 1 is up, 1 -> 12 is down. The wrap was handled in one
            // direction only, so 1B -> 12B — one step anticlockwise — was
            // announced as "Perfect Fifth Up" because 12 is the larger number.
            // The score is 90 either way, so this is advice text, but it is
            // advice the UI states as fact.
            val up = if (diff == 11) cA.number == 12 else cB.number > cA.number
            val description = if (up) {
                "Perfect Fifth Up (Boosts energy and brightness)"
            } else {
                "Perfect Fifth Down (Lowers tension, deepens groove)"
            }
            return Pair(90, description)
        }

        if (isAdjacent && cA.mode != cB.mode) {
            return Pair(75, "Diagonal Shift (Smooth chromatic color blend)")
        }

        val isTwoSteps = diff == 2 || diff == 10
        if (isTwoSteps && cA.mode == cB.mode) {
            return Pair(70, "Whole Step Jump (+2 Energy Boost transition)")
        }

        return Pair(40, "Incompatible Keys (Keep transition short, filter EQ heavily)")
    }

    fun calculateTempoCompatibility(bpmA: Double, bpmB: Double): Map<String, Any> {
        val ratio = bpmB / bpmA
        val diffStandard = abs(bpmB - bpmA) / bpmA
        val diffHalf = abs(bpmB - bpmA * 0.5) / (bpmA * 0.5)
        val diffDouble = abs(bpmB - bpmA * 2.0) / (bpmA * 2.0)

        var minDiff = diffStandard
        var relativeBpmTarget = bpmA
        var isHalfOrDoubleTarget = false

        if (diffHalf < minDiff) {
            minDiff = diffHalf
            relativeBpmTarget = bpmA * 0.5
            isHalfOrDoubleTarget = true
        }
        if (diffDouble < minDiff) {
            minDiff = diffDouble
            relativeBpmTarget = bpmA * 2.0
            isHalfOrDoubleTarget = true
        }

        val diffPercent = (bpmB - relativeBpmTarget) / relativeBpmTarget
        val diffPercentAbs = abs(diffPercent)

        val score = max(0.0, 100.0 * (1.0 - minDiff / 0.15)).roundToInt()
        val canMixWithNudge = diffPercentAbs <= 0.06
        // Merely being the *closest* of the three candidates says nothing
        // about whether it is actually usable — the half/double comparison
        // can still be nowhere near the target. Only claim "half/double time"
        // when the half/double pairing itself is within nudging distance;
        // otherwise fall through to the ordinary "too far apart" advice.
        val isHalfTimeDoubleTime = isHalfOrDoubleTarget && canMixWithNudge

        val advice = when {
            diffPercentAbs < 0.005 -> {
                if (isHalfTimeDoubleTime) {
                    "Perfect half/double-time match (${bpmA.roundToInt()} ⇄ ${bpmB.roundToInt()} BPM)"
                } else {
                    "Perfect tempo match (${bpmA.roundToInt()} BPM)"
                }
            }
            canMixWithNudge -> {
                val percentStr = String.format("%.1f", diffPercentAbs * 100)
                if (isHalfTimeDoubleTime) {
                    "Pitch-compatible half/double-time! Adjust Track B by $percentStr%"
                } else {
                    val sign = if (diffPercent > 0) "-" else "+"
                    "Pitch-compatible! Adjust pitch fader by $sign$percentStr%"
                }
            }
            else -> {
                "Tempos too far apart (${bpmA.roundToInt()} vs ${bpmB.roundToInt()} BPM). Mix during beat-free sections."
            }
        }

        return mapOf(
            "score" to score,
            "advice" to advice,
            "diffPercent" to diffPercent * 100,
            "isHalfTimeDoubleTime" to isHalfTimeDoubleTime,
            "canMixWithNudge" to canMixWithNudge
        )
    }

    /**
     * Compares two tracks on measured tempo and key.
     *
     * When either value is missing, its half of the score is reported as
     * unknown (0) and [MixMatch.isComplete] is false, rather than substituting a
     * plausible-looking default.
     */
    fun compareTracks(trackA: Track, trackB: Track): MixMatch {
        val bpmA = trackA.bpm
        val bpmB = trackB.bpm
        val keyA = trackA.camelotKey
        val keyB = trackB.camelotKey

        val hasTempo = bpmA != null && bpmB != null
        val hasKey = keyA != null && keyB != null

        val tempoRes = if (hasTempo) calculateTempoCompatibility(bpmA!!, bpmB!!) else null
        val keyRes = if (hasKey) calculateKeyCompatibility(keyA!!, keyB!!) else null

        val tempoScore = tempoRes?.get("score") as? Int ?: 0
        val keyScore = keyRes?.first ?: 0

        val overall = when {
            hasTempo && hasKey -> ((tempoScore + keyScore) / 2.0).roundToInt()
            hasTempo -> tempoScore
            hasKey -> keyScore
            else -> 0
        }

        return MixMatch(
            trackA = trackA,
            trackB = trackB,
            overallScore = overall,
            tempoScore = tempoScore,
            keyScore = keyScore,
            tempoAdvice = tempoRes?.get("advice") as? String ?: "Tempo not measured yet",
            keyAdvice = keyRes?.second ?: "Key not measured yet",
            tempoDiffPercent = tempoRes?.get("diffPercent") as? Double ?: 0.0,
            isHalfTimeDoubleTime = tempoRes?.get("isHalfTimeDoubleTime") as? Boolean ?: false,
            canMixWithNudge = tempoRes?.get("canMixWithNudge") as? Boolean ?: false,
            isComplete = hasTempo && hasKey,
        )
    }

}
