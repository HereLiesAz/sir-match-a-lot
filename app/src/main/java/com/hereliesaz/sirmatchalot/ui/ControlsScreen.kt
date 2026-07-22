package com.hereliesaz.sirmatchalot.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.domain.HarmonicEngine
import kotlin.math.*

@Composable
fun ControlsScreen(
    viewModel: SirMatchALotViewModel,
    modifier: Modifier = Modifier
) {
    var draggingTrack by remember { mutableStateOf<Track?>(null) }
    var dragTouchOffset by remember { mutableStateOf(Offset.Zero) }

    var platterPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    var platterSize by remember { mutableStateOf(IntSize.Zero) }

    val spots = remember {
        mutableStateListOf(
            GestureSpot("12:00", (-Math.PI / 2).toFloat()),
            GestureSpot("1:30", (-Math.PI / 4).toFloat()),
            GestureSpot("10:30", (-3 * Math.PI / 4).toFloat()),
            GestureSpot("3:00", 0f),
            GestureSpot("9:00", Math.PI.toFloat()),
            GestureSpot("4:30", (Math.PI / 4).toFloat()),
            GestureSpot("7:30", (3 * Math.PI / 4).toFloat()),
            GestureSpot("6:00", (Math.PI / 2).toFloat())
        )
    }

    val updateGestureActive: (String, Boolean) -> Unit = { gestureName: String, isActive: Boolean ->
        if (gestureName == "ALL" && !isActive) {
            spots.forEach { it.isGestureActive = false }
        } else {
            val existingSpot = spots.firstOrNull { it.text == gestureName }
            if (isActive) {
                if (existingSpot != null) {
                    existingSpot.isGestureActive = true
                    existingSpot.alpha = 1.0f
                    existingSpot.fadeStartTime = 0L
                } else {
                    val freeSpot = spots.firstOrNull { it.alpha == 0f }
                    if (freeSpot != null) {
                        freeSpot.text = gestureName
                        freeSpot.isGestureActive = true
                        freeSpot.alpha = 1.0f
                        freeSpot.fadeStartTime = 0L
                        freeSpot.yOffset = 0f
                    }
                }
            } else {
                existingSpot?.let { it.isGestureActive = false }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            val now = System.currentTimeMillis()
            for (i in spots.indices) {
                val spot = spots[i]
                if (spot.isGestureActive) {
                    spot.alpha = 1.0f
                    spot.yOffset += 1f
                } else if (spot.alpha > 0f) {
                    spot.yOffset += 1f
                    if (spot.fadeStartTime == 0L) {
                        spot.fadeStartTime = now
                    }
                    val elapsed = now - spot.fadeStartTime
                    val nextAlpha = 1.0f - (elapsed.toFloat() / 500f) // 500ms fade duration
                    if (nextAlpha <= 0f) {
                        spot.alpha = 0f
                        spot.text = ""
                        spot.fadeStartTime = 0L
                        spot.yOffset = 0f
                    } else {
                        spot.alpha = nextAlpha
                    }
                }
            }
        }
    }

    val loadedTracksA by viewModel.loadedTracksA.collectAsState()
    val loadedTracksB by viewModel.loadedTracksB.collectAsState()
    val controllersA by viewModel.controllersA.collectAsState()
    val controllersB by viewModel.controllersB.collectAsState()
    val trackVolumes by viewModel.trackVolumes.collectAsState()
    val trackOverlaps by viewModel.trackOverlaps.collectAsState()
    val selectedTrackIds by viewModel.selectedTrackIds.collectAsState(initial = emptySet())
    val isPlaying by viewModel.isPlaying.collectAsState()
    val infiniteTransition = rememberInfiniteTransition()
    val phase by if (isPlaying) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1000f,
            animationSpec = infiniteRepeatable(
                animation = tween(10000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val tts = remember { mutableStateOf<android.speech.tts.TextToSpeech?>(null) }
    
    var platterScale by remember { mutableStateOf(1f) }
    var platterOffsetX by remember { mutableStateOf(0f) }
    var platterOffsetY by remember { mutableStateOf(0f) }
    var platterRotation by remember { mutableStateOf(0f) }
    var playheadAngle by remember { mutableStateOf(0f) }
    var isGrabbingPlayhead by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        tts.value = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts.value?.setLanguage(java.util.Locale.US)
                tts.value?.setPitch(0.2f) // Exceptionally low
                tts.value?.setSpeechRate(0.5f) // Slowly at first
            }
        }
    }

    LaunchedEffect(isPlaying, isGrabbingPlayhead) {
        while(true) {
            if (isPlaying && !isGrabbingPlayhead) {
                playheadAngle += (2 * Math.PI / 10f).toFloat() * (16f/1000f)
                if (playheadAngle > 2 * Math.PI) playheadAngle -= (2 * Math.PI).toFloat()
            }
            delay(16)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF050505))) {
        // EDM Rave Background Visualizer
        val intensity = if (isPlaying) {
            val beatPulse = (kotlin.math.sin(phase * 40f) + 1f) / 2f
            0.4f + 0.6f * beatPulse
        } else {
            0f
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            
            val colors = listOf(
                Color(0xFF00E5FF), // Turquoise
                Color(0xFFFF1744), // Red
                Color(0xFFD500F9), // Purple
                Color(0xFF76FF03), // Lime
                Color(0xFFFFC400)  // Gold
            )
            
            for (i in 0 until 5) {
                // Animate position slowly using phase
                val angle = (phase * (0.2f + i * 0.1f)) + (i * Math.PI * 2 / 5)
                val distance = (min(cx, cy) * 0.4f) + sin(phase * (0.5f + i * 0.2f)) * (min(cx, cy) * 0.4f)
                
                val x = cx + cos(angle.toFloat()) * distance
                val y = cy + sin(angle.toFloat()) * distance
                
                // Pulse size and alpha with intensity
                val baseRadius = min(cx, cy) * 0.5f
                val pulseRadius = baseRadius + (intensity * baseRadius * 1.5f)
                
                val baseAlpha = 0.15f
                val pulseAlpha = (baseAlpha + (intensity * 0.35f)).coerceIn(0f, 1f)
                
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            colors[i].copy(alpha = pulseAlpha),
                            colors[i].copy(alpha = 0f)
                        ),
                        center = androidx.compose.ui.geometry.Offset(x, y),
                        radius = pulseRadius
                    ),
                    radius = pulseRadius,
                    center = androidx.compose.ui.geometry.Offset(x, y),
                    blendMode = androidx.compose.ui.graphics.BlendMode.Screen
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
            .pointerInput(selectedTrackIds, loadedTracksA, loadedTracksB) {
                awaitPointerEventScope {
                    var prevSpan2 = 0f
                    var prevSpan3 = 0f
                    var prevAngle2 = 0f
                    var prevAngle3 = 0f
                    var prevPos1 = Offset.Zero
                    var prevPos2 = Offset.Zero
                    var prevPos3 = Offset.Zero
                    
                    var initialPos1 = Offset.Zero
                    var isDragging1 = false
                    
                    // State for Easter Egg
                    var consecutiveBackwardScratch = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.filter { it.pressed }

                        // Platter center approximation
                        val cx = size.width / 2f
                        val cy = size.height * 0.4f
                        val baseRadius = min(size.width, size.height) * 0.35f

                        // Inverse transform helper to map screen touches to platter space
                        // We use the same cx, cy as pivot for the transform
                        val getTransformedPos = { screenPos: Offset ->
                            val dx = screenPos.x - cx - platterOffsetX
                            val dy = screenPos.y - cy - platterOffsetY
                            
                            val rad = -platterRotation
                            val rx = dx * cos(rad) - dy * sin(rad)
                            val ry = dx * sin(rad) + dy * cos(rad)
                            
                            val sx = rx / platterScale
                            val sy = ry / platterScale
                            
                            Offset(cx + sx.toFloat(), cy + sy.toFloat())
                        }

                        val getTargetDeck = { pos: Offset ->
                            if (selectedTrackIds.isNotEmpty()) {
                                val inA = loadedTracksA.any { it.id in selectedTrackIds }
                                val inB = loadedTracksB.any { it.id in selectedTrackIds }
                                if (inA && !inB) "A" else if (inB && !inA) "B" else "A"
                            } else {
                                val dist = sqrt((pos.x - cx) * (pos.x - cx) + (pos.y - cy) * (pos.y - cy))
                                if (dist > baseRadius) "A" else "B"
                            }
                        }

                        when (pointers.size) {
                            1 -> {
                                val change = pointers[0]
                                val pos = change.position
                                val transformedPos = getTransformedPos(pos)
                                
                                if (prevPos1 == Offset.Zero) {
                                    initialPos1 = transformedPos
                                    isDragging1 = false
                                    
                                    // Check if grab is near the playhead angle
                                    val dist = sqrt((transformedPos.x - cx) * (transformedPos.x - cx) + (transformedPos.y - cy) * (transformedPos.y - cy))
                                    if (dist > baseRadius * 0.25f && dist < baseRadius * 1.5f) {
                                        val touchAngle = atan2(transformedPos.y - cy, transformedPos.x - cx)
                                        var normalizedTouchAngle = touchAngle
                                        if (normalizedTouchAngle < 0) normalizedTouchAngle += (2 * Math.PI).toFloat()
                                        
                                        var playheadAngleNorm = playheadAngle
                                        if (playheadAngleNorm < 0) playheadAngleNorm += (2 * Math.PI).toFloat()
                                        
                                        // If tap is very close to playhead angle, grab it
                                        val diff = abs(normalizedTouchAngle - playheadAngleNorm)
                                        if (diff < 0.2f || diff > (2 * Math.PI - 0.2f)) {
                                            isGrabbingPlayhead = true
                                        }
                                    }
                                } else {
                                    val dx = transformedPos.x - initialPos1.x
                                    val dy = transformedPos.y - initialPos1.y

                                    if (abs(dx) > 5f || abs(dy) > 5f) {
                                        isDragging1 = true
                                    }

                                    if (isDragging1) {
                                        val deckZone = getTargetDeck(transformedPos)
                                        
                                        if (isGrabbingPlayhead) {
                                            // Scrub Playhead directly
                                            val currentTouchAngle = atan2(transformedPos.y - cy, transformedPos.x - cx)
                                            var normalizedCurrent = currentTouchAngle
                                            if (normalizedCurrent < 0) normalizedCurrent += (2 * Math.PI).toFloat()
                                            
                                            // Calculate shortest angular distance to move playhead
                                            var angleDiff = normalizedCurrent - playheadAngle
                                            if (angleDiff > Math.PI) angleDiff -= (2 * Math.PI).toFloat()
                                            if (angleDiff < -Math.PI) angleDiff += (2 * Math.PI).toFloat()
                                            
                                            playheadAngle = normalizedCurrent
                                            
                                            // Apply seek based on angle moved (roughly mapped)
                                            val scrubAmount = angleDiff * 50f
                                            viewModel.scrubPlayhead("A", scrubAmount)
                                            viewModel.scrubPlayhead("B", scrubAmount)
                                            updateGestureActive("SCRUB PLAYHEAD", true)
                                        } else {
                                            // 1-Finger manipulate clip
                                            // Map Y-axis (or X-axis) to adjustOverlap
                                            val deltaY = transformedPos.y - prevPos1.y
                                            if (abs(deltaY) > 1.5f) {
                                                viewModel.adjustOverlap(deltaY * 0.05f, deckZone, 0f, 0f)
                                                updateGestureActive("CLIP OVERLAP", true)
                                            } else {
                                                updateGestureActive("CLIP OVERLAP", false)
                                            }
                                        }
                                    }
                                }
                                prevPos1 = transformedPos
                                prevSpan2 = 0f
                                prevSpan3 = 0f
                            }
                            2 -> {
                                val p1 = pointers[0].position
                                val p2 = pointers[1].position

                                val center = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                                val currentSpan = sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y))
                                val currentAngle = atan2(p2.y - p1.y, p2.x - p1.x)

                                val deckZone = getTargetDeck(getTransformedPos(center))

                                if (prevSpan2 > 0f) {
                                    val spanDelta = currentSpan - prevSpan2
                                    var angleDelta = currentAngle - prevAngle2
                                    if (angleDelta > Math.PI.toFloat()) angleDelta -= (2 * Math.PI).toFloat()
                                    if (angleDelta < -Math.PI.toFloat()) angleDelta += (2 * Math.PI).toFloat()

                                    val dy2 = (center.y - prevPos2.y)
                                    val dx2 = (center.x - prevPos2.x)

                                    // Pinch/Spread -> Bass Boost
                                    if (abs(spanDelta) > 4f) {
                                        val eqDelta = spanDelta * 2f
                                        viewModel.adjustEqBassTreble(deckZone, eqDelta)
                                        updateGestureActive("BASS BOOST", true)
                                    } else {
                                        updateGestureActive("BASS BOOST", false)
                                    }

                                    // Rotate -> Volume
                                    if (abs(angleDelta) > 0.05f) {
                                        val volDelta = angleDelta * 0.5f
                                        viewModel.setVolume((viewModel.audioVolume.value + volDelta).coerceIn(0f, 0.8f))
                                        updateGestureActive("VOLUME KNOB", true)
                                    } else {
                                        updateGestureActive("VOLUME KNOB", false)
                                    }

                                    // Horizontal -> Crossfader
                                    if (abs(dx2) > 2f) {
                                        viewModel.adjustCrossfaderDelta(dx2 * 1.5f)
                                        updateGestureActive("CROSSFADER", true)
                                    } else {
                                        updateGestureActive("CROSSFADER", false)
                                    }

                                    // Vertical -> Smart Scratch (BPM/Pitch drop + Seek backward)
                                    if (abs(dy2) > 2f) {
                                        // Dragging down (positive dy2) = pulling backward
                                        if (dy2 > 0) {
                                            viewModel.adjustBpmSpeed(deckZone, -dy2 * 0.05f)
                                            viewModel.adjustPitchOnly(deckZone, -dy2 * 0.05f)
                                            viewModel.scrubPlayhead("A", -dy2 * 50f)
                                            viewModel.scrubPlayhead("B", -dy2 * 50f)
                                            
                                            // Easter Egg logic
                                            consecutiveBackwardScratch += dy2
                                            if (consecutiveBackwardScratch > 1500f) {
                                                tts.value?.speak("I am Satan, Lord of Darkness.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
                                                consecutiveBackwardScratch = 0f // Reset after playing
                                            }
                                        } else {
                                            // Pushing forward
                                            viewModel.adjustBpmSpeed(deckZone, -dy2 * 0.05f)
                                            viewModel.adjustPitchOnly(deckZone, -dy2 * 0.05f)
                                            viewModel.scrubPlayhead("A", -dy2 * 50f)
                                            viewModel.scrubPlayhead("B", -dy2 * 50f)
                                            consecutiveBackwardScratch = 0f
                                        }
                                        
                                        updateGestureActive("SMART SCRATCH", true)
                                    } else {
                                        updateGestureActive("SMART SCRATCH", false)
                                        consecutiveBackwardScratch = 0f
                                    }
                                }
                                prevPos2 = center
                                prevSpan2 = currentSpan
                                prevAngle2 = currentAngle
                                prevSpan3 = 0f
                            }
                            3 -> {
                                val p1 = pointers[0].position
                                val p2 = pointers[1].position
                                val p3 = pointers[2].position

                                val cx3 = (p1.x + p2.x + p3.x) / 3f
                                val cy3 = (p1.y + p2.y + p3.y) / 3f

                                val d1 = sqrt((p1.x - cx3)*(p1.x - cx3) + (p1.y - cy3)*(p1.y - cy3))
                                val d2 = sqrt((p2.x - cx3)*(p2.x - cx3) + (p2.y - cy3)*(p2.y - cy3))
                                val d3 = sqrt((p3.x - cx3)*(p3.x - cx3) + (p3.y - cy3)*(p3.y - cy3))
                                val currentSpan3 = (d1 + d2 + d3) / 3f

                                val a1 = atan2(p1.y - cy3, p1.x - cx3)
                                val a2 = atan2(p2.y - cy3, p2.x - cx3)
                                val a3 = atan2(p3.y - cy3, p3.x - cx3)
                                val currentAngle3 = (a1 + a2 + a3) / 3f

                                if (prevSpan3 > 0f) {
                                    val spanDelta = currentSpan3 - prevSpan3
                                    var angleDelta = currentAngle3 - prevAngle3
                                    if (angleDelta > Math.PI.toFloat()) angleDelta -= (2 * Math.PI).toFloat()
                                    if (angleDelta < -Math.PI.toFloat()) angleDelta += (2 * Math.PI).toFloat()

                                    val dx3 = cx3 - prevPos3.x
                                    val dy3 = cy3 - prevPos3.y
                                    
                                    var isPlatterTransformActive = false

                                    if (abs(spanDelta) > 1f) {
                                        platterScale = (platterScale + spanDelta * 0.005f).coerceIn(0.5f, 3f)
                                        isPlatterTransformActive = true
                                    }
                                    
                                    if (abs(angleDelta) > 0.02f) {
                                        platterRotation += angleDelta
                                        isPlatterTransformActive = true
                                    }
                                    
                                    if (abs(dx3) > 1f || abs(dy3) > 1f) {
                                        platterOffsetX += dx3
                                        platterOffsetY += dy3
                                        isPlatterTransformActive = true
                                    }
                                    
                                    if (isPlatterTransformActive) {
                                        updateGestureActive("PLATTER TRANSFORM", true)
                                    } else {
                                        updateGestureActive("PLATTER TRANSFORM", false)
                                    }
                                }
                                prevPos3 = Offset(cx3, cy3)
                                prevSpan3 = currentSpan3
                                prevAngle3 = currentAngle3
                            }
                        }

                        if (pointers.isEmpty()) {
                            if (prevPos1 != Offset.Zero && !isDragging1 && !isGrabbingPlayhead) {
                                // Tap!
                                val dist = sqrt((initialPos1.x - cx) * (initialPos1.x - cx) + (initialPos1.y - cy) * (initialPos1.y - cy))
                                if (dist > baseRadius * 0.25f && dist < baseRadius * 1.5f) {
                                    val deckZone = if (dist > baseRadius) "A" else "B"
                                    val tracksToToggle = if (deckZone == "A") loadedTracksA else loadedTracksB
                                    val targetId = tracksToToggle.firstOrNull()?.id
                                    
                                    if (targetId != null) {
                                        if (selectedTrackIds.contains(targetId)) {
                                            viewModel.setSelectedTracks(selectedTrackIds - targetId)
                                        } else {
                                            viewModel.setSelectedTracks(selectedTrackIds + targetId)
                                        }
                                    }
                                }
                            }

                            updateGestureActive("ALL", false)
                            prevPos1 = Offset.Zero
                            prevSpan2 = 0f
                            prevSpan3 = 0f
                            isDragging1 = false
                            isGrabbingPlayhead = false
                            consecutiveBackwardScratch = 0f
                        }
                    }
                }
            }
    ) {
        // Controls Row: Play/Sync/Harmonize/Gain etc.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val audioVolume by viewModel.audioVolume.collectAsState()
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.autoSync() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("AUTO BEAT SYNC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                Button(
                    onClick = { /* viewModel.applyHarmonicMatch() */ },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC4899)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("HARMONIZE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
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

        Box(modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = platterScale
                scaleY = platterScale
                translationX = platterOffsetX
                translationY = platterOffsetY
                rotationZ = Math.toDegrees(platterRotation.toDouble()).toFloat()
            }
        ) {
            RadialControllerPlatter(
                viewModel = viewModel,
                onPlatterPositioned = { pos, size ->
                    platterPositionInWindow = pos
                    platterSize = size
                },
                spots = spots,
                updateGestureActive = updateGestureActive,
                loadedTracksA = loadedTracksA,
                loadedTracksB = loadedTracksB,
                controllersA = controllersA,
                controllersB = controllersB,
                trackVolumes = trackVolumes,
                trackOverlaps = trackOverlaps,
                selectedTrackIds = selectedTrackIds,
                isPlaying = isPlaying,
                playheadAngle = playheadAngle
            )
        }
        
        Box(modifier = Modifier.height(140.dp).fillMaxWidth().padding(bottom = 16.dp)) {
            HorizontalSongList(viewModel = viewModel)
        }
    }
    }
}

