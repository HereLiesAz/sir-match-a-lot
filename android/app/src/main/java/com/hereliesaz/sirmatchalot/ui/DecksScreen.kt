package com.hereliesaz.sirmatchalot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.sirmatchalot.data.Track
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.hereliesaz.sirmatchalot.audio.DeckController
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

@Composable
fun DecksScreen(
    viewModel: SirMatchALotViewModel,
    modifier: Modifier = Modifier
) {
    val loadedTracksA by viewModel.loadedTracksA.collectAsState()
    val loadedTracksB by viewModel.loadedTracksB.collectAsState()
    val controllersA by viewModel.controllersA.collectAsState()
    val controllersB by viewModel.controllersB.collectAsState()

    val deckATrack = loadedTracksA.firstOrNull()
    val deckBTrack = loadedTracksB.firstOrNull()
    val deckAController = controllersA.firstOrNull()
    val deckBController = controllersB.firstOrNull()

    val isPlaying by viewModel.isPlaying.collectAsState()
    val audioVolume by viewModel.audioVolume.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { viewModel.autoSync() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("AUTO BEAT SYNC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                Button(
                    onClick = { viewModel.togglePlayback() },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isPlaying) Color(0xFFF59E0B) else Color(0xFF06B6D4)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (isPlaying) "HALT MIX" else "PLAY MIX", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 12.sp)
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("GAIN: ${(audioVolume * 125).roundToInt()}%", color = Color.Gray, fontSize = 9.sp)
                    Slider(
                        value = audioVolume,
                        onValueChange = { viewModel.setVolume(it) },
                        valueRange = 0f..0.8f,
                        modifier = Modifier.width(100.dp),
                        colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                    )
                }
            }
        }

        // Deck A
        if (deckAController != null && deckATrack != null) {
            DeckView(
                deckName = "A (Primary)",
                track = deckATrack,
                deckController = deckAController,
                cuesFlow = viewModel.cuesA,
                isPlaying = isPlaying,
                onSetCue = { idx, t -> viewModel.setCue("A", idx, t) },
                onTriggerCue = { idx -> viewModel.triggerCue("A", idx) },
                onPitchChange = { viewModel.adjustPitch("A", it) },
                onNudge = { dir ->
                    val nudgeOffset = if (dir == "forward") 0.08f else -0.08f
                    deckAController.seekTo(deckAController.currentTime.value + nudgeOffset)
                }
            )
        } else {
            EmptyDeckPlaceholder(deckName = "A (Primary)", deckColor = Color(0xFF06B6D4))
        }

        // Deck B
        if (deckBController != null && deckBTrack != null) {
            DeckView(
                deckName = "B (Primary)",
                track = deckBTrack,
                deckController = deckBController,
                cuesFlow = viewModel.cuesB,
                isPlaying = isPlaying,
                onSetCue = { idx, t -> viewModel.setCue("B", idx, t) },
                onTriggerCue = { idx -> viewModel.triggerCue("B", idx) },
                onPitchChange = { viewModel.adjustPitch("B", it) },
                onNudge = { dir ->
                    val nudgeOffset = if (dir == "forward") 0.08f else -0.08f
                    deckBController.seekTo(deckBController.currentTime.value + nudgeOffset)
                }
            )
        } else {
            EmptyDeckPlaceholder(deckName = "B (Primary)", deckColor = Color(0xFFF59E0B))
        }
    }
}

