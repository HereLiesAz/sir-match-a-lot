package com.hereliesaz.sirmatchalot.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.hereliesaz.sirmatchalot.domain.HarmonicEngine
import com.hereliesaz.sirmatchalot.domain.MixMatch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: SirMatchALotViewModel,
    modifier: Modifier = Modifier
) {
    val tracks by viewModel.tracks.collectAsState()
    val feedbackMsg by viewModel.feedbackMsg.collectAsState()

    var searchQuery by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                val fileName = uri.lastPathSegment ?: "local_file.mp3"
                val parsedNames = LinkParser.parseFileName(fileName)
                viewModel.analyzeTrack(
                    query = "${parsedNames.second} ${parsedNames.first}",
                    path = uri.toString(),
                    fileName = fileName
                )
            }
        }
    )

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
                Text("Import Local File", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search song, artist, paste Spotify/YouTube link...", color = Color.Gray, fontSize = 11.sp) },
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
                    if (searchQuery.isNotEmpty()) {
                        viewModel.analyzeTrack(query = searchQuery)
                        searchQuery = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Analyze", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 11.sp)
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
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tracks) { track ->
                TrackRowItem(
                    track = track,
                    onLoadA = { viewModel.addTrackToDeckA(track) },
                    onLoadB = { viewModel.addTrackToDeckB(track) },
                    onDelete = { viewModel.deleteTrack(track) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        CompatibleTransitionsSection(tracks = tracks, onLoadPair = { a, b ->
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

    val energyColor = when {
        track.energyLevel >= 8 -> Color(0xFFF43F5E)
        track.energyLevel >= 5 -> Color(0xFFA855F7)
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
                if (track.localPath != null) {
                    Text(
                        "LOCAL FILE",
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
                Text("${track.bpm} BPM", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(Color(0xFF09090B), RoundedCornerShape(6.dp))
                        .border(1.dp, energyColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(track.camelotKey, color = energyColor, fontWeight = FontWeight.Bold, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Divider(color = Color(0xFF27272A))
            Spacer(Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Vibe: ${track.atmosphere}", color = Color.LightGray, fontSize = 10.sp)
                Text("Progression: ${track.progression}", color = Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text("DJ Tip: ${track.mixTips}", color = Color.Cyan, fontSize = 10.sp)
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

@Composable
fun CompatibleTransitionsSection(
    tracks: List<Track>,
    onLoadPair: (Track, Track) -> Unit
) {
    val compatiblePairs = remember(tracks) {
        val list = mutableListOf<MixMatch>()
        if (tracks.size >= 2) {
            for (i in 0 until tracks.size) {
                for (j in (i + 1) until tracks.size) {
                    val match = HarmonicEngine.compareTracks(tracks[i], tracks[j])
                    if (match.overallScore >= 60) {
                        list.add(match)
                    }
                }
            }
        }
        list.sortByDescending { it.overallScore }
        list
    }

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
