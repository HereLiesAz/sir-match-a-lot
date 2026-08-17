package com.hereliesaz.sirmatchalot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.sirmatchalot.audio.Sampler
import com.hereliesaz.sirmatchalot.ui.platter.ClipPalette
import com.hereliesaz.sirmatchalot.ui.platter.PlatterGeometry
import kotlinx.coroutines.delay

/**
 * The sampler pads.
 *
 * Every pad here is backed by real audio — a take recorded from the master bus,
 * or a loop lifted from a loaded track. The screen this replaces showed eight
 * pads labelled "808 KICK", "RETRO SNARE" and so on, all of which synthesised a
 * tone; none sampled anything and none could record.
 *
 * Holding a pad plays it; arming record and then holding a pad captures into it.
 */
@Composable
fun SamplerScreen(
    viewModel: SirMatchALotViewModel,
    modifier: Modifier = Modifier,
) {
    val sampler = viewModel.audioEngine.sampler
    var armed by remember { mutableStateOf(false) }
    // The engine mutates pads off-composition, so poll a revision counter to
    // keep the grid in step without making every pad a snapshot state holder.
    var revision by remember { mutableIntStateOf(0) }
    var recordProgress by remember { mutableStateOf(0f) }
    val harvestProgress by viewModel.harvestProgress.collectAsState()
    val filterPosition by viewModel.filterPosition.collectAsState()

    // Polled, but the rate follows whether anything is moving. This bumped the
    // revision sixteen times a second for as long as the tab was open, whether
    // or not a single pad was doing anything — and pads spend almost all of
    // their time doing nothing. Pads can also be
    // filled from off-composition — a loop harvest, an auto-fill — so the idle
    // tick still runs; it just runs seven times less often, which is the
    // difference between a hundred needless recompositions a minute and a
    // hundred and fifty a second's worth of them.
    LaunchedEffect(Unit) {
        while (true) {
            val busy = sampler.isActive
            recordProgress = if (sampler.isRecording) sampler.recordProgress() else 0f
            revision++
            delay(if (busy) ACTIVE_POLL_MILLIS else IDLE_POLL_MILLIS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05050A))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { armed = !armed },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (armed) Color(0xFFDC2626) else Color(0xFF27272A),
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (armed) "ARMED — HOLD A PAD" else "ARM RECORD",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                )
            }
            Button(
                onClick = { viewModel.autoFillPads() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("FILL FROM TRACK", color = Color.White, fontWeight = FontWeight.Black, fontSize = 10.sp)
            }
            Button(
                onClick = { sampler.clearAll(); revision++ },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("CLEAR", color = Color(0xFF9CA3AF), fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
        }

        // The loop maker across the whole playlist, rather than one track.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    if (harvestProgress != null) viewModel.cancelHarvest()
                    else viewModel.harvestLoopsFromPlaylist()
                    revision++
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (harvestProgress != null) Color(0xFFDC2626) else Color(0xFF0E7490),
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    harvestProgress?.let { (done, total) -> "STOP — SCANNING $done/$total" }
                        ?: "FILL FROM PLAYLIST",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                )
            }
        }

        // A pad bank can occupy a deck slot, shown as loops on the circle the
        // same way songs are.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Loops on the platter:",
                color = Color(0xFF9CA3AF),
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            listOf(
                PlatterGeometry.Deck.A to "→ DECK A",
                PlatterGeometry.Deck.B to "→ DECK B",
            ).forEach { (deck, label) ->
                Button(
                    onClick = { viewModel.placePadsOnDeck(deck); revision++ },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
            }
            Button(
                onClick = {
                    viewModel.removePadsFromDeck(PlatterGeometry.Deck.A)
                    viewModel.removePadsFromDeck(PlatterGeometry.Deck.B)
                    revision++
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A)),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("OFF", color = Color(0xFF9CA3AF), fontWeight = FontWeight.Bold, fontSize = 9.sp)
            }
        }

        if (sampler.isRecording) {
            LinearProgressIndicator(
                progress = { recordProgress },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFDC2626),
                trackColor = Color(0xFF27272A),
            )
        }

        // `revision` is read so the grid recomposes as pads change underneath it.
        @Suppress("UNUSED_EXPRESSION") revision

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(sampler.pads) { pad ->
                PadButton(
                    label = pad.label,
                    isEmpty = pad.isEmpty,
                    isPlaying = pad.isPlaying,
                    isRecording = sampler.recordingPad == pad.index,
                    hue = ClipPalette.hueFor(PlatterGeometry.Deck.A, pad.index),
                    onPress = {
                        if (armed) {
                            sampler.beginRecording(pad.index)
                        } else {
                            sampler.trigger(pad.index)
                        }
                        // The audio thread may be parked with nothing playing.
                        // It would notice within a poll anyway; a pad is played
                        // by hand, so it gets the sample it was hit on.
                        viewModel.audioEngine.wake()
                        revision++
                    },
                    onRelease = {
                        if (sampler.recordingPad == pad.index) {
                            sampler.endRecording()
                            armed = false
                        } else if (!pad.loop) {
                            // Looping pads latch; one-shots follow the finger.
                            sampler.stop(pad.index)
                        }
                        revision++
                    },
                )
            }
        }

        Text(
            text = "${sampler.emptyPadCount} of ${sampler.padCount} pads free",
            color = Color(0xFF9CA3AF),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )

        FilterPad(
            position = filterPosition,
            onMove = { x, y -> viewModel.moveFilter(x, y) },
            onRelease = { viewModel.releaseFilter() },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

/**
 * The XY performance pad.
 *
 * Horizontal is a bipolar filter — centre is bypass, left sweeps a lowpass down,
 * right sweeps a highpass up — and vertical is resonance. The centre line is
 * drawn because "off" needs to be findable by hand at speed.
 *
 * The pad this replaces triggered a synthesised tone that was never in the
 * music's signal path. This one filters the master bus.
 */
@Composable
private fun FilterPad(
    position: Pair<Float, Float>?,
    onMove: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A drag-only XY pad has no discrete "activate" action to expose, so this
    // only announces what the control is and its current position — a screen
    // reader user cannot drive it precisely, but is no longer told nothing
    // about it exists at all.
    val stateText = position?.let { (x, y) ->
        val side = when {
            x < -0.05f -> "low pass"
            x > 0.05f -> "high pass"
            else -> "bypass"
        }
        "$side, resonance ${(y * 100).toInt()} percent"
    } ?: "bypass"
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0B0B12))
            .border(1.dp, if (position != null) Color(0xFF22D3EE) else Color(0xFF27272A), RoundedCornerShape(14.dp))
            .semantics {
                contentDescription = "Filter pad. Horizontal is low pass to high pass, vertical is resonance."
                stateDescription = stateText
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed }
                        if (change != null) {
                            val x = (change.position.x / size.width * 2f - 1f).coerceIn(-1f, 1f)
                            // Screen y grows downward; resonance grows upward.
                            val y = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                            onMove(x, y)
                        } else if (event.changes.isNotEmpty()) {
                            // Only release once every pointer that touched the pad
                            // has actually lifted — not just whichever one happens
                            // to be first in the change list this event.
                            onRelease()
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = size.width / 2f
            drawLine(
                color = Color(0xFF27272A),
                start = Offset(centre, 0f),
                end = Offset(centre, size.height),
                strokeWidth = 1f,
            )
            position?.let { (x, y) ->
                val px = (x + 1f) / 2f * size.width
                val py = (1f - y) * size.height
                drawLine(
                    color = Color(0x3322D3EE),
                    start = Offset(px, 0f),
                    end = Offset(px, size.height),
                    strokeWidth = 1f,
                )
                drawCircle(color = Color(0xFF22D3EE), radius = 14f, center = Offset(px, py))
            }
        }
        Text(
            text = if (position == null) "FILTER — LOW ← | → HIGH, UP = RESONANCE" else "FILTER",
            color = Color(0xFF9CA3AF),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp),
        )
    }
}

