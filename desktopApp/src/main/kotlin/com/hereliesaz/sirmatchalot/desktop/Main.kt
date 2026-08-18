package com.hereliesaz.sirmatchalot.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

/**
 * A touch-laptop's entry point: the room, and one deck.
 *
 * The room half (pairing, roster, live status) and the playback half (load a
 * local file, play it through [DesktopAudioOutput]) are independent — a
 * laptop can be in a room without anything loaded, or play a file with no
 * room at all. Wiring the two together (a loaded deck reacting to room
 * state, the way `SirMatchALotViewModel` does) is UI work on top of two
 * already-working halves, not new plumbing.
 */
fun main() = application {
    val roomSession = remember { RoomSession(DesktopKeyValueStore()) }
    val playback = remember { PlaybackSession() }
    Window(
        onCloseRequest = {
            playback.release()
            exitApplication()
        },
        title = "Sir Match-a-Lot",
    ) {
        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                PlaybackPanel(playback, modifier = Modifier.padding(24.dp))
                RoomScreen(roomSession)
            }
        }
    }
}

@Composable
private fun PlaybackPanel(playback: PlaybackSession, modifier: Modifier = Modifier) {
    val loadedFileName by playback.loadedFileName.collectAsState()
    val isPlaying by playback.isPlaying.collectAsState()
    val loadErrorMessage by playback.loadErrorMessage.collectAsState()
    val outputErrorMessage by playback.outputErrorMessage.collectAsState()
    var pathInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Deck A", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = { Text("Path to a WAV/AIFF/AU file") },
            )
            Button(onClick = { playback.load(pathInput) }, enabled = pathInput.isNotBlank()) {
                Text("Load")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { if (isPlaying) playback.stop() else playback.play() },
                enabled = loadedFileName != null,
            ) { Text(if (isPlaying) "Stop" else "Play") }
            loadedFileName?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        outputErrorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        loadErrorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
