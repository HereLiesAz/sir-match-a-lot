package com.hereliesaz.sirmatchalot.ai

import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.data.LinkParser
import com.hereliesaz.sirmatchalot.domain.HarmonicEngine
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import org.json.JSONObject
import java.util.UUID

interface SongAnalyzer {
    suspend fun analyze(query: String, fileName: String? = null): Track
}

class HeuristicAnalyzer : SongAnalyzer {
    override suspend fun analyze(query: String, fileName: String?): Track {
        val songQuery = (query.trim().takeIf { it.isNotEmpty() } ?: fileName ?: "Unknown Track").lowercase()
        
        val parsedNames = if (fileName != null) {
            LinkParser.parseFileName(fileName)
        } else {
            val parts = songQuery.split(" - ")
            if (parts.size >= 2) {
                Pair(parts[1].capitalizeWords(), parts[0].capitalizeWords())
            } else {
                Pair(songQuery.capitalizeWords(), "Unknown Artist")
            }
        }

        var estimatedBpm = 124
        var detectedKey = "A minor"
        var energy = 7
        var atmosphere = "driving, electronic, steady groove"

        when {
            songQuery.contains("lofi") || songQuery.contains("chill") || songQuery.contains("relax") -> {
                estimatedBpm = 82
                detectedKey = "F minor"
                energy = 3
                atmosphere = "laid-back, warm vinyl crackle, nostalgic, mellow keys, ambient"
            }
            songQuery.contains("techno") || songQuery.contains("dark") -> {
                estimatedBpm = 132
                detectedKey = "B minor"
                energy = 9
                atmosphere = "hypnotic, industrial, pounding kick, dark synth, underground vibe"
            }
            songQuery.contains("house") || songQuery.contains("groove") || songQuery.contains("dance") -> {
                estimatedBpm = 125
                detectedKey = "G minor"
                energy = 8
                atmosphere = "bouncy, funky bassline, shuffling high hats, danceable vocal chops"
            }
            songQuery.contains("bass") || songQuery.contains("dub") || songQuery.contains("step") -> {
                estimatedBpm = 140
                detectedKey = "D minor"
                energy = 9
                atmosphere = "heavy, sub-bass growl, mechanical syncopated drums, aggressive"
            }
            songQuery.contains("dnb") || songQuery.contains("drum") || songQuery.contains("liquid") -> {
                estimatedBpm = 174
                detectedKey = "F# minor"
                energy = 9
                atmosphere = "rapid rolling breakbeats, deep sub bass, atmospheric pads, sweeping transitions"
            }
            songQuery.contains("hiphop") || songQuery.contains("rap") || songQuery.contains("trap") -> {
                estimatedBpm = 95
                detectedKey = "C# minor"
                energy = 6
                atmosphere = "bumping 808 sub, crisp hi-hat rolls, lyrical focus, relaxed groove"
            }
            else -> {
                val charSum = songQuery.sumOf { it.code }
                estimatedBpm = 110 + (charSum % 40)
                val keys = listOf("A minor", "G major", "D minor", "C# minor", "F minor", "E minor", "B minor", "Eb major")
                detectedKey = keys[charSum % keys.size]
                energy = 4 + (charSum % 6)
            }
        }

        val camelot = getCamelotKey(detectedKey)
        val progression = if (camelot.endsWith("A")) "i - bVI - bIII - bVII" else "I - V - vi - IV"

        return Track(
            id = "track-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}",
            title = parsedNames.first,
            artist = parsedNames.second,
            bpm = estimatedBpm,
            keyName = detectedKey,
            camelotKey = camelot,
            progression = progression,
            atmosphere = atmosphere,
            energyLevel = energy,
            mixTips = "Matches nicely with tracks around $estimatedBpm BPM. Standard blend transition works best. Key is $detectedKey ($camelot). Try mixing with $camelot or neighboring Camelot numbers.",
            youtubeId = null,
            localPath = null,
            isUserAdded = true
        )
    }

