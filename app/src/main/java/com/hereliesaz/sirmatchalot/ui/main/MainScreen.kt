package com.hereliesaz.sirmatchalot.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hereliesaz.sirmatchalot.ui.SirMatchALotViewModel
import com.hereliesaz.sirmatchalot.ui.LibraryScreen
import com.hereliesaz.sirmatchalot.ui.platter.PlatterActions
import com.hereliesaz.sirmatchalot.ui.platter.PlatterGeometry
import com.hereliesaz.sirmatchalot.ui.platter.PlatterScreen
import com.hereliesaz.sirmatchalot.ui.SamplerScreen
import com.hereliesaz.sirmatchalot.sync.SyncRole

enum class DjTab {
    LIBRARY, CONTROLS, PERFORMANCE
}

/**
 * The screen a role is for.
 *
 * This is where "each device shows a different screen" actually happens: a phone
 * that joined as the pad player opens on the pads and its other tabs go dark,
 * so the room is one instrument with several surfaces rather than three copies
 * of the same app shouting at each other.
 */
fun SyncRole.tab(): DjTab = when (this) {
    SyncRole.LIBRARY -> DjTab.LIBRARY
    SyncRole.PADS -> DjTab.PERFORMANCE
    SyncRole.DECKS, SyncRole.ALL -> DjTab.CONTROLS
}

