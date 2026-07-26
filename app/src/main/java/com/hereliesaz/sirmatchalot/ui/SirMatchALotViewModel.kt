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
import com.hereliesaz.sirmatchalot.domain.DeckCapacity
import com.hereliesaz.sirmatchalot.domain.HarmonicEngine
import com.hereliesaz.sirmatchalot.domain.MixCommand
import com.hereliesaz.sirmatchalot.domain.MixDeck
import com.hereliesaz.sirmatchalot.domain.MixDirector
import com.hereliesaz.sirmatchalot.domain.MixPlan
import com.hereliesaz.sirmatchalot.domain.MixPlanner
import com.hereliesaz.sirmatchalot.domain.MixMatch
import com.hereliesaz.sirmatchalot.domain.LoopHarvest
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

    /**
     * Decoded audio per track id, so a clip is decoded once and reused.
     *
     * Bounded. The plain map this replaces was never emptied, so every track
     * ever loaded stayed in memory as full PCM — about 58 MB per five-minute
     * stereo track — until the ViewModel died.
     */
    private val decoded = com.hereliesaz.sirmatchalot.audio.DecodedCache()
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
            val pcm = decoded[track.id] ?: run {
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
                val converted = raw.resampledTo(audioEngine.output.sampleRate)
                decoded.put(track.id, converted, pinnedTrackIds())
                if (decoded.overBudget) {
                    _feedbackMsg.value =
                        "Low on memory — unload a track before loading more"
                }
                converted
            }
            peaksCache.getOrPut(track.id) {
                track.peaksPath
                    ?.let { path -> runCatching { PeakEnvelope.fromByteArray(java.io.File(path).readBytes()) }.getOrNull() }
                    ?: PeakEnvelope.compute(pcm.toMonoFloat())
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
                    ?: com.hereliesaz.sirmatchalot.dsp.EnergyCurve.compute(pcm.toMonoFloat(), pcm.sampleRate)
            }

            // Landmarks come from the energy curve that was just cached, so
            // marking the drops costs no decode and no second analysis pass.
            poiCache.getOrPut(track.id) {
                val curve = energyCache[track.id]
                if (curve == null) {
                    emptyList()
                } else {
                    val grid = track.bpm?.let { bpm ->
                        track.firstBeatSeconds?.let { first ->
                            com.hereliesaz.sirmatchalot.dsp.BeatGrid(bpm, first, track.downbeatOffset)
                        }
                    }
                    com.hereliesaz.sirmatchalot.dsp.StructureFinder()
                        .findPointsOfInterest(curve, grid)
                }
            }

            // The first track on the platter sets the session's tempo and key;
            // everything after is rendered to match before it is ever heard.
            // Conforming the audio itself, rather than setting a deck rate, is
            // what lets several clips share one circle: a deck has one rate, so
            // rate-matching only ever works when a deck holds one track.
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
                if (!_isPlaying.value && audioEngine.deckA.clips.size + audioEngine.deckB.clips.size == 1) {
                    _isPlaying.value = true
                    audioEngine.deckA.playing = true
                    audioEngine.deckB.playing = true
                }
                engineDeck.playing = _isPlaying.value
            }
            onDeck.value = onDeck.value + track
            republishPlatter()
            if (!startSilent) reportConformed(track)
            syncClient.triggerLoadTrack(if (deck == PlatterGeometry.Deck.A) "A" else "B", track.id, _roomCode.value)
        }
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
                    durationSeconds = clip.frameCount.toDouble() / clip.buffer.sampleRate,
                    peaks = peaksCache[clip.id] ?: PeakEnvelope.compute(FloatArray(0)),
                    energy = energyCache[clip.id],
                )
            }
        }

        val selected = _selectedTrackIds.value
        _platterState.value = _platterState.value.copy(
            deckA = PlatterState.layout(inputsFor(PlatterGeometry.Deck.A), selected, PlatterGeometry.Deck.A),
            deckB = PlatterState.layout(inputsFor(PlatterGeometry.Deck.B), selected, PlatterGeometry.Deck.B),
            markers = buildMarkers(),
        )
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
            val finder = com.hereliesaz.sirmatchalot.dsp.StructureFinder()
            val sources = ArrayList<LoopHarvest.Source>(usable.size)

            usable.forEachIndexed { index, track ->
                ensureActive()
                _harvestProgress.value = (index + 1) to usable.size
                val pcm = decodeForHarvest(track) ?: return@forEachIndexed
                val candidates = finder.findLoops(
                    pcm.toMonoFloat(),
                    pcm.sampleRate,
                    com.hereliesaz.sirmatchalot.dsp.BeatGrid(
                        bpm = track.bpm!!,
                        firstBeatSeconds = track.firstBeatSeconds!!,
                        downbeatOffset = track.downbeatOffset,
                    ),
                )
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
        }
    }

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
            AudioDecoder.decode(getApplication(), Uri.parse(source))?.pcm
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
            if (added.isEmpty()) return@launch

            engineDeck.clips = engineDeck.clips.filterNot { it.id.startsWith(PAD_CLIP_PREFIX) } + added
            republishPlatter()
            _feedbackMsg.value = "Placed ${added.size} loops on Deck ${deckLabel(deck)}"
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
            val stretched = pristine.timeStretched(snapped)
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

        val director = MixDirector(plan)
        _mixDirector.value = director
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
        autoMixJob?.cancel()
        autoMixJob = null
        _mixDirector.value = null
        _transitionProgress.value = 0f
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
                // Enter from the top of the track, not from wherever the deck's
                // playhead happened to be left.
                engineDeck.playhead = 0.0
                engineDeck.playing = true
                _isPlaying.value = true

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

            is MixCommand.Crossfade -> applyCrossfade(((command.position * 200f) - 100f).toInt())

            is MixCommand.Retire -> {
                val name = if (command.deck == MixDeck.A) "A" else "B"
                deckNamed(name).playing = false
                removeTrackFromDecks(command.track.id)
            }

            is MixCommand.NowPlaying -> {
                _nowPlaying.value = command
                _feedbackMsg.value =
                    "${command.index + 1}/${command.total}: ${command.step.track.title}"
            }

            MixCommand.Finished -> {
                _mixDirector.value = null
                _transitionProgress.value = 0f
            }
        }
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
            val shifted = engineDeck.clips.map { clip ->
                val pristine = decoded[clip.id] ?: clip.buffer
                Clip(
                    id = clip.id,
                    buffer = pristine.pitchShifted(semitones),
                    startFrame = clip.startFrame,
                    gain = clip.gain,
                    loop = clip.loop,
                )
            }
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
        _feedbackMsg.value = syncServer.websocketUrl()
            ?.let { "Hosting room $code — others can join now" }
            ?: "Hosting room $code, but this device is not on a network"
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
            val decoded = AudioDecoder.decode(getApplication(), Uri.parse(source))
            if (decoded == null) {
                _feedbackMsg.value = "Could not decode ${track.title}"
                return false
            }

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
                    return@launch
                }

            if (found.isEmpty()) {
                _feedbackMsg.value = "No audio files in that folder"
                return@launch
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
            _feedbackMsg.value = "Reading..."
            val document = when {
                // Not a URL at all: a pasted tracklist is itself the document.
                !trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true) -> trimmed
                else -> fetchPlaylistDocument(trimmed) ?: run {
                    _feedbackMsg.value = "Could not read that link"
                    return@launch
                }
            }

            val entries = PlaylistParser.parse(document)
            if (entries.isEmpty()) {
                _feedbackMsg.value = "Nothing recognisable as a playlist there"
                return@launch
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
    private val _analysisProgress = MutableStateFlow<AnalysisProgress?>(null)
    val analysisProgress: StateFlow<AnalysisProgress?> = _analysisProgress

    /** @param done tracks finished so far, out of [total]. */
    data class AnalysisProgress(val done: Int, val total: Int, val current: String) {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    private var analysisJob: kotlinx.coroutines.Job? = null

    /**
     * Measures every track that has audio and has not been measured yet.
     *
     * Reports something in *every* case. The previous version reported only from
     * inside a successful per-track analysis, so an empty library, a fully
     * analysed one, and one full of entries with no audio file were all silent —
     * which is how "pressing analyse does nothing" came about. Three of those
     * four outcomes were doing exactly nothing, correctly, and saying so.
     */
    fun analysePending(rescan: Boolean = false) {
        if (analysisJob?.isActive == true) {
            _feedbackMsg.value = "Already analysing — let it finish"
            return
        }
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
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            var done = 0
            var failed = 0
            for (track in plan.toAnalyse) {
                _analysisProgress.value = AnalysisProgress(done, plan.toAnalyse.size, track.title)
                if (!analyseTrack(track)) failed++
                done++
            }
            _analysisProgress.value = null
            _feedbackMsg.value = buildString {
                append("Analysed ${done - failed} of ${plan.toAnalyse.size}")
                if (failed > 0) append(", $failed could not be decoded")
                if (plan.missingAudio.isNotEmpty()) {
                    append("; ${plan.missingAudio.size} have no audio file")
                }
            }
        }
    }

    /** Stops an analysis run in progress. */
    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _analysisProgress.value = null
        _feedbackMsg.value = "Analysis stopped"
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
    }
}
