package com.hereliesaz.sirmatchalot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

    LaunchedEffect(Unit) {
        while (true) {
            delay(60)
            recordProgress = if (sampler.isRecording) sampler.recordProgress() else 0f
            revision++
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
            color = Color(0xFF52525B),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
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
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label ?: "empty",
            color = if (isEmpty) Color(0xFF3F3F46) else Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(4.dp),
        )
    }
}
