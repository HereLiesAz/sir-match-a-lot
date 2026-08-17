package com.hereliesaz.sirmatchalot.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.data.LinkParser
import com.hereliesaz.sirmatchalot.domain.MixMatch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: SirMatchALotViewModel,
    modifier: Modifier = Modifier
) {
    // The filtered, sorted list — the previous screen rendered the raw list and
    // ignored the ViewModel's sorting entirely, so none of it had any effect.
    val tracks by viewModel.visibleTracks.collectAsState()
    val feedbackMsg by viewModel.feedbackMsg.collectAsState()
    val librarySort by viewModel.librarySort.collectAsState()
    val compatiblePairs by viewModel.compatiblePairs.collectAsState()
    val searchQuery by viewModel.libraryFilter.collectAsState()
    val isAutoMixing by viewModel.isAutoMixing.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val transitionProgress by viewModel.transitionProgress.collectAsState()
    val transitionStyle by viewModel.transitionStyle.collectAsState()
    val analysisProgress by viewModel.analysisProgress.collectAsState()
    var linkInput by rememberSaveable { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let { viewModel.importTrack(it) }
        }
    )

    // A whole folder, walked recursively — a music library is a folder of
    // folders, so importing only the top level would usually find nothing.
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? -> uri?.let { viewModel.importFolder(it) } },
    )

    val backgroundAnalysis by viewModel.backgroundAnalysis.collectAsState()
    val missingFromSession by viewModel.missingFromSession.collectAsState()

    // CreateDocument rather than a path of our own: the user decides where a
    // session they may want to send someone actually lives.
    val saveSessionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        onResult = { uri: Uri? ->
            uri?.let { viewModel.saveSession(it, viewModel.suggestedSessionName()) }
        },
    )

    val openSessionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? -> uri?.let { viewModel.openSession(it) } },
    )

    // The whole screen is one LazyColumn, header controls included, rather
    // than a fixed Column with a weight(1f) LazyColumn buried inside it. The
    // fixed chrome above the track list — three rows of import buttons, two
    // multi-line text fields, four more button rows, an optional missing-
    // tracks panel — routinely added up to more vertical space than a phone
    // screen has, and weight(1f) only distributes space that is left over:
    // with none left, the list rendered zero to one row with no way to
    // scroll to the rest. Folding everything into one scrollable list means
    // the tracks are always reachable, however tall the chrome above them is.
    LazyColumn(
        // Hoisted so it is saveable: with the tab content wrapped in a
        // SaveableStateHolder, where you were in a long library survives
        // leaving the tab and coming back.
        state = androidx.compose.foundation.lazy.rememberLazyListState(),
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF09090B)),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MIX LIBRARY", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)

                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("audio/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = "Import File", tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("File", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }

                Button(
                    onClick = { folderPickerLauncher.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E7490)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Import Folder", tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Folder", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }

                // Store packs arrive when asked for. They used to download
                // themselves whenever the library was empty, which is a built-in
                // clip that took a detour through someone's mobile data.
                Button(
                    onClick = { viewModel.importAzphaltPack() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = "Azphalt Store", tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Store", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        // The background run, mirrored from the same state its notification
        // shows, so the two can never disagree.
        if (backgroundAnalysis.total > 0) {
            item {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF111827))
                        .padding(10.dp),
                ) {
                    Text(
                        text = (if (backgroundAnalysis.paused) "PAUSED " else "ANALYSING ") +
                            "${backgroundAnalysis.done}/${backgroundAnalysis.total}" +
                            (if (backgroundAnalysis.current.isNotEmpty()) " — ${backgroundAnalysis.current}" else ""),
                        color = Color(0xFF81E6D9),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    LinearProgressIndicator(
                        progress = { backgroundAnalysis.fraction },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        color = Color.Cyan,
                        trackColor = Color(0xFF27272A),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                if (backgroundAnalysis.paused) viewModel.resumeBackgroundAnalysis()
                                else viewModel.pauseBackgroundAnalysis()
                            },
                        ) {
                            Text(
                                if (backgroundAnalysis.paused) "RESUME" else "PAUSE",
                                color = Color(0xFF22D3EE),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                        TextButton(onClick = { viewModel.stopBackgroundAnalysis() }) {
                            Text("STOP", color = Color(0xFF9CA3AF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setLibraryFilter(it) },
                    placeholder = { Text("Filter by title or artist...", color = Color.Gray, fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF18181B)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Cyan,
                        unfocusedBorderColor = Color(0xFF27272A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Button(
                    onClick = {
                        if (analysisProgress != null) viewModel.cancelAnalysis() else viewModel.analysePending()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (analysisProgress != null) Color(0xFFDC2626) else Color.Cyan,
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (analysisProgress != null) "Stop" else "Analyse new",
                        color = if (analysisProgress != null) Color.White else Color.Black,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // Analysis is an FFT pass over each whole track and takes real seconds.
        // Without this the button was indistinguishable from a dead one for the
        // length of the run.
        analysisProgress?.let { progress ->
            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Analysing ${progress.done + 1}/${progress.total}: ${progress.current}",
                    color = Color(0xFF81E6D9),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = Color.Cyan,
                    trackColor = Color(0xFF27272A),
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))

            // Import by link. There was previously no way to paste anything at all:
            // LinkParser existed but was called from nowhere, so a playlist could
            // not be imported by any route.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = linkInput,
                    onValueChange = { linkInput = it },
                    placeholder = {
                        Text(
                            "Playlist link, .m3u, or a pasted tracklist",
                            color = Color.Gray,
                            fontSize = 11.sp,
                        )
                    },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF18181B)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF7C3AED),
                        unfocusedBorderColor = Color(0xFF27272A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                )
                Button(
                    onClick = {
                        viewModel.importFromLink(linkInput)
                        linkInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Import", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))

            // Shuffle Crate and the Automatchic Mix, both driven by measured values.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.shuffleCrate() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("SHUFFLE CRATE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 10.sp)
                }
                Button(
                    onClick = { viewModel.buildAutomatchicMix() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDB2777)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("AUTOMATCHIC MIX", color = Color.White, fontWeight = FontWeight.Black, fontSize = 10.sp)
                }
            }
        }

        item {
            // Planning and performing are separate actions: the plan is worth seeing
            // before it is committed to, and it was previously the only half that
            // existed.
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    if (isAutoMixing) viewModel.stopAutomatchicMix() else viewModel.startAutomatchicMix()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAutoMixing) Color(0xFFDC2626) else Color(0xFF059669),
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isAutoMixing) "STOP THE MIX" else "PLAY THE MIX",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                )
            }
        }

        item {
            // A session is the set as arranged — the lineup, where every clip sits on
            // its deck, the pads and their takes. Saving it is the difference between
            // an evening's work and an evening.
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = { saveSessionLauncher.launch("${viewModel.suggestedSessionName()}.sir") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F3F46)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("SAVE SESSION", color = Color.White, fontWeight = FontWeight.Black, fontSize = 10.sp)
                }
                Button(
                    // Any type: a `.sir` has no registered MIME type, and filtering on
                    // one the system has never heard of hides the file the user is
                    // looking straight at.
                    onClick = { openSessionLauncher.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F3F46)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("OPEN SESSION", color = Color.White, fontWeight = FontWeight.Black, fontSize = 10.sp)
                }
            }
        }

        // Naming what a restored session could not find. The feedback line says it
        // once and vanishes; this is the list the user has to actually act on.
        if (missingFromSession.isNotEmpty()) {
            item {
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3F1D1D))
                        .padding(10.dp),
                ) {
                    Text(
                        text = "NOT IN YOUR LIBRARY — ${missingFromSession.size}",
                        color = Color(0xFFFCA5A5),
                        fontWeight = FontWeight.Black,
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                    )
                    for (track in missingFromSession.take(6)) {
                        Text(
                            text = "${track.title}${if (track.artist.isBlank()) "" else " — ${track.artist}"}",
                            color = Color(0xFFFECACA),
                            fontSize = 10.sp,
                        )
                    }
                    if (missingFromSession.size > 6) {
                        Text(
                            text = "and ${missingFromSession.size - 6} more",
                            color = Color(0xFFFCA5A5),
                            fontSize = 10.sp,
                        )
                    }
                    Text(
                        text = "DISMISS",
                        color = Color(0xFFFCA5A5),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { viewModel.clearMissingFromSession() }
                            .padding(top = 4.dp, end = 6.dp),
                    )
                }
            }
        }

        nowPlaying?.let { current ->
            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "NOW ${current.index + 1}/${current.total} — ${current.step.track.title}" +
                        (current.step.transition?.let { " · ${it.overallScore}% in" } ?: ""),
                    color = Color(0xFF6EE7B7),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (transitionProgress > 0f) {
                    // Naming the move is not decoration. A set that varies its
                    // transitions and describes every one of them as a nameless
                    // green bar still reads as one thing happening over and over.
                    transitionStyle?.let { style ->
                        Text(
                            text = style.label.uppercase(),
                            color = Color(0xFFFBBF24),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    LinearProgressIndicator(
                        progress = { transitionProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = Color(0xFF059669),
                        trackColor = Color(0xFF27272A),
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))

            // The harmonic filter: order the library by Camelot proximity to Deck A.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SirMatchALotViewModel.LibrarySort.entries.toList()) { option ->
                    val selected = option == librarySort
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) Color(0xFF0891B2) else Color(0xFF18181B))
                            .border(
                                1.dp,
                                if (selected) Color.Cyan else Color(0xFF27272A),
                                RoundedCornerShape(20.dp)
                            )
                            .selectable(
                                selected = selected,
                                onClick = { viewModel.setLibrarySort(option) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            option.label,
                            color = if (selected) Color.White else Color(0xFF9CA3AF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (feedbackMsg.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF16202A))
                        .border(1.dp, Color(0xFF1E3A5F), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = "Feedback", tint = Color.Cyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(feedbackMsg, color = Color(0xFF81E6D9), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text("TRACKS (${tracks.size})", color = Color(0xFF9CA3AF), fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
        }

        items(tracks, key = { it.id }) { track ->
            TrackRowItem(
                track = track,
                onLoadA = { viewModel.addTrackToDeckA(track) },
                onLoadB = { viewModel.addTrackToDeckB(track) },
                onDelete = { viewModel.deleteTrack(track) }
            )
            Spacer(Modifier.height(8.dp))
        }

        item {
            Spacer(Modifier.height(16.dp))
            CompatibleTransitionsSection(pairs = compatiblePairs, onLoadPair = { a, b ->
                viewModel.addTrackToDeckA(a)
                viewModel.addTrackToDeckB(b)
            })
        }
    }
}

@Composable
fun TrackRowItem(
    track: Track,
    onLoadA: () -> Unit,
    onLoadB: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val energy = track.energyLevel
    val energyColor = when {
        energy == null -> Color(0xFF9CA3AF)
        energy >= 8 -> Color(0xFFF43F5E)
        energy >= 5 -> Color(0xFFA855F7)
        else -> Color(0xFF06B6D4)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF18181B))
            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(track.artist, color = Color.LightGray, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                if (!track.isAnalysed) {
                    // Amber, not the green this used to be: green is this
                    // app's colour for a good match and a connected sync
                    // link everywhere else, and styling "this track has no
                    // BPM or key yet" the same way as an achievement was
                    // exactly backwards.
                    Text(
                        "NOT ANALYSED",
                        color = Color(0xFFFBBF24),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xFF3F2D0A), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (track.bpm != null) "${track.bpmLabel()} BPM" else "— BPM",
                    color = if (track.bpm != null) Color.White else Color.Gray,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(Color(0xFF09090B), RoundedCornerShape(6.dp))
                        .border(1.dp, energyColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(track.keyLabel(), color = energyColor, fontWeight = FontWeight.Bold, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Divider(color = Color(0xFF27272A))
            Spacer(Modifier.height(8.dp))

            // Only measured facts are shown. The previous version displayed a
            // "Vibe", a chord progression and a "DJ Tip" that were all generated
            // prose, presented as though they described the audio.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = track.keyName?.let { "Key: $it (${track.keyLabel()})" } ?: "Key: not measured",
                    color = Color.LightGray,
                    fontSize = 10.sp,
                )
                Text(
                    text = track.energyLevel?.let { "Energy: $it / 10" } ?: "Energy: not measured",
                    color = Color.LightGray,
                    fontSize = 10.sp,
                )
                if (track.bpm != null) {
                    Text(
                        text = "Tempo confidence: ${(track.tempoConfidence * 100).toInt()}%",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onLoadA,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0891B2)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("LOAD DECK A", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = onLoadB,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("LOAD DECK B", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * Pairs from the library that mix, best first.
 *
 * The pairing is computed in the ViewModel now, off the main thread and capped.
 * It used to be an all-pairs comparison inside a `remember` in this composable,
 * so opening this tab with a 500-track library ran 124,750 comparisons on the
 * frame that opened it — quadratic work on the thread that draws.
 */
@Composable
fun CompatibleTransitionsSection(
    pairs: List<MixMatch>,
    onLoadPair: (Track, Track) -> Unit
) {
    val compatiblePairs = pairs

    Text("COMPATIBLE PAIRINGS", color = Color(0xFF9CA3AF), fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.sp)
    Spacer(Modifier.height(8.dp))

    if (compatiblePairs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No compatible matches found. Load more tracks in Crate.", color = Color.Gray, fontSize = 11.sp)
        }
    } else {
        LazyColumn(
            modifier = Modifier.height(150.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(compatiblePairs) { match ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF18181B))
                        .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                        .clickable { onLoadPair(match.trackA, match.trackB) }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${match.trackA.title} ⇄ ${match.trackB.title}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text("${match.trackA.camelotKey} ➔ ${match.trackB.camelotKey} | ${match.keyAdvice}", color = Color.Gray, fontSize = 9.sp)
                    }
                    Text(
                        "${match.overallScore}% Match",
                        color = if (match.overallScore >= 85) Color.Green else Color.Yellow,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
