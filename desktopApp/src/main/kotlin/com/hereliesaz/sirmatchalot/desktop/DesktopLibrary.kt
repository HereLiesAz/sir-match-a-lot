package com.hereliesaz.sirmatchalot.desktop

import com.hereliesaz.sirmatchalot.analysis.AnalysisProgressBus
import com.hereliesaz.sirmatchalot.analysis.TrackAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * One remembered local audio file, with whatever [TrackAnalyzer] has measured
 * about it so far — null fields mean "not analysed yet", not "silent" or
 * "unknown key found". [analysisFailed] marks a file that could not be
 * decoded or measured at all, so it stops reading as "Analysing…" forever.
 */
data class LibraryTrack(
    val path: String,
    val displayName: String,
    val bpm: Double? = null,
    val camelotKey: String? = null,
    val energyLevel: Int? = null,
    val analysisFailed: Boolean = false,
) {
    val isAnalysed: Boolean get() = bpm != null || camelotKey != null || energyLevel != null

    /**
     * "128 BPM · 8A", a partial version of that, "Could not analyse" for a
     * failed file, or "Analysing…" before anything is measured.
     */
    fun analysisLabel(): String {
        if (analysisFailed && !isAnalysed) return "Could not analyse"
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
 * them every time — with the same BPM/key/energy measurements the Android
 * library shows, using the portable [TrackAnalyzer] from `:shared`. Tracks
 * left unanalysed by a previous run (the app closed mid-batch) are queued
 * again when the library is opened.
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

    /**
     * Guards every read-modify-write of [_tracks] (and the file it is
     * persisted to). `add`, `remove`, and each analysis result all replace
     * the whole list based on its current value; without a lock, two of
     * those racing — a folder import landing while a previous batch's
     * analysis is still writing results back, say — is the same lost-update
     * shape `DecodedCache` was fixed for: whichever write finishes last wins
     * and the other's change vanishes.
     */
    private val lock = Any()

    private val _tracks = MutableStateFlow(load())
    val tracks: StateFlow<List<LibraryTrack>> = _tracks

    /**
     * Runs analysis batches one at a time.
     *
     * [AnalysisProgressBus] is a single process-wide progress readout, not
     * one per batch — two batches analysing concurrently (a second `add()`
     * call arriving mid-analysis) used to each spawn their own `Thread` and
     * both call `begin`/`update`/`finish` on it independently, so the totals
     * and "current track" shown were whichever batch wrote last, and one
     * batch's `finish()` could hide the progress bar while the other was
     * still only half done. A single-thread executor serializes them: a
     * second `add()` while one batch is running enqueues rather than races.
     */
    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SirMatchALot-DesktopAnalysis").apply { isDaemon = true }
    }

    init {
        // Anything still neither measured nor marked failed was cut off by the
        // app closing mid-batch; without this it would read "Analysing…"
        // forever, since nothing else would ever queue it again.
        val pending = _tracks.value.filter { !it.isAnalysed && !it.analysisFailed }.map { File(it.path) }
        if (pending.isNotEmpty()) analysisExecutor.execute { analyse(pending) }
    }

    /**
     * Adds [files] not already present (by path), then measures each of them
     * on a background thread — analysis is real DSP work (FFTs over the
     * whole track) and must not block the caller, which on desktop is
     * whatever click handler is on the UI thread.
     */
    fun add(files: List<File>) {
        if (files.isEmpty()) return
        val newFiles = synchronized(lock) {
            val existingPaths = _tracks.value.map { it.path }.toSet()
            val toAdd = files.filter { it.path !in existingPaths }
            if (toAdd.isNotEmpty()) {
                _tracks.value = _tracks.value + toAdd.map { LibraryTrack(path = it.path, displayName = it.name) }
                persistOrLog()
            }
            toAdd
        }
        if (newFiles.isEmpty()) return
        analysisExecutor.execute { analyse(newFiles) }
    }

    fun remove(path: String) {
        synchronized(lock) {
            val next = _tracks.value.filterNot { it.path == path }
            if (next.size == _tracks.value.size) return
            _tracks.value = next
            persistOrLog()
        }
    }

    private fun analyse(files: List<File>) {
        AnalysisProgressBus.begin(files.size)
        var failed = 0
        try {
            files.forEachIndexed { index, sourceFile ->
                // Each track's own try/catch, so one bad file — a decode
                // that throws instead of returning null, say — fails just
                // that track and moves on, rather than escaping the loop
                // entirely and leaving every track after it (and the ones
                // still to come in this batch) stuck showing "Analysing…"
                // forever because AnalysisProgressBus.update/finish never
                // ran again.
                try {
                    val pcm = runCatching { DesktopAudioDecoder.decode(sourceFile) }.getOrNull()
                    val analysis = pcm?.let { runCatching { analyzer.analyse(it) }.getOrNull() }
                    if (analysis == null) {
                        failed++
                        markFailed(sourceFile)
                    } else {
                        synchronized(lock) {
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
                            persistOrLog()
                        }
                    }
                } catch (e: Exception) {
                    failed++
                    runCatching { markFailed(sourceFile) }
                } finally {
                    AnalysisProgressBus.update(done = index + 1, current = sourceFile.name, failed = failed)
                }
            }
        } finally {
            // Always reached, even if something above threw past its own
            // per-track guard — the progress readout must never be left
            // showing a run that has actually stopped.
            AnalysisProgressBus.finish()
        }
    }

    private fun markFailed(sourceFile: File) {
        synchronized(lock) {
            _tracks.value = _tracks.value.map { track ->
                if (track.path == sourceFile.path) track.copy(analysisFailed = true) else track
            }
            persistOrLog()
        }
    }

    /**
     * Blocks until every analysis batch queued so far has finished, or
     * [timeoutMs] passes. Returns false on timeout. For tests and shutdown —
     * never call this from the UI thread.
     */
    fun awaitIdle(timeoutMs: Long): Boolean =
        runCatching {
            analysisExecutor.submit { }.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)

    /** Stops accepting new analysis work; a batch already running is abandoned. */
    fun close() {
        analysisExecutor.shutdownNow()
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
                    analysisFailed = entry.optBoolean("analysisFailed", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * [persistLocked], but a write failure (full disk, read-only config dir)
     * is reported to stderr instead of thrown — the in-memory list stays
     * correct for this session, and a click handler must not crash over it.
     * Caller must hold [lock].
     */
    private fun persistOrLog() {
        try {
            persistLocked()
        } catch (e: java.io.IOException) {
            System.err.println("Sir Match-a-Lot: could not save the library to $file: ${e.message}")
        }
    }

    /** Writes [_tracks] to disk atomically. Caller must hold [lock]. */
    private fun persistLocked() {
        val array = JSONArray()
        _tracks.value.forEach { track ->
            array.put(
                JSONObject().apply {
                    put("path", track.path)
                    put("displayName", track.displayName)
                    track.bpm?.let { put("bpm", it) }
                    track.camelotKey?.let { put("camelotKey", it) }
                    track.energyLevel?.let { put("energyLevel", it) }
                    if (track.analysisFailed) put("analysisFailed", true)
                },
            )
        }
        AtomicFiles.write(file, array.toString().toByteArray(Charsets.UTF_8))
    }
}
