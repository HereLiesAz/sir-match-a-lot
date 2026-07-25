package com.hereliesaz.sirmatchalot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.hereliesaz.sirmatchalot.analysis.TrackAnalyzer
import com.hereliesaz.sirmatchalot.audio.AudioDecoder
import com.hereliesaz.sirmatchalot.audio.AudioEngine
import com.hereliesaz.sirmatchalot.audio.AudioTrackOutput
import com.hereliesaz.sirmatchalot.audio.Clip
import com.hereliesaz.sirmatchalot.dsp.PeakEnvelope
import com.hereliesaz.sirmatchalot.ui.platter.PlatterGeometry
import com.hereliesaz.sirmatchalot.ui.platter.PlatterState
import com.hereliesaz.sirmatchalot.audio.DeckController
import com.hereliesaz.sirmatchalot.data.AppDatabase
import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.domain.BeatSync
import com.hereliesaz.sirmatchalot.domain.HarmonicEngine
import com.hereliesaz.sirmatchalot.domain.MixPlan
import com.hereliesaz.sirmatchalot.domain.MixPlanner
import com.hereliesaz.sirmatchalot.domain.MixMatch
import com.hereliesaz.sirmatchalot.sync.SyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

class SirMatchALotViewModel(application: Application) : AndroidViewModel(application), SyncClient.SyncListener {

    private val db = AppDatabase.getDatabase(application)
    private val trackDao = db.trackDao()


    /**
     * The real-time mixing engine.
     *
     * Runs at the device's native mixer rate so AudioFlinger is not resampling
     * every block on the way out. This replaces the previous arrangement of one
     * ExoPlayer per clip, which had no mixing stage and so could not crossfade,
     * EQ, scratch, or play in reverse.
     */
    val audioEngine = AudioEngine(AudioTrackOutput.forDevice(application))

    private val _platterState = MutableStateFlow(PlatterState())
    val platterState: StateFlow<PlatterState> = _platterState

    /** Decoded audio per track id, so a clip is decoded once and reused. */
    private val decoded = mutableMapOf<String, com.hereliesaz.sirmatchalot.audio.PcmBuffer>()
    private val peaksCache = mutableMapOf<String, PeakEnvelope>()

    init {
        audioEngine.onReverseThreshold = { _feedbackMsg.value = "I am Satan, Lord of Darkness." }
        audioEngine.start()
        // Publish the engine's playhead and metered level for the platter. The
        // playhead comes from the audio graph, not from a wall-clock animation,
        // so what is drawn is where playback actually is.
        viewModelScope.launch {
            while (true) {
                delay(16)
                val deck = audioEngine.deckA.takeIf { it.cycleFrames > 0 } ?: audioEngine.deckB
                _platterState.value = _platterState.value.copy(
                    playheadFraction = deck.cyclePosition(),
                    outputLevel = audioEngine.mixer.level.peak,
                    isPlaying = audioEngine.deckA.playing || audioEngine.deckB.playing,
                )
            }
        }
    }

    /**
     * Loads [track] onto a deck, decoding it if necessary, and republishes the
     * platter layout.
     */
    fun loadOntoDeck(track: Track, deck: PlatterGeometry.Deck) {
        viewModelScope.launch(Dispatchers.IO) {
            val source = track.sourceUri ?: return@launch
            val pcm = decoded.getOrPut(track.id) {
                AudioDecoder.decode(getApplication(), Uri.parse(source))?.pcm ?: return@launch
            }
            peaksCache.getOrPut(track.id) {
                track.peaksPath
                    ?.let { path -> runCatching { PeakEnvelope.fromByteArray(java.io.File(path).readBytes()) }.getOrNull() }
                    ?: PeakEnvelope.compute(pcm.toMonoFloat())
            }

            val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
            val existing = engineDeck.clips
            val startFrame = existing.maxOfOrNull { it.endFrame } ?: 0
            engineDeck.clips = existing + Clip(
                id = track.id,
                buffer = pcm,
                startFrame = startFrame,
                loop = existing.isEmpty(),
            )
            engineDeck.playing = true
            republishPlatter()
        }
    }

    /** Rebuilds the platter layout from the engine's clips. */
    private fun republishPlatter() {
        fun inputsFor(deck: PlatterGeometry.Deck): List<PlatterState.ClipLayoutInput> {
            val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
            return engineDeck.clips.map { clip ->
                PlatterState.ClipLayoutInput(
                    id = clip.id,
                    title = _tracks.value.firstOrNull { it.id == clip.id }?.title ?: clip.id,
                    durationSeconds = clip.frameCount.toDouble() / clip.buffer.sampleRate,
                    peaks = peaksCache[clip.id] ?: PeakEnvelope.compute(FloatArray(0)),
                )
            }
        }

        val selected = _selectedTrackIds.value
        _platterState.value = _platterState.value.copy(
            deckA = PlatterState.layout(inputsFor(PlatterGeometry.Deck.A), selected, PlatterGeometry.Deck.A),
            deckB = PlatterState.layout(inputsFor(PlatterGeometry.Deck.B), selected, PlatterGeometry.Deck.B),
        )
    }

