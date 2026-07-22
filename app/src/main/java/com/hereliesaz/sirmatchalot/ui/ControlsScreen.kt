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
                }
            }
        } else {
            existingSpot?.let { it.isGestureActive = false }
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
                } else if (spot.alpha > 0f) {
                    if (spot.fadeStartTime == 0L) {
                        spot.fadeStartTime = now
                    }
                    val elapsed = now - spot.fadeStartTime
                    val nextAlpha = 1.0f - (elapsed.toFloat() / 500f) // 500ms fade duration
                    if (nextAlpha <= 0f) {
                        spot.alpha = 0f
                        spot.text = ""
                        spot.fadeStartTime = 0L
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

    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
            .pointerInput(selectedTrackIds, loadedTracksA, loadedTracksB) {
                awaitPointerEventScope {
                    var prevSpan2 = 0f
                    var prevSpan3 = 0f
                    var prevAngle2 = 0f
                    var prevAngle3 = 0f
                    var prevPos1 = Offset.Zero
                    var prevPos2 = Offset.Zero

                    var initialPos1 = Offset.Zero
                    var isDragging1 = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.filter { it.pressed }

                        // Platter center approximation for zone fallback
                        val cx = size.width / 2f
                        val cy = size.height * 0.4f // Platter is roughly at top 40% in Column
                        val baseRadius = min(size.width, size.height) * 0.35f

                        // Determine target deck based on selection or fallback to position
                        val getTargetDeck = { pos: Offset ->
                            if (selectedTrackIds.isNotEmpty()) {
                                val inA = loadedTracksA.any { it.id in selectedTrackIds }
                                val inB = loadedTracksB.any { it.id in selectedTrackIds }
                                if (inA && !inB) "A" else if (inB && !inA) "B" else "A" // default A if both or neither selected
                            } else {
                                val dist = sqrt((pos.x - cx) * (pos.x - cx) + (pos.y - cy) * (pos.y - cy))
                                if (dist > baseRadius) "A" else "B"
                            }
                        }

                        when (pointers.size) {
                            1 -> {
                                val change = pointers[0]
                                val pos = change.position
                                
                                if (prevPos1 == Offset.Zero) {
                                    initialPos1 = pos
                                    isDragging1 = false
                                } else {
                                    val dx = pos.x - prevPos1.x
                                    val dy = pos.y - prevPos1.y

                                    if (abs(pos.x - initialPos1.x) > 5f || abs(pos.y - initialPos1.y) > 5f) {
                                        isDragging1 = true
                                    }

                                    if (isDragging1) {
                                        val deckZone = getTargetDeck(pos)

                                        if (abs(dy) > abs(dx) && abs(dy) > 1.5f) {
                                            val pitchDelta = -dy * 0.02f
                                            viewModel.adjustPitchOnly(deckZone, pitchDelta)
                                            updateGestureActive("PITCH SHIFT", true)
                                            updateGestureActive("BASS / TREBLE EQ", false)
                                        } else if (abs(dx) > abs(dy) && abs(dx) > 1.5f) {
                                            val eqDelta = dx * 15f
                                            viewModel.adjustEqBassTreble(deckZone, eqDelta)
                                            updateGestureActive("BASS / TREBLE EQ", true)
                                            updateGestureActive("PITCH SHIFT", false)
                                        }
                                    }
                                }
                                prevPos1 = pos
                                prevSpan2 = 0f
                                prevSpan3 = 0f
                            }
                            2 -> {
                                val p1 = pointers[0].position
                                val p2 = pointers[1].position

                                val center = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                                val currentSpan = sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y))
                                val currentAngle = atan2(p2.y - p1.y, p2.x - p1.x)

                                val deckZone = getTargetDeck(center)

                                if (prevSpan2 > 0f) {
                                    val spanDelta = currentSpan - prevSpan2
                                    var angleDelta = currentAngle - prevAngle2
                                    if (angleDelta > Math.PI.toFloat()) angleDelta -= (2 * Math.PI).toFloat()
                                    if (angleDelta < -Math.PI.toFloat()) angleDelta += (2 * Math.PI).toFloat()

                                    val dy2 = (center.y - prevPos2.y)
                                    val dx2 = (center.x - prevPos2.x)

                                    if (abs(spanDelta) > 4f) {
                                        val bpmDelta = spanDelta * 0.005f
                                        viewModel.adjustBpmSpeed(deckZone, bpmDelta)
                                        updateGestureActive("BPM SPEED", true)
                                    } else if (abs(angleDelta) > 0.05f) {
                                        val overlapDelta = angleDelta * 0.1f
                                        // Use approximate playhead angle for relative overlap
                                        viewModel.adjustOverlap(overlapDelta, deckZone, 0f, 0f)
                                        updateGestureActive("DECK OVERLAP", true)
                                    } else if (abs(dy2) > abs(dx2) && abs(dy2) > 2f) {
                                        viewModel.adjustCrossfaderDelta(-dy2 * 1.5f)
                                        updateGestureActive("CROSSFADER", true)
                                    } else if (abs(dx2) > abs(dy2) && abs(dx2) > 2f) {
                                        viewModel.scrubPlayhead("A", dx2 * 20f)
                                        viewModel.scrubPlayhead("B", dx2 * 20f)
                                        updateGestureActive("SEEK / SCRATCH", true)
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

                                    if (abs(spanDelta) > 3f) {
                                        val volDelta = spanDelta * 0.005f
                                        viewModel.setVolume((viewModel.audioVolume.value + volDelta).coerceIn(0f, 0.8f))
                                        updateGestureActive("MASTER VOLUME", true)
                                    } else if (abs(angleDelta) > 0.05f) {
                                        val spinDelta = angleDelta * 0.5f
                                        viewModel.scrubPlayhead("A", spinDelta * 100f)
                                        viewModel.scrubPlayhead("B", spinDelta * 100f)
                                        updateGestureActive("PLATTER SPIN", true)
                                    }
                                }
                                prevSpan3 = currentSpan3
                                prevAngle3 = currentAngle3
                            }
                        }

                        if (pointers.isEmpty()) {
                            if (prevPos1 != Offset.Zero && !isDragging1) {
                                // Tap!
                                val dist = sqrt((initialPos1.x - cx) * (initialPos1.x - cx) + (initialPos1.y - cy) * (initialPos1.y - cy))
                                if (dist > baseRadius * 0.25f && dist < baseRadius * 1.5f) { // Ignore spindle and far off-platter taps
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

                            prevPos1 = Offset.Zero
                            prevSpan2 = 0f
                            prevSpan3 = 0f
                            isDragging1 = false
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

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                isPlaying = isPlaying
            )
        }
        
        Box(modifier = Modifier.height(140.dp).fillMaxWidth().padding(bottom = 16.dp)) {
            HorizontalSongList(viewModel = viewModel)
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
    isPlaying: Boolean
) {
    // Rotating Platter Angle (Circle Playhead)
    val maxDurationA = controllersA.maxOfOrNull { it.duration.value } ?: 0f
    val maxDurationB = controllersB.maxOfOrNull { it.duration.value } ?: 0f
    val platterDurationSeconds = kotlin.math.max(maxDurationA, maxDurationB).coerceAtLeast(8f)

    val infiniteTransition = rememberInfiniteTransition()
    val platterRotationAngle by if (isPlaying) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween((platterDurationSeconds * 1000).toInt(), easing = LinearEasing),
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
                        val arcSpan = (2 * Math.PI) / list.size
                        var angle = atan2(offset.y - cy, offset.x - cx) + Math.PI / 2
                        if (angle < 0) angle += 2 * Math.PI
                        val idx = (angle / arcSpan).toInt().coerceIn(0, list.size - 1)
                        if (list.isNotEmpty()) {
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

            // 1. Draw Single Primary Dividing Circle Outline
            drawCircle(
                color = Color(0xFF3F3F46),
                radius = baseRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )

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
                        val pattern = 10f + (track.id.hashCode() % (i + 5) % 18f)
                        val peakH = (pattern * volMultiplier).coerceIn(4f, 60f)

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

                    drawPath(
                        path = path,
                        color = clipColor,
                        style = Stroke(
                            width = if (isTargeted) 3.dp.toPx() else 2.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round
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
                    val pattern = 8f + (track.id.hashCode() % (i + 3) % 14f)
                    val peakH = (pattern * volMultiplier).coerceIn(4f, 45f)

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

                drawPath(
                    path = path,
                    color = clipColor,
                    style = Stroke(
                        width = if (isTargeted) 3.dp.toPx() else 2.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
                }
            }

            // 4. Center Spindle
            val innerSpindleRadius = baseRadius * 0.35f
            drawCircle(
                color = Color(0xFF18181B),
                radius = innerSpindleRadius,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color(0xFF3F3F46),
                radius = innerSpindleRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 5. Stopwatch Rotating Red Playhead Line (Hand on a Stopwatch)
            val currentPlayheadAngle = platterRotationAngle - Math.PI.toFloat() / 2f
            val maxWaveProtrusion = 45f
            val playheadLineLength = baseRadius + maxWaveProtrusion

            val redLineEndX = cx + cos(currentPlayheadAngle) * playheadLineLength
            val redLineEndY = cy + sin(currentPlayheadAngle) * playheadLineLength

            drawLine(
                color = Color(0xFFFF1744), // Bright Red
                start = Offset(cx, cy),
                end = Offset(redLineEndX, redLineEndY),
                strokeWidth = 3.dp.toPx()
            )

            // Red Playhead Tip Bulb & Spindle Cap
            drawCircle(
                color = Color(0xFFFF1744),
                radius = 5.dp.toPx(),
                center = Offset(redLineEndX, redLineEndY)
            )
            drawCircle(
                color = Color(0xFFFF1744),
                radius = 7.dp.toPx(),
                center = Offset(cx, cy)
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
                    val posYDp = with(density) { posY.toDp() } - 15.dp

                    Box(
                        modifier = Modifier
                            .offset(x = posXDp, y = posYDp)
                            .alpha(spot.alpha)
                            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                            .border(1.dp, Color.Cyan.copy(alpha = 0.5f * spot.alpha), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = spot.text,
                            color = Color.Cyan,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
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
}
