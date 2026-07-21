package com.hereliesaz.sirmatchalot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.sirmatchalot.ai.GeminiAnalyzer
import com.hereliesaz.sirmatchalot.ai.SongAnalyzer
import com.hereliesaz.sirmatchalot.audio.DeckController
import com.hereliesaz.sirmatchalot.audio.SynthEngine
import com.hereliesaz.sirmatchalot.data.AppDatabase
import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.domain.HarmonicEngine
import com.hereliesaz.sirmatchalot.domain.MixMatch
import com.hereliesaz.sirmatchalot.sync.SyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

class SirMatchALotViewModel(application: Application) : AndroidViewModel(application), SyncClient.SyncListener {

    private val db = AppDatabase.getDatabase(application)
    private val trackDao = db.trackDao()

    val synthEngine = SynthEngine(application)
    val syncClient = SyncClient(this)

    private val analyzer: SongAnalyzer = GeminiAnalyzer(apiKey = "MY_GEMINI_API_KEY")

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    // Sorting state flow options
    enum class SortOption {
        BPM, PITCH, BOTH, ORIGINAL, CUSTOM
    }
    private val _sortOption = MutableStateFlow(SortOption.ORIGINAL)
    val sortOption: StateFlow<SortOption> = _sortOption

    private val _customOrderList = MutableStateFlow<List<String>>(emptyList())
    val customOrderList: StateFlow<List<String>> = _customOrderList

    // Reactive Combined Sorted Tracks List
    val sortedTracks = combine(_tracks, _sortOption, _customOrderList) { rawTracks, option, customOrder ->
        // Use the first loaded track (if any) as a reference point for harmonic/BPM matching
        val referenceTrack = _loadedTracksA.value.firstOrNull() ?: _loadedTracksB.value.firstOrNull()
        
        when (option) {
            SortOption.ORIGINAL -> rawTracks
            SortOption.BPM -> {
                if (referenceTrack != null) {
                    rawTracks.sortedBy { kotlin.math.abs(it.bpm - referenceTrack.bpm) }
                } else {
                    rawTracks.sortedBy { it.bpm }
                }
            }
            SortOption.PITCH -> {
                if (referenceTrack != null) {
                    rawTracks.sortedBy { HarmonicEngine.getCamelotDistance(it.camelotKey, referenceTrack.camelotKey) }
                } else {
                    rawTracks.sortedBy { it.camelotKey }
                }
            }
            SortOption.BOTH -> {
                if (referenceTrack != null) {
                    rawTracks.sortedBy { track ->
                        val bpmDiff = kotlin.math.abs(track.bpm - referenceTrack.bpm)
                        val keyDist = HarmonicEngine.getCamelotDistance(track.camelotKey, referenceTrack.camelotKey)
                        bpmDiff * 2 + keyDist * 10
                    }
                } else {
                    rawTracks.sortedBy { it.bpm }
                }
            }
            SortOption.CUSTOM -> {
                rawTracks.sortedBy { track ->
                    val idx = customOrder.indexOf(track.id)
                    if (idx != -1) idx else 999
                }
            }
        }
    }

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