@Composable
fun RadialControllerPlatter(
    viewModel: SirMatchALotViewModel,
    onPlatterPositioned: (Offset, IntSize) -> Unit,
    spots: List<GestureSpot>,
    updateGestureActive: (String, Boolean) -> Unit,
    draggingTrack: Track? = null,
    dragTouchOffset: Offset = Offset.Zero,
    platterPositionInWindow: Offset = Offset.Zero,
    loadedTracksA: List<Track>,
    loadedTracksB: List<Track>,
    controllersA: List<com.hereliesaz.sirmatchalot.audio.DeckController>,
    controllersB: List<com.hereliesaz.sirmatchalot.audio.DeckController>,
    trackVolumes: Map<String, Float>,
    trackOverlaps: Map<String, Float>,
    selectedTrackIds: Set<String>,
    isPlaying: Boolean,
    playheadAngle: Float
) {
    // Rotating Platter Angle (Circle Playhead)
    val maxDurationA = controllersA.maxOfOrNull { it.duration.value } ?: 0f
    val maxDurationB = controllersB.maxOfOrNull { it.duration.value } ?: 0f
    val platterDurationSeconds = kotlin.math.max(maxDurationA, maxDurationB).coerceAtLeast(8f)

    val infiniteTransition = rememberInfiniteTransition()
    val platterRotationAngle = playheadAngle

    val visualizerPhase by if (isPlaying) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1000f,
            animationSpec = infiniteRepeatable(
                animation = tween(10000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val scope = rememberCoroutineScope()
    var platterSize by remember { mutableStateOf(IntSize.Zero) }

    // Continuous Beat-Grid Snapping for Selected Tracks
    LaunchedEffect(selectedTrackIds, isPlaying) {
        if (isPlaying && selectedTrackIds.isNotEmpty()) {
            while (true) {
                delay(250) // Beat grid check interval
                val refController = controllersA.firstOrNull() ?: controllersB.firstOrNull()
                val refTrack = loadedTracksA.firstOrNull() ?: loadedTracksB.firstOrNull()
                val refBpm = refTrack?.bpm ?: 120
                val beatDurationSecs = 60f / refBpm.toFloat()
                val refTime = refController?.currentTime?.value ?: 0f

                selectedTrackIds.forEach { selId ->
                    val cA = controllersA.firstOrNull { it.loadedTrack?.id == selId }
                    val cB = controllersB.firstOrNull { it.loadedTrack?.id == selId }
                    val targetCtrl = cA ?: cB
                    targetCtrl?.let { ctrl ->
                        val curT = ctrl.currentTime.value
                        val targetBeatIndex = kotlin.math.round(curT / beatDurationSecs)
                        val refBeatPhase = refTime % beatDurationSecs
                        val syncedTime = (targetBeatIndex * beatDurationSecs) + refBeatPhase
                        if (kotlin.math.abs(curT - syncedTime) > 0.04f) {
                            ctrl.seekTo(syncedTime)
                        }
                    }
                }
            }
        }
    }

    // Vibrant Distinct Clip Colors
    val clipColorsA = remember {
        listOf(
            Color(0xFFFF5722), // Vibrant Orange
            Color(0xFFE91E63), // Vibrant Pink
            Color(0xFF9C27B0), // Purple
            Color(0xFF3F51B5), // Indigo
            Color(0xFF00BCD4), // Cyan
            Color(0xFFFFEB3B), // Yellow
            Color(0xFF4CAF50), // Green
            Color(0xFFFF9800)  // Amber
        )
    }
    val clipColorsB = remember {
        listOf(
            Color(0xFF00E676), // Bright Green
            Color(0xFF00E5FF), // Bright Turquoise
            Color(0xFFFF1744), // Bright Red
            Color(0xFFFFC400), // Gold
            Color(0xFFD500F9), // Neon Purple
            Color(0xFF2979FF), // Bright Blue
            Color(0xFFFF3D00), // Deep Orange
            Color(0xFF76FF03)  // Lime
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF121214))
            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(16.dp))
            .pointerInput(selectedTrackIds, loadedTracksA, loadedTracksB) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        // Center Play/Pause button bounds
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val tx = offset.x - cx
                        val ty = offset.y - cy
                        if (sqrt(tx * tx + ty * ty) < 60f) {
                            viewModel.togglePlayback()
                        }
                    },
                    onTap = { offset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val tx = offset.x - cx
                        val ty = offset.y - cy
                        val dist = sqrt(tx * tx + ty * ty)
                        val baseRadius = min(cx, cy) * 0.7f

                        if (dist > baseRadius * 0.35f) { // Ignore spindle area taps
                            val isOuter = dist > baseRadius
                            val list = if (isOuter) loadedTracksA else loadedTracksB
                            if (list.isNotEmpty()) {
                                val arcSpan = (2 * Math.PI) / list.size
                                // Use playhead zero since tracks don't spin!
                                var angle = atan2(ty, tx) + Math.PI / 2
                                if (angle < 0) angle += 2 * Math.PI
                                val idx = (angle / arcSpan).toInt().coerceIn(0, list.size - 1)
                                val track = list[idx]
                                if (selectedTrackIds.contains(track.id)) {
                                    viewModel.setSelectedTracks(selectedTrackIds - track.id)
                                } else {
                                    viewModel.setSelectedTracks(selectedTrackIds + track.id)
                                }
                            }
                        }
                    },
                    onLongPress = { offset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dist = sqrt((offset.x - cx) * (offset.x - cx) + (offset.y - cy) * (offset.y - cy))
                        val isOuter = dist > min(cx, cy) * 0.7f
                        val list = if (isOuter) loadedTracksA else loadedTracksB
                        if (list.isNotEmpty()) {
                            val arcSpan = (2 * Math.PI) / list.size
                            var angle = atan2(offset.y - cy, offset.x - cx) + Math.PI / 2
                            if (angle < 0) angle += 2 * Math.PI
                            val idx = (angle / arcSpan).toInt().coerceIn(0, list.size - 1)
                            val track = list[idx]
                            if (selectedTrackIds.contains(track.id)) {
                                viewModel.setSelectedTracks(selectedTrackIds - track.id)
                            } else {
                                viewModel.setSelectedTracks(selectedTrackIds + track.id)
                            }
                        }
                    }
                )
            }
            .onGloballyPositioned { layoutCoordinates ->
                platterSize = layoutCoordinates.size
                onPlatterPositioned(layoutCoordinates.positionInWindow(), layoutCoordinates.size)
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseRadius = min(cx, cy) * 0.68f

            // Helper to get waveform height at a specific angle
            fun getPeakHeightAtAngle(angle: Float, isOuter: Boolean): Float {
                val list = if (isOuter) loadedTracksA else loadedTracksB
                if (list.isEmpty()) return 0f
                
                val numClips = list.size
                val arcSpan = (2 * Math.PI) / numClips
                
                var normalizedAngle = (angle + Math.PI / 2) % (2 * Math.PI)
                if (normalizedAngle < 0) normalizedAngle += 2 * Math.PI
                val clipIdx = (normalizedAngle / arcSpan).toInt().coerceIn(0, numClips - 1)
                
                val track = list[clipIdx]
                val volMultiplier = trackVolumes[track.id] ?: 1.0f
                val trackOverlap = trackOverlaps[track.id] ?: 0f
                val effectiveArcSpan = arcSpan + trackOverlap
                
                val startAngle = clipIdx * arcSpan - Math.PI / 2
                var angleDiff = angle - startAngle
                while (angleDiff < 0) angleDiff += 2 * Math.PI.toFloat()
                
                val numSpikes = if (isOuter) (42 * (effectiveArcSpan / arcSpan)).toInt() else (36 * (effectiveArcSpan / arcSpan)).toInt()
                val i = ((angleDiff / effectiveArcSpan) * numSpikes).toInt().coerceIn(0, numSpikes - 1)
                
                val globalIntensity = if (isPlaying) (kotlin.math.sin(visualizerPhase * 50f) + 1f).toFloat() / 2f else 0f
                val spatialEnergy = if (isPlaying) {
                     if (isOuter) (kotlin.math.sin(visualizerPhase * 30f + i * 0.4f) + 1f).toFloat() / 2f
                     else (kotlin.math.sin(visualizerPhase * 30f - i * 0.4f) + 1f).toFloat() / 2f
                } else 0.1f
                
                val dynamicMultiplier = 0.6f + 0.8f * globalIntensity * spatialEnergy
                val pattern = 15f + (track.id.hashCode() % (i + 5) % 24f) * dynamicMultiplier
                return (pattern * volMultiplier).coerceIn(4f, 120f)
            }


            // 2. Deck A Audio Clips (Outer Zone - Waveforms Protruding OUTWARD)
            if (loadedTracksA.isNotEmpty()) {
                val numClipsA = loadedTracksA.size
                val arcSpanA = (2 * Math.PI) / numClipsA

                loadedTracksA.forEachIndexed { clipIdx, track ->
                    val startAngle = clipIdx * arcSpanA - Math.PI / 2 // Removed platterRotationAngle!
                    val isTargeted = selectedTrackIds.contains(track.id)
                    val baseColor = clipColorsA[clipIdx % clipColorsA.size]
                    val clipColor = if (isTargeted) Color.White else baseColor
                    val volMultiplier = trackVolumes[track.id] ?: 1.0f
                    val trackOverlap = trackOverlaps[track.id] ?: 0f
                    val effectiveArcSpan = arcSpanA + trackOverlap

                    val path = androidx.compose.ui.graphics.Path()
                    val numSpikes = (42 * (effectiveArcSpan / arcSpanA)).toInt()
                    for (i in 0 until numSpikes) {
                        val angle = startAngle + (i.toFloat() / numSpikes) * effectiveArcSpan
                        
                        val globalIntensity = if (isPlaying) (kotlin.math.sin(visualizerPhase * 50f) + 1f).toFloat() / 2f else 0f
                        val spatialEnergy = if (isPlaying) (kotlin.math.sin(visualizerPhase * 30f + i * 0.4f) + 1f).toFloat() / 2f else 0.1f
                        val dynamicMultiplier = 0.6f + 0.8f * globalIntensity * spatialEnergy
                        val pattern = 15f + (track.id.hashCode() % (i + 5) % 24f) * dynamicMultiplier
                        val peakH = (pattern * volMultiplier).coerceIn(4f, 120f)

                        val valleyAngle = angle - (effectiveArcSpan / (numSpikes * 2))
                        val vx = cx + cos(valleyAngle).toFloat() * baseRadius
                        val vy = cy + sin(valleyAngle).toFloat() * baseRadius

                        val px = cx + cos(angle).toFloat() * (baseRadius + peakH)
                        val py = cy + sin(angle).toFloat() * (baseRadius + peakH)

                        if (i == 0) {
                            path.moveTo(vx, vy)
                        } else {
                            path.lineTo(vx, vy)
                        }
                        path.lineTo(px, py)
                    }

                    // Final valley point
                    val endValleyAngle = startAngle + effectiveArcSpan - (effectiveArcSpan / (numSpikes * 2))
                    val evx = cx + cos(endValleyAngle).toFloat() * baseRadius
                    val evy = cy + sin(endValleyAngle).toFloat() * baseRadius
                    path.lineTo(evx, evy)

                    val strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    val strokeJoin = androidx.compose.ui.graphics.StrokeJoin.Round
                    val glowIntensity = if(isPlaying) 1.5f else 1f

                    // Outer Faint Glow
                    drawPath(
                        path = path,
                        color = baseColor.copy(alpha = (0.15f * glowIntensity).coerceAtMost(1f)),
                        style = Stroke(width = 18.dp.toPx(), cap = strokeCap, join = strokeJoin)
                    )
                    
                    // Inner Bright Glow
                    drawPath(
                        path = path,
                        color = baseColor.copy(alpha = (0.35f * glowIntensity).coerceAtMost(1f)),
                        style = Stroke(width = 8.dp.toPx(), cap = strokeCap, join = strokeJoin)
                    )

                    // Core Line
                    drawPath(
                        path = path,
                        color = clipColor,
                        style = Stroke(
                            width = if (isTargeted) 3.5.dp.toPx() else 2.dp.toPx(),
                            cap = strokeCap,
                            join = strokeJoin
                        )
                    )

                    // Draw clip segment border accent
                    if (isTargeted) {
                        drawCircle(
                            color = baseColor.copy(alpha = 0.3f),
                            radius = baseRadius + 25f * volMultiplier,
                            center = Offset(cx, cy),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }

        // 3. Deck B Audio Clips (Inner Zone - Waveforms Protruding INWARD)
        if (loadedTracksB.isNotEmpty()) {
            val numClipsB = loadedTracksB.size
            val arcSpanB = (2 * Math.PI) / numClipsB

            loadedTracksB.forEachIndexed { clipIdx, track ->
                val startAngle = clipIdx * arcSpanB - Math.PI / 2 // Removed platterRotationAngle!
                val isTargeted = selectedTrackIds.contains(track.id)
                val baseColor = clipColorsB[clipIdx % clipColorsB.size]
                val clipColor = if (isTargeted) Color.White else baseColor
                val volMultiplier = trackVolumes[track.id] ?: 1.0f
                val trackOverlap = trackOverlaps[track.id] ?: 0f
                val effectiveArcSpan = arcSpanB + trackOverlap

                val path = androidx.compose.ui.graphics.Path()
                val numSpikes = (36 * (effectiveArcSpan / arcSpanB)).toInt()
                for (i in 0 until numSpikes) {
                    val angle = startAngle + (i.toFloat() / numSpikes) * effectiveArcSpan
                    
                        val globalIntensity = if (isPlaying) (kotlin.math.sin(visualizerPhase * 50f) + 1f).toFloat() / 2f else 0f
                        val spatialEnergy = if (isPlaying) (kotlin.math.sin(visualizerPhase * 30f - i * 0.4f) + 1f).toFloat() / 2f else 0.1f
                        val dynamicMultiplier = 0.6f + 0.8f * globalIntensity * spatialEnergy
                        val pattern = 15f + (track.id.hashCode() % (i + 5) % 24f) * dynamicMultiplier
                        val peakH = (pattern * volMultiplier).coerceIn(4f, 120f)

                    val valleyAngle = angle - (effectiveArcSpan / (numSpikes * 2))
                    val vx = cx + cos(valleyAngle).toFloat() * baseRadius
                    val vy = cy + sin(valleyAngle).toFloat() * baseRadius

                    val px = cx + cos(angle).toFloat() * (baseRadius - peakH)
                    val py = cy + sin(angle).toFloat() * (baseRadius - peakH)

                    if (i == 0) {
                        path.moveTo(vx, vy)
                    } else {
                        path.lineTo(vx, vy)
                    }
                    path.lineTo(px, py)
                }

                val endValleyAngle = startAngle + effectiveArcSpan - (effectiveArcSpan / (numSpikes * 2))
                val evx = cx + cos(endValleyAngle).toFloat() * baseRadius
                val evy = cy + sin(endValleyAngle).toFloat() * baseRadius
                path.lineTo(evx, evy)

                val strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                val strokeJoin = androidx.compose.ui.graphics.StrokeJoin.Round
                val globalIntensity = if (isPlaying) (kotlin.math.sin(visualizerPhase * 50f) + 1f).toFloat() / 2f else 0f
                val glowIntensity = if(isPlaying) 1.5f + 1.0f * globalIntensity else 1f

                drawPath(
                    path = path,
                    color = baseColor.copy(alpha = (0.15f * glowIntensity).coerceAtMost(1f)),
                    style = Stroke(width = 18.dp.toPx(), cap = strokeCap, join = strokeJoin)
                )
                
                drawPath(
                    path = path,
                    color = baseColor.copy(alpha = (0.35f * glowIntensity).coerceAtMost(1f)),
                    style = Stroke(width = 8.dp.toPx(), cap = strokeCap, join = strokeJoin)
                )

                drawPath(
                    path = path,
                    color = clipColor,
                    style = Stroke(
                        width = if (isTargeted) 3.5.dp.toPx() else 2.dp.toPx(),
                        cap = strokeCap,
                        join = strokeJoin
                    )
                )
                }
            }

            // 5. Stopwatch Rotating Red Playhead Line (Glowing Slash)
            val currentPlayheadAngle = platterRotationAngle - Math.PI.toFloat() / 2f
            
            val outerPeak = getPeakHeightAtAngle(currentPlayheadAngle, true)
            val innerPeak = getPeakHeightAtAngle(currentPlayheadAngle, false)
            
            val combinedHeight = outerPeak + innerPeak
            val playheadHalfLength = combinedHeight
            val playheadCenterRadius = baseRadius
            
            val startRadius = playheadCenterRadius - playheadHalfLength
            val endRadius = playheadCenterRadius + playheadHalfLength
            
            val startX = cx + cos(currentPlayheadAngle.toDouble()).toFloat() * startRadius
            val startY = cy + sin(currentPlayheadAngle.toDouble()).toFloat() * startRadius
            val endX = cx + cos(currentPlayheadAngle.toDouble()).toFloat() * endRadius
            val endY = cy + sin(currentPlayheadAngle.toDouble()).toFloat() * endRadius
            
            val globalIntensity = if (isPlaying) (kotlin.math.sin(visualizerPhase * 50f) + 1f).toFloat() / 2f else 0f
            val playheadGlow = 0.4f + 0.6f * globalIntensity

            drawLine(
                color = Color(0xFFFF1744).copy(alpha = playheadGlow * 0.4f),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 24.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                color = Color(0xFFFF1744).copy(alpha = playheadGlow * 0.8f),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 10.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // 6. Draw Stationary Song Beat Grid Lines around Circle (Lines on a Grid)
            val numBeatGridTicks = 32
            val beatGridAngles = List(numBeatGridTicks) { b ->
                (b * (2 * Math.PI / numBeatGridTicks) - Math.PI / 2).toFloat()
            }

            beatGridAngles.forEachIndexed { bIdx, gridAngle ->
                val isMajorBeat = bIdx % 4 == 0
                val tickLen = if (isMajorBeat) 12f else 6f
                val tickColor = if (isMajorBeat) Color.Cyan.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f)

                val tickSx = cx + cos(gridAngle) * (baseRadius - tickLen)
                val tickSy = cy + sin(gridAngle) * (baseRadius - tickLen)
                val tickEx = cx + cos(gridAngle) * (baseRadius + tickLen)
                val tickEy = cy + sin(gridAngle) * (baseRadius + tickLen)

                drawLine(
                    color = tickColor,
                    start = Offset(tickSx, tickSy),
                    end = Offset(tickEx, tickEy),
                    strokeWidth = if (isMajorBeat) 1.5.dp.toPx() else 0.8.dp.toPx()
                )
            }

            // 7. Semi-Transparent Drag Placement Preview Waveform (snapping song edges to grid lines)
            if (draggingTrack != null && platterSize.width > 0) {
                val px = dragTouchOffset.x - platterPositionInWindow.x
                val py = dragTouchOffset.y - platterPositionInWindow.y

                val dx = px - cx
                val dy = py - cy
                val dist = sqrt(dx * dx + dy * dy)

                val isOuter = dist > baseRadius
                val dropAngle = atan2(dy, dx)

                // MAGNETIC BEAT GRID SNAPPING: Find closest beat grid line for song edge
                var closestBeatAngle = dropAngle
                var minAngleDiff = Float.MAX_VALUE
                beatGridAngles.forEach { gridAngle ->
                    var diff = abs(dropAngle - gridAngle)
                    if (diff > Math.PI.toFloat()) diff = (2 * Math.PI.toFloat()) - diff
                    if (diff < minAngleDiff) {
                        minAngleDiff = diff
                        closestBeatAngle = gridAngle
                    }
                }

                // Snap song placement angle to nearest beat grid line if within snap threshold
                val isSnapped = minAngleDiff < 0.35f
                val effectiveDropAngle = if (isSnapped) closestBeatAngle else dropAngle
                val normDropAngle = (effectiveDropAngle + Math.PI / 2 + 2 * Math.PI) % (2 * Math.PI)

                val previewColor = if (isSnapped) Color.Cyan.copy(alpha = 0.7f) else Color.Cyan.copy(alpha = 0.4f)
                val numSpikes = 32

                if (isOuter) {
                    val numExisting = loadedTracksA.size
                    val newTotal = (numExisting + 1).coerceAtLeast(1)
                    val arcSpan = (2 * Math.PI) / newTotal
                    val startAngle = normDropAngle - arcSpan / 2 - Math.PI / 2

                    for (i in 0 until numSpikes) {
                        val angle = (startAngle + (i.toFloat() / numSpikes) * arcSpan).toFloat()
                        val peakH = 18f + (i % 6) * 4f
                        val sx = cx + cos(angle) * baseRadius
                        val sy = cy + sin(angle) * baseRadius
                        val ex = cx + cos(angle) * (baseRadius + peakH)
                        val ey = cy + sin(angle) * (baseRadius + peakH)

                        drawLine(
                            color = previewColor,
                            start = Offset(sx, sy),
                            end = Offset(ex, ey),
                            strokeWidth = if (isSnapped) 3.5.dp.toPx() else 2.5.dp.toPx()
                        )
                    }
                    // Highlight magnetic snapped beat line
                    if (isSnapped) {
                        val snapSx = cx + cos(closestBeatAngle) * (baseRadius - 20f)
                        val snapSy = cy + sin(closestBeatAngle) * (baseRadius - 20f)
                        val snapEx = cx + cos(closestBeatAngle) * (baseRadius + 45f)
                        val snapEy = cy + sin(closestBeatAngle) * (baseRadius + 45f)
                        drawLine(
                            color = Color.Cyan,
                            start = Offset(snapSx, snapSy),
                            end = Offset(snapEx, snapEy),
                            strokeWidth = 2.5.dp.toPx()
                        )
                    }
                    drawCircle(
                        color = Color.Cyan.copy(alpha = 0.3f),
                        radius = baseRadius + 32f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx())
                    )
                } else {
                    val numExisting = loadedTracksB.size
                    val newTotal = (numExisting + 1).coerceAtLeast(1)
                    val arcSpan = (2 * Math.PI) / newTotal
                    val startAngle = normDropAngle - arcSpan / 2 - Math.PI / 2

                    for (i in 0 until numSpikes) {
                        val angle = (startAngle + (i.toFloat() / numSpikes) * arcSpan).toFloat()
                        val peakH = 14f + (i % 5) * 3f
                        val sx = cx + cos(angle) * baseRadius
                        val sy = cy + sin(angle) * baseRadius
                        val ex = cx + cos(angle) * (baseRadius - peakH)
                        val ey = cy + sin(angle) * (baseRadius - peakH)

                        drawLine(
                            color = previewColor,
                            start = Offset(sx, sy),
                            end = Offset(ex, ey),
                            strokeWidth = if (isSnapped) 3.5.dp.toPx() else 2.5.dp.toPx()
                        )
                    }
                    if (isSnapped) {
                        val snapSx = cx + cos(closestBeatAngle) * (baseRadius + 20f)
                        val snapSy = cy + sin(closestBeatAngle) * (baseRadius + 20f)
                        val snapEx = cx + cos(closestBeatAngle) * (baseRadius - 35f)
                        val snapEy = cy + sin(closestBeatAngle) * (baseRadius - 35f)
                        drawLine(
                            color = Color.Cyan,
                            start = Offset(snapSx, snapSy),
                            end = Offset(snapEx, snapEy),
                            strokeWidth = 2.5.dp.toPx()
                        )
                    }
                    drawCircle(
                        color = Color.Cyan.copy(alpha = 0.3f),
                        radius = baseRadius - 28f,
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        // HUD Overlay Showing Selected Track Info & Volume
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (selectedTrackIds.isNotEmpty()) {
                val selectedTracks = (loadedTracksA + loadedTracksB).filter { selectedTrackIds.contains(it.id) }
                val titleStr = selectedTracks.joinToString(" + ") { it.title }
                val avgVol = selectedTracks.map { trackVolumes[it.id] ?: 1.0f }.average()
                Text("TARGETED: $titleStr", color = Color.Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("Volume: ${(avgVol * 100).toInt()}% (Drag Vertically to Scale Waveform)", color = Color.Yellow, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
            } else {
                Text("SINGLE CIRCLE PLATTER", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("Outer: Deck A | Inner: Deck B | Red Line: Playhead", color = Color.Gray, fontSize = 8.sp)
            }
        }

        // Floating Gesture Text Overlays Aligned to Radial Clock Positions
        val density = androidx.compose.ui.platform.LocalDensity.current
        val cx = platterSize.width / 2f
        val cy = platterSize.height / 2f
        val baseRadius = min(cx, cy) * 0.68f

        if (platterSize.width > 0) {
            spots.forEach { spot ->
                if (spot.alpha > 0f) {
                    val radiusOffset = baseRadius + 45f
                    val posX = cx + cos(spot.angleRad) * radiusOffset
                    val posY = cy + sin(spot.angleRad) * radiusOffset

                    val posXDp = with(density) { posX.toDp() } - 50.dp
                    val posYDp = with(density) { posY.toDp() } - 15.dp - with(density) { spot.yOffset.toDp() }

                    Box(
                        modifier = Modifier
                            .offset(x = posXDp, y = posYDp)
                            .alpha(spot.alpha)
                    ) {
                        Text(
                            text = spot.text,
                            color = Color.Cyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black,
                                    blurRadius = 8f
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HorizontalSongList(viewModel: SirMatchALotViewModel) {
    val tracks by viewModel.tracks.collectAsState()
    val loadedTracksA by viewModel.loadedTracksA.collectAsState()
    val loadedTracksB by viewModel.loadedTracksB.collectAsState()

    LazyRow(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(tracks) { track ->
            SongListItemCard(
                track = track,
                onClick = {
                    if (loadedTracksA.isEmpty()) {
                        viewModel.addTrackToDeckA(track)
                    } else if (loadedTracksB.isEmpty()) {
                        viewModel.addTrackToDeckB(track)
                    } else {
                        viewModel.addTrackToDeckA(track)
                    }
                }
            )
        }
    }
}

@Composable
fun SongListItemCard(track: Track, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .background(Color(0xFF18181B))
            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(track.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${track.bpm} BPM", color = Color.Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(track.camelotKey, color = Color.Magenta, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

class GestureSpot(
    val clockPosition: String,
    val angleRad: Float
) {
    var text by androidx.compose.runtime.mutableStateOf("")
    var isGestureActive by androidx.compose.runtime.mutableStateOf(false)
    var alpha by androidx.compose.runtime.mutableStateOf(0f)
    var fadeStartTime by androidx.compose.runtime.mutableStateOf(0L)
    var yOffset by androidx.compose.runtime.mutableStateOf(0f)
}
