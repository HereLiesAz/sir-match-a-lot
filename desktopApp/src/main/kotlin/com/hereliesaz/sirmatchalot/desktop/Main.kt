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
 * A touch-laptop's entry point into a room.
 *
 * This is deliberately *only* the room — pairing, roster, live status — and
 * not the mixer. There is no desktop playback backend yet (see
 * `docs/ARCHITECTURE.md`), so a laptop here is a controller, the same shape
 * a phone in `PADS`/`LIBRARY` role already is: it can be in the room and
 * see it happen, before it can make it happen.
 */
fun main() = application {
    val session = remember { RoomSession(DesktopKeyValueStore()) }
    Window(onCloseRequest = ::exitApplication, title = "Sir Match-a-Lot") {
        MaterialTheme {
            RoomScreen(session)
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
