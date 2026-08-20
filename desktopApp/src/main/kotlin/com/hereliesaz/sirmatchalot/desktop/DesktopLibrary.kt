package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.analysis.AnalysisProgressBus
import com.hereliesaz.sirmatchalot.analysis.TrackAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One remembered local audio file, with whatever [TrackAnalyzer] has measured
 * about it so far — null fields mean "not analysed yet", not "silent" or
 * "unknown key found".
 */
data class LibraryTrack(
    val path: String,
    val displayName: String,
    val bpm: Double? = null,
    val camelotKey: String? = null,
    val energyLevel: Int? = null,
) {
    val isAnalysed: Boolean get() = bpm != null || camelotKey != null || energyLevel != null

    /** "128 BPM · 8A", a partial version of that, or "Analysing…" before anything is measured. */
    fun analysisLabel(): String {
        if (!isAnalysed) return "Analysing…"
        val parts = listOfNotNull(
            bpm?.let { "%.0f BPM".format(it) },
            camelotKey,
        )
        return parts.joinToString(" · ").ifBlank { "No tempo or key found" }
    }
}

/**
 * A desktop-side track library: local files the user has pointed the app at
 * before, remembered across launches so they don't have to re-browse for
 * them every time — now with the same BPM/key/energy measurements the
 * Android library shows, since [TrackAnalyzer] and the `dsp` pipeline behind
 * it were already fully portable (Phase 1 moved them to `:shared` with zero
 * Android dependency).
 *
 * What this still isn't is the Android app's `Track` entity — no cached-copy
 * bookkeeping, no cue points, and nothing backed by Room. Room's
 * multiplatform story needs a SQLite driver and KSP wiring `:shared` isn't
 * set up for, so this stays a JSON-backed file list rather than a database
 * table. That's a real gap for a large library (no query planner, no
 * indices), but not one that blocks a laptop from measuring and remembering
 * the tracks a working DJ actually has loaded.
 */
class DesktopLibrary(
    private val file: File = File(DesktopKeyValueStore.configDir(), "library.json"),
    private val analyzer: TrackAnalyzer = TrackAnalyzer(),
) {

    private val _tracks = MutableStateFlow(load())
    val tracks: StateFlow<List<LibraryTrack>> = _tracks

    /**
     * Adds [files] not already present (by path), then measures each of them
     * on a background thread — analysis is real DSP work (FFTs over the
     * whole track) and must not block the caller, which on desktop is
     * whatever click handler is on the UI thread.
     */
    fun add(files: List<File>) {
        if (files.isEmpty()) return
        val existingPaths = _tracks.value.map { it.path }.toSet()
        val newFiles = files.filter { it.path !in existingPaths }
        if (newFiles.isEmpty()) return

        _tracks.value = _tracks.value + newFiles.map { LibraryTrack(path = it.path, displayName = it.name) }
        persist()
        analyseInBackground(newFiles)
    }

    fun remove(path: String) {
        val next = _tracks.value.filterNot { it.path == path }
        if (next.size == _tracks.value.size) return
        _tracks.value = next
        persist()
    }

    private fun analyseInBackground(files: List<File>) {
        Thread {
            AnalysisProgressBus.begin(files.size)
            var failed = 0
            files.forEachIndexed { index, sourceFile ->
                val pcm = runCatching { DesktopAudioDecoder.decode(sourceFile) }.getOrNull()
                val analysis = pcm?.let { runCatching { analyzer.analyse(it) }.getOrNull() }
                if (analysis == null) failed++
                if (analysis != null) {
                    _tracks.value = _tracks.value.map { track ->
                        if (track.path == sourceFile.path) {
                            track.copy(
                                bpm = analysis.bpm,
                                camelotKey = analysis.camelotKey,
                                energyLevel = analysis.energyLevel,
                            )
                        } else {
                            track
                        }
                    }
                    persist()
                }
                AnalysisProgressBus.update(done = index + 1, current = sourceFile.name, failed = failed)
            }
            AnalysisProgressBus.finish()
        }.apply {
            name = "SirMatchALot-DesktopAnalysis"
            isDaemon = true
            start()
        }
    }

    private fun load(): List<LibraryTrack> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val path = entry.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LibraryTrack(
                    path = path,
                    displayName = entry.optString("displayName", File(path).name),
                    bpm = entry.optDouble("bpm").takeUnless { it.isNaN() },
                    camelotKey = entry.optString("camelotKey", "").takeIf { it.isNotBlank() },
                    energyLevel = if (entry.has("energyLevel")) entry.optInt("energyLevel") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val array = JSONArray()
        _tracks.value.forEach { track ->
            array.put(
                JSONObject().apply {
                    put("path", track.path)
                    put("displayName", track.displayName)
                    track.bpm?.let { put("bpm", it) }
                    track.camelotKey?.let { put("camelotKey", it) }
                    track.energyLevel?.let { put("energyLevel", it) }
                },
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(array.toString())
    }
}
