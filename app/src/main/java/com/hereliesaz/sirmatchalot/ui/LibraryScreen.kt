package com.hereliesaz.sirmatchalot.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    var linkInput by remember { mutableStateOf("") }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
            .padding(16.dp)
    ) {
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

        // The background run, mirrored from the same state its notification
        // shows, so the two can never disagree.
        if (backgroundAnalysis.total > 0) {
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

        // Analysis is an FFT pass over each whole track and takes real seconds.
        // Without this the button was indistinguishable from a dead one for the
        // length of the run.
        analysisProgress?.let { progress ->
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

        nowPlaying?.let { current ->
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
                        .clickable { viewModel.setLibrarySort(option) }
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

        Spacer(Modifier.height(8.dp))

        if (feedbackMsg.isNotEmpty()) {
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

        Spacer(Modifier.height(16.dp))

        Text("TRACKS (${tracks.size})", color = Color(0xFF71717A), fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            // Hoisted so it is saveable: with the tab content wrapped in a
            // SaveableStateHolder, where you were in a long library survives
            // leaving the tab and coming back.
            state = androidx.compose.foundation.lazy.rememberLazyListState(),
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                TrackRowItem(
                    track = track,
                    onLoadA = { viewModel.addTrackToDeckA(track) },
                    onLoadB = { viewModel.addTrackToDeckB(track) },
                    onDelete = { viewModel.deleteTrack(track) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        CompatibleTransitionsSection(pairs = compatiblePairs, onLoadPair = { a, b ->
            viewModel.addTrackToDeckA(a)
            viewModel.addTrackToDeckB(b)
        })
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
        energy == null -> Color(0xFF52525B)
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
                    Text(
                        "NOT ANALYSED",
                        color = Color.Green,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xFF064E3B), RoundedCornerShape(4.dp))
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

    Text("COMPATIBLE PAIRINGS", color = Color(0xFF71717A), fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.sp)
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