/** True when a device in this role may reach [tab]. */
fun SyncRole.allows(tab: DjTab): Boolean = isFullControl || tab == tab()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: SirMatchALotViewModel = viewModel()
) {
    // Waveform Circle Platter is the Unified Main Screen
    var currentTab by remember { mutableStateOf(DjTab.CONTROLS) }
    val crossfader by viewModel.crossfader.collectAsState()
    val isWsConnected by viewModel.isWsConnected.collectAsState()
    val roomCode by viewModel.roomCode.collectAsState()

    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var showSyncDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showWork by remember { mutableStateOf(false) }
    val settings by viewModel.settings.collectAsState()
    val engineGeneration by viewModel.engineGeneration.collectAsState()
    val activeWork by viewModel.activeWork.collectAsState()
    val feedbackMsg by viewModel.feedbackMsg.collectAsState()

    // The detail sheet is worth nothing once the work it describes is done, and
    // a dialog left open over an empty list is a dead end to back out of.
    LaunchedEffect(activeWork.isEmpty()) {
        if (activeWork.isEmpty()) showWork = false
    }

    // Nothing on this screen is worth a wake-up when the screen is not in front
    // of anyone. The ViewModel's publishing loop runs off this, and used to run
    // regardless — sixty-two times a second, for the life of the process.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.setUiActive(true)
        onPauseOrDispose { viewModel.setUiActive(false) }
    }
    var inputIp by remember { mutableStateOf("192.168.1.100") }
    var inputCode by remember { mutableStateOf("ROOM") }
    val isPlaying by viewModel.isPlaying.collectAsState()
    val keylock by viewModel.keylock.collectAsState()
    val isHosting by viewModel.isHosting.collectAsState()
    val peerCount by viewModel.peerCount.collectAsState()
    val hostUrl by viewModel.hostUrl.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var linkInput by remember { mutableStateOf("") }
    val role by viewModel.role.collectAsState()

    // Changing role moves the device to the screen it is now for, rather than
    // leaving it on a tab it is no longer allowed to reach.
    LaunchedEffect(role) { currentTab = role.tab() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Weighted and ellipsised: with the work indicator
                        // showing there are four things competing for a phone's
                        // width, and the name of the app is the one of them
                        // nobody needs to finish reading.
                        Text(
                            text = "SIR MATCH-A-LOT",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            letterSpacing = 1.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        // What the app is doing, wherever you are. Everything
                        // slow here reported itself on exactly one tab — and
                        // never the tab you started it from.
                        if (activeWork.isNotEmpty()) {
                            WorkIndicator(
                                work = activeWork,
                                onClick = { showWork = true },
                            )
                        }

                        // Where the memory and battery levers live. Reachable
                        // from every tab and every role, because running out of
                        // heap is not something that waits for a convenient
                        // screen to be open.
                        IconButton(
                            onClick = { showSettings = true },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color(0xFF9CA3AF),
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        // Wireless Sync Link Indicator
                        Button(
                            onClick = { showSyncDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isWsConnected) Color(0xFF10B981) else Color(0xFFEF4444)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Icon(
                                imageVector = if (isWsConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = "Sync Info",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isWsConnected) "SYNC LINKED ($roomCode)" else "SYNC OFFLINE",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF09090B))
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(Color(0xFF09090B))) {
                // Global DJ Crossfader Slider (shared above tabs)
                Divider(color = Color(0xFF18181B))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("DECK A", color = Color(0xFF06B6D4), fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.width(45.dp))
                    Slider(
                        value = crossfader.toFloat(),
                        onValueChange = { viewModel.setCrossfaderValue(it.toInt()) },
                        valueRange = -100f..100f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF27272A),
                            inactiveTrackColor = Color(0xFF27272A)
                        )
                    )
                    Text("DECK B", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.width(45.dp))
                }

                // Beat-sync and key-match Deck B to Deck A. Until now this was
                // reachable only from a remote sync event, so the app's harmonic
                // mixing had no local control at all.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { viewModel.syncToDeckA() }) {
                        Text(
                            "SYNC B → A",
                            color = Color(0xFF22D3EE),
                            fontWeight = FontWeight.Black,
                            fontSize = 9.sp,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.setKeylock(!keylock) }) {
                        Text(
                            if (keylock) "KEYLOCK ON" else "KEYLOCK OFF",
                            // Off is the turntable behaviour the scratch gestures
                            // rely on, so neither state is an error state.
                            color = if (keylock) Color(0xFF22D3EE) else Color(0xFF71717A),
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                        )
                    }
                }

                NavigationBar(
                    containerColor = Color(0xFF18181B),
                    tonalElevation = 8.dp,
                    modifier = Modifier.height(64.dp)
                ) {
                    NavigationBarItem(
                        selected = currentTab == DjTab.CONTROLS,
                        enabled = role.allows(DjTab.CONTROLS),
                        onClick = { currentTab = DjTab.CONTROLS },
                        label = { Text("Platter Main", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.Refresh, contentDescription = "Platter Main") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Cyan,
                            selectedTextColor = Color.Cyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color.Transparent
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == DjTab.LIBRARY,
                        enabled = role.allows(DjTab.LIBRARY),
                        onClick = { currentTab = DjTab.LIBRARY },
                        label = { Text("Library", fontSize = 10.sp) },
                        icon = { Icon(Icons.Default.List, contentDescription = "Library") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Cyan,
                            selectedTextColor = Color.Cyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color.Transparent
                        )
                    )

                    // Play/pause is not here any more. It is at the centre of
                    // the platter, under the waveform, where the hand already
                    // is — a transport control in the tab bar sat among things
                    // that navigate, pretending to be one of them.

                    NavigationBarItem(
                        selected = currentTab == DjTab.PERFORMANCE,
                        enabled = role.allows(DjTab.PERFORMANCE),
                        onClick = { currentTab = DjTab.PERFORMANCE },
                        label = { Text("Sampler", fontSize = 10.sp) },
                        icon = { Icon(Icons.Default.Star, contentDescription = "Sampler") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Cyan,
                            selectedTextColor = Color.Cyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF09090B))
        ) {
            // Each tab keeps its own saved state — scroll positions above all.
            //
            // A `when` swaps composables, which disposes the one leaving and
            // discards everything it remembered, so scrolling to the far end of
            // a library and glancing at the platter put you back at the top on
            // return. A SaveableStateHolder keeps each tab's saveable state
            // while it is off screen and restores it when it comes back, which
            // is what a tab bar implies and what a `when` alone does not do.
            stateHolder.SaveableStateProvider(currentTab) {
            when (currentTab) {
                DjTab.LIBRARY -> LibraryScreen(viewModel = viewModel)
                DjTab.CONTROLS -> {
                    val platterState by viewModel.platterState.collectAsState()
                    // Ordered by what mixes next, and each card carrying its
                    // own score. This was `viewModel.tracks` — the raw table in
                    // insertion order, which is the one list in the app that
                    // every measurement it makes had no effect on.
                    val ranked by viewModel.rankedTracks.collectAsState()
                    val sort by viewModel.librarySort.collectAsState()
                    PlatterScreen(
                        state = platterState,
                        tracks = ranked,
                        actions = rememberPlatterActions(viewModel),
                        transportRunning = isPlaying,
                        lightShow = settings.lightShow,
                        sortLabel = sort.label,
                        onCycleSort = { viewModel.cycleLibrarySort() },
                    )
                }
                // Keyed on the engine generation: the pad grid holds the
                // sampler itself, and changing the sample rate builds a new
                // engine with new pads. Without this the grid would keep
                // driving a released one.
                DjTab.PERFORMANCE -> key(engineGeneration) {
                    SamplerScreen(viewModel = viewModel)
                }
            }
            }

            // Drawn over the content rather than above it. Taking layout space
            // would mean the platter resized itself every time a message
            // appeared or expired — including mid-gesture, with a finger on it.
            FeedbackBanner(
                message = feedbackMsg,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    if (showWork) {
        AlertDialog(
            onDismissRequest = { showWork = false },
            containerColor = Color(0xFF18181B),
            title = {
                Text(
                    if (activeWork.size == 1) "Working" else "${activeWork.size} things running",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (item in activeWork) {
                        Column {
                            Text(item.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            if (!item.detail.isNullOrBlank()) {
                                Text(
                                    item.detail!!,
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            // A known fraction gets a bar; an unknown one gets a
                            // moving line. Neither pretends to be the other.
                            val fraction = item.progress
                            if (fraction != null) {
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color.Cyan,
                                    trackColor = Color(0xFF27272A),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color.Cyan,
                                    trackColor = Color(0xFF27272A),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWork = false }) {
                    Text("Close", color = Color(0xFF22D3EE), fontWeight = FontWeight.Bold)
                }
            },
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = Color(0xFF18181B),
            title = {
                Text(
                    "Settings",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            },
            text = {
                com.hereliesaz.sirmatchalot.ui.SettingsScreen(viewModel = viewModel)
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("Done", color = Color(0xFF22D3EE), fontWeight = FontWeight.Bold)
                }
            },
        )
    }

    // Sync Dialog
    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            containerColor = Color(0xFF18181B),
            title = { Text("Link Multi-Device Session", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(
                    // The dialog now carries hosting, joining, roles and links;
                    // without this the last controls are off the bottom of a
                    // phone and unreachable.
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // One device has to host. Nothing answered the discovery
                    // broadcast before this, which is why auto-connect could
                    // never find anything.
                    Text(
                        if (isHosting) {
                            "Hosting room $roomCode — $peerCount ${if (peerCount == 1) "device" else "devices"} joined" +
                                (hostUrl?.let { "\n$it" } ?: "\nNot on a network")
                        } else {
                            "One device hosts the room; the others find it automatically on the same Wi-Fi."
                        },
                        color = if (isHosting) Color(0xFF6EE7B7) else Color.LightGray,
                        fontSize = 11.sp,
                    )

                    Button(
                        onClick = { if (isHosting) viewModel.stopHosting() else viewModel.startHosting() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isHosting) Color(0xFFDC2626) else Color(0xFF059669),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (isHosting) "STOP HOSTING" else "HOST THIS DEVICE",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                        )
                    }

                    Divider(color = Color(0xFF27272A))

                    // "Each device shows a different screen": the role picks
                    // which one this device is, and the tab bar follows.
                    Text("This device is for:", color = Color.LightGray, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SyncRole.entries.forEach { option ->
                            FilterChip(
                                selected = role == option,
                                onClick = { viewModel.setRole(option) },
                                label = { Text(option.label, fontSize = 9.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    labelColor = Color.LightGray,
                                    selectedContainerColor = Color(0xFF0E7490),
                                    selectedLabelColor = Color.White,
                                ),
                            )
                        }
                    }

                    Divider(color = Color(0xFF27272A))

                    Text("Auto Connect will search your local Wi-Fi for a hosting device.", color = Color.LightGray, fontSize = 11.sp)

                    Button(
                        onClick = {
                            viewModel.startAutoDiscovery()
                            showSyncDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ONE-CLICK AUTO CONNECT", color = Color.White, fontWeight = FontWeight.Black)
                    }

                    Divider(color = Color(0xFF27272A))

                    Text("Or enter connection parameters manually:", color = Color.LightGray, fontSize = 11.sp)
                    OutlinedTextField(
                        value = inputIp,
                        onValueChange = { inputIp = it },
                        label = { Text("Server Base IP", color = Color.Gray, fontSize = 10.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Cyan,
                            unfocusedBorderColor = Color(0xFF27272A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { inputCode = it },
                        label = { Text("Room Code", color = Color.Gray, fontSize = 10.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Cyan,
                            unfocusedBorderColor = Color(0xFF27272A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    Divider(color = Color(0xFF27272A))

                    // The loaded session as a link: readable query parameters,
                    // so it can be pasted anywhere and read by anything.
                    Text("Share this session, or open one:", color = Color.LightGray, fontSize = 11.sp)
                    Button(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(viewModel.sessionLink().toUrl()))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("COPY SESSION LINK", color = Color.White, fontWeight = FontWeight.Black)
                    }
                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it },
                        label = { Text("Paste a session link", color = Color.Gray, fontSize = 10.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF7C3AED),
                            unfocusedBorderColor = Color(0xFF27272A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Button(
                        onClick = {
                            viewModel.openSessionLink(linkInput)
                            linkInput = ""
                            showSyncDialog = false
                        },
                        enabled = linkInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E7490)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("OPEN SESSION", color = Color.White, fontWeight = FontWeight.Black)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanIp = inputIp.trim()
                        // The port the app's own host listens on. The old value
                        // pointed at a server that never existed.
                        val wsUrl = "ws://$cleanIp:${com.hereliesaz.sirmatchalot.sync.SyncServer.DEFAULT_PORT}"
                        viewModel.connectToRoom(wsUrl, inputCode.trim())
                        showSyncDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)
                ) {
                    Text("Connect Manual", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSyncDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}

/**
 * Binds the platter's gestures to the audio engine.
 *
 * Kept as a separate adapter so [PlatterScreen] depends only on an interface and
 * can be previewed or driven by a synthetic platter in a test.
 */
@Composable
private fun rememberPlatterActions(viewModel: SirMatchALotViewModel): PlatterActions =
    remember(viewModel) {
        object : PlatterActions {
            override fun onCrossfade(delta: Float) = viewModel.nudgeCrossfade(delta)

            override fun onScratchBegin() = viewModel.audioEngine.beginScratch()

            override fun onScratch(totalDeltaY: Float) = viewModel.audioEngine.updateScratch(totalDeltaY)

            override fun onScratchEnd() = viewModel.audioEngine.endScratch()

            override fun onVolume(delta: Float) = viewModel.nudgeMasterVolume(delta)

            override fun onTogglePlayback() = viewModel.togglePlayback()

            override fun onBassBoost(delta: Float) = viewModel.nudgeBassBoost(delta)

            override fun onSelectAt(deck: PlatterGeometry.Deck, fraction: Float, additive: Boolean) {
                val clip = viewModel.platterState.value.clipAt(deck, fraction) ?: return
                val current = viewModel.selectedTrackIds.value
                viewModel.setSelectionAndPublish(
                    when {
                        additive && clip.id in current -> current - clip.id
                        additive -> current + clip.id
                        clip.id in current -> current - clip.id
                        else -> setOf(clip.id)
                    },
                )
            }

            // Double tap selects both decks' waveforms at that spot.
            override fun onSelectBothAt(fraction: Float) {
                val state = viewModel.platterState.value
                val ids = listOfNotNull(
                    state.clipAt(PlatterGeometry.Deck.A, fraction)?.id,
                    state.clipAt(PlatterGeometry.Deck.B, fraction)?.id,
                ).toSet()
                if (ids.isNotEmpty()) viewModel.setSelectionAndPublish(ids)
            }

            // Long press removes the selected track(s).
            override fun onRemoveSelected() = viewModel.removeSelectedClips()

            override fun onLoadTrack(track: com.hereliesaz.sirmatchalot.data.Track) {
                // Fill Deck A first, then Deck B, then keep stacking onto A.
                val state = viewModel.platterState.value
                val deck =
                    if (state.deckA.isEmpty() || state.deckB.isNotEmpty()) PlatterGeometry.Deck.A
                    else PlatterGeometry.Deck.B
                viewModel.loadOntoDeck(track, deck)
            }

            override fun onDropTrack(
                track: com.hereliesaz.sirmatchalot.data.Track,
                deck: PlatterGeometry.Deck,
                fraction: Float,
            ) {
                // A drop says which deck and where on it; nothing is inferred.
                viewModel.loadOntoDeck(track, deck, atFraction = fraction)
            }

            override fun onMoveClip(
                clipId: String,
                deck: PlatterGeometry.Deck,
                fraction: Float,
            ) = viewModel.moveClip(clipId, deck, fraction)

            override fun onRemoveClip(clipId: String) =
                viewModel.removeTrackFromDecks(clipId)

            override fun onScaleClip(
                clipId: String,
                deck: PlatterGeometry.Deck,
                ratio: Float,
            ) = viewModel.scaleClip(clipId, deck, ratio.toDouble())
        }
    }

/**
 * The one place the app says it is busy, in the bar every tab shares.
 *
 * A spinner and the name of the work, which is more than the count on its own
 * would say: "loading" and "loading Blue Monday — stretching to 128 BPM" answer
 * different questions, and the second is the one being asked when a drop on the
 * platter has apparently done nothing for twenty seconds.
 *
 * Only the first item is named. Several things at once is normal here — a mix
 * director preloads while the library analyses — and cycling names would make a
 * bar that reads as broken; the count says there is more, and tapping shows all
 * of it.
 */
@Composable
private fun WorkIndicator(
    work: List<com.hereliesaz.sirmatchalot.ui.WorkItem>,
    onClick: () -> Unit,
) {
    val first = work.firstOrNull() ?: return
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0E3A44))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Determinate where the fraction is known, so a long analysis run reads
        // as progress rather than as a hang with a nice animation on it.
        val fraction = first.progress
        if (fraction != null) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(11.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF22D3EE),
                trackColor = Color(0xFF164E5C),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF22D3EE),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = first.summary(),
            color = Color(0xFF7DD3FC),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp),
        )
        if (work.size > 1) {
            Spacer(Modifier.width(4.dp))
            Text(
                "+${work.size - 1}",
                color = Color(0xFF22D3EE),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * The last thing the app said, wherever you are.
 *
 * `feedbackMsg` carries every outcome the app reports — what a track conformed
 * to, that the platter dropped a clip to make room, that a file would not
 * decode, that it ran out of memory and which setting would help — and it was
 * rendered on the Library tab and nowhere else. Most of those messages are
 * about things that happen while you are on the platter.
 *
 * Dismisses itself, because it is a report on something finished rather than a
 * state to be acknowledged, and a line that stays forever stops being read.
 */
@Composable
private fun FeedbackBanner(message: String, modifier: Modifier = Modifier) {
    var visible by remember(message) { mutableStateOf(message.isNotBlank()) }
    LaunchedEffect(message) {
        if (message.isBlank()) return@LaunchedEffect
        visible = true
        kotlinx.coroutines.delay(6_000)
        visible = false
    }
    if (!visible) return

    Text(
        text = message,
        color = Color(0xFF81E6D9),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC0B1A1F))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