    private fun getCamelotKey(keyStr: String): String {
        val normalized = keyStr.lowercase().replace(Regex("[^a-z0-9#\\s]"), "")
        val keyMap = mapOf(
            "a minor" to "8A", "am" to "8A",
            "e minor" to "9A", "em" to "9A",
            "b minor" to "10A", "bm" to "10A",
            "f# minor" to "11A", "f#m" to "11A", "gb minor" to "11A", "gbm" to "11A",
            "c# minor" to "12A", "c#m" to "12A", "db minor" to "12A", "dbm" to "12A",
            "g# minor" to "1A", "g#m" to "1A", "ab minor" to "1A", "abm" to "1A",
            "d# minor" to "2A", "d#m" to "2A", "eb minor" to "2A", "ebm" to "2A",
            "a# minor" to "3A", "a#m" to "3A", "bb minor" to "3A", "bbm" to "3A",
            "f minor" to "4A", "fm" to "4A",
            "c minor" to "5A", "cm" to "5A",
            "g minor" to "6A", "gm" to "6A",
            "d minor" to "7A", "dm" to "7A",
            "c major" to "8B", "c maj" to "8B", "c" to "8B",
            "g major" to "9B", "g maj" to "9B", "g" to "9B",
            "d major" to "10B", "d maj" to "10B", "d" to "10B",
            "a major" to "11B", "a maj" to "11B", "a" to "11B",
            "e major" to "12B", "e maj" to "12B", "e" to "12B",
            "b major" to "1B", "b maj" to "1B", "b" to "1B",
            "f# major" to "2B", "f# maj" to "2B", "gb major" to "2B",
            "db major" to "3B", "db maj" to "3B", "c# major" to "3B",
            "ab major" to "4B", "ab maj" to "4B",
            "eb major" to "5B", "eb maj" to "5B",
            "bb major" to "6B", "bb maj" to "6B",
            "f major" to "7B", "f maj" to "7B", "f" to "7B"
        )
        for ((name, camelot) in keyMap) {
            if (normalized.contains(name)) {
                return camelot
            }
        }
        val hash = normalized.sumOf { it.code }
        val num = (hash % 12) + 1
        val letter = if (hash % 2 == 0) "A" else "B"
        return "$num$letter"
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
    }
}

class GeminiAnalyzer(
    private val apiKey: String,
    private val fallback: SongAnalyzer = HeuristicAnalyzer()
) : SongAnalyzer {

    override suspend fun analyze(query: String, fileName: String?): Track {
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return fallback.analyze(query, fileName)
        }

        try {
            val model = GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = apiKey,
                generationConfig = generationConfig {}
            )

            val songQuery = "${query.trim()} ${fileName ?: ""}".trim()
            val prompt = """
                You are a professional musical archivist and DJ music analysis engine.
                Analyze the following track or audio descriptor: "$songQuery" (fileName: "${fileName ?: "none"}").
                Extract or estimate:
                1. Exact or typical BPM (Tempo)
                2. Song Key (both standard, e.g., 'A minor' or 'G# major', and its exact Camelot Key, e.g. '8A', '4A', '11B')
                3. Chord Progression (e.g., 'Am - F - C - G' or 'i - VI - III - VII')
                4. Atmosphere description (detailed vibe tags, energy level, instrumentation, e.g., 'dark, groovy, rolling bassline, energetic')
                5. Energy Level (number from 1 to 10)
                6. Specific DJ Mixing Tips (advice on how to mix this track, transition ideas)
                
                If this is a real-world song, provide real-world music theory data. If it is an unknown or custom file name, perform a plausible simulation based on the file name's keywords.
                
                Ensure you return a clean JSON response structured exactly as follows:
                {
                  "title": "Song Title",
                  "artist": "Artist Name",
                  "bpm": 124,
                  "key": "A Minor",
                  "camelotKey": "8A",
                  "progression": "Am - F - C - G",
                  "atmosphere": "dark, groovy, rolling bassline",
                  "energyLevel": 7,
                  "mixTips": "Pro DJ mixing tips..."
                }
            """.trimIndent()

            val response = model.generateContent(prompt)
            val jsonText = response.text?.trim() ?: throw Exception("Empty AI response")
            
            val cleanJson = if (jsonText.startsWith("```json")) {
                jsonText.substringAfter("```json").substringBeforeLast("```").trim()
            } else if (jsonText.startsWith("```")) {
                jsonText.substringAfter("```").substringBeforeLast("```").trim()
            } else {
                jsonText
            }

            val json = JSONObject(cleanJson)
            val detectedCamelot = json.optString("camelotKey", "8A")

            return Track(
                id = "track-${System.currentTimeMillis()}",
                title = json.optString("title", query.capitalizeWords()),
                artist = json.optString("artist", "Unknown Artist"),
                bpm = json.optInt("bpm", 120),
                keyName = json.optString("key", "A minor"),
                camelotKey = detectedCamelot,
                progression = json.optString("progression", "Am - F - C - G"),
                atmosphere = json.optString("atmosphere", "electronic"),
                energyLevel = json.optInt("energyLevel", 7),
                mixTips = json.optString("mixTips", "Matches beautifully with relative major/minor keys."),
                youtubeId = null,
                localPath = null,
                isUserAdded = true
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return fallback.analyze(query, fileName)
        }
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
    }
}
