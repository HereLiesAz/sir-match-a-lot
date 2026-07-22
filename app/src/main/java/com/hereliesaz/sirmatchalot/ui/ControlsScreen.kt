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
    var activeSubTab by remember { mutableStateOf("radial") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF18181B))
                .padding(4.dp)
        ) {
            Button(
                onClick = { activeSubTab = "radial" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeSubTab == "radial") Color.Cyan else Color.Transparent
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Text("RADIAL CONTROLLER", color = if (activeSubTab == "radial") Color.Black else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Button(
                onClick = { activeSubTab = "energy" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeSubTab == "energy") Color.Cyan else Color.Transparent
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Text("SESSION ENERGY GRAPH", color = if (activeSubTab == "energy") Color.Black else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }

        if (activeSubTab == "radial") {
            RadialControllerSplitPane(viewModel = viewModel)
        } else {
            EnergyGraphView(viewModel = viewModel)
        }
    }
}

@Composable
fun RadialControllerSplitPane(viewModel: SirMatchALotViewModel) {
    var draggingTrack by remember { mutableStateOf<Track?>(null) }
    var dragTouchOffset by remember { mutableStateOf(Offset.Zero) }

    var platterPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    var platterSize by remember { mutableStateOf(IntSize.Zero) }

    val scope = rememberCoroutineScope()

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

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Left Column: Radial Platter Controller (55% width)
        Box(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
        ) {
            RadialControllerPlatter(
                viewModel = viewModel,
                onPlatterPositioned = { pos, size ->
                    platterPositionInWindow = pos
                    platterSize = size
                },
                spots = spots,
                updateGestureActive = updateGestureActive,
                draggingTrack = draggingTrack,
                dragTouchOffset = dragTouchOffset,
                platterPositionInWindow = platterPositionInWindow
            )
        }

        // Right Column: Draggable/Sortable Side Library List (45% width)
        Box(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
        ) {
            SideLibraryList(
                viewModel = viewModel,
                onDragStart = { track, offset ->
                    draggingTrack = track
                    dragTouchOffset = offset
                },
                onDragMove = { offset ->
                    dragTouchOffset += offset
                },
                onDragEnd = {
                    val track = draggingTrack
                    if (track != null && platterSize.width > 0) {
                        val px = dragTouchOffset.x
                        val py = dragTouchOffset.y

                        val cx = platterPositionInWindow.x + platterSize.width / 2f
                        val cy = platterPositionInWindow.y + platterSize.height / 2f

                        val dx = px - cx
                        val dy = py - cy
                        val dist = sqrt(dx * dx + dy * dy)

                        val maxRadius = platterSize.width / 2f

                        if (dist <= maxRadius) {
                            // Check outer vs inner zone drops
                            val boundary = maxRadius * 0.65f
                            if (dist > boundary) {
                                viewModel.addTrackToDeckA(track)
                            } else {
                                viewModel.addTrackToDeckB(track)
                            }
                            // Trigger TRACK LOAD gesture text
                            scope.launch {
                                updateGestureActive("TRACK LOAD", true)
                                delay(1200)
                                updateGestureActive("TRACK LOAD", false)
                            }
                        }
                    }
                    draggingTrack = null
                }
            )

            // Floating drag card overlay representation
            draggingTrack?.let { track ->
                Card(
                    modifier = Modifier
                        .offset(
                            x = (dragTouchOffset.x - platterPositionInWindow.x - 70f).dp,
                            y = (dragTouchOffset.y - platterPositionInWindow.y - 30f).dp
                        )
                        .size(140.dp, 60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Cyan.copy(alpha = 0.8f))
                        .border(1.dp, Color.White, RoundedCornerShape(8.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color.Cyan.copy(alpha = 0.8f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(track.title, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                        Text(track.artist, color = Color.DarkGray, fontSize = 8.sp, maxLines = 1)
                    }
                }
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
    platterPositionInWindow: Offset = Offset.Zero
) {
    val loadedTracksA by viewModel.loadedTracksA.collectAsState()
    val loadedTracksB by viewModel.loadedTracksB.collectAsState()
    val controllersA by viewModel.controllersA.collectAsState()
    val controllersB by viewModel.controllersB.collectAsState()
    val trackVolumes by viewModel.trackVolumes.collectAsState()
    val trackOverlaps by viewModel.trackOverlaps.collectAsState()

    val selectedTrackIds by viewModel.selectedTrackIds.collectAsState(initial = emptySet())
    val isPlaying by viewModel.isPlaying.collectAsState()

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
                awaitPointerEventScope {
                    var prevSpan2 = 0f
                    var prevSpan3 = 0f
                    var prevAngle2 = 0f
                    var prevAngle3 = 0f
                    var prevPos1 = Offset.Zero
                    var prevPos2 = Offset.Zero

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.filter { it.pressed }

                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val baseRadius = min(cx, cy) * 0.7f

                        when (pointers.size) {
                            1 -> {
                                val change = pointers[0]
                                val pos = change.position
                                if (prevPos1 != Offset.Zero) {
                                    val dx = pos.x - prevPos1.x
                                    val dy = pos.y - prevPos1.y

                                    val dist = sqrt((pos.x - cx) * (pos.x - cx) + (pos.y - cy) * (pos.y - cy))
                                    val isOuter = dist > baseRadius
                                    val deckZone = if (isOuter) "A" else "B"

                                    if (abs(dy) > abs(dx) && abs(dy) > 1.5f) {
                                        // 1-Finger Vertical = PITCH SHIFT (Pitch Only!)
                                        val pitchDelta = -dy * 0.02f
                                        viewModel.adjustPitchOnly(deckZone, pitchDelta)

                                        updateGestureActive("PITCH SHIFT", true)
                                        updateGestureActive("BASS / TREBLE EQ", false)
                                    } else if (abs(dx) > abs(dy) && abs(dx) > 1.5f) {
                                        // 1-Finger Horizontal = BASS / TREBLE EQ!
                                        val eqDelta = dx * 15f
                                        viewModel.adjustEqBassTreble(deckZone, eqDelta)

                                        updateGestureActive("BASS / TREBLE EQ", true)
                                        updateGestureActive("PITCH SHIFT", false)
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

                                val dist = sqrt((center.x - cx) * (center.x - cx) + (center.y - cy) * (center.y - cy))
                                val deckZone = if (dist > baseRadius) "A" else "B"

                                if (prevSpan2 > 0f) {
                                    val spanDelta = currentSpan - prevSpan2
                                    var angleDelta = currentAngle - prevAngle2
                                    if (angleDelta > Math.PI.toFloat()) angleDelta -= (2 * Math.PI).toFloat()
                                    if (angleDelta < -Math.PI.toFloat()) angleDelta += (2 * Math.PI).toFloat()

                                    val dx2 = (center.x - prevPos2.x)
                                    val dy2 = (center.y - prevPos2.y)

                                    if (abs(spanDelta) > 4f) {
                                        // 2-Finger Pinch to Zoom = BPM SPEED!
                                        val bpmDelta = spanDelta * 0.005f
                                        viewModel.adjustBpmSpeed(deckZone, bpmDelta)

                                        updateGestureActive("BPM SPEED", true)
                                    } else if (abs(angleDelta) > 0.05f) {
                                        // 2-Finger Rotation = DECK OVERLAP!
                                        val overlapDelta = angleDelta * 0.1f
                                        val playhead = platterRotationAngle - Math.PI.toFloat() / 2f
                                        viewModel.adjustOverlap(overlapDelta, deckZone, playhead, 0f)

                                        updateGestureActive("DECK OVERLAP", true)
                                    } else if (abs(dy2) > abs(dx2) && abs(dy2) > 2f) {
                                        // 2-Finger Vertical Drag = CROSSFADER!
                                        viewModel.adjustCrossfaderDelta(-dy2 * 1.5f)

                                        updateGestureActive("CROSSFADER", true)
                                    } else if (abs(dx2) > abs(dy2) && abs(dx2) > 2f) {
                                        // 2-Finger Horizontal Drag = REWIND / FAST-FORWARD!
                                        val seekSecs = dx2 * 0.1f
                                        viewModel.seekTrack(deckZone, seekSecs)

                                        updateGestureActive("REWIND / FAST-FORWARD", true)
                                    }
                                }

                                prevSpan2 = currentSpan
                                prevAngle2 = currentAngle
                                prevPos2 = center
                                prevPos1 = Offset.Zero
                                prevSpan3 = 0f
                            }
                            3 -> {
                                val p1 = pointers[0].position
                                val p2 = pointers[1].position
                                val p3 = pointers[2].position

                                val cxP = (p1.x + p2.x + p3.x) / 3f
                                val cyP = (p1.y + p2.y + p3.y) / 3f

                                val currentSpan = (
                                    sqrt((p1.x - cxP) * (p1.x - cxP) + (p1.y - cyP) * (p1.y - cyP)) +
                                    sqrt((p2.x - cxP) * (p2.x - cxP) + (p2.y - cyP) * (p2.y - cyP)) +
                                    sqrt((p3.x - cxP) * (p3.x - cxP) + (p3.y - cyP) * (p3.y - cyP))
                                ) / 3f
                                val currentAngle = atan2(cyP - cy, cxP - cx)

                                val dist = sqrt((cxP - cx) * (cxP - cx) + (cyP - cy) * (cyP - cy))
                                val deckZone = if (dist > baseRadius) "A" else "B"

                                if (prevSpan3 > 0f) {
                                    val spanDelta = currentSpan - prevSpan3
                                    var angleDelta = currentAngle - prevAngle3
                                    if (angleDelta > Math.PI.toFloat()) angleDelta -= (2 * Math.PI).toFloat()
                                    if (angleDelta < -Math.PI.toFloat()) angleDelta += (2 * Math.PI).toFloat()

                                    if (abs(angleDelta) > 0.04f) {
                                        // 3-Finger Rotation = VINYL PLATTER SPIN (Manual Circle Spin)!
                                        viewModel.scrubPlayhead(deckZone, angleDelta)

                                        updateGestureActive("VINYL PLATTER SPIN", true)
                                        updateGestureActive("VOLUME GAIN", false)
                                    } else if (abs(spanDelta) > 2f) {
                                        // 3-Finger Pinch In/Out = VOLUME GAIN!
                                        val volDelta = spanDelta * 0.015f
                                        viewModel.adjustTrackVolume("A", volDelta)
                                        viewModel.adjustTrackVolume("B", volDelta)

                                        updateGestureActive("VOLUME GAIN", true)
                                        updateGestureActive("VINYL PLATTER SPIN", false)
                                    }
                                }
                                prevSpan3 = currentSpan
                                prevAngle3 = currentAngle
                                prevSpan2 = 0f
                                prevPos1 = Offset.Zero
                            }
                            else -> {
                                prevPos1 = Offset.Zero
                                prevSpan2 = 0f
                                prevSpan3 = 0f
                            }
                        }
                    }
                }
            }
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

                    val numSpikes = (42 * (effectiveArcSpan / arcSpanA)).toInt()
                    for (i in 0 until numSpikes) {
                        val angle = startAngle + (i.toFloat() / numSpikes) * effectiveArcSpan
                        val pattern = 10f + (track.id.hashCode() % (i + 5) % 18f)
                        val peakH = (pattern * volMultiplier).coerceIn(4f, 60f)

                        val sx = cx + cos(angle).toFloat() * baseRadius
                        val sy = cy + sin(angle).toFloat() * baseRadius
                        val ex = cx + cos(angle).toFloat() * (baseRadius + peakH)
                        val ey = cy + sin(angle).toFloat() * (baseRadius + peakH)

                        drawLine(
                            color = clipColor,
                            start = Offset(sx, sy),
                            end = Offset(ex, ey),
                            strokeWidth = if (isTargeted) 2.5f.dp.toPx() else 1.5f.dp.toPx()
                        )
                    }

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

                    val numSpikes = (36 * (effectiveArcSpan / arcSpanB)).toInt()
                    for (i in 0 until numSpikes) {
                        val angle = startAngle + (i.toFloat() / numSpikes) * effectiveArcSpan
                        val pattern = 8f + (track.id.hashCode() % (i + 3) % 14f)
                        val peakH = (pattern * volMultiplier).coerceIn(4f, 45f)

                        val sx = cx + cos(angle).toFloat() * baseRadius
                        val sy = cy + sin(angle).toFloat() * baseRadius
                        val ex = cx + cos(angle).toFloat() * (baseRadius - peakH)
                        val ey = cy + sin(angle).toFloat() * (baseRadius - peakH)

                        drawLine(
                            color = clipColor,
                            start = Offset(sx, sy),
                            end = Offset(ex, ey),
                            strokeWidth = if (isTargeted) 2.5f.dp.toPx() else 1.5f.dp.toPx()
                        )
                    }
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
fun SideLibraryList(
    viewModel: SirMatchALotViewModel,
    onDragStart: (Track, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val sortedTracks by viewModel.sortedTracks.collectAsState(initial = emptyList())
    val sortOption by viewModel.sortOption.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF18181B))
            .padding(8.dp)
    ) {
        // Title
        Text("DECK BUILDER DRAG-ZONE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))

        // Sorting Toolbar Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val sortOptions = listOf(
                Pair(SirMatchALotViewModel.SortOption.BPM, "BPM"),
                Pair(SirMatchALotViewModel.SortOption.PITCH, "KEY"),
                Pair(SirMatchALotViewModel.SortOption.BOTH, "MIX"),
                Pair(SirMatchALotViewModel.SortOption.ORIGINAL, "ORIG"),
                Pair(SirMatchALotViewModel.SortOption.CUSTOM, "CUST")
            )

            sortOptions.forEach { opt ->
                Button(
                    onClick = { viewModel.setSortOption(opt.first) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sortOption == opt.first) Color.Cyan else Color(0xFF27272A)
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    modifier = Modifier.weight(1f).height(24.dp)
                ) {
                    Text(opt.second, color = if (sortOption == opt.first) Color.Black else Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Scrollable Lists
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(sortedTracks) { index, track ->
                DraggableTrackItemCard(
                    track = track,
                    index = index,
                    sortOption = sortOption,
                    onMoveUp = { viewModel.moveTrackInCustomOrder(index, max(0, index - 1)) },
                    onMoveDown = { viewModel.moveTrackInCustomOrder(index, min(sortedTracks.size - 1, index + 1)) },
                    onDragStart = { offset -> onDragStart(track, offset) },
                    onDragMove = onDragMove,
                    onDragEnd = onDragEnd,
                    onLoadA = { viewModel.addTrackToDeckA(track) },
                    onLoadB = { viewModel.addTrackToDeckB(track) }
                )
            }
        }
    }
}

@Composable
fun DraggableTrackItemCard(
    track: Track,
    index: Int,
    sortOption: SirMatchALotViewModel.SortOption,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onLoadA: () -> Unit,
    onLoadB: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF27272A))
            .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Drag handle to drag onto Platter
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset -> onDragStart(changeToWindowOffset(offset, size)) },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd,
                            onDrag = { change, dragAmount -> onDragMove(dragAmount) }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Drag to Circle", tint = Color.LightGray, modifier = Modifier.size(16.dp))
            }

            Spacer(Modifier.width(6.dp))

            // Track details
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                Text(track.artist, color = Color.Gray, fontSize = 9.sp, maxLines = 1)
                Text("${track.bpm} BPM | ${track.camelotKey}", color = Color.Cyan, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            }

            // Quick add load helpers or reorder buttons
            if (sortOption == SirMatchALotViewModel.SortOption.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = onLoadA,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0891B2)),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        modifier = Modifier.height(20.dp)
                    ) {
                        Text("+A", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = onLoadB,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        modifier = Modifier.height(20.dp)
                    ) {
                        Text("+B", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

// Convert local gesture touch coordinate to relative drag position
private fun changeToWindowOffset(localOffset: Offset, size: IntSize): Offset {
    return Offset(localOffset.x, localOffset.y)
}

@Composable
fun EnergyGraphView(viewModel: SirMatchALotViewModel) {
    val energyPoints = remember {
        mutableStateListOf(25f, 35f, 45f, 40f, 55f, 70f, 85f, 75f, 90f, 95f, 80f, 65f, 50f, 40f, 30f, 20f)
    }

    val averageBpm = 122f
    val bpmFactor = 1.0f

    val adjustedPoints = remember(energyPoints.toList(), bpmFactor) {
        energyPoints.map { (it * bpmFactor).coerceIn(5f, 100f).roundToInt() }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("INTERACTIVE ENERGY CURVE (16 BARS)", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF09090B))
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        val colWidth = size.width / 15f
                        val colIndex = (change.position.x / colWidth).roundToInt().coerceIn(0, 15)
                        
                        val maxH = size.height - 40f
                        val relativeY = size.height - 20f - change.position.y
                        val percent = ((relativeY / maxH) * 100f).coerceIn(5f, 100f)

                        energyPoints[colIndex] = percent / bpmFactor
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val colWidth = width / 15f

                for (j in 0..4) {
                    val y = (height - 30f) * (j / 4f) + 15f
                    drawLine(Color(0xFF18181B), Offset(0f, y), Offset(width, y), strokeWidth = 1f)
                }

                drawRect(Color(0xFF06B6D4).copy(alpha = 0.08f), Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(colWidth * 3, height))
                drawRect(Color(0xFFEC4899).copy(alpha = 0.06f), Offset(colWidth * 12, 0f), size = androidx.compose.ui.geometry.Size(colWidth * 3, height))

                val pointsList = adjustedPoints
                val getX = { idx: Int -> idx * colWidth }
                val getY = { valPercent: Int ->
                    val maxH = height - 40f
                    height - 20f - (valPercent / 100f) * maxH
                }

                for (k in 0 until pointsList.size - 1) {
                    val x1 = getX(k)
                    val y1 = getY(pointsList[k])
                    val x2 = getX(k + 1)
                    val y2 = getY(pointsList[k + 1])

                    drawLine(
                        Brush.linearGradient(listOf(Color(0xFF06B6D4), Color(0xFFA855F7))),
                        Offset(x1, y1),
                        Offset(x2, y2),
                        strokeWidth = 3.dp.toPx()
                    )
                }

                pointsList.forEachIndexed { idx, valPercent ->
                    val x = getX(idx)
                    val y = getY(valPercent)
                    drawCircle(Color.White, radius = 3.dp.toPx(), center = Offset(x, y))
                }
            }
        }

        val peak = adjustedPoints.maxOrNull() ?: 50
        val end = adjustedPoints.lastOrNull() ?: 20
        val mixRecommendation = when {
            peak > 85 && end < 35 -> Pair("Intense Drop & Quick Outro", "High visual spike matches raw peak time tech-house energy. Plan a quick blend mix to transition safely before energy bottoms out.")
            peak > 70 && abs(peak - end) < 15 -> Pair("Sustained Peak Power", "Steady elevated energy is excellent for main-room techno loops. Keep channels fully open and loop key vocal stems for high impact.")
            else -> Pair("Classic Dynamic Wave", "Perfect dynamic narrative for open-format DJ sets. Introduces a steady build, peak impact, and gentle tail-off for the next track.")
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("RECOMMENDED TRANSITION STYLE", color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                Text(mixRecommendation.first, color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(mixRecommendation.second, color = Color.LightGray, fontSize = 10.sp)
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