    private fun writeWavHeader(
        out: java.io.OutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Long,
        channels: Int,
        byteRate: Long
    ) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte() // 'data' chunk
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        out.write(header, 0, 44)
    }

    private fun generateMockWavs(context: android.content.Context) {
        val sampleRate = 44100
        val duration = 2.0f // 2 seconds loops (corresponds to 120 BPM 4-beat bar)
        val numSamples = (duration * sampleRate).toInt()
        
        val loopFiles = listOf(
            Pair("kick_loop.wav", 1),
            Pair("snare_loop.wav", 2),
            Pair("hat_loop.wav", 3),
            Pair("vox_loop.wav", 4)
        )
        
        loopFiles.forEach { (filename, padId) ->
            val file = java.io.File(context.cacheDir, filename)
            if (file.exists()) return@forEach
            
            try {
                val fos = java.io.FileOutputStream(file)
                val totalAudioLen = numSamples * 2L
                val totalDataLen = totalAudioLen + 36
                
                writeWavHeader(fos, totalAudioLen, totalDataLen, sampleRate.toLong(), 1, sampleRate * 2L)
                
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toFloat() / sampleRate
                    buffer[i] = when (padId) {
                        1 -> { // 808 Kick: 4 beats
                            val beatTime = t % 0.5f
                            if (beatTime < 0.25f) {
                                val freq = 120f * (1.0f - beatTime / 0.25f) + 35f
                                val phase = 2.0 * Math.PI * freq * beatTime
                                (kotlin.math.sin(phase) * 24000f * (1.0f - beatTime / 0.25f)).toInt().toShort()
                            } else 0
                        }
                        2 -> { // Retro Snare: beat 2 and 4
                            val beat = (t / 0.5f).toInt()
                            val beatTime = t % 0.5f
                            if ((beat == 1 || beat == 3) && beatTime < 0.25f) {
                                val noise = (Math.random() * 2.0 - 1.0) * 12000f
                                val tone = kotlin.math.sin(2.0 * Math.PI * 180.0 * beatTime) * 6000f * (1.0f - beatTime / 0.25f)
                                ((noise + tone) * (1.0f - beatTime / 0.25f)).toInt().toShort()
                            } else 0
                        }
                        3 -> { // Open Hat: offbeat
                            val beatTime = (t + 0.25f) % 0.5f
                            if (beatTime < 0.12f) {
                                val noise = (Math.random() * 2.0 - 1.0) * 18000f
                                (noise * (1.0f - beatTime / 0.12f) * (1.0f - beatTime / 0.12f)).toInt().toShort()
                            } else 0
                        }
                        else -> { // Formant Vox
                            val lfo = kotlin.math.sin(2.0 * Math.PI * 2.0 * t).toFloat() * 100f
                            val phase = 2.0 * Math.PI * (220.0 + lfo) * t
                            (kotlin.math.sin(phase) * 12000f).toInt().toShort()
                        }
                    }
                }
                
                val byteBuffer = java.nio.ByteBuffer.allocate(buffer.size * 2)
                byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buffer.forEach { byteBuffer.putShort(it) }
                fos.write(byteBuffer.array())
                fos.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    init {
        generateMockWavs(application)
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.getAllTracksFlow().collect { list ->
                if (list.isEmpty()) {
                    loadMockCrates()
                } else {
                    _tracks.value = list
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val exists = trackDao.getTrackById("loop1") != null
            if (!exists) {
                val loops = listOf(
                    Track("loop1", "808 KICK (Loop)", "Sampler Engine", 120, "C major", "8B", "", "synth-loop", 8, "808 Kick loop.", null, java.io.File(application.cacheDir, "kick_loop.wav").absolutePath, false),
                    Track("loop2", "RETRO SNARE (Loop)", "Sampler Engine", 120, "C major", "8B", "", "synth-loop", 8, "Retro Snare loop.", null, java.io.File(application.cacheDir, "snare_loop.wav").absolutePath, false),
                    Track("loop3", "OPEN HAT (Loop)", "Sampler Engine", 120, "C major", "8B", "", "synth-loop", 8, "Open Hat loop.", null, java.io.File(application.cacheDir, "hat_loop.wav").absolutePath, false),
                    Track("loop4", "FORMANT VOX (Loop)", "Sampler Engine", 120, "A minor", "8A", "", "synth-loop", 8, "Formant Vox loop.", null, java.io.File(application.cacheDir, "vox_loop.wav").absolutePath, false)
                )
                trackDao.insertTracks(loops)
            }
        }
    }

    private suspend fun loadMockCrates() {
        val mockTracks = listOf(
            Track("th1", "Around the World", "Daft Punk", 121, "A minor", "8A", "Am - C - Em - G", "hypnotic, funk-house, looping bassline", 8, "Blend intro drums. Neighbors: 8A, 8B, 9A.", null, null, false),
            Track("th2", "One More Time", "Daft Punk", 123, "G major", "9B", "G - D - Em - C", "celebratory, vocal filter sweep", 9, "Mix relative keys. Neighbors: 9B, 8B, 10B.", null, null, false),
            Track("th3", "Billie Jean", "Michael Jackson", 117, "F# minor", "11A", "F#m - G#m - A - G#m", "groovy, tight analog bass", 7, "Match speeds and blend. Neighbors: 11A, 10A, 12A.", null, null, false),
            Track("tc1", "Blue Monday", "New Order", 130, "D minor", "7A", "Dm - C - F - G", "dark mechanical retro synth", 8, "Use 16-bar intro. Neighbors: 7A, 6A, 8A.", null, null, false),
            Track("tc2", "Sandstorm", "Darude", 136, "F minor", "4A", "Fm - Ab - Eb - Db", "pumping leads, classic rave trance", 10, "Cut transitions on breaks. Neighbors: 4A, 3A, 5A.", null, null, false)
        )
        trackDao.insertTracks(mockTracks)
    }

    fun selectTrack(trackId: String?) {
        _selectedTrackIds.value = if (trackId != null) setOf(trackId) else emptySet()
    }

    fun setSelectedTracks(trackIds: Set<String>) {
        _selectedTrackIds.value = trackIds
    }

    fun setSortOption(option: SortOption) {
        _sortOption.value = option
    }

    fun moveTrackInCustomOrder(fromIndex: Int, toIndex: Int) {
        val currentList = _customOrderList.value.toMutableList()
        if (currentList.isEmpty()) {
            currentList.addAll(_tracks.value.map { it.id })
        }
        if (fromIndex in currentList.indices && toIndex in currentList.indices) {
            val item = currentList.removeAt(fromIndex)
            currentList.add(toIndex, item)
            _customOrderList.value = currentList
        }
    }

    // Dynamic Platter loading functions
    fun addTrackToDeckA(track: Track) {
        val list = _loadedTracksA.value.toMutableList()
        if (list.any { it.id == track.id }) return
        list.add(track)
        _loadedTracksA.value = list

        val controller = DeckController(getApplication(), "Deck A - ${track.title}")
        controller.loadTrack(track)
        val controllers = _controllersA.value.toMutableList()
        controllers.add(controller)
        _controllersA.value = controllers

        if (_isPlaying.value) controller.play()
        updateAllVolumes()

        syncClient.triggerLoadTrack("A", track.id, _roomCode.value)
    }

    fun addTrackToDeckB(track: Track) {
        val list = _loadedTracksB.value.toMutableList()
        if (list.any { it.id == track.id }) return
        list.add(track)
        _loadedTracksB.value = list

        val controller = DeckController(getApplication(), "Deck B - ${track.title}")
        controller.loadTrack(track)
        val controllers = _controllersB.value.toMutableList()
        controllers.add(controller)
        _controllersB.value = controllers

        if (_isPlaying.value) controller.play()
        updateAllVolumes()

        syncClient.triggerLoadTrack("B", track.id, _roomCode.value)
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

        _controllersA.value.forEach { it.setVolume(vol * crossA) }
        _controllersB.value.forEach { it.setVolume(vol * crossB) }
    }

    fun adjustPitch(deck: String, percent: Float) {
        val targetedIds = _selectedTrackIds.value
        if (targetedIds.isNotEmpty()) {
            targetedIds.forEach { targetedId ->
                val idxA = _loadedTracksA.value.indexOfFirst { it.id == targetedId }
                if (idxA != -1) {
                    _controllersA.value[idxA].setPlaybackRate(1f + percent / 100f)
                }
                val idxB = _loadedTracksB.value.indexOfFirst { it.id == targetedId }
                if (idxB != -1) {
                    _controllersB.value[idxB].setPlaybackRate(1f + percent / 100f)
                }
            }
        } else {
            // Modify all tracks in the respective Deck Zone
            if (deck == "A") {
                _controllersA.value.forEach { it.setPlaybackRate(1f + percent / 100f) }
            } else {
                _controllersB.value.forEach { it.setPlaybackRate(1f + percent / 100f) }
            }
        }
    }

    fun autoSync() {
        val baseBpm = _loadedTracksA.value.firstOrNull()?.bpm ?: 120
        _controllersA.value.forEach { it.setPlaybackRate(baseBpm.toFloat() / (it.loadedTrack?.bpm ?: 120)) }
        _controllersB.value.forEach { it.setPlaybackRate(baseBpm.toFloat() / (it.loadedTrack?.bpm ?: 120)) }
        _feedbackMsg.value = "Synced all platter play rates to baseline ($baseBpm BPM)"
    }

    fun startAutoDiscovery() {
        _feedbackMsg.value = "Broadcasting LAN search..."
        syncClient.startLanDiscovery()
    }

    fun connectToRoom(wsUrl: String, code: String) {
        _roomCode.value = code
        syncClient.connect(wsUrl)
    }

    fun addTrackManually(title: String, artist: String, bpm: Int, key: String, camelot: String, energy: Int, path: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val newTrack = Track(
                id = "track-${System.currentTimeMillis()}",
                title = title,
                artist = artist,
                bpm = bpm,
                keyName = key,
                camelotKey = camelot,
                progression = if (camelot.endsWith("A")) "i - bVI - bIII - bVII" else "I - V - vi - IV",
                atmosphere = "custom, manual",
                energyLevel = energy,
                mixTips = "Custom manual track.",
                youtubeId = null,
                localPath = path
            )
            trackDao.insertTrack(newTrack)
        }
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch(Dispatchers.IO) {
            trackDao.deleteTrack(track)
            removeTrackFromDecks(track.id)
        }
    }

    fun analyzeTrack(query: String, path: String? = null, fileName: String? = null) {
        _feedbackMsg.value = "Running song analysis..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val track = analyzer.analyze(query, fileName)
                val finalTrack = if (path != null) track.copy(localPath = path) else track
                trackDao.insertTrack(finalTrack)
                _feedbackMsg.value = "Track Analyzed: ${finalTrack.title}"
            } catch (e: Exception) {
                _feedbackMsg.value = "Analysis Failed. Used procedural fallback."
            }
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
        synthEngine.frequency = x * 1500f + 50f
        synthEngine.filterCutoff = y
    }

    override fun onSamplerTriggerEvent(padId: Int) {
        synthEngine.playSample(padId)
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
        synthEngine.release()
        syncClient.disconnect()
    }
}
