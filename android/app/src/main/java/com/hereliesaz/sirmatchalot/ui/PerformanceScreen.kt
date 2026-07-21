package com.hereliesaz.sirmatchalot.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import com.hereliesaz.sirmatchalot.audio.SynthEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(
    viewModel: SirMatchALotViewModel,
    modifier: Modifier = Modifier
) {
    val synth = viewModel.synthEngine
    var isKaossActive by remember { mutableStateOf(false) }

    var kaossX by remember { mutableStateOf(0.5f) }
    var kaossY by remember { mutableStateOf(0.5f) }

    var isStutter by remember { mutableStateOf(false) }
    var isDelay by remember { mutableStateOf(false) }

    val particles = remember { mutableStateListOf<TouchParticle>() }

    LaunchedEffect(isKaossActive) {
        if (isKaossActive) {
            while (true) {
                delay(30)
                val iterator = particles.iterator()
                while (iterator.hasNext()) {
                    val p = iterator.next()
                    p.alpha -= 0.08f
                    p.radius += 1.5f
                    if (p.alpha <= 0f) {
                        iterator.remove()
                    }
                }
            }
        } else {
            particles.clear()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("SAMPLER DRUM PADS", color = Color(0xFF71717A), fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
        
        SamplerPadGrid(onPadTrigger = { padId ->
            synth.playSample(padId)
            viewModel.syncClient.triggerSamplerPad(padId, viewModel.roomCode.value)
        })

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("KAOSS VECTOR PAD", color = Color(0xFF71717A), fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = isDelay,
                    onClick = {
                        isDelay = !isDelay
                        synth.delayFeedback = if (isDelay) 0.6f else 0f
                    },
                    label = { Text("ECHO DELAY", fontSize = 9.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF06B6D4),
                        selectedLabelColor = Color.Black
                    )
                )
                FilterChip(
                    selected = isStutter,
                    onClick = {
                        isStutter = !isStutter
                        synth.isStutterActive = isStutter
                    },
                    label = { Text("STUTTER LFO", fontSize = 9.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF06B6D4),
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF09090B))
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isKaossActive = true
                            synth.startSynth()
                            val rx = (offset.x / size.width).coerceIn(0f, 1f)
                            val ry = (offset.y / size.height).coerceIn(0f, 1f)
                            kaossX = rx
                            kaossY = ry
                            synth.frequency = rx * 1500f + 50f
                            synth.filterCutoff = 1f - ry
                            viewModel.syncClient.triggerKaossMove(rx, 1f - ry, 1, viewModel.roomCode.value)
                            particles.add(TouchParticle(offset.x, offset.y))
                        },
                        onDragEnd = {
                            isKaossActive = false
                            synth.stopSynth()
                        },
                        onDragCancel = {
                            isKaossActive = false
                            synth.stopSynth()
                        },
                        onDrag = { change, dragAmount ->
                            val rx = (change.position.x / size.width).coerceIn(0f, 1f)
                            val ry = (change.position.y / size.height).coerceIn(0f, 1f)
                            kaossX = rx
                            kaossY = ry
                            synth.frequency = rx * 1500f + 50f
                            synth.filterCutoff = 1f - ry
                            viewModel.syncClient.triggerKaossMove(rx, 1f - ry, 1, viewModel.roomCode.value)
                            particles.add(TouchParticle(change.position.x, change.position.y))
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            isKaossActive = true
                            synth.startSynth()
                            val rx = (offset.x / size.width).coerceIn(0f, 1f)
                            val ry = (offset.y / size.height).coerceIn(0f, 1f)
                            kaossX = rx
                            kaossY = ry
                            synth.frequency = rx * 1500f + 50f
                            synth.filterCutoff = 1f - ry
                            viewModel.syncClient.triggerKaossMove(rx, 1f - ry, 1, viewModel.roomCode.value)
                            particles.add(TouchParticle(offset.x, offset.y))
                            
                            tryAwaitRelease()
                            
                            isKaossActive = false
                            synth.stopSynth()
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height

                val gridLines = 8
                for (i in 1 until gridLines) {
                    val x = width * (i.toFloat() / gridLines)
                    val y = height * (i.toFloat() / gridLines)
                    drawLine(Color(0xFF18181B), Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
                    drawLine(Color(0xFF18181B), Offset(0f, y), Offset(width, y), strokeWidth = 1f)
                }

                if (isKaossActive) {
                    val px = kaossX * width
                    val py = kaossY * height
                    drawLine(Color(0xFF06B6D4).copy(alpha = 0.3f), Offset(px, 0f), Offset(px, height), strokeWidth = 1.5f)
                    drawLine(Color(0xFF06B6D4).copy(alpha = 0.3f), Offset(0f, py), Offset(width, py), strokeWidth = 1.5f)
                }

                particles.forEach { p ->
                    drawCircle(
                        color = Color(0xFFA855F7).copy(alpha = p.alpha.coerceIn(0f, 1f)),
                        radius = p.radius,
                        center = Offset(p.x, p.y),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            if (isKaossActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = "FREQ: ${(kaossX * 1500f + 50f).roundToInt()}Hz | CUTOFF: ${((1f - kaossY) * 100f).roundToInt()}%",
                        color = Color.Cyan,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("TAP & DRAG TO PLAY KAOSS SYNTHESIZER", color = Color.DarkGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

class TouchParticle(
    val x: Float,
    val y: Float,
    var radius: Float = 10f,
    var alpha: Float = 1f
)

@Composable
fun SamplerPadGrid(onPadTrigger: (Int) -> Unit) {
    val padNames = listOf(
        "808 KICK", "RETRO SNARE", "OPEN HAT", "FORMANT VOX",
        "LOOP 1", "LOOP 2", "SAMPLER 3", "SAMPLER 4"
    )
    val padColors = listOf(
        Color(0xFFEF4444), Color(0xFFF97316), Color(0xFFF59E0B), Color(0xFFA855F7),
        Color(0xFF10B981), Color(0xFF06B6D4), Color(0xFF6366F1), Color(0xFFEC4899)
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(8) { index ->
            val padId = index + 1
            var isPressed by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .aspectRatio(1.2f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isPressed) padColors[index] else padColors[index].copy(alpha = 0.15f)
                    )
                    .border(
                        2.dp,
                        if (isPressed) Color.White else padColors[index].copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                onPadTrigger(padId)
                                tryAwaitRelease()
                                isPressed = false
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        padNames[index],
                        color = if (isPressed) Color.Black else padColors[index],
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "P$padId",
                        color = if (isPressed) Color.Black.copy(alpha = 0.6f) else Color.Gray,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