    /**
     * Fills empty sampler pads with loops found in the track on Deck A.
     *
     * The loops come from measured self-similarity over the measured beat grid,
     * so they are whole bars of material that genuinely repeats.
     */
    fun autoFillPads() {
        val track = _loadedTracksA.value.firstOrNull()
        if (track == null) {
            _feedbackMsg.value = "Load a track on Deck A first"
            return
        }
        val bpm = track.bpm
        val firstBeat = track.firstBeatSeconds
        if (bpm == null || firstBeat == null) {
            _feedbackMsg.value = "${track.title} has no measured beat grid to find loops on"
            return
        }
        val pcm = decoded[track.id]
        if (pcm == null) {
            _feedbackMsg.value = "${track.title} is not decoded yet"
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            val grid = com.hereliesaz.sirmatchalot.dsp.BeatGrid(
                bpm = bpm,
                firstBeatSeconds = firstBeat,
                downbeatOffset = track.downbeatOffset,
            )
            val loops = com.hereliesaz.sirmatchalot.dsp.StructureFinder()
                .findLoops(pcm.toMonoFloat(), pcm.sampleRate, grid)
            val filled = audioEngine.sampler.autoFill(loops, pcm, track.title)
            _feedbackMsg.value = when {
                loops.isEmpty() -> "No repeating sections found in ${track.title}"
                filled == 0 -> "No free pads to fill"
                else -> "Filled $filled pads from ${track.title}"
            }
        }
    }

    /** Removes every selected clip from both decks. */
    fun removeSelectedClips() {
        val selected = _selectedTrackIds.value
        if (selected.isEmpty()) return
        audioEngine.deckA.clips = audioEngine.deckA.clips.filterNot { it.id in selected }
        audioEngine.deckB.clips = audioEngine.deckB.clips.filterNot { it.id in selected }
        _selectedTrackIds.value = emptySet()
        republishPlatter()
    }

    /** Replaces the selection and republishes so the ring highlights it. */
    fun setSelectionAndPublish(ids: Set<String>) {
        _selectedTrackIds.value = ids
        republishPlatter()
    }

    // --- Gesture entry points, mapped to the engine ---

    fun nudgeCrossfade(delta: Float) {
        audioEngine.mixer.crossfade = (audioEngine.mixer.crossfade + delta / 600f).coerceIn(0f, 1f)
    }

    fun nudgeMasterVolume(deltaRadians: Float) {
        audioEngine.mixer.masterGain =
            (audioEngine.mixer.masterGain + deltaRadians * 0.25f).coerceIn(0f, 1f)
    }

    fun nudgeBassBoost(delta: Float) {
        val next = (audioEngine.deckA.bassBoostDb + delta * 0.06).coerceIn(-18.0, 18.0)
        audioEngine.deckA.bassBoostDb = next
        audioEngine.deckB.bassBoostDb = next
    }

    val syncClient = SyncClient(this)

    /**
     * Measures tempo, key, energy and peaks from decoded audio.
     *
     * Replaces the previous `GeminiAnalyzer`, which was constructed with the
     * literal placeholder key "MY_GEMINI_API_KEY" and so always fell through to
     * a heuristic that derived BPM from a character-code sum of the filename.
     */
    private val analyzer = TrackAnalyzer()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    /**
     * How the library is ordered.
     *
     * The previous version had five options and a `sortedTracks` flow, but
     * `LibraryScreen` rendered the unsorted list — so none of it did anything.
     * This is wired to the UI, and every option orders by measured values only.
     */
    enum class LibrarySort(val label: String) {
        /** Import order. */
        RECENT("Recent"),
        /** Camelot proximity to the track on Deck A — the harmonic filter. */
        HARMONIC("Harmonic match"),
        /** Closest tempo to the track on Deck A. */
        TEMPO("Tempo match"),
        TITLE("Title"),
        ENERGY("Energy"),
    }

    private val _librarySort = MutableStateFlow(LibrarySort.RECENT)
    val librarySort: StateFlow<LibrarySort> = _librarySort

    private val _libraryFilter = MutableStateFlow("")
    val libraryFilter: StateFlow<String> = _libraryFilter

    fun setLibrarySort(sort: LibrarySort) { _librarySort.value = sort }

    fun setLibraryFilter(text: String) { _libraryFilter.value = text }

