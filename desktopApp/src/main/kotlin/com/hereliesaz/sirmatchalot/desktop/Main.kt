package com.hereliesaz.sirmatchalot.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.Frame

/**
 * A touch-laptop's entry point: the room, and a two-deck mixing instrument.
 *
 * The room half (pairing, roster, live status) and the playback half (two
 * decks, a crossfader, an eight-pad sampler, all played through
 * [DesktopAudioOutput]) are independent — a laptop can be in a room without
 * anything loaded, or mix locally with no room at all. Wiring the two
 * together (a loaded deck reacting to room state, the way
 * `SirMatchALotViewModel` does) is UI work on top of two already-working
 * halves, not new plumbing.
 */
fun main() {
    // Installed before anything else runs, so nothing in the window setup
    // itself is uncaptured. See DesktopCrashLog's own doc for what this is
    // and isn't — a plain-text last-crash file, not the Android app's
    // prefilled-issue flow.
    DesktopCrashLog().installAsUncaughtExceptionHandler()
    runApplication()
}

private fun runApplication() = application {
    val roomSession = remember { RoomSession(DesktopKeyValueStore()) }
    val playback = remember { PlaybackSession() }
    val library = remember { DesktopLibrary() }
    Window(
        onCloseRequest = {
            playback.release()
            exitApplication()
        },
        title = "Sir Match-a-Lot",
    ) {
        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                PlaybackPanel(playback, library, window, modifier = Modifier.padding(24.dp))
                RoomScreen(roomSession)
            }
        }
    }
}

@Composable
private fun PlaybackPanel(
    playback: PlaybackSession,
    library: DesktopLibrary,
    owner: Frame,
    modifier: Modifier = Modifier,
) {
    val outputErrorMessage by playback.outputErrorMessage.collectAsState()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        outputErrorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            DeckPanel("Deck A", playback.deckA, owner, modifier = Modifier.weight(1f))
            DeckPanel("Deck B", playback.deckB, owner, modifier = Modifier.weight(1f))
        }
        CrossfaderPanel(playback)
        SamplerPanel(playback.samplerPads, owner)
        LibraryPanel(library, playback.deckA, playback.deckB, owner)
    }
}

@Composable
private fun DeckPanel(title: String, deck: DeckControl, owner: Frame, modifier: Modifier = Modifier) {
    val loadedFileName by deck.loadedFileName.collectAsState()
    val isPlaying by deck.isPlaying.collectAsState()
    val loadErrorMessage by deck.loadErrorMessage.collectAsState()
    var pathInput by remember { mutableStateOf("") }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = pathInput,
            onValueChange = { pathInput = it },
            label = { Text("Path to a WAV/AIFF/AU file") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { DesktopFilePicker.pickAudioFile(owner)?.let { pathInput = it.path } },
            ) { Text("Browse…") }
            Button(onClick = { deck.load(pathInput) }, enabled = pathInput.isNotBlank()) {
                Text("Load")
            }
            Button(
                onClick = { if (isPlaying) deck.stop() else deck.play() },
                enabled = loadedFileName != null,
            ) { Text(if (isPlaying) "Stop" else "Play") }
        }
        loadedFileName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        loadErrorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CrossfaderPanel(playback: PlaybackSession, modifier: Modifier = Modifier) {
    val crossfade by playback.crossfade.collectAsState()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Crossfader", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("A")
            Slider(
                value = crossfade,
                onValueChange = { playback.setCrossfade(it) },
                modifier = Modifier.weight(1f),
            )
            Text("B")
        }
    }
}

@Composable
private fun SamplerPanel(pads: List<SamplerPadControl>, owner: Frame, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sampler", style = MaterialTheme.typography.titleSmall)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().height(160.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pads) { pad -> SamplerPadButton(pad, owner) }
        }
    }
}

