package com.hereliesaz.sirmatchalot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import android.net.Uri
import com.hereliesaz.sirmatchalot.analysis.TrackAnalyzer
import com.hereliesaz.sirmatchalot.audio.AudioDecoder
import com.hereliesaz.sirmatchalot.audio.AudioEngine
import com.hereliesaz.sirmatchalot.audio.AudioTrackOutput
import com.hereliesaz.sirmatchalot.audio.Clip
import com.hereliesaz.sirmatchalot.dsp.PeakEnvelope
import com.hereliesaz.sirmatchalot.ui.platter.PlatterGeometry
import com.hereliesaz.sirmatchalot.ui.platter.PlatterState
import com.hereliesaz.sirmatchalot.data.AnalysisQueue
import com.hereliesaz.sirmatchalot.data.AppDatabase
import com.hereliesaz.sirmatchalot.data.PlaylistParser
import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.domain.BeatSnap
import com.hereliesaz.sirmatchalot.domain.BeatSync
import com.hereliesaz.sirmatchalot.domain.measuredBeatGrid
import com.hereliesaz.sirmatchalot.domain.DeckCapacity
import com.hereliesaz.sirmatchalot.domain.HarmonicEngine
import com.hereliesaz.sirmatchalot.domain.MixCommand
import com.hereliesaz.sirmatchalot.domain.MixDeck
import com.hereliesaz.sirmatchalot.domain.MixDirector
import com.hereliesaz.sirmatchalot.domain.MixPlan
import com.hereliesaz.sirmatchalot.domain.MixPlanner
import com.hereliesaz.sirmatchalot.domain.MixMatch
import com.hereliesaz.sirmatchalot.domain.RankedTrack
import com.hereliesaz.sirmatchalot.domain.LoopHarvest
import com.hereliesaz.sirmatchalot.domain.TrackStructure
import com.hereliesaz.sirmatchalot.domain.TransitionRecord
import com.hereliesaz.sirmatchalot.domain.TransitionStyle
import com.hereliesaz.sirmatchalot.domain.TransitionTaste
import com.hereliesaz.sirmatchalot.audio.PcmBuffer
import com.hereliesaz.sirmatchalot.dsp.PointOfInterest
import com.hereliesaz.sirmatchalot.ui.platter.PlatterMarker
import com.hereliesaz.sirmatchalot.sync.SessionLink
import com.hereliesaz.sirmatchalot.sync.SyncClient
import com.hereliesaz.sirmatchalot.sync.SyncRole
import com.hereliesaz.sirmatchalot.sync.SyncServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import org.json.JSONObject

private const val MAX_FOLDER_IMPORT = 5_000

/** Stretch limits. Beyond these WSOLA stops sounding like the music it started as. */
private const val MIN_CLIP_SCALE = 0.25
private const val MAX_CLIP_SCALE = 4.0

class SirMatchALotViewModel(application: Application) : AndroidViewModel(application), SyncClient.SyncListener {

    private val db = AppDatabase.getDatabase(application)
    private val trackDao = db.trackDao()


    private val settingsStore = com.hereliesaz.sirmatchalot.data.SettingsStore.forContext(application)

    /**
     * What the user has chosen about memory, power and rate.
     *
     * Read before the engine is built, because the engine's sample rate is one
     * of them and it is fixed for the life of an [AudioEngine].
     */
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<com.hereliesaz.sirmatchalot.data.EngineSettings> = _settings

    /**
     * The real-time mixing engine.
     *
     * Runs at whatever rate the settings ask for, which defaults to the device's
     * native mixer rate so AudioFlinger is not resampling every block on the way
     * out. This replaces the previous arrangement of one ExoPlayer per clip,
     * which had no mixing stage and so could not crossfade, EQ, scratch, or play
     * in reverse.
     *
     * A `var` because sample rate is not something an engine can be talked into
     * changing: every buffer on every deck, every pad, the mixer's scratch
     * space, the meter's filter coefficients and the sampler's capture ceiling
     * are all sized or tuned for one rate. Changing it builds a new engine and
     * clears the decks, which [rebuildEngine] says out loud rather than doing
     * quietly.
     */
    private var engine = AudioEngine(
        AudioTrackOutput.forDevice(application, _settings.value.sampleRate),
    )

    val audioEngine: AudioEngine get() = engine

    /**
     * Bumped whenever the engine is replaced.
     *
     * Anything holding a piece of the engine across recompositions — the sampler
     * grid holds `audioEngine.sampler` — keys on this so it picks up the new one
     * rather than driving a released engine's pads.
     */
    private val _engineGeneration = MutableStateFlow(0)
    val engineGeneration: StateFlow<Int> = _engineGeneration

    private val _platterState = MutableStateFlow(PlatterState())
    val platterState: StateFlow<PlatterState> = _platterState

    /**
     * What the app is doing right now.
     *
     * Everything slow here used to happen silently unless you were on the one
     * tab that reported it: analysis on the Library screen, loop harvesting on
     * the Sampler screen, and loading a track — which decodes a whole file,
     * converts its rate, then stretches and shifts it to match the session —
     * nowhere at all.
     */
    private val work = BackgroundWork()

    /**
     * Local copies of imported audio, so the app owns what it plays.
     *
     * A `content://` source is a reference: a cloud provider re-fetches it over
     * the network on every read, a grant can lapse, and a file can move. A copy
     * of the encoded file answers all three, and costs about a megabyte a
     * minute rather than the ten a decoded one would.
     */
    private val audioCache = com.hereliesaz.sirmatchalot.data.AudioFileCache.forContext(application)

    /**
     * The URI to decode [track] from, making a local copy first if there is not
     * one already.
     *
     * Falls back to the original on any failure, so a copy that cannot be made
     * costs the benefit and never the track.
     */
    private suspend fun playableSource(
        track: Track,
        source: String,
        onStage: ((String) -> Unit)? = null,
    ): Uri {
        val original = Uri.parse(source)
        val existing = audioCache.localFile(track.id)
        if (existing != null) {
            audioCache.touch(track.id)
            return Uri.fromFile(existing)
        }
        if (!_settings.value.localCopies.isEnabled) return original

        onStage?.invoke("copying locally")
        val copied = audioCache.store(
            context = getApplication(),
            uri = original,
            trackId = track.id,
            extension = com.hereliesaz.sirmatchalot.data.AudioFileCache
                .extensionOf(getApplication(), original),
        ) ?: return original

        // Recorded on the row so the copy is found again without a directory
        // scan, and so deleting a track can delete its audio too.
        runCatching { trackDao.updateTrack(track.copy(cachedPath = copied.absolutePath)) }
        audioCache.trim(_settings.value.localCopies.ceiling(), keep = pinnedTrackIds())
        return Uri.fromFile(copied)
    }

    /** Bytes of local audio held, and the ceiling, for the settings screen. */
    fun localCopyUsage(): Pair<Long, Long> =
        audioCache.heldBytes() to _settings.value.localCopies.ceiling()

    fun localCopyCount(): Int = audioCache.count()