@Composable
private fun PadButton(
    label: String?,
    isEmpty: Boolean,
    isPlaying: Boolean,
    isRecording: Boolean,
    hue: Float,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val colour = when {
        isRecording -> Color(0xFFDC2626)
        isEmpty -> Color(0xFF18181B)
        else -> Color.hsl(hue, 0.7f, if (isPlaying) 0.55f else 0.28f)
    }
    val border = when {
        isRecording -> Color(0xFFFCA5A5)
        isPlaying -> Color.White
        isEmpty -> Color(0xFF27272A)
        else -> Color.hsl(hue, 0.7f, 0.5f)
    }

    val description = buildString {
        append(if (isEmpty) "Empty pad" else label ?: "Pad")
        if (isRecording) append(", recording")
        if (isPlaying) append(", playing")
    }
    Box(
        modifier = Modifier
            .aspectRatio(1.1f)
            .clip(RoundedCornerShape(12.dp))
            .background(colour)
            .border(if (isPlaying || isRecording) 2.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .pointerInput(isEmpty, label) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            }
            .semantics {
                contentDescription = description
                role = Role.Button
                // Mirrors the press-and-release the raw gesture above does —
                // a screen reader activation is a full tap.
                onClick {
                    onPress()
                    onRelease()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label ?: "empty",
            color = if (isEmpty) Color(0xFF9CA3AF) else Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(4.dp),
        )
    }
}

/** Poll interval while a pad is playing or a take is being captured. */
private const val ACTIVE_POLL_MILLIS = 60L

/**
 * Poll interval while every pad is idle.
 *
 * Still polled rather than stopped, because pads can be filled from elsewhere —
 * an auto-fill, a loop harvest, a remote trigger — and a grid that only updated
 * when it was already updating would never show them arrive.
 */
private const val IDLE_POLL_MILLIS = 400L