@Composable
private fun SamplerPadButton(pad: SamplerPadControl, owner: Frame) {
    val label by pad.label.collectAsState()
    val loadErrorMessage by pad.loadErrorMessage.collectAsState()
    var showLoader by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }

    Column {
        Button(
            onClick = { if (label != null) pad.trigger() else showLoader = !showLoader },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label ?: "Pad ${pad.index + 1}")
        }
        if (showLoader) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { DesktopFilePicker.pickAudioFile(owner)?.let { pathInput = it.path } },
                ) { Text("Browse…") }
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text("File") },
                )
                Button(
                    onClick = { pad.load(pathInput); showLoader = false },
                    enabled = pathInput.isNotBlank(),
                ) { Text("Load") }
            }
        }
        loadErrorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LibraryPanel(
    library: DesktopLibrary,
    deckA: DeckControl,
    deckB: DeckControl,
    owner: Frame,
    modifier: Modifier = Modifier,
) {
    val tracks by library.tracks.collectAsState()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { library.add(DesktopFilePicker.pickAudioFiles(owner)) }) { Text("Add files…") }
        }
        if (tracks.isEmpty()) {
            Text("No tracks yet — add a few WAV/AIFF/AU files.", style = MaterialTheme.typography.bodySmall)
        }
        tracks.forEach { track ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(track.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(track.analysisLabel(), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { deckA.load(track.path) }) { Text("→ A") }
                        OutlinedButton(onClick = { deckB.load(track.path) }) { Text("→ B") }
                        OutlinedButton(onClick = { library.remove(track.path) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomScreen(session: RoomSession) {
    val isHosting by session.isHosting.collectAsState()
    val isConnected by session.isConnected.collectAsState()
    val roomCode by session.roomCode.collectAsState()
    val hostUrl by session.hostUrl.collectAsState()
    val peerCount by session.peerCount.collectAsState()
    val statusMessage by session.statusMessage.collectAsState()
    val pendingPeers by session.pendingPeers.collectAsState()
    val pendingHostPairing by session.pendingHostPairing.collectAsState()

    var codeInput by remember { mutableStateOf("") }
    var addressInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sir Match-a-Lot — Desktop", style = MaterialTheme.typography.headlineSmall)

        if (!isHosting && !isConnected) {
            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it.uppercase() },
                label = { Text("Room code (blank to host a new one)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { session.startHosting(codeInput.ifBlank { null }) }) { Text("Host") }
                Button(
                    onClick = { session.findAndJoin(codeInput) },
                    enabled = codeInput.isNotBlank(),
                ) { Text("Find & join on this network") }
            }
            OutlinedTextField(
                value = addressInput,
                onValueChange = { addressInput = it },
                label = { Text("Or a host address, e.g. ws://192.168.1.20:8890") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { session.joinRoom(addressInput, codeInput) },
                enabled = addressInput.isNotBlank() && codeInput.isNotBlank(),
            ) { Text("Join by address") }
        }

        if (isHosting) {
            Text("Hosting room $roomCode", style = MaterialTheme.typography.titleMedium)
            hostUrl?.let { Text("Address: $it") }
            Text("Devices joined: $peerCount")
            Button(onClick = { session.stopHosting() }) { Text("Stop hosting") }
        }

        if (isConnected) {
            Text("Connected to room $roomCode", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { session.leaveRoom() }) { Text("Leave room") }
        }

        if (statusMessage.isNotBlank()) {
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
        }

        if (pendingPeers.isNotEmpty()) {
            Text("Waiting for approval", style = MaterialTheme.typography.titleSmall)
            pendingPeers.forEach { peer ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(peer.name)
                            Text("Code: ${peer.pairingCode}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { session.approvePeer(peer.id) }) { Text("Approve") }
                            OutlinedButton(onClick = { session.refusePeer(peer.id) }) { Text("Refuse") }
                        }
                    }
                }
            }
        }

        pendingHostPairing?.let { code ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Confirm this matches the host's screen:")
                    Text(code, style = MaterialTheme.typography.headlineMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { session.approveHostPairing() }) { Text("Matches") }
                        OutlinedButton(onClick = { session.refuseHostPairing() }) { Text("Doesn't match") }
                    }
                }
            }
        }
    }
}