    /** Deletes every local copy, keeping the library itself. */
    fun clearLocalCopies() {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = audioCache.clear()
            _tracks.value.filter { it.cachedPath != null }.forEach {
                runCatching { trackDao.updateTrack(it.copy(cachedPath = null)) }
            }
            _feedbackMsg.value =
                if (removed == 0) "No local copies to remove" else "Removed $removed local copies"
        }
    }

    /**
     * Clips asked for and not ready yet, for the platter to draw where they will
     * land.
     *
     * Separate from [work] because the app bar's indicator and the platter
     * answer different questions. The indicator is for work you are not
     * watching; this is for the ring you just dropped something onto and are
     * staring at. A track load is the one operation that is both.
     */
    private val _pendingClips = MutableStateFlow<List<com.hereliesaz.sirmatchalot.ui.platter.PendingClip>>(emptyList())

    private fun beginPending(
        track: Track,
        deck: PlatterGeometry.Deck,
        atFraction: Float?,
        stage: String,
    ) {
        val entry = com.hereliesaz.sirmatchalot.ui.platter.PendingClip(
            id = track.id,
            deck = deck,
            fraction = atFraction ?: 0f,
            title = track.title,
            stage = stage,
        )
        _pendingClips.value = _pendingClips.value.filterNot { it.id == track.id } + entry
    }

    /** Moves a pending clip on to its next stage, leaving it where it is. */
    private fun updatePending(trackId: String, stage: String) {
        _pendingClips.value = _pendingClips.value.map {
            if (it.id == trackId) it.copy(stage = stage) else it
        }
    }

    private fun endPending(trackId: String) {
        _pendingClips.value = _pendingClips.value.filterNot { it.id == trackId }
    }

    /**
     * In-flight work, including the background service's.
     *
     * The service runs in its own right and survives this ViewModel, so its
     * progress is folded in from [AnalysisProgressBus] rather than registered:
     * a run started before the app was reopened still has to show up.
     */
    val activeWork: StateFlow<List<WorkItem>> =
        combine(
            work.items,
            com.hereliesaz.sirmatchalot.analysis.AnalysisProgressBus.state,
        ) { items, analysis ->
            if (analysis.total <= 0) {
                items
            } else {
                items + WorkItem(
                    id = BackgroundWork.SERVICE_ANALYSIS,
                    label = if (analysis.paused) "Analysis paused" else "Analysing library",
                    detail = "${analysis.done + 1} of ${analysis.total}" +
                        if (analysis.current.isNotEmpty()) " — ${analysis.current}" else "",
                    progress = analysis.fraction,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Decoded audio per track id, so a clip is decoded once and reused.
     *
     * Bounded. The plain map this replaces was never emptied, so every track
     * ever loaded stayed in memory as full PCM — about 58 MB per five-minute
     * stereo track — until the ViewModel died.
     */
    private val decoded = com.hereliesaz.sirmatchalot.audio.DecodedCache(
        maxBytes = budgetBytesFor(_settings.value.memoryBudget),
    )
    /**
     * The track that set the session's tempo and key.
     *
     * The first thing put on the platter sets the tone, and everything after
     * conforms to it: stretched to its BPM and shifted to its key. Without a
     * fixed reference, "match the other deck" means whatever happens to be
     * loaded at the time, so the same two tracks align differently depending on
     * the order they went on — and a circle whose clips are at different tempos
     * has no coherent bar grid to snap to.
     *
     * Cleared with the decks.
     */
    private val _reference = MutableStateFlow<Track?>(null)
    val reference: StateFlow<Track?> = _reference

    private val peaksCache = mutableMapOf<String, PeakEnvelope>()
    private val energyCache = mutableMapOf<String, com.hereliesaz.sirmatchalot.dsp.EnergyCurve>()

    /**
     * Whether a screen is actually in front of someone.
     *
     * Set from the composition's lifecycle. The publishing loop below used to run
     * unconditionally for the ViewModel's entire life: sixty-two wake-ups a
     * second, reading the engine and rebuilding a state object, whether or not
     * anything was playing and whether or not the app was even on screen. On a
     * phone in a pocket that is pure loss, and it ran for as long as the process
     * did.
     */
    private val _uiActive = MutableStateFlow(false)

    /** Called by the UI as it comes to the foreground and leaves it. */
    fun setUiActive(active: Boolean) {
        _uiActive.value = active
    }

    init {
        engine.idleShutdown = _settings.value.idleShutdown
        engine.onReverseThreshold = { _feedbackMsg.value = "I am Satan, Lord of Darkness." }
        engine.start()
        // Publish the engine's playhead and metered level for the platter. The
        // playhead comes from the audio graph, not from a wall-clock animation,
        // so what is drawn is where playback actually is.
        //
        // Only while something is looking at it, and only as often as the chosen
        // refresh rate: nothing here is worth a wake-up if the result cannot be
        // seen. `MutableStateFlow` conflates equal values, so a paused platter
        // publishes once and then costs nothing downstream until it changes.
        viewModelScope.launch {
            while (true) {
                if (!_uiActive.value) {
                    delay(BACKGROUND_POLL_MILLIS)
                    continue
                }
                delay(_settings.value.visualRefresh.frameMillis)
                val deck = engine.deckA.takeIf { it.cycleFrames > 0 } ?: engine.deckB
                _platterState.value = _platterState.value.copy(
                    pending = _pendingClips.value,
                    playheadFraction = deck.cyclePosition(),
                    outputLevel = engine.mixer.level.peak,
                    // Sounding, not merely "transport running". The platter uses
                    // this to decide whether to animate, and a deck left marked
                    // playing with nothing on it — the state clearing the decks
                    // leaves behind — would otherwise hold the frame clock open
                    // for an image that never changes.
                    isPlaying = engine.deckA.isSounding || engine.deckB.isSounding,
                    bands = com.hereliesaz.sirmatchalot.ui.platter.SpectrumBands(
                        low = engine.spectrum.low,
                        mid = engine.spectrum.mid,
                        high = engine.spectrum.high,
                    ),
                )
            }
        }
    }

    // --- Settings ---

    /**
     * Applies a change to the settings, saves it, and does whatever it implies.
     *
     * Everything except sample rate takes effect immediately. Sample rate
     * rebuilds the engine, because nothing that is already decoded is at the new
     * rate.
     */
    fun updateSettings(
        transform: (com.hereliesaz.sirmatchalot.data.EngineSettings) ->
        com.hereliesaz.sirmatchalot.data.EngineSettings,
    ) {
        val current = _settings.value
        val next = transform(current)
        if (next == current) return

        settingsStore.save(next)
        _settings.value = next

        if (next.memoryBudget != current.memoryBudget) {
            decoded.setBudget(budgetBytesFor(next.memoryBudget), pinnedTrackIds())
        }
        if (next.localCopies != current.localCopies) {
            viewModelScope.launch(Dispatchers.IO) {
                audioCache.trim(next.localCopies.ceiling(), keep = pinnedTrackIds())
            }
        }
        engine.idleShutdown = next.idleShutdown
        if (next.requiresEngineRebuild(current)) rebuildEngine(next)
    }

    /**
     * Replaces the engine so it can run at a new sample rate.
     *
     * The decks are cleared and every decoded buffer dropped, and that is not a
     * shortcut: a buffer decoded for a 48 kHz engine plays a fifth flat and a
     * fifth slow on a 32 kHz one, and the beat grid, the snapping arithmetic and
     * every clip's start frame are all counted in frames of the old rate. The
     * honest options were to re-render everything loaded or to start clean, and
     * starting clean is the one that cannot be subtly wrong.
     */
    private fun rebuildEngine(settings: com.hereliesaz.sirmatchalot.data.EngineSettings) {
        stopAutomatchicMix()
        harvestJob?.cancel()

        val previous = engine
        previous.deckA.clips = emptyList()
        previous.deckB.clips = emptyList()
        previous.sampler.clearAll()
        previous.release()

        decoded.clear()
        peaksCache.clear()
        energyCache.clear()
        poiCache.clear()
        loopCache.clear()
        clipTitles.clear()

        val next = AudioEngine(
            AudioTrackOutput.forDevice(getApplication(), settings.sampleRate),
        )
        next.idleShutdown = settings.idleShutdown
        next.onReverseThreshold = { _feedbackMsg.value = "I am Satan, Lord of Darkness." }
        // Carry the mixer positions across; the rate changed, not the mix.
        next.mixer.masterGain = _audioVolume.value
        next.mixer.crossfade = (_crossfader.value + 100) / 200f
        next.start()
        engine = next

        _loadedTracksA.value = emptyList()
        _loadedTracksB.value = emptyList()
        _selectedTrackIds.value = emptySet()
        _reference.value = null
        _isPlaying.value = false
        _platterState.value = PlatterState()
        _engineGeneration.value++

        _feedbackMsg.value =
            "Engine now at ${next.output.sampleRate} Hz — decks cleared, reload to hear it"
    }

    /** The decoded-audio ceiling for [budget], from this device's actual heap. */
    private fun budgetBytesFor(
        budget: com.hereliesaz.sirmatchalot.data.MemoryBudget,
    ): Long = com.hereliesaz.sirmatchalot.audio.DecodedCache.budgetFor(
        heapBytes = Runtime.getRuntime().maxMemory(),
        divisor = budget.heapDivisor,
    )

    /** Decoded audio held right now, and the ceiling, for the settings screen. */
    fun memoryUsage(): Pair<Long, Long> = decoded.heldBytes to decoded.maxBytes

    /**
     * Loads [track] onto a deck, decoding it if necessary, beat-syncs it to
     * whatever is already playing, and republishes the platter layout.
     *
     * This is the only route onto a deck. There used to be a second one — a
     * `DeckController` wrapping an ExoPlayer per clip — which meant a track
     * could be "on Deck A" in two incompatible senses at once: as a clip on the
     * engine's timeline, and as an independent player with its own clock that
     * the mixer, crossfader, EQ and scratch could not touch.
     *
     * @param startSilent load the audio but neither start the deck nor beat-match
     *   it. Used when a [MixDirector] is driving: it decides when the track
     *   enters and applies the alignment its plan already computed, so a load
     *   that started playback or aligned on its own would fight it.
     */
    fun loadOntoDeck(
        track: Track,
        deck: PlatterGeometry.Deck,
        startSilent: Boolean = false,
        atFraction: Float? = null,
    ) {
        val onDeck = if (deck == PlatterGeometry.Deck.A) _loadedTracksA else _loadedTracksB
        if (onDeck.value.any { it.id == track.id }) return

        viewModelScope.launch(Dispatchers.IO) {
            val source = track.sourceUri
            if (source == null) {
                _feedbackMsg.value = "${track.title} has no audio file"
                return@launch
            }

            // One mono downmix, shared by everything that wants one.
            //
            // Peaks, the energy curve and vocal detection each used to call
            // `toMonoFloat()` for themselves — three whole-track FloatArrays,
            // four bytes a frame, allocated one after another at the exact
            // moment the decoded track and its rate-converted copy are both
            // still live. On a five-minute track at 48 kHz that is 57 MB apiece,
            // churned through a heap that has just been asked for its largest
            // allocations of the session.
            var monoDownmix: FloatArray? = null

            try {
                work.track(BackgroundWork.loadId(track.id), "Loading ${track.title}") { progress ->
                    // The same words in both places, so the ring and the app bar
                    // never disagree about what is happening.
                    fun stage(text: String) {
                        progress.detail(text)
                        updatePending(track.id, text)
                    }
                    beginPending(track, deck, atFraction, "reading")

                    val pcm = decoded[track.id] ?: run {
                        stage("decoding")
                        // Ask the container how big this is before committing to
                        // holding it. Finding out from the allocator instead means
                        // an OutOfMemoryError thrown while the decoder holds a whole
                        // track's accumulation buffers — which is a crash, where
                        // this is a sentence.
                        val probe = AudioDecoder.probe(getApplication(), Uri.parse(source))
                        if (probe != null && probe.durationSeconds > 0.0) {
                            val estimate = probe.decodedBytes(engine.output.sampleRate)
                            if (!decoded.canAdmit(estimate, pinnedTrackIds())) {
                                _feedbackMsg.value = tooLargeMessage(track, estimate)
                                return@track
                            }
                        }

                        val playable = playableSource(track, source) { stage(it) }
                        stage("decoding")
                        val outcome = AudioDecoder.decodeDetailed(getApplication(), playable)
                        val raw = when (outcome) {
                            is com.hereliesaz.sirmatchalot.audio.DecodeOutcome.Success -> outcome.audio.pcm
                            is com.hereliesaz.sirmatchalot.audio.DecodeOutcome.Failure -> {
                                _feedbackMsg.value = decodeFailureMessage(track, outcome)
                                return@track
                            }
                        }
                        // Convert to the engine's rate here, once, with a filter good
                        // enough to be inaudible. The alternative is the render loop's
                        // 4-point spline doing it on every sample forever, at rate
                        // 0.919 for a 44.1 kHz file on a 48 kHz device.
                        if (raw.sampleRate != engine.output.sampleRate) {
                            stage("converting ${raw.sampleRate} Hz to ${engine.output.sampleRate} Hz")
                            _feedbackMsg.value =
                                "Converting ${track.title} to ${engine.output.sampleRate} Hz..."
                        }
                        val converted = raw.resampledTo(engine.output.sampleRate)
                        decoded.put(track.id, converted, pinnedTrackIds())
                        if (decoded.overBudget) {
                            _feedbackMsg.value =
                                "Low on memory — unload a track, or lower the engine rate in Settings"
                        }
                        converted
                    }

                    stage("measuring")

                    fun mono(): FloatArray = monoDownmix ?: pcm.toMonoFloat().also { monoDownmix = it }

                    peaksCache.getOrPut(track.id) {
                        track.peaksPath
                            ?.let { path -> runCatching { PeakEnvelope.fromByteArray(java.io.File(path).readBytes()) }.getOrNull() }
                            ?: PeakEnvelope.compute(mono())
                    }
                    // Recomputing the curve is cheap next to the decode that just
                    // happened, so a track analysed before energyPath was written still
                    // gets coloured rather than falling back to neutral forever.
                    energyCache.getOrPut(track.id) {
                        track.energyPath
                            ?.let { path ->
                                runCatching {
                                    com.hereliesaz.sirmatchalot.dsp.EnergyCurve.fromByteArray(java.io.File(path).readBytes())
                                }.getOrNull()
                            }
                            ?: com.hereliesaz.sirmatchalot.dsp.EnergyCurve.compute(mono(), pcm.sampleRate)
                    }

                    // Landmarks come from the energy curve that was just cached, so
                    // marking the drops costs no decode and no second analysis pass.
                    poiCache.getOrPut(track.id) {
                        val curve = energyCache[track.id]
                        if (curve == null) {
                            emptyList()
                        } else {
                            // Landmarks are enrichment on a track that is already
                            // decoded, conformed and ready to play. Losing them
                            // must cost the drops and not the track — this pass
                            // sits inside a `try` that catches OutOfMemoryError
                            // and nothing else, so anything else thrown here used
                            // to unwind the load and take the process with it.
                            runCatching {
                                val grid = track.measuredBeatGrid()
                                // Measured here, from the audio that is already in
                                // hand, rather than persisted: three bands is three
                                // times the energy curve on disk for every track,
                                // and the decode this would save has just happened
                                // anyway. Landmarks are only ever needed while a
                                // track is loaded.
                                val bands = com.hereliesaz.sirmatchalot.dsp.BandEnergyCurve
                                    .compute(mono(), pcm.sampleRate)
                                val structural = com.hereliesaz.sirmatchalot.dsp.StructureFinder()
                                    .findPointsOfInterest(curve, grid, bands = bands)
                                // Vocal entry needs the audio rather than the energy curve
                                // — it is a pitch measurement, not an amplitude one — so it
                                // runs here where the decoded buffer is still in hand.
                                val vocal = com.hereliesaz.sirmatchalot.dsp.VocalDetector()
                                    .findVocalEntry(mono(), pcm.sampleRate)
                                if (vocal == null) structural else structural + vocal
                            }.getOrElse {
                                _feedbackMsg.value =
                                    "${track.title} loaded, but its structure could not be read"
                                emptyList()
                            }
                        }
                    }

                    // The first track on the platter sets the session's tempo and key;
                    // everything after is rendered to match before it is ever heard.
                    // Conforming the audio itself, rather than setting a deck rate, is
                    // what lets several clips share one circle: a deck has one rate, so
                    // rate-matching only ever works when a deck holds one track.
                    stage(conformingDetail(track))
                    val playable = conformToReference(track, pcm)

                    val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
                    val existing = engineDeck.clips
                    // A drop names a point on the circle, and angle is time — so the
                    // fraction dropped at *is* the frame the clip starts on. With an
                    // empty deck there is no circle yet: the first clip defines one, so
                    // it starts at zero and loops however it was dropped.
                    val cycle = engineDeck.cycleFrames
                    val startFrame = when {
                        existing.isEmpty() -> 0
                        atFraction != null && cycle > 0 ->
                            // Snapped, exactly as a drag is. A drop that landed off the
                            // grid while a drag of the same clip snapped onto it would
                            // be two different answers to the same question.
                            BeatSnap.snapFrame(
                                frame = (atFraction.coerceIn(0f, 1f) * cycle).toInt().coerceIn(0, cycle),
                                framesPerBeat = sessionFramesPerBeat(),
                                phaseFrames = sessionBeatPhaseFrames(),
                            ).coerceIn(0, cycle)
                        else -> existing.maxOfOrNull { it.endFrame } ?: 0
                    }
                    engineDeck.clips = evictForCapacity(engineDeck, deck, playable) + Clip(
                        id = track.id,
                        buffer = playable,
                        startFrame = startFrame,
                        loop = existing.isEmpty(),
                    )
                    if (!startSilent) {
                        // The first track dropped starts the mix; later ones join whatever
                        // the transport is already doing.
                        if (!_isPlaying.value && engine.deckA.clips.size + engine.deckB.clips.size == 1) {
                            _isPlaying.value = true
                            engine.deckA.playing = true
                            engine.deckB.playing = true
                        }
                        engineDeck.playing = _isPlaying.value
                        if (_isPlaying.value) engine.wake()
                    }
                    onDeck.value = onDeck.value + track
                    republishPlatter()
                    if (!startSilent) reportConformed(track)
                    syncClient.triggerLoadTrack(if (deck == PlatterGeometry.Deck.A) "A" else "B", track.id, _roomCode.value)
                }
            } catch (e: OutOfMemoryError) {
                // Caught deliberately, and only here. Everything large is
                // reachable from the caches this drops, so releasing them really
                // does give the heap back — and the alternative is the process
                // dying with a track half-loaded, which helps nobody diagnose
                // anything. What it must not do is pretend to have worked.
                onOutOfMemory(track)
            } finally {
                // In the same `finally` as everything else this coroutine has to
                // let go of. A pending clip left on the ring after its load died
                // is a clip that never arrives and never stops promising to.
                endPending(track.id)
                // The downmix is the largest thing here that nothing else keeps.
                // Dropping the reference before the coroutine's frame is
                // collected matters when the next load starts immediately, which
                // is exactly what "Shuffle Crate" and the mix director both do.
                monoDownmix = null
            }
        }
    }

    /**
     * Recovers from running out of heap, and says what would stop it happening.
     *
     * The advice is specific because the fix is: engine rate is the one setting
     * that changes how many bytes a minute of audio costs, and nobody would
     * guess that from a stack trace.
     */
    private fun onOutOfMemory(track: Track) {
        decoded.trim(pinnedTrackIds())
        peaksCache.clear()
        energyCache.clear()
        poiCache.clear()
        loopCache.clear()
        System.gc()
        _feedbackMsg.value =
            "Ran out of memory loading ${track.title} — clear a deck, " +
                "or choose a lower engine sample rate in Settings"
    }

    /**
     * Says why a track would not decode, and what to do about it.
     *
     * Every one of these used to be "Could not decode X". The most common cause
     * is not a decoding problem at all — it is a file the app has lost
     * permission to read, which needs re-importing, not re-encoding.
     */
    private fun decodeFailureMessage(
        track: Track,
        failure: com.hereliesaz.sirmatchalot.audio.DecodeOutcome.Failure,
    ): String = when (failure.reason) {
        com.hereliesaz.sirmatchalot.audio.DecodeOutcome.Reason.NO_PERMISSION ->
            "${track.title} — no longer allowed to read this file. " +
                "Import it again, or import its folder, to restore access"
        com.hereliesaz.sirmatchalot.audio.DecodeOutcome.Reason.UNREADABLE ->
            "${track.title} — the file could not be opened. It may have been moved or deleted"
        com.hereliesaz.sirmatchalot.audio.DecodeOutcome.Reason.NO_AUDIO_TRACK ->
            "${track.title} has no audio track in it"
        com.hereliesaz.sirmatchalot.audio.DecodeOutcome.Reason.NO_CODEC ->
            "${track.title} — this device has no decoder for ${failure.detail ?: "that format"}"
        com.hereliesaz.sirmatchalot.audio.DecodeOutcome.Reason.EMPTY ->
            "${track.title} decoded to nothing — the file may be truncated"
    }

    /** Says a track will not fit, in megabytes rather than in bytes. */
    private fun tooLargeMessage(track: Track, estimateBytes: Long): String {
        val megabytes = estimateBytes / (1024.0 * 1024.0)
        return "${track.title} needs ${String.format("%.0f", megabytes)} MB at " +
            "${engine.output.sampleRate} Hz and will not fit — clear a deck, " +
            "or choose a lower engine sample rate in Settings"
    }

    /**
     * Renders [pcm] to the session reference's tempo and key.
     *
     * Stretched so its BPM matches — pitch held, so the stretch does not undo
     * the key match — and then shifted so its key matches. Both are rendered
     * into the buffer rather than applied as deck settings, because a deck has
     * one rate and one playhead: rate-matching can only ever align a deck that
     * holds a single track, and the whole point of the circle is that it holds
     * several.
     *
     * The first track to arrive becomes the reference and is returned untouched.
     * A track with no measured tempo or key is also returned untouched — there
     * is nothing to conform it by, and inventing a ratio is what the original
     * analysis did.
     */
    private fun conformToReference(
        track: Track,
        pcm: com.hereliesaz.sirmatchalot.audio.PcmBuffer,
    ): com.hereliesaz.sirmatchalot.audio.PcmBuffer {
        val existing = _reference.value
        if (existing == null) {
            _reference.value = track
            return pcm
        }
        if (existing.id == track.id) return pcm

        val referenceBpm = existing.bpm
        val trackBpm = track.bpm
        var result = pcm

        if (referenceBpm != null && trackBpm != null && trackBpm > 0.0 && referenceBpm > 0.0) {
            // To play a 140 BPM track at 120, it has to become longer.
            val stretch = (trackBpm / referenceBpm).coerceIn(MIN_CLIP_SCALE, MAX_CLIP_SCALE)
            if (abs(stretch - 1.0) > 1e-3) result = result.timeStretched(stretch)
        }

        val alignment = BeatSync.align(track, existing)
        val semitones = alignment?.semitoneShift ?: 0
        if (semitones != 0) result = result.pitchShifted(semitones.toDouble())

        return result
    }

    /**
     * What conforming is about to do, while it is doing it.
     *
     * Said before rather than after, because it is the slowest part of a load —
     * a WSOLA pass and a phase-vocoder pass over the whole track — and "45
     * seconds of silence" and "45 seconds of silence, then a message about
     * stretching" are the same experience while you are waiting.
     */
    private fun conformingDetail(track: Track): String {
        val existing = _reference.value
        if (existing == null || existing.id == track.id) return "setting the session tempo and key"
        val stretching = existing.bpm != null && track.bpm != null &&
            abs(track.bpm!! - existing.bpm!!) > 0.1
        val shifting = (BeatSync.align(track, existing)?.semitoneShift ?: 0) != 0
        return when {
            stretching && shifting ->
                "stretching to ${String.format("%.1f", existing.bpm)} BPM and shifting to ${existing.keyLabel()}"
            stretching -> "stretching to ${String.format("%.1f", existing.bpm)} BPM"
            shifting -> "shifting to ${existing.keyLabel()}"
            else -> "already in step"
        }
    }

    /** Says what conforming did, once the clip is on the deck. */
    private fun reportConformed(track: Track) {
        val existing = _reference.value
        if (existing == null || existing.id == track.id) {
            _feedbackMsg.value = "${track.title} sets the session — " +
                "${track.bpmLabel()} BPM, ${track.keyLabel()}"
            return
        }
        val referenceBpm = existing.bpm
        val trackBpm = track.bpm
        if (trackBpm == null || referenceBpm == null) {
            _feedbackMsg.value = "${track.title} has no measured tempo — added as it is"
            return
        }
        val semitones = BeatSync.align(track, existing)?.semitoneShift ?: 0
        _feedbackMsg.value = buildString {
            append("${track.title} conformed to ${String.format("%.1f", referenceBpm)} BPM")
            if (semitones != 0) append(", ${String.format("%+d", semitones)} semitones to ${existing.keyLabel()}")
        }
    }

    /**
     * Drops the oldest clips from [engineDeck] until [incoming] fits the circle.
     *
     * @return the clips that should remain, for the caller to add to.
     */
    private fun evictForCapacity(
        engineDeck: com.hereliesaz.sirmatchalot.audio.Deck,
        deck: PlatterGeometry.Deck,
        incoming: com.hereliesaz.sirmatchalot.audio.PcmBuffer,
    ): List<Clip> {
        val framesPerBeat = sessionFramesPerBeat()
        if (framesPerBeat <= 0.0) return engineDeck.clips

        val sounding = clipUnderPlayhead(engineDeck)
        val entries = engineDeck.clips.mapIndexed { index, clip ->
            DeckCapacity.Entry(
                id = clip.id,
                beats = (clip.frameCount / framesPerBeat).toInt().coerceAtLeast(1),
                addedOrder = index,
            )
        }
        val incomingBeats = (incoming.frameCount / framesPerBeat).toInt().coerceAtLeast(1)
        val protectedIds = setOfNotNull(sounding)

        val evictions = DeckCapacity.evictionsFor(
            existing = entries,
            incoming = incomingBeats,
            protectedIds = protectedIds,
        )
        if (evictions.isEmpty()) return engineDeck.clips

        _feedbackMsg.value =
            "Platter full — dropped ${evictions.size} to make room"
        for (id in evictions) {
            _loadedTracksA.value = _loadedTracksA.value.filterNot { it.id == id }
            _loadedTracksB.value = _loadedTracksB.value.filterNot { it.id == id }
            peaksCache.remove(id)
            energyCache.remove(id)
        }
        decoded.trim(pinnedTrackIds())
        return engineDeck.clips.filterNot { it.id in evictions }
    }

    /** The clip the playhead is currently inside, which must never be evicted. */
    private fun clipUnderPlayhead(engineDeck: com.hereliesaz.sirmatchalot.audio.Deck): String? {
        val position = engineDeck.playhead
        return engineDeck.clips.firstOrNull { clip ->
            clip.loop || (position >= clip.startFrame && position < clip.endFrame)
        }?.id
    }

    /** Rebuilds the platter layout from the engine's clips. */
    private fun republishPlatter() {
        fun inputsFor(deck: PlatterGeometry.Deck): List<PlatterState.ClipLayoutInput> {
            val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
            return engineDeck.clips.map { clip ->
                PlatterState.ClipLayoutInput(
                    id = clip.id,
                    // A pad bank on the circle has no library row behind it, so
                    // its name comes from the pad rather than from `_tracks`.
                    title = _tracks.value.firstOrNull { it.id == clip.id }?.title
                        ?: clipTitles[clip.id]
                        ?: clip.id,
                    startSeconds = clip.startFrame.toDouble() / clip.buffer.sampleRate,
                    durationSeconds = clip.frameCount.toDouble() / clip.buffer.sampleRate,
                    peaks = peaksCache[clip.id] ?: PeakEnvelope.compute(FloatArray(0)),
                    energy = energyCache[clip.id],
                )
            }
        }

        fun cycleSecondsOf(deck: PlatterGeometry.Deck): Double =
            (if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB)
                .cycleSeconds

        val selected = _selectedTrackIds.value
        _platterState.value = _platterState.value.copy(
            beatGridA = beatGridFor(PlatterGeometry.Deck.A),
            beatGridB = beatGridFor(PlatterGeometry.Deck.B),
            affinity = affinityBetweenDecks(),
            deckA = PlatterState.layout(
                clips = inputsFor(PlatterGeometry.Deck.A),
                selectedIds = selected,
                deck = PlatterGeometry.Deck.A,
                cycleSeconds = cycleSecondsOf(PlatterGeometry.Deck.A),
            ),
            deckB = PlatterState.layout(
                clips = inputsFor(PlatterGeometry.Deck.B),
                selectedIds = selected,
                deck = PlatterGeometry.Deck.B,
                cycleSeconds = cycleSecondsOf(PlatterGeometry.Deck.B),
            ),
            markers = buildMarkers(),
        )
    }

    /**
     * Beat and bar lines for a deck's revolution.
     *
     * From the session reference's measured tempo, because every clip is
     * conformed to it on load — so one grid describes the whole platter, and a
     * per-clip grid would draw lines at tempos nothing is playing at.
     */
    private fun beatGridFor(
        deck: PlatterGeometry.Deck,
    ): com.hereliesaz.sirmatchalot.ui.platter.PlatterBeatGrid {
        val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
        val reference = _reference.value
            ?: (_loadedTracksA.value + _loadedTracksB.value).firstOrNull { it.bpm != null }
        return com.hereliesaz.sirmatchalot.ui.platter.PlatterBeatGrid.of(
            bpm = reference?.bpm,
            firstBeatSeconds = reference?.firstBeatSeconds,
            cycleSeconds = engineDeck.cycleSeconds,
            downbeatOffset = reference?.downbeatOffset ?: 0,
        )
    }

    /**
     * How well the two decks complement each other, angle by angle.
     *
     * The overall harmonic and tempo score sets the ceiling; the two energy
     * curves decide where under it each moment of the revolution sits. A pair of
     * tracks scoring 90% still has a minute that fights and a minute that locks,
     * and this is the platter saying which is which — the one view in the app
     * looking at both timelines at once.
     */
    private fun affinityBetweenDecks(): com.hereliesaz.sirmatchalot.ui.platter.PlatterAffinity {
        val trackA = _loadedTracksA.value.firstOrNull()
        val trackB = _loadedTracksB.value.firstOrNull()
        if (trackA == null || trackB == null) {
            // One deck, or none. There is nothing to complement.
            return com.hereliesaz.sirmatchalot.ui.platter.PlatterAffinity.NONE
        }

        val match = HarmonicEngine.compareTracks(trackA, trackB)
        if (!match.isComplete) {
            // Unmeasured. A guessed compatibility would make the ring swell for
            // reasons nothing measured.
            return com.hereliesaz.sirmatchalot.ui.platter.PlatterAffinity.NONE
        }

        return com.hereliesaz.sirmatchalot.ui.platter.PlatterAffinity.of(
            compatibility = match.overallScore / 100f,
            energyA = { fraction -> deckEnergyAt(PlatterGeometry.Deck.A, fraction) },
            energyB = { fraction -> deckEnergyAt(PlatterGeometry.Deck.B, fraction) },
        )
    }

    /**
     * Measured energy on [deck] at a fraction of its revolution, or null where
     * the deck has nothing sounding there.
     *
     * Null rather than zero: silence between clips is not a quiet passage, and
     * treating it as one would have two decks "agreeing" through a gap.
     */
    private fun deckEnergyAt(deck: PlatterGeometry.Deck, fraction: Float): Float? {
        val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
        val cycle = engineDeck.cycleSeconds
        if (cycle <= 0.0) return null
        val seconds = fraction.toDouble() * cycle

        for (clip in engineDeck.clips) {
            val rate = clip.buffer.sampleRate
            val start = clip.startFrame.toDouble() / rate
            val length = clip.frameCount.toDouble() / rate
            val within = if (clip.loop) {
                if (length <= 0.0) continue
                var offset = (seconds - start) % length
                if (offset < 0) offset += length
                offset
            } else {
                val offset = seconds - start
                if (offset < 0.0 || offset > length) continue
                offset
            }
            val curve = energyCache[clip.id] ?: continue
            return curve.at(within).coerceIn(0f, 1f)
        }
        return null
    }

    /**
     * Recomputes only the markers, leaving the layout alone.
     *
     * Setting a cue does not move a clip, and rebuilding the whole layout to
     * show one tick would rebuild every waveform's peaks for nothing.
     */
    private fun republishMarkers() {
        _platterState.value = _platterState.value.copy(markers = buildMarkers())
    }

    /**
     * Cue points and structural landmarks, placed on the circle.
     *
     * Angle is time, so both are the same arithmetic: a moment in seconds
     * divided by the deck's revolution. Cues are given in deck time already;
     * a landmark is in *track* time, so it is offset by where its clip starts.
     */
    private fun buildMarkers(): List<PlatterMarker> {
        val markers = ArrayList<PlatterMarker>()

        for (deck in listOf(PlatterGeometry.Deck.A, PlatterGeometry.Deck.B)) {
            val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
            val cycle = engineDeck.cycleSeconds
            if (cycle <= 0.0) continue

            val cues = if (deck == PlatterGeometry.Deck.A) _cuesA.value else _cuesB.value
            cues.forEachIndexed { index, seconds ->
                if (seconds == null) return@forEachIndexed
                markers.add(
                    PlatterMarker(
                        deck = deck,
                        fraction = PlatterGeometry.fractionForTime(seconds.toDouble(), cycle),
                        kind = PlatterMarker.Kind.CUE,
                        label = "Cue ${index + 1}",
                    ),
                )
            }

            for (clip in engineDeck.clips) {
                val points = poiCache[clip.id] ?: continue
                val clipStart = clip.startFrame.toDouble() / clip.buffer.sampleRate
                for (point in points) {
                    val kind = when (point.kind) {
                        PointOfInterest.Kind.DROP -> PlatterMarker.Kind.DROP
                        PointOfInterest.Kind.BREAKDOWN -> PlatterMarker.Kind.BREAKDOWN
                        PointOfInterest.Kind.BUILD -> PlatterMarker.Kind.BUILD
                        PointOfInterest.Kind.VOCAL -> PlatterMarker.Kind.VOCAL
                        // A peak is where the waveform is already tallest. A
                        // tick there says nothing the rays do not.
                        PointOfInterest.Kind.PEAK -> continue
                    }
                    markers.add(
                        PlatterMarker(
                            deck = deck,
                            fraction = PlatterGeometry.fractionForTime(clipStart + point.timeSeconds, cycle),
                            kind = kind,
                            label = point.kind.label,
                        ),
                    )
                }
            }
        }
        return markers
    }

    /** Structural landmarks per clip id, measured once from the energy curve. */
    private val poiCache = HashMap<String, List<PointOfInterest>>()

    /**
     * Loopable sections per track id, found once from self-similarity.
     *
     * Filled opportunistically: finding loops needs decoded audio and a measured
     * grid, and both are already in hand at exactly two moments — when the pads
     * are auto-filled, and when the mix director asks a track for its shape
     * while that track is loaded. Anywhere else it would mean decoding a file to
     * answer a question nobody had yet asked.
     */
    private val loopCache = HashMap<String, List<com.hereliesaz.sirmatchalot.dsp.LoopCandidate>>()

    /** Loops in [track], measured now if the audio is to hand, or empty. */
    private fun loopsFor(track: Track): List<com.hereliesaz.sirmatchalot.dsp.LoopCandidate> {
        loopCache[track.id]?.let { return it }
        val grid = track.measuredBeatGrid() ?: return emptyList()
        val pcm = decoded[track.id] ?: return emptyList()
        // Called from the mix director while a set is running, so a failure here
        // costs a transition its loop roll rather than stopping the music.
        val found = runCatching {
            com.hereliesaz.sirmatchalot.dsp.StructureFinder()
                .findLoops(pcm.toMonoFloat(), pcm.sampleRate, grid)
        }.getOrDefault(emptyList())
        loopCache[track.id] = found
        return found
    }

    /**
     * What the mix director knows about a track's shape.
     *
     * Everything here was already measured and cached and simply never reached
     * the thing performing the mix, which is why every transition it made was
     * the same one.
     *
     * The result is scaled into **playback** time. A track loaded onto a deck
     * has been conformed to the session's tempo — physically time-stretched
     * before it is ever heard — so a drop measured ninety seconds into the file
     * arrives somewhere else entirely. Scaling once here means every consumer
     * downstream can treat these numbers as the wall clock, rather than each
     * remembering a ratio.
     */
    private fun structureFor(track: Track): TrackStructure? {
        if (track.durationMs <= 0L || track.bpm == null) return null
        val structure = TrackStructure.of(
            track = track,
            points = poiCache[track.id].orEmpty(),
            loops = loopsFor(track),
            energy = energyCache[track.id],
        )
        val reference = _reference.value?.bpm
        // Conforming multiplies the rate by reference/source, so the played
        // duration is divided by it.
        val ratio = if (reference != null && reference > 0.0 && track.bpm > 0.0) {
            track.bpm / reference
        } else {
            1.0
        }
        return structure.scaledBy(ratio)
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
        val grid = track.measuredBeatGrid()
        if (grid == null) {
            _feedbackMsg.value = "${track.title} has no measured beat grid to find loops on"
            return
        }
        val pcm = decoded[track.id]
        if (pcm == null) {
            _feedbackMsg.value = "${track.title} is not decoded yet"
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
          work.track(BackgroundWork.PADS, "Finding loops in ${track.title}") {
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
    }

    /** Progress of a playlist-wide loop harvest, or null when none is running. */
    private val _harvestProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val harvestProgress: StateFlow<Pair<Int, Int>?> = _harvestProgress

    private var harvestJob: Job? = null

    /**
     * Fills empty pads with loops taken from **every** song in the playlist.
     *
     * The single-track version above is the loop maker for a song; this is the
     * one that was asked for — *"samples loops from the active playlist's
     * songs"*. `LoopHarvest` decides the allocation: every song contributes its
     * best loop before any song contributes its second, because eight loops
     * from one track is a worse pad bank than eight loops from eight tracks.
     *
     * Two passes, deliberately. The first decodes one track at a time, measures
     * it, and **drops it** — a playlist does not fit in memory, and holding
     * candidate audio for every track would be the same problem in a smaller
     * font. Only the tracks the plan actually uses, at most one per free pad,
     * are decoded again to be sliced.
     */
    fun harvestLoopsFromPlaylist() {
        if (harvestJob?.isActive == true) {
            _feedbackMsg.value = "Already harvesting loops"
            return
        }
        val freePads = audioEngine.sampler.pads.filter { it.isEmpty }.map { it.index }
        if (freePads.isEmpty()) {
            _feedbackMsg.value = "Every pad is occupied — clear one first"
            return
        }

        // A track with no measured grid has no bar lines to cut on. Skipped
        // rather than guessed at, and counted so the report can say so.
        val playlist = _tracks.value.filter { it.sourceUri != null }
        val usable = playlist.filter { it.bpm != null && it.firstBeatSeconds != null }
        if (usable.isEmpty()) {
            _feedbackMsg.value =
                if (playlist.isEmpty()) "No tracks with audio to harvest from"
                else "No analysed tracks — run Analyse first, loops need a beat grid"
            return
        }

        harvestJob = viewModelScope.launch(Dispatchers.IO) {
          try {
            val finder = com.hereliesaz.sirmatchalot.dsp.StructureFinder()
            val sources = ArrayList<LoopHarvest.Source>(usable.size)

            usable.forEachIndexed { index, track ->
                ensureActive()
                _harvestProgress.value = (index + 1) to usable.size
                work.begin(
                    id = BackgroundWork.HARVEST,
                    label = "Harvesting loops",
                    detail = "${index + 1} of ${usable.size} — ${track.title}",
                    progress = (index + 1).toFloat() / usable.size,
                )
                val pcm = decodeForHarvest(track) ?: return@forEachIndexed
                val grid = track.measuredBeatGrid() ?: return@forEachIndexed
                val candidates = finder.findLoops(pcm.toMonoFloat(), pcm.sampleRate, grid)
                if (candidates.isNotEmpty()) {
                    sources.add(LoopHarvest.Source(track.id, track.title, candidates))
                }
            }

            val plan = LoopHarvest.plan(sources, freePads)
            if (plan.isEmpty()) {
                _harvestProgress.value = null
                _feedbackMsg.value = "Nothing in the playlist loops cleanly enough"
                return@launch
            }

            // Second pass: only the tracks the plan chose, and only their
            // chosen seconds. At most one decode per free pad.
            var filled = 0
            for (trackId in LoopHarvest.decodeOrder(plan)) {
                ensureActive()
                val track = usable.first { it.id == trackId }
                val pcm = decodeForHarvest(track) ?: continue
                for (assignment in plan.filter { it.trackId == trackId }) {
                    val start = (assignment.candidate.startSeconds * pcm.sampleRate).toInt()
                    val end = (assignment.candidate.endSeconds * pcm.sampleRate).toInt()
                    if (start >= pcm.frameCount || end <= start) continue
                    audioEngine.sampler.pads[assignment.padIndex].load(
                        buffer = pcm.slice(start, end.coerceAtMost(pcm.frameCount)),
                        label = assignment.label,
                        loop = true,
                    )
                    filled++
                }
            }

            _harvestProgress.value = null
            val songs = plan.map { it.trackId }.distinct().size
            _feedbackMsg.value = buildString {
                append("Filled $filled pads from $songs ")
                append(if (songs == 1) "song" else "songs")
                val skipped = playlist.size - usable.size
                if (skipped > 0) append(" — $skipped unanalysed and skipped")
            }
          } finally {
            // Cancelling a harvest, or an early return when nothing loops
            // cleanly, must take the indicator with it. An indicator that
            // outlives its work teaches you to ignore the indicator.
            work.end(BackgroundWork.HARVEST)
          }
        }
    }

    /** The name a clip goes by, for saying what is being worked on. */
    private fun clipTitle(clipId: String): String =
        _tracks.value.firstOrNull { it.id == clipId }?.title
            ?: clipTitles[clipId]
            ?: "clip"

    /** Stops a harvest in progress. */
    fun cancelHarvest() {
        harvestJob?.cancel()
        harvestJob = null
        _harvestProgress.value = null
        _feedbackMsg.value = "Loop harvest stopped"
    }

    /**
     * Decodes [track] for the harvest, at its own sample rate.
     *
     * Not put in [decoded]: the cache is sized for what is on the decks, and
     * pushing a whole playlist through it would evict the clips that are
     * playing. The result is used and dropped within one iteration.
     *
     * Rate conversion is skipped too — a loop's audio is resampled when it is
     * loaded onto a pad if it needs to be, and self-similarity does not care
     * what rate it is measured at.
     */
    private suspend fun decodeForHarvest(track: Track): PcmBuffer? {
        decoded[track.id]?.let { return it }
        val source = track.sourceUri ?: return null
        return runCatching {
            AudioDecoder.decode(getApplication(), playableSource(track, source))?.pcm
        }.getOrNull()
    }

    // --- The pad bank as a deck slot ---

    /**
     * Titles for clips that are not library tracks, so the platter can name
     * them. A pad bank on a deck has no `Track` row behind it.
     */
    private val clipTitles = HashMap<String, String>()

    /**
     * Puts the loaded pads onto [deck], as clips on the circle.
     *
     * Asked for as *"the sampler/looper can occupy a deck slot, showing N loops
     * the way songs are shown"*. Each loaded pad becomes its own clip, so each
     * gets its own arc, its own colour and its own waveform — shown the way a
     * song is shown, because on this platter that is what showing something
     * means. They are laid consecutively so the bank reads as one run.
     *
     * The pads keep their audio: a loop on the circle and the same loop under a
     * finger are the same material, and taking it off the pad to put it on the
     * deck would make placing it cost you the pad.
     */
    fun placePadsOnDeck(deck: PlatterGeometry.Deck) {
        val loaded = audioEngine.sampler.pads.filter { !it.isEmpty }
        if (loaded.isEmpty()) {
            _feedbackMsg.value = "No pads loaded to place"
            return
        }
        val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB

        viewModelScope.launch(Dispatchers.Default) {
          work.track(BackgroundWork.PADS, "Placing loops on Deck ${deckLabel(deck)}") {
            var cursor = engineDeck.clips.maxOfOrNull { it.endFrame } ?: 0
            val added = ArrayList<com.hereliesaz.sirmatchalot.audio.Clip>()
            for (pad in loaded) {
                val buffer = pad.buffer ?: continue
                // The engine renders one rate; a pad recorded or sliced at
                // another has to be converted or it plays at the wrong speed.
                val playable =
                    if (buffer.sampleRate == audioEngine.output.sampleRate) buffer
                    else buffer.resampledTo(audioEngine.output.sampleRate)
                val id = padClipId(pad.index)
                clipTitles[id] = pad.label ?: "Pad ${pad.index + 1}"
                peaksCache[id] = PeakEnvelope.compute(playable.toMonoFloat())
                added.add(
                    com.hereliesaz.sirmatchalot.audio.Clip(
                        id = id,
                        buffer = playable,
                        startFrame = cursor,
                        loop = false,
                    ),
                )
                cursor += playable.frameCount
            }
            if (added.isEmpty()) return@track

            engineDeck.clips = engineDeck.clips.filterNot { it.id.startsWith(PAD_CLIP_PREFIX) } + added
            republishPlatter()
            _feedbackMsg.value = "Placed ${added.size} loops on Deck ${deckLabel(deck)}"
          }
        }
    }

    /** Takes the pad bank back off [deck], leaving the songs alone. */
    fun removePadsFromDeck(deck: PlatterGeometry.Deck) {
        val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
        val before = engineDeck.clips.size
        engineDeck.clips = engineDeck.clips.filterNot { it.id.startsWith(PAD_CLIP_PREFIX) }
        val removed = before - engineDeck.clips.size
        republishPlatter()
        _feedbackMsg.value =
            if (removed == 0) "No pad loops on Deck ${deckLabel(deck)}"
            else "Removed $removed pad loops from Deck ${deckLabel(deck)}"
    }

    private fun deckLabel(deck: PlatterGeometry.Deck) =
        if (deck == PlatterGeometry.Deck.A) "A" else "B"

    private fun padClipId(index: Int) = "$PAD_CLIP_PREFIX$index"

    /** Removes every selected clip from both decks. */
    fun removeSelectedClips() {
        val selected = _selectedTrackIds.value
        if (selected.isEmpty()) return
        audioEngine.deckA.clips = audioEngine.deckA.clips.filterNot { it.id in selected }
        audioEngine.deckB.clips = audioEngine.deckB.clips.filterNot { it.id in selected }
        _loadedTracksA.value = _loadedTracksA.value.filterNot { it.id in selected }
        _loadedTracksB.value = _loadedTracksB.value.filterNot { it.id in selected }
        _selectedTrackIds.value = emptySet()
        decoded.trim(pinnedTrackIds())
        selected.forEach { peaksCache.remove(it); energyCache.remove(it) }
        republishPlatter()
    }

    /**
     * Moves a clip already on a deck to a new point on the circle, and to the
     * other deck if it was dragged across the base radius.
     *
     * Angle is time, so a move around the circle *is* a move in the timeline: the
     * fraction becomes the clip's start frame. The clip keeps its decoded buffer
     * — this is a placement change, not a reload.
     */
    fun moveClip(clipId: String, deck: PlatterGeometry.Deck, fraction: Float) {
        val target = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
        val source = if (deck == PlatterGeometry.Deck.A) audioEngine.deckB else audioEngine.deckA

        val existing = target.clips.firstOrNull { it.id == clipId }
            ?: source.clips.firstOrNull { it.id == clipId }
            ?: return

        val cycle = target.cycleFrames.takeIf { it > 0 } ?: existing.frameCount
        val raw = (fraction.coerceIn(0f, 1f) * cycle).toInt()
        // Land on a beat. A finger on a five-minute revolution is tens of
        // milliseconds out at best, which is audibly off and impossible to
        // correct by eye.
        val startFrame = BeatSnap.snapFrame(
            frame = raw,
            framesPerBeat = sessionFramesPerBeat(),
            phaseFrames = sessionBeatPhaseFrames(),
        )
        if (existing.startFrame == startFrame && target.clips.any { it.id == clipId }) return

        val moved = Clip(
            id = existing.id,
            buffer = existing.buffer,
            startFrame = startFrame,
            gain = existing.gain,
            loop = existing.loop,
        )
        source.clips = source.clips.filterNot { it.id == clipId }
        target.clips = target.clips.filterNot { it.id == clipId } + moved

        // Keep the deck metadata in step, since a clip can cross decks.
        val track = _tracks.value.firstOrNull { it.id == clipId }
        if (track != null) {
            if (deck == PlatterGeometry.Deck.A) {
                _loadedTracksB.value = _loadedTracksB.value.filterNot { it.id == clipId }
                if (_loadedTracksA.value.none { it.id == clipId }) {
                    _loadedTracksA.value = _loadedTracksA.value + track
                }
            } else {
                _loadedTracksA.value = _loadedTracksA.value.filterNot { it.id == clipId }
                if (_loadedTracksB.value.none { it.id == clipId }) {
                    _loadedTracksB.value = _loadedTracksB.value + track
                }
            }
        }
        republishPlatter()
    }

    /**
     * Frames per beat for the whole platter.
     *
     * One grid, not one per deck, because every clip is conformed to the session
     * reference's tempo when it loads. Reading a deck's *own* first track
     * instead — as this did before conforming existed — gives the grid of a
     * tempo nothing on the platter is actually playing at: a 140 BPM track
     * stretched to a 120 BPM session would be snapped to 140 BPM beat lines,
     * which land nowhere near its beats.
     *
     * Falls back to whatever is loaded when no reference has been set, which is
     * the state a session is in before its first track finishes loading.
     */
    private fun sessionFramesPerBeat(): Double {
        val bpm = _reference.value?.bpm
            ?: (_loadedTracksA.value + _loadedTracksB.value).firstNotNullOfOrNull { it.bpm }
        return BeatSnap.framesPerBeat(bpm, audioEngine.output.sampleRate)
    }

    /**
     * Where the platter's grid starts, so beat lines sit on the music rather
     * than on frame zero.
     *
     * The reference track is the one clip that is never stretched, so its
     * measured first beat is the grid's phase, and every conformed clip's beats
     * line up with it by construction.
     */
    private fun sessionBeatPhaseFrames(): Double {
        val first = _reference.value?.firstBeatSeconds
            ?: (_loadedTracksA.value + _loadedTracksB.value)
                .firstOrNull { it.bpm != null }?.firstBeatSeconds
        return (first ?: 0.0) * audioEngine.output.sampleRate
    }

    /**
     * Stretches a clip by [ratio], changing its tempo without moving its pitch.
     *
     * A ratio above 1 makes the clip longer and slower; below 1, shorter and
     * faster. Pitch is held, which is the whole difference between this and
     * changing the deck's rate — stretching a clip to fit a tempo must not move
     * its key, or a harmonic match made a moment ago stops being one.
     *
     * The ratio is snapped so the result is a whole number of beats: the reason
     * to stretch a clip at all is to make it line up, and 3.97 bars does not.
     *
     * Rendered from the pristine decoded buffer rather than from whatever is on
     * the deck, so repeated pinches do not compound the ratio or the smearing
     * WSOLA leaves on transients. Applied once, on release — re-rendering a
     * whole track on every frame of a pinch is not affordable.
     */
    fun scaleClip(clipId: String, deck: PlatterGeometry.Deck, ratio: Double) {
        if (ratio <= 0.0 || abs(ratio - 1.0) < 1e-3) return
        val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
        val existing = engineDeck.clips.firstOrNull { it.id == clipId } ?: return
        val pristine = decoded[clipId] ?: existing.buffer

        val framesPerBeat = sessionFramesPerBeat()
        val snapped = BeatSnap.snapRatio(pristine.frameCount, ratio, framesPerBeat)
            .coerceIn(MIN_CLIP_SCALE, MAX_CLIP_SCALE)

        viewModelScope.launch(Dispatchers.Default) {
            _feedbackMsg.value = "Stretching to ${String.format("%.2fx", snapped)}..."
            val stretched = work.track(
                id = BackgroundWork.stretchId(clipId),
                label = "Stretching ${clipTitle(clipId)}",
                detail = String.format("%.2fx, holding pitch", snapped),
            ) { pristine.timeStretched(snapped) }
            val current = engineDeck.clips.firstOrNull { it.id == clipId } ?: return@launch
            engineDeck.clips = engineDeck.clips.map { clip ->
                if (clip.id != clipId) {
                    clip
                } else {
                    Clip(
                        id = clip.id,
                        buffer = stretched,
                        startFrame = current.startFrame,
                        gain = clip.gain,
                        loop = clip.loop,
                    )
                }
            }
            republishPlatter()
            val track = _tracks.value.firstOrNull { it.id == clipId }
            val newBpm = track?.bpm?.let { it / snapped }
            _feedbackMsg.value = buildString {
                append("Stretched ${String.format("%.2fx", snapped)}")
                if (newBpm != null) append(" — now ${String.format("%.1f", newBpm)} BPM, same key")
            }
        }
    }

    /** Empties both decks — the clips and the record of what is on them. */
    fun clearDecks() {
        audioEngine.deckA.clips = emptyList()
        audioEngine.deckB.clips = emptyList()
        _loadedTracksA.value = emptyList()
        _loadedTracksB.value = emptyList()
        _selectedTrackIds.value = emptySet()
        decoded.trim(pinned = emptySet())
        // A new session gets a new reference; the old one no longer sets a tone
        // for anything.
        _reference.value = null
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

    /**
     * Positions the XY performance filter on the master bus.
     *
     * @param x -1 to 1; centre is bypass, left sweeps a lowpass down, right
     *   sweeps a highpass up.
     * @param y 0 to 1, resonance.
     */
    fun moveFilter(x: Float, y: Float) {
        audioEngine.mixer.filter.enabled = true
        audioEngine.mixer.filter.x = x.coerceIn(-1f, 1f)
        audioEngine.mixer.filter.y = y.coerceIn(0f, 1f)
        _filterPosition.value = x to y
    }

    /** Lifts the pad, letting the filter glide back to neutral. */
    fun releaseFilter() {
        audioEngine.mixer.filter.release()
        _filterPosition.value = null
    }

    /** Current pad position, or null when nothing is holding it. */
    private val _filterPosition = MutableStateFlow<Pair<Float, Float>?>(null)
    val filterPosition: StateFlow<Pair<Float, Float>?> = _filterPosition

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
        /**
         * How well each track mixes after what is loaded, best first.
         *
         * The default, because it is the only ordering that answers the
         * question being asked of the list. With nothing loaded there is
         * nothing to match against and it falls back to import order, which is
         * what [RECENT] is.
         */
        HARMONIC("Match"),
        /** Import order — the order the folder scan found them in. */
        RECENT("Recent"),
        /** Closest tempo to the reference. */
        TEMPO("Tempo"),
        TITLE("Title"),
        ENERGY("Energy"),
    }

    /**
     * Ordered by match, not by import order.
     *
     * The strip on the platter — the list you actually drag tracks from — was
     * fed the raw table in `SELECT * FROM tracks` order, which is insertion
     * order: whatever sequence the folder walk happened to enumerate. Every
     * measurement the app makes about what mixes with what existed, was
     * computed, and changed nothing about the order they were offered in.
     */
    private val _librarySort = MutableStateFlow(LibrarySort.HARMONIC)
    val librarySort: StateFlow<LibrarySort> = _librarySort

    private val _libraryFilter = MutableStateFlow("")
    val libraryFilter: StateFlow<String> = _libraryFilter

    fun setLibrarySort(sort: LibrarySort) { _librarySort.value = sort }

    /**
     * Steps to the next ordering.
     *
     * The platter has room for one control, not five chips — and cycling is
     * enough when the current one is named on the control itself. The Library's
     * full set of chips sets the same state, so changing it in either place
     * changes both: there is one library, and it should not be in two orders
     * depending on which tab is open.
     */
    fun cycleLibrarySort() {
        val entries = LibrarySort.entries
        _librarySort.value = entries[(entries.indexOf(_librarySort.value) + 1) % entries.size]
    }

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
     * What everything else is matched against.
     *
     * The session reference, specifically — the first thing put on the platter —
     * rather than "whatever is on Deck A". Every clip loaded after it is
     * stretched to its tempo and shifted to its key before it is ever heard, so
     * the reference *is* the session's tempo and key. Matching against Deck A's
     * current occupant would score tracks against a clip that has already been
     * conformed to the reference, which is to say against the reference, one
     * rounding error later.
     *
     * Falls back to whatever is loaded during the moment before the first load
     * completes.
     */
    private val matchReference: StateFlow<Track?> =
        combine(_reference, _loadedTracksA, _loadedTracksB) { reference, deckA, deckB ->
            reference ?: deckA.firstOrNull() ?: deckB.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The library as both screens show it: text-filtered, ordered, and each
     * track carrying its own match against what is loaded.
     *
     * One flow, because the platter strip and the library list were previously
     * two different lists in two different orders — the library's sorted and
     * searchable, the platter's the raw table — and only one of them had any
     * relationship to the matching the app spends its analysis pass computing.
     */
    val rankedTracks: StateFlow<List<RankedTrack>> =
        combine(_tracks, _librarySort, _libraryFilter, matchReference) { all, sort, filter, reference ->
            val matching = if (filter.isBlank()) all else all.filter { track ->
                track.title.contains(filter, ignoreCase = true) ||
                    track.artist.contains(filter, ignoreCase = true)
            }
            when (sort) {
                // Ordered by the same number the card shows. Ordering by Camelot
                // distance while labelling with the overall score put 82% above
                // 91% for reasons the screen never gave.
                LibrarySort.HARMONIC -> MixPlanner.byMixScore(matching, reference)
                LibrarySort.RECENT -> MixPlanner.rank(matching, reference)
                LibrarySort.TITLE ->
                    MixPlanner.rank(matching.sortedBy { it.title.lowercase() }, reference)
                // Unmeasured tracks sort last rather than being given a position
                // they have not earned.
                LibrarySort.ENERGY ->
                    MixPlanner.rank(matching.sortedByDescending { it.energyLevel ?: -1 }, reference)
                LibrarySort.TEMPO -> {
                    val referenceBpm = reference?.bpm
                    val ordered = if (referenceBpm == null) matching else matching.sortedBy { track ->
                        track.bpm?.let { kotlin.math.abs(Math.log(it / referenceBpm)) } ?: Double.MAX_VALUE
                    }
                    MixPlanner.rank(ordered, reference)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The same list without the scores, for callers that only need the tracks. */
    val visibleTracks: StateFlow<List<Track>> =
        rankedTracks.map { ranked -> ranked.map { it.track } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Every pair in the library that mixes, best first — the library's
     * "compatible pairings" list.
     *
     * Here rather than in the composition, and off the main thread. It was an
     * all-pairs comparison inside a `remember`, so a 500-track library ran
     * 124,750 comparisons on the frame that opened the Library tab, and a
     * 2,000-track one ran two million. Capped as well: past a few hundred tracks
     * the answer is a list nobody reads, computed at a cost everybody feels.
     */
    val compatiblePairs: StateFlow<List<MixMatch>> =
        _tracks.map { all ->
            withContext(Dispatchers.Default) {
                val usable = all.filter { it.bpm != null && it.camelotKey != null }
                    .take(MAX_TRACKS_PAIRED)
                buildList {
                    for (i in usable.indices) {
                        for (j in i + 1 until usable.size) {
                            val match = HarmonicEngine.compareTracks(usable[i], usable[j])
                            if (match.overallScore >= STRONG_PAIR_SCORE) add(match)
                        }
                    }
                }.sortedByDescending { it.overallScore }.take(MAX_PAIRS_SHOWN)
            }
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    // --- Performing the plan ---

    private val _mixDirector = MutableStateFlow<MixDirector?>(null)

    /** True while the app is playing a planned mix by itself. */
    val isAutoMixing: StateFlow<Boolean> =
        _mixDirector.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _nowPlaying = MutableStateFlow<MixCommand.NowPlaying?>(null)
    val nowPlaying: StateFlow<MixCommand.NowPlaying?> = _nowPlaying

    /** 0..1 through the current transition, for the UI to show a fade in progress. */
    private val _transitionProgress = MutableStateFlow(0f)
    val transitionProgress: StateFlow<Float> = _transitionProgress

    /**
     * How the transition now running is being performed, or null between them.
     *
     * Shown because a set that varies its transitions and describes them all
     * identically still reads as robotic: the work has to be visible to count.
     */
    private val _transitionStyle = MutableStateFlow<TransitionStyle?>(null)
    val transitionStyle: StateFlow<TransitionStyle?> = _transitionStyle

    // --- Learning what this person actually likes ---

    /**
     * The model of the user's taste in transitions, restored from disk.
     *
     * See [TransitionTaste]. It starts as an exact no-op and stays one until it
     * has something to say, so the rules remain the behaviour until they are
     * genuinely worth overriding.
     */
    private val taste = TransitionTaste.decode(settingsStore.loadTaste())

    /** How many transitions have been learned from, and what they taught. */
    private val _tasteSummary = MutableStateFlow(taste.observations to taste.strongestOpinions())
    val tasteSummary: StateFlow<Pair<Int, List<String>>> = _tasteSummary

    /**
     * The transition being performed, held until the listener passes judgement.
     *
     * The label for a transition arrives after it: letting it play out is
     * approval, reaching for the fader is not. So the decision has to outlive the
     * moment it was made.
     */
    private var pendingTransition: TransitionRecord? = null

    /**
     * Learns from how the transition now running was received.
     *
     * @param approved true when it was allowed to finish.
     */
    private fun judgeTransition(approved: Boolean) {
        val record = pendingTransition ?: return
        pendingTransition = null
        taste.learn(record.features(), if (approved) 1.0 else 0.0)
        settingsStore.saveTaste(taste.encode())
        _tasteSummary.value = taste.observations to taste.strongestOpinions()
    }

    /** Forgets everything learned, for someone whose taste has changed or who never asked. */
    fun forgetTransitionTaste() {
        taste.reset()
        pendingTransition = null
        settingsStore.saveTaste(taste.encode())
        _tasteSummary.value = 0 to emptyList()
        _feedbackMsg.value = "Forgot what it had learned about your transitions"
    }

    private var autoMixJob: kotlinx.coroutines.Job? = null

    /**
     * Performs the planned mix — the half of "Automatchic Mix" that did not exist.
     *
     * `MixPlanner` decided *what* to play and computed the corrections for each
     * transition; the plan was then displayed and never performed. `MixDirector`
     * decides *when*, and this applies what it asks for.
     *
     * The director is driven by a wall-clock delta rather than by a deck playhead,
     * because a deck timeline is circular and wraps. The tick interval is not
     * assumed to be exact — the elapsed time between iterations is measured and
     * passed in, so a stalled frame does not desynchronise the set.
     */
    fun startAutomatchicMix() {
        val plan = _mixPlan.value ?: MixPlanner.automatchicMix(_tracks.value).also { _mixPlan.value = it }
        if (plan.steps.isEmpty()) {
            _feedbackMsg.value = "No analysed tracks to mix"
            return
        }
        stopAutomatchicMix()
        clearDecks()

        // Seeded from the clock, so running the same library twice is not the
        // same set twice — which is the whole point of the exercise.
        val director = MixDirector(
            plan = plan,
            structures = { track -> structureFor(track) },
            seed = System.nanoTime(),
            taste = taste,
        )
        _mixDirector.value = director
        _transitionStyle.value = null
        pendingTransition = null
        apply(director.start())

        autoMixJob = viewModelScope.launch {
            var last = System.nanoTime()
            while (!director.finished) {
                delay(50)
                val now = System.nanoTime()
                val delta = (now - last) / 1_000_000_000.0
                last = now
                apply(director.advance(delta))
                _transitionProgress.value = director.transitionProgress.toFloat()
            }
            _mixDirector.value = null
            _transitionProgress.value = 0f
            _feedbackMsg.value = "Mix finished — ${plan.steps.size} tracks"
        }
    }

    /** Stops performing a mix, leaving whatever is loaded where it is. */
    fun stopAutomatchicMix() {
        // Stopping *during* a transition is a verdict on that transition.
        // Stopping between them is just stopping, and teaches nothing — which is
        // why this is guarded rather than applied to whatever ran last.
        if (_transitionProgress.value > 0f) judgeTransition(approved = false)
        pendingTransition = null

        autoMixJob?.cancel()
        autoMixJob = null
        _mixDirector.value = null
        _transitionProgress.value = 0f
        releaseMixControls()
    }

    /** Carries out what the director asked for. */
    private fun apply(commands: List<MixCommand>) {
        for (command in commands) when (command) {
            is MixCommand.Preload -> loadOntoDeck(
                command.track,
                if (command.deck == MixDeck.A) PlatterGeometry.Deck.A else PlatterGeometry.Deck.B,
                startSilent = true,
            )

            is MixCommand.Start -> {
                val name = if (command.deck == MixDeck.A) "A" else "B"
                val engineDeck = deckNamed(name)
                // Enter where the director asked, not from wherever the deck's
                // playhead happened to be left. Zero is the usual answer and was
                // once the only one; a chosen entry is how a track gets brought
                // in on its build rather than on its intro every single time.
                //
                // The playhead counts frames of the *clip's* audio, not of the
                // output — a deck reads its material at its own rate — so the
                // conversion has to come from the clip that was just loaded.
                val clipRate = engineDeck.clips
                    .firstOrNull { it.id == command.track.id }
                    ?.buffer?.sampleRate
                    ?: engineDeck.outputSampleRate
                engineDeck.playhead =
                    (command.entrySeconds * clipRate).coerceAtLeast(0.0)
                engineDeck.playing = true
                _isPlaying.value = true
                engine.wake()

                // Clips are conformed to the session reference at load, so they
                // already share its tempo and key. Applying the planner's
                // alignment on top would correct a difference that has already
                // been rendered away — a track would be stretched twice and
                // shifted twice. Only phase is still worth applying, and only
                // when the clip was never conformed.
                command.alignment?.let { alignment ->
                    if (_reference.value == null) {
                        audioEngine.applyAlignment(name, alignment.tempoRatio, alignment.phaseOffsetSeconds)
                    } else {
                        audioEngine.applyAlignment(name, 1.0, alignment.phaseOffsetSeconds)
                    }
                }
            }

            // Positioned as a fraction, not rounded through 201 integer steps.
            // A sixteen-bar blend at 128 BPM crosses in thirty seconds; through
            // the integer path that is a staircase of 201 treads, and the
            // mixer's own smoothing was covering for it.
            is MixCommand.Crossfade -> applyCrossfade(command.position)

            is MixCommand.SetDeckEq -> deckNamed(deckName(command.deck)).let {
                it.bassBoostDb = command.bassDb
                it.trebleDb = command.trebleDb
            }

            is MixCommand.SetDeckGain -> deckNamed(deckName(command.deck)).gain = command.gain

            is MixCommand.SetDeckRate -> deckNamed(deckName(command.deck)).rate = command.rate

            is MixCommand.TriggerLoop -> triggerMixLoop(command)

            MixCommand.StopMixLoops -> audioEngine.mixSampler.stopAll()

            is MixCommand.Performing -> {
                _transitionStyle.value = command.style
                pendingTransition = command.record
            }

            is MixCommand.Retire -> {
                val name = deckName(command.deck)
                deckNamed(name).playing = false
                removeTrackFromDecks(command.track.id)
                _transitionStyle.value = null
                // It ran to the end and nobody reached for anything. That is the
                // only positive signal available, and it is a real one: a
                // transition somebody disliked does not get to finish.
                judgeTransition(approved = true)
            }

            is MixCommand.NowPlaying -> {
                _nowPlaying.value = command
                _feedbackMsg.value =
                    "${command.index + 1}/${command.total}: ${command.step.track.title}"
            }

            MixCommand.Finished -> {
                _mixDirector.value = null
                _transitionProgress.value = 0f
                releaseMixControls()
            }
        }
    }

    private fun deckName(deck: MixDeck) = if (deck == MixDeck.A) "A" else "B"

    /**
     * Plays one of a track's own loops over the mix.
     *
     * Sliced from what is **on the deck** rather than from the decoded original,
     * because the two are not the same audio: a clip is conformed to the
     * session's tempo and key before it is heard, and the loop's boundaries were
     * measured against that same conformed timeline. Slicing the original would
     * put a quote of the track slightly out of tune and out of time with the
     * track it is quoting, which is the one thing it must not be.
     */
    private fun triggerMixLoop(command: MixCommand.TriggerLoop) {
        val source = listOf(audioEngine.deckA, audioEngine.deckB)
            .firstNotNullOfOrNull { deck -> deck.clips.firstOrNull { it.id == command.trackId } }
            ?.buffer
            ?: decoded[command.trackId]
            ?: return

        val start = (command.loop.startSeconds * source.sampleRate).toInt()
        val end = ((command.loop.startSeconds + command.loop.durationSeconds) * source.sampleRate).toInt()
        if (start < 0 || end <= start || start >= source.frameCount) return

        val pad = audioEngine.mixSampler.pads.firstOrNull { !it.isPlaying }
            ?: audioEngine.mixSampler.pads.first()
        pad.gain = command.gain
        pad.load(source.slice(start, end.coerceAtMost(source.frameCount)), label = null, loop = command.repeat)
        pad.trigger()
        engine.wake()
    }

    /**
     * Hands the decks back exactly as they were found.
     *
     * A set can end at any moment — the plan runs out, or the user stops it mid
     * transition — and every one of those moments is inside some script. Leaving
     * a deck reversed, shelved eighteen dB down, or at half gain would look like
     * the app breaking rather than a set ending.
     */
    private fun releaseMixControls() {
        audioEngine.mixSampler.stopAll()
        for (deck in listOf(audioEngine.deckA, audioEngine.deckB)) {
            deck.rate = 1.0
            deck.gain = 1f
            deck.bassBoostDb = 0.0
            deck.trebleDb = 0.0
        }
        _transitionStyle.value = null
    }

    /**
     * Whether tempo changes keep the original pitch.
     *
     * On a turntable, speeding a record up raises its pitch, and the scratch
     * gestures depend on exactly that. But when *beat-matching*, dragging the key
     * along with the tempo is what stops two tracks in compatible keys from
     * staying compatible — which defeats the point of harmonic mixing. With
     * keylock on, a sync corrects the rate's pitch drag by pre-rendering the
     * opposite shift into the clip.
     */
    private val _keylock = MutableStateFlow(true)
    val keylock: StateFlow<Boolean> = _keylock

    fun setKeylock(enabled: Boolean) { _keylock.value = enabled }

    /**
     * Beat-syncs and key-matches Deck B to Deck A.
     *
     * A deck is one circular timeline read by one playhead, so it has one tempo
     * and one phase — the alignment is computed against the first track on each
     * deck that has a measured tempo. The previous version looped over every
     * track on Deck B and called `applyAlignment("B", ...)` inside the loop, so
     * every iteration but the last was immediately overwritten and the reported
     * count of tracks "synced" described work that had not survived.
     *
     * Pitch correction is rendered rather than merely computed, which is what
     * "Harmonize" has always claimed to do. Two contributions are combined into
     * one pass over the audio:
     *
     * - **the harmonic interval**, from `BeatAlignment.semitoneShift`, moving
     *   Deck B's key to Deck A's;
     * - **cancelling the rate's own pitch drag**, `-12*log2(tempoRatio)`, when
     *   keylock is on.
     *
     * A ratio of 1.0 and a shift of 0 render nothing, so a sync between tracks
     * already in step costs no audio processing at all.
     */
    fun syncToDeckA() {
        val reference = _loadedTracksA.value.firstOrNull { it.bpm != null }
        if (reference == null) {
            _feedbackMsg.value = "Deck A has no measured tempo to sync to"
            return
        }
        val target = _loadedTracksB.value.firstOrNull { it.bpm != null }
        if (target == null) {
            _feedbackMsg.value = "Deck B has nothing with a measured tempo to sync"
            return
        }
        val alignment = BeatSync.align(target, reference)
        if (alignment == null) {
            _feedbackMsg.value = "${target.title} will not beat-match ${reference.title}"
            return
        }

        // Deck A is the reference, so it returns to its own tempo.
        audioEngine.applyAlignment("A", 1.0, 0.0)
        audioEngine.applyAlignment("B", alignment.tempoRatio, alignment.phaseOffsetSeconds)

        val driftCorrection =
            if (_keylock.value) -12.0 * ln(alignment.tempoRatio) / ln(2.0) else 0.0
        val totalShift = alignment.semitoneShift + driftCorrection

        _feedbackMsg.value = buildString {
            append("Synced ${target.title} to ${String.format("%.1f", reference.bpm)} BPM")
            if (alignment.isHalfOrDoubleTime) append(" (half/double time)")
            if (abs(totalShift) >= 0.01) append(", shifting ${String.format("%+.2f", totalShift)} semitones...")
        }

        if (abs(totalShift) < 0.01) return
        renderPitchShift(PlatterGeometry.Deck.B, totalShift, alignment.semitoneShift)
    }

    /**
     * Re-renders every clip on [deck] pitch-shifted by [semitones].
     *
     * Always shifts from the pristine decoded buffer, never from whatever is
     * currently loaded, so repeated syncs do not compound the interval or the
     * artefacts.
     */
    private fun renderPitchShift(
        deck: PlatterGeometry.Deck,
        semitones: Double,
        harmonicInterval: Int,
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            val engineDeck = if (deck == PlatterGeometry.Deck.A) audioEngine.deckA else audioEngine.deckB
            val shifted = work.track(
                id = BackgroundWork.SYNC,
                label = "Harmonizing Deck ${deckLabel(deck)}",
                detail = String.format("%+.2f semitones", semitones),
            ) { engineDeck.clips.map { clip ->
                val pristine = decoded[clip.id] ?: clip.buffer
                Clip(
                    id = clip.id,
                    buffer = pristine.pitchShifted(semitones),
                    startFrame = clip.startFrame,
                    gain = clip.gain,
                    loop = clip.loop,
                )
            } }
            if (shifted.isEmpty()) return@launch
            // Replaced wholesale; Deck.clips is volatile and keeps the playhead
            // inside the new cycle itself.
            engineDeck.clips = shifted
            republishPlatter()
            _feedbackMsg.value = buildString {
                append("Shifted ${String.format("%+.2f", semitones)} semitones")
                if (harmonicInterval != 0) {
                    append(" (${String.format("%+d", harmonicInterval)} to match key")
                    if (_keylock.value) append(", rest is keylock")
                    append(")")
                } else if (_keylock.value) {
                    append(" to hold pitch against the tempo change")
                }
            }
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

    /**
     * What this device is for in the room.
     *
     * "Each showing a different screen" is half of the multi-device request, and
     * the half that was missing: `joinRoom` sent a role and nothing read it. The
     * role is held here so the UI can follow it, and re-announced on connect so
     * the host knows what this device claims to be.
     */
    private val _role = MutableStateFlow(SyncRole.ALL)
    val role: StateFlow<SyncRole> = _role

    /**
     * Sets this device's role, telling the room if already connected.
     *
     * Choosing a role is deliberately local: which screen *this* phone shows is
     * this phone's business. The room is told so the host can display a roster
     * that means something, not so it can grant permission.
     */
    fun setRole(role: SyncRole) {
        if (_role.value == role) return
        _role.value = role
        if (_isWsConnected.value) {
            syncClient.joinRoom(_roomCode.value, role.wireName, "Android Device")
        }
        _feedbackMsg.value = "This device: ${role.label}"
    }

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
        // A cue that cannot be seen is a cue you have to remember. Republish so
        // the mark appears on the ring the moment it is set.
        republishMarkers()
    }

    fun triggerCue(deck: String, index: Int) {
        val time = (if (deck == "A") _cuesA.value else _cuesB.value)[index - 1] ?: return
        deckNamed(deck).seekToSeconds(time.toDouble())
    }



    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Every emission, including the empty one. The previous version
            // published the list only when it was non-empty, so clearing the
            // library left the last non-empty list on screen for ever — and an
            // empty library reached the branch below instead.
            trackDao.getAllTracksFlow().collect { list -> _tracks.value = list }
        }
    }

    /**
     * Imports a pack from the Azphalt store, on request.
     *
     * This used to run **on its own**, whenever the library was empty: a first
     * launch downloaded and installed a sample pack over the network without
     * being asked. That is the opposite of what F9 says — "no built-in audio
     * clips" — because a pack that arrives unasked is a built-in clip that took
     * a detour. It also spent someone's mobile data before they had touched
     * anything.
     *
     * Now it happens when someone asks for it. Packs still arrive unanalysed;
     * measuring them is the analysis queue's job, not the importer's.
     */
    fun importAzphaltPack() {
        viewModelScope.launch(Dispatchers.IO) {
          work.track(BackgroundWork.IMPORT, "Azphalt store", "checking for packs") { progress ->
            _feedbackMsg.value = "Checking the Azphalt store..."
            val packages = runCatching {
                com.hereliesaz.sirmatchalot.data.AzphaltStoreRepository.fetchAudioPackages()
            }.getOrElse {
                _feedbackMsg.value = "Could not reach the Azphalt store"
                return@track
            }
            if (packages.isEmpty()) {
                _feedbackMsg.value = "No packs available"
                return@track
            }
            val pack = packages.first()
            progress.detail("downloading ${pack.name}")
            _feedbackMsg.value = "Downloading ${pack.name}..."
            val tracks = runCatching {
                com.hereliesaz.sirmatchalot.data.AzphaltStoreRepository
                    .downloadAndExtractPackage(getApplication(), pack)
            }.getOrElse {
                _feedbackMsg.value = "Could not install ${pack.name}"
                return@track
            }
            if (tracks.isEmpty()) {
                _feedbackMsg.value = "${pack.name} contained no audio"
                return@track
            }
            trackDao.insertTracks(tracks)
            _feedbackMsg.value =
                "Imported ${tracks.size} from ${pack.name} — run Analyse to measure them"
          }
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

    /**
     * Ids whose decoded audio the render thread may still be reading.
     *
     * Evicting one of these would not stop playback — the clip holds its own
     * reference — but it would mean the next load decodes a second copy, so the
     * cache would cost memory instead of saving it.
     */
    private fun pinnedTrackIds(): Set<String> =
        (audioEngine.deckA.clips + audioEngine.deckB.clips).map { it.id }.toSet()

    /** Takes [trackId] off whichever deck holds it. */
    fun removeTrackFromDecks(trackId: String) {
        _loadedTracksA.value = _loadedTracksA.value.filterNot { it.id == trackId }
        _loadedTracksB.value = _loadedTracksB.value.filterNot { it.id == trackId }
        audioEngine.deckA.clips = audioEngine.deckA.clips.filterNot { it.id == trackId }
        audioEngine.deckB.clips = audioEngine.deckB.clips.filterNot { it.id == trackId }
        _selectedTrackIds.value = _selectedTrackIds.value - trackId
        // Release the audio as the track leaves, so a long mix does not
        // accumulate every track it has already played.
        decoded.trim(pinnedTrackIds())
        peaksCache.remove(trackId)
        energyCache.remove(trackId)
        republishPlatter()
    }

    fun togglePlayback() {
        val nextPlaying = !_isPlaying.value
        _isPlaying.value = nextPlaying
        engine.deckA.playing = nextPlaying
        engine.deckB.playing = nextPlaying
        // Starting from a pause means the audio thread is parked. It checks
        // every twenty milliseconds regardless; this makes the first sample
        // arrive on the press rather than after it.
        if (nextPlaying) engine.wake()
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
        // A hand on the fader in the middle of an automatic transition is the
        // clearest disapproval there is — clearer than stopping, because the
        // person is not leaving, they are correcting. Taken once per transition:
        // a drag arrives as a stream of these, and one objection is one
        // objection however many samples it was delivered in.
        if (_transitionProgress.value > 0f && pendingTransition != null) {
            judgeTransition(approved = false)
            _transitionStyle.value = null
        }
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

    /**
     * Positions the crossfader from a continuous 0..1, keeping full resolution.
     *
     * The slider is an `Int` because a slider is a thing a finger moves, and 201
     * positions is finer than a finger. A programmed fade is not: routing one
     * through the same integer means a thirty-second blend is delivered as 201
     * discrete steps. The UI's own reading is still quantised — it is a slider —
     * but the audio is not.
     */
    private fun applyCrossfade(position: Float) {
        val clamped = position.coerceIn(0f, 1f)
        _crossfader.value = ((clamped * 200f) - 100f).toInt().coerceIn(-100, 100)
        audioEngine.mixer.crossfade = clamped
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

    // --- Hosting a room ---

    /**
     * This device's room server, when it is the host.
     *
     * One phone hosts and the others join it, which is what makes one-click
     * connection on the same Wi-Fi possible with no infrastructure at all.
     * `SyncClient` has always broadcast for a server; nothing ever answered.
     */
    private val syncServer = SyncServer()

    private val _isHosting = MutableStateFlow(false)
    val isHosting: StateFlow<Boolean> = _isHosting

    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount

    private val _hostUrl = MutableStateFlow<String?>(null)
    val hostUrl: StateFlow<String?> = _hostUrl

    /**
     * Starts hosting, generating a room code if none is set.
     *
     * The host applies remote events to its own engine as well as relaying them,
     * so a device driving the pads from across the room is playing *this*
     * instrument rather than talking to a relay that ignores it.
     */
    fun startHosting() {
        if (_isHosting.value) return
        val code = _roomCode.value.takeIf { it.isNotBlank() } ?: SyncServer.generateRoomCode()

        syncServer.onPeersChanged = { _peerCount.value = it }
        syncServer.onEvent = { event, payload ->
            when (event) {
                "play_sampler_pad" -> onSamplerTriggerEvent(payload.optInt("padId"))
                "kaoss_move" -> onKaossMoveEvent(
                    payload.optDouble("x").toFloat(),
                    payload.optDouble("y").toFloat(),
                    payload.optInt("padId"),
                )
                "sync_click" -> onAutoSyncEvent()
                "load_track_direct" ->
                    onLoadTrackEvent(payload.optString("deck"), payload.optString("trackId"))
                "nudge_deck_direct" ->
                    onNudgeEvent(payload.optString("deck"), payload.optString("direction"))
                "seek_deck" ->
                    onSeekEvent(payload.optString("deck"), payload.optDouble("time").toFloat())
            }
        }
        syncServer.onStateChanged = { state -> onRoomStateReceived(state) }

        if (!syncServer.start(code)) {
            _feedbackMsg.value = "Could not host — port ${syncServer.port} is in use"
            return
        }
        _roomCode.value = code
        _isHosting.value = true
        _hostUrl.value = syncServer.websocketUrl()
        _feedbackMsg.value = when {
            syncServer.websocketUrl() == null ->
                "Hosting room $code, but this device is not on a network"
            // Hosting works; being *found* does not. Worth saying, because the
            // symptom is another device searching and finding nothing, which
            // looks like a broken network rather than a busy port.
            !syncServer.isDiscoverable ->
                "Hosting room $code — port ${SyncServer.DISCOVERY_PORT} is in use, " +
                    "so others must enter ${syncServer.websocketUrl()} by hand"
            else -> "Hosting room $code — others can join now"
        }
    }

    fun stopHosting() {
        syncServer.stop()
        _isHosting.value = false
        _peerCount.value = 0
        _hostUrl.value = null
        _feedbackMsg.value = "Stopped hosting"
    }

    // --- Sharing a session ---

    /**
     * The loaded session as a link.
     *
     * Built from what is actually on the decks, so a link always describes the
     * session as it stands rather than as it was when something was last saved.
     */
    fun sessionLink(): SessionLink = SessionLink(
        deckA = _loadedTracksA.value.map { SessionLink.TrackRef(it.title, it.artist) },
        deckB = _loadedTracksB.value.map { SessionLink.TrackRef(it.title, it.artist) },
        cuesA = _cuesA.value.filterNotNull().map { it.toDouble() },
        cuesB = _cuesB.value.filterNotNull().map { it.toDouble() },
        crossfade = _crossfader.value,
        referenceBpm = _reference.value?.bpm,
        referenceKey = _reference.value?.camelotKey,
        roomCode = _roomCode.value.takeIf { it.isNotBlank() },
    )

    /**
     * Loads what a shared link describes, matching its tracks against this
     * library by title and artist.
     *
     * A track the receiving library does not hold is named in the message rather
     * than skipped silently — the point of sharing a session is to be able to
     * recreate it, and knowing which two songs are missing is what makes that
     * possible.
     */
    fun openSessionLink(url: String) {
        val session = SessionLink.fromUrl(url)
        if (session.isEmpty) {
            _feedbackMsg.value = "That link has no session in it"
            return
        }
        clearDecks()
        session.roomCode?.let { _roomCode.value = it }
        setCrossfaderValue(session.crossfade)

        val missing = ArrayList<String>()
        fun load(refs: List<SessionLink.TrackRef>, deck: PlatterGeometry.Deck) {
            for (ref in refs) {
                val match = _tracks.value.firstOrNull { track ->
                    track.title.equals(ref.title, ignoreCase = true) &&
                        (ref.artist.isBlank() || track.artist.equals(ref.artist, ignoreCase = true))
                }
                if (match == null) missing.add(ref.toString()) else loadOntoDeck(match, deck)
            }
        }
        load(session.deckA, PlatterGeometry.Deck.A)
        load(session.deckB, PlatterGeometry.Deck.B)

        _cuesA.value = session.cuesA.map { it.toFloat() as Float? }
            .plus(List(4) { null }).take(4)
        _cuesB.value = session.cuesB.map { it.toFloat() as Float? }
            .plus(List(4) { null }).take(4)

        _feedbackMsg.value = when {
            missing.isEmpty() -> "Session loaded"
            else -> "Session loaded — not in your library: ${missing.joinToString(", ")}"
        }
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
            // Persist the grant, or the library outlives the right to read it.
            //
            // A document from `OpenDocument` is readable until the process dies
            // and no longer. The database row, meanwhile, is forever. So every
            // single-file import worked in the session it was made and then
            // failed from the next app start — or the next app *update*, which
            // also kills the process — with the row still sitting there looking
            // perfectly fine. Reported as "Could not decode", which sent anyone
            // looking at codecs instead of at permissions.
            //
            // `runCatching` because not every provider offers a persistable
            // grant; when it declines, the import still works for this session
            // and the failure later is at least now named correctly.
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }

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
    suspend fun analyseTrack(track: Track): Boolean {
        val source = track.sourceUri
        if (source.isNullOrBlank()) {
            // Previously a bare `?: return`. A track with no audio is a normal
            // state now that a playlist can name songs the library does not
            // hold, so it needs saying rather than swallowing.
            _feedbackMsg.value = "${track.title} has no audio file to analyse"
            return false
        }
        try {
            val outcome = AudioDecoder.decodeDetailed(getApplication(), playableSource(track, source))
            if (outcome is com.hereliesaz.sirmatchalot.audio.DecodeOutcome.Failure) {
                _feedbackMsg.value = decodeFailureMessage(track, outcome)
                return false
            }
            val decoded = (outcome as com.hereliesaz.sirmatchalot.audio.DecodeOutcome.Success).audio

            val analysis = analyzer.analyse(decoded.pcm)
            val peaksFile = java.io.File(getApplication<Application>().filesDir, "peaks/${track.id}.peaks")
            peaksFile.parentFile?.mkdirs()
            peaksFile.writeBytes(analysis.peaks.toByteArray())

            // The energy curve was measured and then discarded, which is why the
            // platter's clips all drew at neutral brightness however different
            // the music was. Track.energyPath has existed for it since the schema
            // was written; nothing ever filled it in.
            val energyFile = java.io.File(getApplication<Application>().filesDir, "energy/${track.id}.energy")
            energyFile.parentFile?.mkdirs()
            energyFile.writeBytes(analysis.energyCurve.toByteArray())

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
                    energyPath = energyFile.absolutePath,
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
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is the user stopping the run, not a failure of this
            // track; let it propagate so the loop above unwinds.
            throw e
        } catch (e: OutOfMemoryError) {
            // A library scan must survive one file it cannot hold. Analysis
            // decodes at the source's own rate, so a long hi-res track can be
            // several times the size of anything the decks would ever carry —
            // and failing the whole run over it would leave every track after it
            // unmeasured.
            System.gc()
            _feedbackMsg.value = "${track.title} is too large to analyse on this device"
            return false
        } catch (e: Exception) {
            _feedbackMsg.value = "Analysis failed for ${track.title}: ${e.message}"
            return false
        }
    }

    /**
     * Imports every audio file under [treeUri], recursively.
     *
     * Walks the document tree rather than taking a directory listing, because a
     * music folder is almost always a folder of folders — artist, then album —
     * and importing only the top level would find nothing at all in the usual
     * layout.
     *
     * Files are added unanalysed and the background service is started once at
     * the end, rather than analysing each file as it is found: a folder can hold
     * hundreds of tracks, and measuring them inline would block the import for
     * as long as the analysis takes.
     */
    fun importFolder(treeUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
          work.track(BackgroundWork.IMPORT, "Importing folder", "scanning") {
            _feedbackMsg.value = "Scanning folder..."
            val resolver = getApplication<Application>().contentResolver
            runCatching {
                resolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }

            val found = ArrayList<Pair<Uri, String>>()
            runCatching { walk(treeUri, treeUri, found) }
                .onFailure {
                    _feedbackMsg.value = "Could not read that folder"
                    return@track
                }

            if (found.isEmpty()) {
                _feedbackMsg.value = "No audio files in that folder"
                return@track
            }

            val existing = _tracks.value.mapNotNull { it.sourceUri }.toHashSet()
            var added = 0
            for ((uri, name) in found) {
                val source = uri.toString()
                if (source in existing) continue
                val parsed = com.hereliesaz.sirmatchalot.data.LinkParser.parseFileName(name)
                trackDao.insertTrack(
                    Track(title = parsed.first, artist = parsed.second, sourceUri = source),
                )
                added++
            }

            _feedbackMsg.value = when (added) {
                0 -> "All ${found.size} files were already in the library"
                else -> "Added $added of ${found.size} files — analysing in the background"
            }
            if (added > 0) startBackgroundAnalysis()
          }
        }
    }

    /**
     * Collects audio documents under [folder] into [into], descending into
     * subfolders.
     *
     * Iterative rather than recursive: a deep or symlink-looped tree would
     * otherwise be a stack overflow, and the visited set makes a provider that
     * reports a cycle terminate instead of spinning.
     */
    private fun walk(treeUri: Uri, folder: Uri, into: MutableList<Pair<Uri, String>>) {
        val resolver = getApplication<Application>().contentResolver
        val visited = HashSet<String>()
        val pending = ArrayDeque<Uri>()
        pending.add(folder)

        while (pending.isNotEmpty() && into.size < MAX_FOLDER_IMPORT) {
            val current = pending.removeFirst()
            val documentId = runCatching {
                android.provider.DocumentsContract.getDocumentId(current)
            }.getOrNull() ?: runCatching {
                android.provider.DocumentsContract.getTreeDocumentId(current)
            }.getOrNull() ?: continue
            if (!visited.add(documentId)) continue

            val children = android.provider.DocumentsContract
                .buildChildDocumentsUriUsingTree(treeUri, documentId)
            resolver.query(
                children,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val name = cursor.getString(1)
                    val mime = cursor.getString(2)
                    val childUri = android.provider.DocumentsContract
                        .buildDocumentUriUsingTree(treeUri, childId)
                    when {
                        com.hereliesaz.sirmatchalot.data.AudioFileFilter.isDirectory(mime) ->
                            pending.add(childUri)
                        com.hereliesaz.sirmatchalot.data.AudioFileFilter.isAudio(mime, name) ->
                            into.add(childUri to name)
                    }
                }
            }
        }
    }

    /**
     * Hands analysis to the background service.
     *
     * Analysis of a folder runs for minutes, and doing it in the ViewModel meant
     * it stopped the moment the app was backgrounded — which is exactly when
     * someone would leave it running.
     */
    fun startBackgroundAnalysis() {
        com.hereliesaz.sirmatchalot.analysis.AnalysisService.start(getApplication())
    }

    fun pauseBackgroundAnalysis() {
        com.hereliesaz.sirmatchalot.analysis.AnalysisService.send(
            getApplication(),
            com.hereliesaz.sirmatchalot.analysis.AnalysisService.ACTION_PAUSE,
        )
    }

    fun resumeBackgroundAnalysis() {
        com.hereliesaz.sirmatchalot.analysis.AnalysisService.send(
            getApplication(),
            com.hereliesaz.sirmatchalot.analysis.AnalysisService.ACTION_RESUME,
        )
    }

    fun stopBackgroundAnalysis() {
        com.hereliesaz.sirmatchalot.analysis.AnalysisService.send(
            getApplication(),
            com.hereliesaz.sirmatchalot.analysis.AnalysisService.ACTION_STOP,
        )
    }

    /** Progress of the background run, shared with its notification. */
    val backgroundAnalysis: StateFlow<com.hereliesaz.sirmatchalot.analysis.AnalysisState> =
        com.hereliesaz.sirmatchalot.analysis.AnalysisProgressBus.state

    /**
     * Imports whatever [input] refers to: a playlist, a track listing, or a
     * direct audio link.
     *
     * A playlist becomes **one library entry per song**, which is the whole
     * point — the previous behaviour was to treat an imported playlist as a
     * single track, and before that there was no link import at all
     * ([com.hereliesaz.sirmatchalot.data.LinkParser] existed but was called from
     * nowhere).
     *
     * Entries that carry a playable location are analysed straight away.
     * Entries that only *name* a song — everything a YouTube or Spotify listing
     * gives you — are still added, with no `sourceUri`, so the running order is
     * preserved and each can be pointed at a file later. They are not given
     * invented audio, and they are not silently dropped.
     */
    fun importFromLink(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            _feedbackMsg.value = "Paste a playlist link, a track listing, or an audio link"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
          work.track(BackgroundWork.IMPORT, "Importing playlist", "reading the link") {
            _feedbackMsg.value = "Reading..."
            val document = when {
                // Not a URL at all: a pasted tracklist is itself the document.
                !trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true) -> trimmed
                else -> fetchPlaylistDocument(trimmed) ?: run {
                    _feedbackMsg.value = "Could not read that link"
                    return@track
                }
            }

            val entries = PlaylistParser.parse(document)
            if (entries.isEmpty()) {
                _feedbackMsg.value = "Nothing recognisable as a playlist there"
                return@track
            }

            val existing = _tracks.value
            var added = 0
            var withAudio = 0
            for (entry in entries) {
                val duplicate = existing.any { track ->
                    (entry.sourceUri != null && track.sourceUri == entry.sourceUri) ||
                        (track.title.equals(entry.title, ignoreCase = true) &&
                            track.artist.equals(entry.artist ?: "", ignoreCase = true))
                }
                if (duplicate) continue

                trackDao.insertTrack(
                    Track(
                        title = entry.title,
                        artist = entry.artist ?: "Unknown Artist",
                        sourceUri = entry.sourceUri,
                        durationMs = ((entry.durationSeconds ?: 0.0) * 1000).toLong(),
                    ),
                )
                added++
                if (entry.hasAudio) withAudio++
            }

            _feedbackMsg.value = buildString {
                append("Imported $added of ${entries.size} ${if (entries.size == 1) "song" else "songs"}")
                val named = added - withAudio
                if (named > 0) append(" — $named named only, with no audio file yet")
            }
            if (withAudio > 0) analysePending()
          }
        }
    }

    /**
     * Fetches a playlist document.
     *
     * A YouTube playlist URL is rewritten to the Atom feed YouTube publishes for
     * it, which lists the playlist's videos without an API key. That gives the
     * songs; it does not give audio, and this app deliberately does not attempt
     * to extract audio from YouTube — doing so breaches their terms and Google
     * Play's policy on such apps.
     */
    private fun fetchPlaylistDocument(url: String): String? {
        val playlistId = Regex("[?&]list=([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1)
        val target = if (playlistId != null) {
            "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
        } else {
            url
        }
        return runCatching {
            val connection = java.net.URL(target).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "SirMatchALot")
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    /** Re-measures every track whose stored analysis predates the current analyser. */
    /**
     * Progress through the current analysis run, or null when idle.
     *
     * Analysing a full track is an FFT pass over the whole file and takes real
     * seconds. Without something visible changing, the button was
     * indistinguishable from a dead one for the entire run.
     */
    /**
     * Progress of the analysis run, mirrored from the service.
     *
     * A view of [com.hereliesaz.sirmatchalot.analysis.AnalysisProgressBus] rather
     * than a second source. It used to be a second source — the ViewModel's own
     * analysis loop — and the two could disagree about whether anything was
     * running at all.
     */
    val analysisProgress: StateFlow<AnalysisProgress?> =
        com.hereliesaz.sirmatchalot.analysis.AnalysisProgressBus.state
            .map { state ->
                if (state.total <= 0) {
                    null
                } else {
                    AnalysisProgress(state.done, state.total, state.current)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** @param done tracks finished so far, out of [total]. */
    data class AnalysisProgress(val done: Int, val total: Int, val current: String) {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    /**
     * Measures every track that has audio and has not been measured yet.
     *
     * Reports something in *every* case. The previous version reported only from
     * inside a successful per-track analysis, so an empty library, a fully
     * analysed one, and one full of entries with no audio file were all silent —
     * which is how "pressing analyse does nothing" came about. Three of those
     * four outcomes were doing exactly nothing, correctly, and saying so.
     */
    /**
     * Measures every track that has audio and has not been measured yet.
     *
     * Hands the work to [com.hereliesaz.sirmatchalot.analysis.AnalysisService],
     * always — which is the whole change. There used to be two analysis paths:
     * this one, which ran inside the ViewModel with no notification and died the
     * moment the app was backgrounded, and the service, which had the
     * notification and the Pause and Stop controls and was started only by a
     * folder import. The button in the library called the first one.
     *
     * So the run a user actually started was the one that could not be seen from
     * outside the app and did not survive leaving it — for work that takes
     * minutes and that nobody sits and watches. One path now, and it is the one
     * with the notification.
     *
     * @param rescan re-measure everything, not only what has never been measured.
     */
    fun analysePending(rescan: Boolean = false) {
        val plan = if (rescan) {
            AnalysisQueue.planFullRescan(_tracks.value)
        } else {
            AnalysisQueue.plan(_tracks.value)
        }
        if (!plan.hasWork) {
            _feedbackMsg.value = plan.idleMessage()
            return
        }
        _feedbackMsg.value = plan.startMessage()
        com.hereliesaz.sirmatchalot.analysis.AnalysisService.start(getApplication(), rescan)
    }

    /** Stops an analysis run in progress. */
    fun cancelAnalysis() = stopBackgroundAnalysis()

    fun deleteTrack(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            // The copy goes with the row. Leaving it would be disk nobody can
            // account for and nothing can reach.
            audioCache.remove(track.id)
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
        syncClient.joinRoom(_roomCode.value, _role.value.wireName, "Android Device")
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
            // Cue points travel; playhead position deliberately does not. A
            // remote `currentTime` applied here would fight the local render
            // loop and, since every applied change is echoed back as state,
            // would do it in a feedback loop. Cues are static marks, so they
            // can be shared without either problem.
            json.optJSONObject("deckA")?.let { _cuesA.value = remoteCues(it, _cuesA.value) }
            json.optJSONObject("deckB")?.let { _cuesB.value = remoteCues(it, _cuesB.value) }
        }
    }

    /** Cue points out of a remote deck object, keeping the local ones it omits. */
    private fun remoteCues(deck: JSONObject, current: List<Float?>): List<Float?> {
        val cues = deck.optJSONArray("cues") ?: return current
        return List(4) { index ->
            if (index >= cues.length() || cues.isNull(index)) {
                null
            } else {
                cues.optDouble(index).takeIf { !it.isNaN() }?.toFloat()
            }
        }
    }

    override fun onKaossMoveEvent(x: Float, y: Float, padId: Int) {
        // Applies to the master bus, so a remote move is heard on this device's
        // own output — which is what a linked pad is for.
        moveFilter(x, y)
    }

    override fun onSamplerTriggerEvent(padId: Int) {
        // Remote pads trigger the real sampler rather than a synthesised tone.
        engine.sampler.trigger(padId)
        engine.wake()
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
        harvestJob?.cancel()
        audioEngine.release()
        decoded.clear()
        syncServer.stop()
        syncClient.disconnect()
    }

    companion object {
        /**
         * Marks a deck clip as coming from a sampler pad rather than a library
         * track. Prefixed rather than tracked in a set so the mark survives
         * anything that rebuilds the clip list from the engine.
         */
        const val PAD_CLIP_PREFIX = "pad:"

        /**
         * How often the platter state is refreshed while nothing is on screen.
         *
         * Twice a second, and only to notice that a screen has come back. There
         * is nothing to draw and nobody to draw it for.
         */
        private const val BACKGROUND_POLL_MILLIS = 500L

        /**
         * How many analysed tracks the all-pairs list will compare.
         *
         * The work is quadratic, so this is the difference between 20,000
         * comparisons and two million. Two hundred tracks already yields far
         * more strong pairs than anyone scrolls.
         */
        private const val MAX_TRACKS_PAIRED = 200

        /** How many pairs to keep, best first. */
        private const val MAX_PAIRS_SHOWN = 50

        /** Below this a pairing is not worth offering as one. */
        private const val STRONG_PAIR_SCORE = 60
    }
}