@Composable
fun EmptyDeckPlaceholder(deckName: String, deckColor: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp))
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DECK $deckName", color = deckColor, fontWeight = FontWeight.Black, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text("Drag & drop track to Platter or use Library view to load", color = Color.Gray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun DeckView(
    deckName: String,
    track: Track?,
    deckController: DeckController,
    cuesFlow: StateFlow<List<Float?>>,
    isPlaying: Boolean,
    onSetCue: (Int, Float) -> Unit,
    onTriggerCue: (Int) -> Unit,
    onPitchChange: (Float) -> Unit,
    onNudge: (String) -> Unit
) {
    val currentTime by deckController.currentTime.collectAsState()
    val duration by deckController.duration.collectAsState()
    val cues by cuesFlow.collectAsState()

    val deckColor = if (deckName.startsWith("A")) Color(0xFF06B6D4) else Color(0xFFF59E0B)

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("DECK $deckName", color = deckColor, fontWeight = FontWeight.Black, fontSize = 10.sp)
                    Text(track?.title ?: "Empty Deck", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(track?.artist ?: "Load track from library", color = Color.LightGray, fontSize = 11.sp)
                }

                Column(horizontalAlignment = Alignment.End) {
                    val rate = 1f + deckController.pitch / 100f
                    val displayBpm = ((track?.bpm ?: 120) * rate)
                    Text(String.format("%.1f BPM", displayBpm), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(String.format("Pitch: %+.2f%%", deckController.pitch), color = Color.Gray, fontSize = 9.sp)
                }
            }

            if (track != null && track.localPath == null && track.youtubeId != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            YouTubePlayerView(ctx).apply {
                                enableBackgroundPlayback(true)
                                addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
                                    override fun onReady(player: YouTubePlayer) {
                                        deckController.bindYouTubePlayer(player)
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            BeatgridCanvas(
                currentTime = currentTime,
                bpm = track?.bpm ?: 120,
                pitch = deckController.pitch,
                isPlaying = isPlaying,
                deckColor = deckColor
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format("%02d:%02d / %02d:%02d", 
                        (currentTime / 60).toInt(), (currentTime % 60).toInt(),
                        (duration / 60).toInt(), (duration % 60).toInt()
                    ),
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onNudge("backward") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("NUDGE -", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onNudge("forward") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("NUDGE +", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }

                    Slider(
                        value = deckController.pitch,
                        onValueChange = { onPitchChange(it) },
                        valueRange = -8f..8f,
                        modifier = Modifier.width(80.dp),
                        colors = SliderDefaults.colors(thumbColor = deckColor, activeTrackColor = deckColor)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in 1..4) {
                    val cueTime = cues[i - 1]
                    Button(
                        onClick = {
                            if (cueTime == null) {
                                onSetCue(i, currentTime)
                            } else {
                                onTriggerCue(i)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (cueTime != null) deckColor else Color(0xFF27272A)
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        Text(
                            text = if (cueTime != null) String.format("CUE %d\n%.1fs", i, cueTime) else "SET $i",
                            color = if (cueTime != null) Color.Black else Color.Gray,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BeatgridCanvas(
    currentTime: Float,
    bpm: Int,
    pitch: Float,
    isPlaying: Boolean,
    deckColor: Color
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF09090B))
            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
    ) {
        val width = size.width
        val height = size.height

        val rate = 1f + pitch / 100f
        val currentBpm = bpm * rate
        val beatDuration = 60f / currentBpm
        val pixelsPerSecond = 50f

        val centerX = width / 2f
        drawLine(
            color = Color.Red,
            start = Offset(centerX, 0f),
            end = Offset(centerX, height),
            strokeWidth = 2f
        )

        val startSec = currentTime - (centerX / pixelsPerSecond)
        val endSec = currentTime + (centerX / pixelsPerSecond)

        val firstBeatNum = (startSec / beatDuration).toInt()
        val firstBeatTime = firstBeatNum * beatDuration

        var beatTime = firstBeatTime
        while (beatTime <= endSec) {
            if (beatTime >= 0) {
                val dx = centerX + (beatTime - currentTime) * pixelsPerSecond
                val isDownbeat = (beatTime / beatDuration).toInt() % 4 == 0

                drawLine(
                    color = if (isDownbeat) deckColor else Color.DarkGray,
                    start = Offset(dx, if (isDownbeat) 5f else 15f),
                    end = Offset(dx, if (isDownbeat) height - 5f else height - 15f),
                    strokeWidth = if (isDownbeat) 2.5f else 1.2f
                )
            }
            beatTime += beatDuration
        }
    }
}
