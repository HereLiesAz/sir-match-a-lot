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
     * Loads [track] onto a deck, decoding it if necessary, beat-syncs it to
     * whatever is already playing, and republishes the platter layout.
     *
     * This is the only route onto a deck. There used to be a second one — a
     * `DeckController` wrapping an ExoPlayer per clip — which meant a track
     * could be "on Deck A" in two incompatible senses at once: as a clip on the
     * engine's timeline, and as an independent player with its own clock that
     * the mixer, crossfader, EQ and scratch could not touch.
     */
    fun loadOntoDeck(track: Track, deck: PlatterGeometry.Deck) {
        val onDeck = if (deck == PlatterGeometry.Deck.A) _loadedTracksA else _loadedTracksB
        if (onDeck.value.any { it.id == track.id }) return

        viewModelScope.launch(Dispatchers.IO) {
            val source = track.sourceUri
            if (source == null) {
                _feedbackMsg.value = "${track.title} has no audio file"
                return@launch
            }
            val pcm = decoded.getOrPut(track.id) {
                val raw = AudioDecoder.decode(getApplication(), Uri.parse(source))?.pcm ?: run {
                    _feedbackMsg.value = "Could not decode ${track.title}"
                    return@launch
                }
                // Convert to the engine's rate here, once, with a filter good
                // enough to be inaudible. The alternative is the render loop's
                // 4-point spline doing it on every sample forever, at rate
                // 0.919 for a 44.1 kHz file on a 48 kHz device.
                if (raw.sampleRate != audioEngine.output.sampleRate) {
                    _feedbackMsg.value =
                        "Converting ${track.title} to ${audioEngine.output.sampleRate} Hz..."
                }
                raw.resampledTo(audioEngine.output.sampleRate)
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
            // The first track dropped starts the mix; later ones join whatever
            // the transport is already doing.
            if (!_isPlaying.value && audioEngine.deckA.clips.size + audioEngine.deckB.clips.size == 1) {
                _isPlaying.value = true
                audioEngine.deckA.playing = true
                audioEngine.deckB.playing = true
            }
            engineDeck.playing = _isPlaying.value
            onDeck.value = onDeck.value + track
            republishPlatter()
            alignOnDrop(track, deck)
            syncClient.triggerLoadTrack(if (deck == PlatterGeometry.Deck.A) "A" else "B", track.id, _roomCode.value)
        }
    }

    /**
     * Beat-matches a freshly dropped track against whatever is on the other deck.
     *
     * A track with no measured tempo is left at its own speed and said so, rather
     * than being warped by a ratio derived from a tempo nobody measured.
     */
    private fun alignOnDrop(track: Track, deck: PlatterGeometry.Deck) {
        val other = if (deck == PlatterGeometry.Deck.A) _loadedTracksB.value else _loadedTracksA.value
        val reference = other.firstOrNull { it.bpm != null }
        if (track.bpm == null) {
            _feedbackMsg.value = "${track.title} has not been analysed yet"
            return
        }
        if (reference == null) return

        val alignment = BeatSync.align(track, reference)
        if (alignment == null) {
            _feedbackMsg.value = "${track.title} will not beat-match ${reference.title}"
            return
        }
        audioEngine.applyAlignment(
            if (deck == PlatterGeometry.Deck.A) "A" else "B",
            alignment.tempoRatio,
            alignment.phaseOffsetSeconds,
        )
        _feedbackMsg.value =
            "Matched ${track.title} to ${String.format("%.1f", reference.bpm)} BPM"
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
        _loadedTracksA.value = _loadedTracksA.value.filterNot { it.id in selected }
        _loadedTracksB.value = _loadedTracksB.value.filterNot { it.id in selected }
        _selectedTrackIds.value = emptySet()
        republishPlatter()
    }

    /** Empties both decks — the clips and the record of what is on them. */
    fun clearDecks() {
        audioEngine.deckA.clips = emptyList()
        audioEngine.deckB.clips = emptyList()
        _loadedTracksA.value = emptyList()
        _loadedTracksB.value = emptyList()
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
        clearDecks()
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

    // UI Mixer controls
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    // Seeded from the mixer rather than from a separate literal, so the slider
    // starts where the audio actually is.
    private val _audioVolume = MutableStateFlow(audioEngine.mixer.masterGain)
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
        val time = (if (deck == "A") _cuesA.value else _cuesB.value)[index - 1] ?: return
        deckNamed(deck).seekToSeconds(time.toDouble())
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



    /** Library entry point for Deck A. */
    fun addTrackToDeckA(track: Track) = loadOntoDeck(track, PlatterGeometry.Deck.A)

    /** Library entry point for Deck B. */
    fun addTrackToDeckB(track: Track) = loadOntoDeck(track, PlatterGeometry.Deck.B)

    /** Takes [trackId] off whichever deck holds it. */
    fun removeTrackFromDecks(trackId: String) {
        _loadedTracksA.value = _loadedTracksA.value.filterNot { it.id == trackId }
        _loadedTracksB.value = _loadedTracksB.value.filterNot { it.id == trackId }
        audioEngine.deckA.clips = audioEngine.deckA.clips.filterNot { it.id == trackId }
        audioEngine.deckB.clips = audioEngine.deckB.clips.filterNot { it.id == trackId }
        _selectedTrackIds.value = _selectedTrackIds.value - trackId
        republishPlatter()
    }

    fun togglePlayback() {
        val nextPlaying = !_isPlaying.value
        _isPlaying.value = nextPlaying
        audioEngine.deckA.playing = nextPlaying
        audioEngine.deckB.playing = nextPlaying
    }

    /**
     * Positions the crossfader, on the UI's -100..100 scale.
     *
     * The mixer takes 0..1 and applies an **equal-power** law, so a sweep holds
     * its perceived loudness across the middle. The previous arrangement set two
     * linear gains on two independent players, which dips about 3 dB at centre —
     * audible as a lurch on every transition.
     */
    fun setCrossfaderValue(value: Int) {
        applyCrossfade(value)
        syncClient.updateCrossfader(_crossfader.value, _roomCode.value)
    }

    /**
     * Positions the crossfader without telling the server.
     *
     * Separate from [setCrossfaderValue] so applying a position that *came from*
     * the server does not echo straight back to it.
     */
    private fun applyCrossfade(value: Int) {
        val clamped = value.coerceIn(-100, 100)
        _crossfader.value = clamped
        audioEngine.mixer.crossfade = (clamped + 100) / 200f
    }

    fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        _audioVolume.value = clamped
        audioEngine.mixer.masterGain = clamped
    }

    fun adjustCrossfaderDelta(delta: Float) {
        setCrossfaderValue(_crossfader.value + delta.toInt())
    }

    /**
     * Moves a deck's playhead by [deltaSeconds].
     *
     * There is one playhead per deck, not one per clip, because the deck is a
     * single circular timeline — which is what lets a scratch stay continuous
     * through zero rate instead of being a series of seeks.
     */
    fun seekTrack(deck: String, deltaSeconds: Float) {
        deckNamed(deck).nudgeSeconds(deltaSeconds.toDouble())
    }

    /** Scrubs a deck by a platter rotation, where a full turn is the whole timeline. */
    fun scrubPlayhead(deck: String, deltaAngleRad: Float) {
        val engineDeck = deckNamed(deck)
        engineDeck.nudgeSeconds(deltaAngleRad / (2 * Math.PI) * engineDeck.cycleSeconds)
    }

    private fun deckNamed(deck: String) =
        if (deck == "A") audioEngine.deckA else audioEngine.deckB

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
                if (syncPlaying != _isPlaying.value) togglePlayback()
            }
            if (json.has("crossfader")) {
                applyCrossfade(json.getInt("crossfader"))
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
        syncToDeckA()
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
        deckNamed(deck).seekToSeconds(time.toDouble())
    }

    override fun onNudgeEvent(deck: String, direction: String) {
        seekTrack(deck, if (direction == "forward") 0.05f else -0.05f)
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
        syncClient.disconnect()
    }
}