    // Concentric Circular Platters (Multi-track lists for Deck A and B)
    private val _loadedTracksA = MutableStateFlow<List<Track>>(emptyList())
    val loadedTracksA: StateFlow<List<Track>> = _loadedTracksA

    private val _loadedTracksB = MutableStateFlow<List<Track>>(emptyList())
    val loadedTracksB: StateFlow<List<Track>> = _loadedTracksB

    // Dynamic player controllers
    private val _controllersA = MutableStateFlow<List<DeckController>>(emptyList())
    val controllersA: StateFlow<List<DeckController>> = _controllersA

    private val _controllersB = MutableStateFlow<List<DeckController>>(emptyList())
    val controllersB: StateFlow<List<DeckController>> = _controllersB

    // Gesture targeting state: targets specific trackIds (empty = apply to all)
    private val _selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTrackIds: StateFlow<Set<String>> = _selectedTrackIds

    /**
     * The library as the UI shows it: text-filtered, then ordered.
     *
     * Harmonic and tempo ordering are relative to whatever is on Deck A, since
     * "what mixes next" only means anything relative to what is playing.
     */
    val visibleTracks: StateFlow<List<Track>> =
        combine(_tracks, _librarySort, _libraryFilter, _loadedTracksA) { all, sort, filter, deckA ->
            val reference = deckA.firstOrNull()
            val matching = if (filter.isBlank()) all else all.filter { track ->
                track.title.contains(filter, ignoreCase = true) ||
                    track.artist.contains(filter, ignoreCase = true)
            }
            when (sort) {
                LibrarySort.RECENT -> matching
                LibrarySort.TITLE -> matching.sortedBy { it.title.lowercase() }
                // Unmeasured tracks sort last rather than being given a position
                // they have not earned.
                LibrarySort.ENERGY -> matching.sortedByDescending { it.energyLevel ?: -1 }
                LibrarySort.HARMONIC -> MixPlanner.byHarmonicProximity(matching, reference)
                LibrarySort.TEMPO -> {
                    val referenceBpm = reference?.bpm
                    if (referenceBpm == null) matching
                    else matching.sortedBy { track ->
                        track.bpm?.let { kotlin.math.abs(Math.log(it / referenceBpm)) } ?: Double.MAX_VALUE
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Tracks that mix well after whatever is on Deck A, best first. */
    val suggestions: StateFlow<List<MixMatch>> =
        combine(_tracks, _loadedTracksA) { all, deckA ->
            val reference = deckA.firstOrNull() ?: return@combine emptyList()
            MixPlanner.suggestNext(all, reference)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Fills both decks with a compatible pair — "Shuffle Crate".
     */
    fun shuffleCrate() {
        val pick = MixPlanner.shuffleCrate(_tracks.value)
        if (pick == null) {
            _feedbackMsg.value = "Need two analysed tracks that actually mix"
            return
        }
        audioEngine.deckA.clips = emptyList()
        audioEngine.deckB.clips = emptyList()
        loadOntoDeck(pick.deckA, PlatterGeometry.Deck.A)
        loadOntoDeck(pick.deckB, PlatterGeometry.Deck.B)
        _feedbackMsg.value =
            "${pick.deckA.title} + ${pick.deckB.title} — ${pick.match.overallScore}% match. ${pick.match.keyAdvice}"
    }

    private val _mixPlan = MutableStateFlow<MixPlan?>(null)
    val mixPlan: StateFlow<MixPlan?> = _mixPlan

    /**
     * Plans a running order across the whole library — the "Automatchic Mix".
     */
    fun buildAutomatchicMix() {
        val plan = MixPlanner.automatchicMix(_tracks.value)
        _mixPlan.value = plan
        _feedbackMsg.value = when {
            plan.steps.isEmpty() -> "No analysed tracks to plan a mix from"
            plan.skipped.isEmpty() ->
                "Planned ${plan.steps.size} tracks, ${plan.averageScore}% average transition"
            else ->
                "Planned ${plan.steps.size} tracks (${plan.skipped.size} not analysed), " +
                    "${plan.averageScore}% average transition"
        }
    }

    /**
     * Beat-syncs both decks to whatever is on Deck A, using measured tempo and
     * phase. Tracks without a measured tempo are left alone.
     */
    fun syncToDeckA() {
        val reference = _loadedTracksA.value.firstOrNull()
        if (reference?.bpm == null) {
            _feedbackMsg.value = "Deck A has no measured tempo to sync to"
            return
        }
        var synced = 0
        var skipped = 0
        for (track in _loadedTracksB.value) {
            val alignment = BeatSync.align(track, reference)
            if (alignment == null) {
                skipped++
                continue
            }
            audioEngine.applyAlignment("B", alignment.tempoRatio, alignment.phaseOffsetSeconds)
            synced++
        }
        // Deck A is the reference, so it returns to its own tempo.
        audioEngine.applyAlignment("A", 1.0, 0.0)
        _feedbackMsg.value = buildString {
            append("Synced $synced to ${String.format("%.1f", reference.bpm)} BPM")
            if (skipped > 0) append(", $skipped skipped for want of a measured tempo")
        }
    }

    // Per-track volume multipliers (1.0 = normal, scaled up/down by vertical gesture)
    private val _trackVolumes = MutableStateFlow<Map<String, Float>>(emptyMap())
    val trackVolumes: StateFlow<Map<String, Float>> = _trackVolumes

    // Per-track angular overlap amount (in radians)
    private val _trackOverlaps = MutableStateFlow<Map<String, Float>>(emptyMap())
    val trackOverlaps: StateFlow<Map<String, Float>> = _trackOverlaps

    private val _trackPeaks = MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    val trackPeaks: StateFlow<Map<String, FloatArray>> = _trackPeaks

    // UI Mixer controls
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _audioVolume = MutableStateFlow(0.4f)
    val audioVolume: StateFlow<Float> = _audioVolume

    private val _crossfader = MutableStateFlow(0)
    val crossfader: StateFlow<Int> = _crossfader

    private val _roomCode = MutableStateFlow("")
    val roomCode: StateFlow<String> = _roomCode

    private val _isWsConnected = MutableStateFlow(false)
    val isWsConnected: StateFlow<Boolean> = _isWsConnected

    private val _feedbackMsg = MutableStateFlow("Offline Mode")
    val feedbackMsg: StateFlow<String> = _feedbackMsg

    private val _cuesA = MutableStateFlow<List<Float?>>(listOf(null, null, null, null))
    val cuesA: StateFlow<List<Float?>> = _cuesA

    private val _cuesB = MutableStateFlow<List<Float?>>(listOf(null, null, null, null))
    val cuesB: StateFlow<List<Float?>> = _cuesB

    fun setCue(deck: String, index: Int, time: Float) {
        if (deck == "A") {
            val nextCues = _cuesA.value.toMutableList()
            nextCues[index - 1] = time
            _cuesA.value = nextCues
        } else {
            val nextCues = _cuesB.value.toMutableList()
            nextCues[index - 1] = time
            _cuesB.value = nextCues
        }
    }

    fun triggerCue(deck: String, index: Int) {
        val time = if (deck == "A") _cuesA.value[index - 1] else _cuesB.value[index - 1]
        time?.let { t ->
            if (deck == "A") {
                _controllersA.value.forEach { it.seekTo(t) }
            } else {
                _controllersB.value.forEach { it.seekTo(t) }
            }
        }
    }



    init {
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.getAllTracksFlow().collect { list ->
                if (list.isEmpty()) {
                    fetchAndImportAzphaltStore()
                } else {
                    _tracks.value = list
                }
            }
        }
    }

    private suspend fun fetchAndImportAzphaltStore() {
        try {
            val packages = com.hereliesaz.sirmatchalot.data.AzphaltStoreRepository.fetchAudioPackages()
            if (packages.isNotEmpty()) {
                // Download the first audio package as default library
                val downloadedTracks = com.hereliesaz.sirmatchalot.data.AzphaltStoreRepository.downloadAndExtractPackage(getApplication(), packages.first())
                if (downloadedTracks.isNotEmpty()) {
                    trackDao.insertTracks(downloadedTracks)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun selectTrack(trackId: String?) {
        _selectedTrackIds.value = if (trackId != null) setOf(trackId) else emptySet()
    }

    fun setSelectedTracks(trackIds: Set<String>) {
        _selectedTrackIds.value = trackIds
    }



    // Dynamic Platter loading functions with AutoSync BPM & Harmonize on drop
    fun addTrackToDeckA(track: Track) {
        val list = _loadedTracksA.value.toMutableList()
        if (list.any { it.id == track.id }) return
        list.add(track)
        _loadedTracksA.value = list
        loadPeaksForTrack(track)

        val controller = DeckController(getApplication(), "Deck A - ${track.title}")
        controller.loadTrack(track)
        val controllers = _controllersA.value.toMutableList()
        controllers.add(controller)
        _controllersA.value = controllers

        // AutoSync BPM & Harmonize against reference track (Deck B or Deck A first)
        val refTrack = _loadedTracksB.value.firstOrNull() ?: _loadedTracksA.value.firstOrNull()
        val trackBpm = track.bpm
        val refBpm = refTrack?.bpm
        if (refTrack != null && refTrack.id != track.id && trackBpm != null && refBpm != null && trackBpm > 0) {
            val rate = (refBpm / trackBpm).toFloat().coerceIn(0.5f, 2.0f)
            controller.setPlaybackRate(rate)
            _feedbackMsg.value = "Synced ${track.title} to ${String.format("%.1f", refBpm)} BPM"
        } else if (trackBpm == null) {
            _feedbackMsg.value = "${track.title} has not been analysed yet"
        }

        if (_isPlaying.value) controller.play()
        updateAllVolumes()

        syncClient.triggerLoadTrack("A", track.id, _roomCode.value)
    }

    fun addTrackToDeckB(track: Track) {
        val list = _loadedTracksB.value.toMutableList()
        if (list.any { it.id == track.id }) return
        list.add(track)
        _loadedTracksB.value = list
        loadPeaksForTrack(track)

        val controller = DeckController(getApplication(), "Deck B - ${track.title}")
        controller.loadTrack(track)
        val controllers = _controllersB.value.toMutableList()
        controllers.add(controller)
        _controllersB.value = controllers

        // AutoSync BPM & Harmonize against reference track (Deck A or Deck B first)
        val refTrack = _loadedTracksA.value.firstOrNull() ?: _loadedTracksB.value.firstOrNull()
        val trackBpm = track.bpm
        val refBpm = refTrack?.bpm
        if (refTrack != null && refTrack.id != track.id && trackBpm != null && refBpm != null && trackBpm > 0) {
            val rate = (refBpm / trackBpm).toFloat().coerceIn(0.5f, 2.0f)
            controller.setPlaybackRate(rate)
            _feedbackMsg.value = "Synced ${track.title} to ${String.format("%.1f", refBpm)} BPM"
        } else if (trackBpm == null) {
            _feedbackMsg.value = "${track.title} has not been analysed yet"
        }

        if (_isPlaying.value) controller.play()
        updateAllVolumes()

        syncClient.triggerLoadTrack("B", track.id, _roomCode.value)
    }

    private fun loadPeaksForTrack(track: Track) {
        if (track.peaksPath == null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = java.io.File(track.peaksPath)
                if (!file.exists()) return@launch
                
                val bytes = file.readBytes()
                val floatArray = FloatArray(bytes.size / 4)
                val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buffer.asFloatBuffer().get(floatArray)
                
                val current = _trackPeaks.value.toMutableMap()
                current[track.id] = floatArray
                _trackPeaks.value = current
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeTrackFromDecks(trackId: String) {
        val listA = _loadedTracksA.value.toMutableList()
        val idxA = listA.indexOfFirst { it.id == trackId }
        if (idxA != -1) {
            listA.removeAt(idxA)
            _loadedTracksA.value = listA

            val controllers = _controllersA.value.toMutableList()
            val controller = controllers.removeAt(idxA)
            controller.release()
            _controllersA.value = controllers
        }

        val listB = _loadedTracksB.value.toMutableList()
        val idxB = listB.indexOfFirst { it.id == trackId }
        if (idxB != -1) {
            listB.removeAt(idxB)
            _loadedTracksB.value = listB

            val controllers = _controllersB.value.toMutableList()
            val controller = controllers.removeAt(idxB)
            controller.release()
            _controllersB.value = controllers
        }

        if (_selectedTrackIds.value.contains(trackId)) {
            _selectedTrackIds.value = _selectedTrackIds.value - trackId
        }
    }

    fun togglePlayback() {
        val nextPlaying = !_isPlaying.value
        _isPlaying.value = nextPlaying
        _controllersA.value.forEach { if (nextPlaying) it.play() else it.pause() }
        _controllersB.value.forEach { if (nextPlaying) it.play() else it.pause() }
    }

    fun setVolume(vol: Float) {
        _audioVolume.value = vol
        updateAllVolumes()
    }

    fun setCrossfaderValue(value: Int) {
        _crossfader.value = value
        updateAllVolumes()
        syncClient.updateCrossfader(value, _roomCode.value)
    }

    private fun updateAllVolumes() {
        val vol = _audioVolume.value
        val cf = _crossfader.value
        val crossA = if (cf < 0) 1f else (100f - cf) / 100f
        val crossB = if (cf > 0) 1f else (100f + cf) / 100f

        val map = _trackVolumes.value
        _loadedTracksA.value.forEachIndexed { idx, track ->
            val trackMult = map[track.id] ?: 1.0f
            _controllersA.value.getOrNull(idx)?.setVolume((vol * crossA * trackMult).coerceIn(0f, 1f))
        }
        _loadedTracksB.value.forEachIndexed { idx, track ->
            val trackMult = map[track.id] ?: 1.0f
            _controllersB.value.getOrNull(idx)?.setVolume((vol * crossB * trackMult).coerceIn(0f, 1f))
        }
    }

    fun adjustTrackVolume(deck: String, delta: Float) {
        val currentMap = _trackVolumes.value.toMutableMap()
        val targetedIds = _selectedTrackIds.value
        if (targetedIds.isNotEmpty()) {
            targetedIds.forEach { id ->
                val prev = currentMap[id] ?: 1.0f
                currentMap[id] = (prev + delta).coerceIn(0.15f, 3.5f)
            }
        } else {
            val targets = if (deck == "A") _loadedTracksA.value else _loadedTracksB.value
            targets.forEach { tr ->
                val prev = currentMap[tr.id] ?: 1.0f
                currentMap[tr.id] = (prev + delta).coerceIn(0.15f, 3.5f)
            }
        }
        _trackVolumes.value = currentMap
        updateAllVolumes()
    }

    fun adjustPitch(deck: String, percent: Float) {
        adjustPitchOnly(deck, percent / 100f)
    }

    fun adjustBpmSpeed(deck: String, delta: Float) {
        val targetedIds = _selectedTrackIds.value
        if (targetedIds.isNotEmpty()) {
            targetedIds.forEach { selId ->
                val cA = _controllersA.value.firstOrNull { it.loadedTrack?.id == selId }
                val cB = _controllersB.value.firstOrNull { it.loadedTrack?.id == selId }
                val targetCtrl = cA ?: cB
                targetCtrl?.let { ctrl ->
                    val newRate = (1f + (ctrl.pitch / 100f) + delta).coerceIn(0.5f, 2.0f)
                    ctrl.setPlaybackRate(newRate)
                }
            }
        } else {
            val list = if (deck == "A") _controllersA.value else _controllersB.value
            list.forEach { ctrl ->
                val newRate = (1f + (ctrl.pitch / 100f) + delta).coerceIn(0.5f, 2.0f)
                ctrl.setPlaybackRate(newRate)
            }
        }
    }

    fun adjustPitchOnly(deck: String, delta: Float) {
        val targetedIds = _selectedTrackIds.value
        if (targetedIds.isNotEmpty()) {
            targetedIds.forEach { selId ->
                val cA = _controllersA.value.firstOrNull { it.loadedTrack?.id == selId }
                val cB = _controllersB.value.firstOrNull { it.loadedTrack?.id == selId }
                val targetCtrl = cA ?: cB
                targetCtrl?.let { ctrl ->
                    val newPitch = (1f + (ctrl.pitch / 100f) + delta).coerceIn(0.5f, 2.0f)
                    ctrl.setPitchOnly(newPitch)
                }
            }
        } else {
            val list = if (deck == "A") _controllersA.value else _controllersB.value
            list.forEach { ctrl ->
                val newPitch = (1f + (ctrl.pitch / 100f) + delta).coerceIn(0.5f, 2.0f)
                ctrl.setPitchOnly(newPitch)
            }
        }
    }


    fun adjustOverlap(delta: Float, deckZone: String, playheadAngle: Float, platterRotationAngle: Float) {
        val targetedIds = _selectedTrackIds.value
        val currentMap = _trackOverlaps.value.toMutableMap()

        val list = if (deckZone == "A") _loadedTracksA.value else _loadedTracksB.value
        if (list.isEmpty()) return

        val numClips = list.size
        val arcSpan = (2 * Math.PI) / numClips

        // Normalize playheadAngle into 0..2PI relative to platter
        var normalizedPlayhead = (playheadAngle - platterRotationAngle + Math.PI / 2) % (2 * Math.PI)
        if (normalizedPlayhead < 0) normalizedPlayhead += 2 * Math.PI

        val currentPlayingIdx = (normalizedPlayhead / arcSpan).toInt().coerceIn(0, numClips - 1)

        val targetTrackId = if (targetedIds.isNotEmpty()) {
            val selIdx = list.indexOfFirst { targetedIds.contains(it.id) }
            if (selIdx != -1) {
                // If it is playing, we adjust its own overlap (end of song)
                // If it is NOT playing, we adjust the previous song's overlap (beginning of song)
                if (selIdx == currentPlayingIdx) {
                    list[selIdx].id
                } else {
                    val prevIdx = if (selIdx - 1 < 0) numClips - 1 else selIdx - 1
                    list[prevIdx].id
                }
            } else null
        } else {
            // No selection: adjust currently playing track's overlap
            list[currentPlayingIdx].id
        }

        if (targetTrackId != null) {
            val prev = currentMap[targetTrackId] ?: 0f
            // Adjust overlap (allow up to half the arc span)
            currentMap[targetTrackId] = (prev + delta).coerceIn(0f, (arcSpan / 2).toFloat())
            _trackOverlaps.value = currentMap
        }
    }

    fun adjustCrossfaderDelta(delta: Float) {
        val newCross = (_crossfader.value + delta).toInt().coerceIn(-100, 100)
        _crossfader.value = newCross
        updateAllVolumes()
    }

    fun seekTrack(deck: String, deltaSeconds: Float) {
        val targetedIds = _selectedTrackIds.value
        if (targetedIds.isNotEmpty()) {
            targetedIds.forEach { selId ->
                val cA = _controllersA.value.firstOrNull { it.loadedTrack?.id == selId }
                val cB = _controllersB.value.firstOrNull { it.loadedTrack?.id == selId }
                val targetCtrl = cA ?: cB
                targetCtrl?.let { ctrl ->
                    val newTime = (ctrl.currentTime.value + deltaSeconds).coerceAtLeast(0f)
                    ctrl.seekTo(newTime)
                }
            }
        } else {
            val list = if (deck == "A") _controllersA.value else _controllersB.value
            list.forEach { ctrl ->
                val newTime = (ctrl.currentTime.value + deltaSeconds).coerceAtLeast(0f)
                ctrl.seekTo(newTime)
            }
        }
    }

    fun scrubPlayhead(deck: String, deltaAngleRad: Float) {
        val targetedIds = _selectedTrackIds.value
        val deltaSeconds = (deltaAngleRad / (2 * Math.PI).toFloat()) * 10f
        if (targetedIds.isNotEmpty()) {
            targetedIds.forEach { targetedId ->
                val idxA = _loadedTracksA.value.indexOfFirst { it.id == targetedId }
                if (idxA != -1) {
                    val controller = _controllersA.value.getOrNull(idxA)
                    controller?.let {
                        val newTime = (it.currentTime.value + deltaSeconds).coerceAtLeast(0f)
                        it.seekTo(newTime)
                    }
                }
                val idxB = _loadedTracksB.value.indexOfFirst { it.id == targetedId }
                if (idxB != -1) {
                    val controller = _controllersB.value.getOrNull(idxB)
                    controller?.let {
                        val newTime = (it.currentTime.value + deltaSeconds).coerceAtLeast(0f)
                        it.seekTo(newTime)
                    }
                }
            }
        } else {
            val controllers = if (deck == "A") _controllersA.value else _controllersB.value
            controllers.forEach { controller ->
                val newTime = (controller.currentTime.value + deltaSeconds).coerceAtLeast(0f)
                controller.seekTo(newTime)
            }
        }
    }

    fun autoSync() {
        val baseBpm = _loadedTracksA.value.firstNotNullOfOrNull { it.bpm }
        if (baseBpm == null) {
            _feedbackMsg.value = "Nothing to sync to — no loaded track has a measured tempo"
            return
        }
        // Tracks without a measured tempo are left alone rather than warped by a
        // guessed ratio.
        (_controllersA.value + _controllersB.value).forEach { controller ->
            val bpm = controller.loadedTrack?.bpm ?: return@forEach
            if (bpm > 0) controller.setPlaybackRate((baseBpm / bpm).toFloat().coerceIn(0.5f, 2.0f))
        }
        _feedbackMsg.value = "Synced to ${String.format("%.1f", baseBpm)} BPM"
    }

    fun startAutoDiscovery() {
        _feedbackMsg.value = "Broadcasting LAN search..."
        syncClient.startLanDiscovery()
    }

    fun connectToRoom(wsUrl: String, code: String) {
        _roomCode.value = code
        syncClient.connect(wsUrl)
    }

    /**
     * Registers a local audio file and measures it.
     *
     * Replaces `addTrackManually`, which took a BPM and key as arguments and
     * synthesised a chord progression from whether the Camelot code ended in
     * "A", and `analyzeTrack`, which produced a whole track record from a search
     * string with no audio involved at all. Neither could produce a real
     * measurement, because neither ever opened the file.
     */
    fun importTrack(uri: Uri, title: String? = null, artist: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = uri.lastPathSegment ?: "Unknown"
            val parsed = com.hereliesaz.sirmatchalot.data.LinkParser.parseFileName(fileName)
            val track = Track(
                title = title ?: parsed.first,
                artist = artist ?: parsed.second,
                sourceUri = uri.toString(),
            )
            trackDao.insertTrack(track)
            _feedbackMsg.value = "Analysing ${track.title}..."
            analyseTrack(track)
        }
    }

    /**
     * Decodes [track] and stores what was measured from the audio.
     *
     * A track whose tempo or key could not be determined keeps nulls for those
     * columns; it is not given a plausible-looking substitute.
     */
    suspend fun analyseTrack(track: Track) {
        val source = track.sourceUri ?: return
        try {
            val decoded = AudioDecoder.decode(getApplication(), Uri.parse(source))
            if (decoded == null) {
                _feedbackMsg.value = "Could not decode ${track.title}"
                return
            }

            val analysis = analyzer.analyse(decoded.pcm)
            val peaksFile = java.io.File(getApplication<Application>().filesDir, "peaks/${track.id}.peaks")
            peaksFile.parentFile?.mkdirs()
            peaksFile.writeBytes(analysis.peaks.toByteArray())

            val sampleRate = decoded.pcm.sampleRate.toDouble()
            trackDao.updateTrack(
                track.copy(
                    bpm = analysis.bpm,
                    tempoConfidence = analysis.tempoConfidence,
                    firstBeatSeconds = analysis.beatGrid?.firstBeatSeconds,
                    downbeatOffset = analysis.beatGrid?.downbeatOffset ?: 0,
                    camelotKey = analysis.camelotKey,
                    keyName = analysis.keyName,
                    keyConfidence = analysis.keyConfidence,
                    energyLevel = analysis.energyLevel,
                    durationMs = (analysis.durationSeconds * 1000).toLong(),
                    sampleRate = decoded.pcm.sampleRate,
                    trimStartMs = (decoded.trimmedStartFrames / sampleRate * 1000).toLong(),
                    trimEndMs = ((decoded.trimmedStartFrames + decoded.pcm.frameCount) / sampleRate * 1000).toLong(),
                    peaksPath = peaksFile.absolutePath,
                    analysisVersion = Track.CURRENT_ANALYSIS_VERSION,
                ),
            )

            _feedbackMsg.value = buildString {
                append(track.title)
                append(": ")
                append(analysis.bpm?.let { String.format("%.1f BPM", it) } ?: "tempo not found")
                append(", ")
                append(analysis.camelotKey ?: "key not found")
            }
        } catch (e: Exception) {
            _feedbackMsg.value = "Analysis failed for ${track.title}: ${e.message}"
        }
    }

    /** Re-measures every track whose stored analysis predates the current analyser. */
    fun analysePending() {
        viewModelScope.launch(Dispatchers.IO) {
            _tracks.value.filterNot { it.isAnalysed }.forEach { analyseTrack(it) }
        }
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.deleteTrack(track)
            removeTrackFromDecks(track.id)
        }
    }

    override fun onServerDiscovered(serverIp: String, wsUrl: String) {
        _feedbackMsg.value = "Server found at $serverIp"
        connectToRoom(wsUrl, "ROOM")
    }

    override fun onConnected() {
        _isWsConnected.value = true
        _feedbackMsg.value = "Linked to Sync Server!"
        syncClient.joinRoom(_roomCode.value, "all", "Android Device")
    }

    override fun onDisconnected() {
        _isWsConnected.value = false
        _feedbackMsg.value = "Sync Disconnected"
    }

    override fun onRoomStateReceived(json: JSONObject) {
        viewModelScope.launch(Dispatchers.Main) {
            if (json.has("isPlaying")) {
                val syncPlaying = json.getBoolean("isPlaying")
                if (syncPlaying != _isPlaying.value) {
                    _isPlaying.value = syncPlaying
                    _controllersA.value.forEach { if (syncPlaying) it.play() else it.pause() }
                    _controllersB.value.forEach { if (syncPlaying) it.play() else it.pause() }
                }
            }
            if (json.has("crossfader")) {
                _crossfader.value = json.getInt("crossfader")
                updateAllVolumes()
            }
        }
    }

    override fun onKaossMoveEvent(x: Float, y: Float, padId: Int) {
        // The XY pad drove a standalone synthesiser that was never in the
        // music's signal path. Until it is re-implemented as a real effect, a
        // remote move has nothing to apply.
    }

    override fun onSamplerTriggerEvent(padId: Int) {
        // Remote pads trigger the real sampler rather than a synthesised tone.
        audioEngine.sampler.trigger(padId)
    }

    override fun onAutoSyncEvent() {
        autoSync()
    }

    override fun onLoadTrackEvent(deck: String, trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val track = trackDao.getTrackById(trackId)
            track?.let {
                viewModelScope.launch(Dispatchers.Main) {
                    if (deck == "A") addTrackToDeckA(it) else addTrackToDeckB(it)
                }
            }
        }
    }

    override fun onSeekEvent(deck: String, time: Float) {
        if (deck == "A") {
            _controllersA.value.forEach { it.seekTo(time) }
        } else {
            _controllersB.value.forEach { it.seekTo(time) }
        }
    }

    override fun onNudgeEvent(deck: String, direction: String) {
        val controllers = if (deck == "A") _controllersA.value else _controllersB.value
        val offset = if (direction == "forward") 0.05f else -0.05f
        controllers.forEach { it.seekTo(it.currentTime.value + offset) }
    }

    override fun onCleared() {
        super.onCleared()
        _controllersA.value.forEach { it.release() }
        _controllersB.value.forEach { it.release() }
        audioEngine.release()
        syncClient.disconnect()
    }
}
