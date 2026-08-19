package com.hereliesaz.sirmatchalot.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One remembered local audio file — just enough to find it and show it in a list. */
data class LibraryTrack(val path: String, val displayName: String)

/**
 * A desktop-side track library: local files the user has pointed the app at
 * before, remembered across launches so they don't have to re-browse for
 * them every time.
 *
 * This is deliberately not the Android app's `Track` — that entity carries
 * BPM/key/energy analysis, cached-copy bookkeeping, and cue points, all
 * backed by Room. None of that exists on the desktop side yet (Room's
 * multiplatform story needs a SQLite driver and KSP wiring `:shared` isn't
 * set up for), so this is only a remembered file list, persisted as JSON
 * next to the rest of the desktop app's local state. Growing it into real
 * analysis is a follow-up, not something this class blocks.
 */
class DesktopLibrary(private val file: File = File(DesktopKeyValueStore.configDir(), "library.json")) {

    private val _tracks = MutableStateFlow(load())
    val tracks: StateFlow<List<LibraryTrack>> = _tracks

    /** Adds [files] not already present (by path), then persists. */
    fun add(files: List<File>) {
        if (files.isEmpty()) return
        val existingPaths = _tracks.value.map { it.path }.toSet()
        val additions = files
            .filter { it.path !in existingPaths }
            .map { LibraryTrack(path = it.path, displayName = it.name) }
        if (additions.isEmpty()) return
        _tracks.value = _tracks.value + additions
        persist()
    }

    fun remove(path: String) {
        val next = _tracks.value.filterNot { it.path == path }
        if (next.size == _tracks.value.size) return
        _tracks.value = next
        persist()
    }

    private fun load(): List<LibraryTrack> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val path = entry.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LibraryTrack(path = path, displayName = entry.optString("displayName", File(path).name))
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
                },
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(array.toString())
    }
}
