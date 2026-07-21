package com.hereliesaz.sirmatchalot.domain

import com.hereliesaz.sirmatchalot.data.Track
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class MixMatch(
    val trackA: Track,
    val trackB: Track,
    val overallScore: Int,
    val tempoScore: Int,
    val keyScore: Int,
    val progressionScore: Int,
    val atmosphereScore: Int,
    val tempoAdvice: String,
    val keyAdvice: String,
    val tempoDiffPercent: Double,
    val isHalfTimeDoubleTime: Boolean,
    val canMixWithNudge: Boolean
)

object HarmonicEngine {
    private val CHROMATIC_MINOR = listOf("Am", "A#m", "Bm", "Cm", "C#m", "Dm", "D#m", "Em", "Fm", "F#m", "Gm", "G#m")
    private val CAMELOT_MINOR = listOf("8A", "3A", "10A", "5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A")

    private val CHROMATIC_MAJOR = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val CAMELOT_MAJOR = listOf("8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B")

    data class CamelotKey(val number: Int, val mode: Char)

    fun parseCamelotKey(camelot: String): CamelotKey? {
        val trimmed = camelot.trim().uppercase()
        val regex = Regex("^(\\d+)([AB])$")
        val match = regex.find(trimmed) ?: return null
        val num = match.groupValues[1].toIntOrNull() ?: return null
        val mode = match.groupValues[2][0]
        return CamelotKey(num, mode)
    }

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

        val idxA = listA.indexOf(keyA.uppercase())
        val idxB = listB.indexOf(keyB.uppercase())
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
            val description = if (cB.number > cA.number || (cA.number == 12 && cB.number == 1)) {
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
        var isHalfTimeDoubleTime = false
        var relativeBpmTarget = bpmA

        if (diffHalf < minDiff) {
            minDiff = diffHalf
            isHalfTimeDoubleTime = true
            relativeBpmTarget = bpmA * 0.5
        }
        if (diffDouble < minDiff) {
            minDiff = diffDouble
            isHalfTimeDoubleTime = true
            relativeBpmTarget = bpmA * 2.0
        }

        val diffPercent = (bpmB - relativeBpmTarget) / relativeBpmTarget
        val diffPercentAbs = abs(diffPercent)

        val score = max(0.0, 100.0 * (1.0 - minDiff / 0.15)).roundToInt()
        val canMixWithNudge = diffPercentAbs <= 0.06

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

    fun calculateProgressionCompatibility(progA: String, progB: String): Int {
        if (progA.isBlank() || progB.isBlank()) return 60

        val cleanA = progA.lowercase().replace(Regex("[^a-z0-9\\s-]"), "").split(Regex("\\s*-\\s*|\\s+")).toSet()
        val cleanB = progB.lowercase().replace(Regex("[^a-z0-9\\s-]"), "").split(Regex("\\s*-\\s*|\\s+")).toSet()

        val intersection = cleanA.intersect(cleanB).size
        val union = cleanA.union(cleanB).size

        if (union == 0) return 60
        val ratio = intersection.toDouble() / union
        return (65 + ratio * 35).roundToInt()
    }

    fun calculateAtmosphereCompatibility(atmosA: String, atmosB: String): Int {
        if (atmosA.isBlank() || atmosB.isBlank()) return 60

        val wordsA = atmosA.lowercase().split(Regex("[\\s,]+")).map { it.trim() }.filter { it.length > 2 }
        val wordsB = atmosB.lowercase().split(Regex("[\\s,]+")).map { it.trim() }.filter { it.length > 2 }

        val commonKeywords = setOf("dark", "groovy", "energetic", "melodic", "bouncy", "ambient", "hypnotic", "uplifting", "euphoric", "heavy", "chill", "atmospheric", "soulful", "industrial")

        val activeA = wordsA.filter { commonKeywords.contains(it) }.toSet()
        val activeB = wordsB.filter { commonKeywords.contains(it) }.toSet()

        val setA = if (activeA.isNotEmpty()) activeA else wordsA.toSet()
        val setB = if (activeB.isNotEmpty()) activeB else wordsB.toSet()

        val intersect = setA.intersect(setB).size
        val union = setA.union(setB).size

        if (union == 0) return 60
        val ratio = intersect.toDouble() / union
        return (60 + ratio * 40).roundToInt()
    }

    fun compareTracks(trackA: Track, trackB: Track): MixMatch {
        val keyRes = calculateKeyCompatibility(trackA.camelotKey, trackB.camelotKey)
        val tempoRes = calculateTempoCompatibility(trackA.bpm.toDouble(), trackB.bpm.toDouble())
        val progScore = calculateProgressionCompatibility(trackA.progression, trackB.progression)
        val atmosScore = calculateAtmosphereCompatibility(trackA.atmosphere, trackB.atmosphere)

        val tempoScore = tempoRes["score"] as Int
        val keyScore = keyRes.first
        val keyAdvice = keyRes.second
        val tempoAdvice = tempoRes["advice"] as String
        val diffPercent = tempoRes["diffPercent"] as Double
        val isHalfDouble = tempoRes["isHalfTimeDoubleTime"] as Boolean
        val canNudge = tempoRes["canMixWithNudge"] as Boolean

        val overall = (tempoScore * 0.30 + keyScore * 0.30 + progScore * 0.20 + atmosScore * 0.20).roundToInt()

        return MixMatch(
            trackA = trackA,
            trackB = trackB,
            overallScore = overall,
            tempoScore = tempoScore,
            keyScore = keyScore,
            progressionScore = progScore,
            atmosphereScore = atmosScore,
            tempoAdvice = tempoAdvice,
            keyAdvice = keyAdvice,
            tempoDiffPercent = diffPercent,
            isHalfTimeDoubleTime = isHalfDouble,
            canMixWithNudge = canNudge
        )
    }

    fun getCamelotDistance(camelotA: String, camelotB: String): Int {
        val cA = parseCamelotKey(camelotA) ?: return 999
        val cB = parseCamelotKey(camelotB) ?: return 999

        val numDiff = abs(cA.number - cB.number)
        val dN = min(numDiff, 12 - numDiff)
        val dM = if (cA.mode == cB.mode) 0 else 1

        return dN + dM
    }
}
