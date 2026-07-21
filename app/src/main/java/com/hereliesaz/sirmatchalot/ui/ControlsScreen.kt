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
                updateGestureActive = updateGestureActive
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
    updateGestureActive: (String, Boolean) -> Unit
) {
    val loadedTracksA by viewModel.loadedTracksA.collectAsState()
    val loadedTracksB by viewModel.loadedTracksB.collectAsState()
    val controllersA by viewModel.controllersA.collectAsState()
    val controllersB by viewModel.controllersB.collectAsState()

    val selectedTrackIds by viewModel.selectedTrackIds.collectAsState(initial = emptySet())

    val isPlaying by viewModel.isPlaying.collectAsState()

    val infiniteTransition = rememberInfiniteTransition()
    val autoRotationAngle by if (isPlaying) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(6000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    var manualRotationOffset by remember { mutableStateOf(0f) }
    val totalRotation = autoRotationAngle + manualRotationOffset

    val scope = rememberCoroutineScope()
    var platterSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF121214))
            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(16.dp))
            .onGloballyPositioned { layoutCoordinates ->
                onPlatterPositioned(
                    layoutCoordinates.positionInWindow(),
                    layoutCoordinates.size
                )
                platterSize = layoutCoordinates.size
            }
            .pointerInput(selectedTrackIds, loadedTracksA, loadedTracksB) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        val dx = dragAmount.x
                        val dy = dragAmount.y

                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val tx = change.position.x - cx
                        val ty = change.position.y - cy
                        val dist = sqrt(tx * tx + ty * ty)

                        val baseRadius = min(cx, cy) * 0.7f
                        val isOuter = dist > baseRadius

                        // Pitch adjustment rate delta
                        val pitchDelta = -dy * 0.05f

                        if (selectedTrackIds.isNotEmpty()) {
                            // Apply to all targeted tracks simultaneously
                            selectedTrackIds.forEach { selId ->
                                val idxA = loadedTracksA.indexOfFirst { it.id == selId }
                                if (idxA != -1) {
                                    val currentPitch = controllersA.getOrNull(idxA)?.pitch ?: 0f
                                    viewModel.adjustPitch("A", (currentPitch + pitchDelta).coerceIn(-8f, 8f))
                                }
                                val idxB = loadedTracksB.indexOfFirst { it.id == selId }
                                if (idxB != -1) {
                                    val currentPitch = controllersB.getOrNull(idxB)?.pitch ?: 0f
                                    viewModel.adjustPitch("B", (currentPitch + pitchDelta).coerceIn(-8f, 8f))
                                }
                            }
                        } else {
                            // Apply to zone (Deck A vs Deck B)
                            if (isOuter) {
                                val currentPitch = controllersA.firstOrNull()?.pitch ?: 0f
                                viewModel.adjustPitch("A", (currentPitch + pitchDelta).coerceIn(-8f, 8f))
                            } else {
                                val currentPitch = controllersB.firstOrNull()?.pitch ?: 0f
                                viewModel.adjustPitch("B", (currentPitch + pitchDelta).coerceIn(-8f, 8f))
                            }
                        }

                        // Apply visual spin fader
                        manualRotationOffset += dx * 0.005f

                        // Detect drag direction and trigger gesture names
                        if (abs(dy) > abs(dx) && abs(dy) > 2f) {
                            updateGestureActive("PITCH FADER", true)
                            updateGestureActive("VINYL SCRATCH", false)
                        } else if (abs(dx) > abs(dy) && abs(dx) > 2f) {
                            updateGestureActive("VINYL SCRATCH", true)
                            updateGestureActive("PITCH FADER", false)
                        }
                    },
                    onDragEnd = {
                        updateGestureActive("PITCH FADER", false)
                        updateGestureActive("VINYL SCRATCH", false)
                    },
                    onDragCancel = {
                        updateGestureActive("PITCH FADER", false)
                        updateGestureActive("VINYL SCRATCH", false)
                    }
                )
            }
            .pointerInput(selectedTrackIds, loadedTracksA, loadedTracksB) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val tx = offset.x - cx
                        val ty = offset.y - cy
                        val touchRadius = sqrt(tx * tx + ty * ty)

                        val baseRadius = min(cx, cy) * 0.7f
                        val layerOffset = 18.dp.toPx()

                        // Calculate layer index regardless of which zone was tapped
                        val layerIdx = if (touchRadius > baseRadius) {
                            ((touchRadius - baseRadius) / layerOffset).toInt()
                        } else {
                            ((baseRadius - touchRadius) / layerOffset).toInt()
                        }

                        // Select both Deck A and Deck B waveforms in that spot
                        val selectedSet = mutableSetOf<String>()
                        if (layerIdx in loadedTracksA.indices) {
                            selectedSet.add(loadedTracksA[layerIdx].id)
                        }
                        if (layerIdx in loadedTracksB.indices) {
                            selectedSet.add(loadedTracksB[layerIdx].id)
                        }
                        viewModel.setSelectedTracks(selectedSet)

                        updateGestureActive("DOUBLE-TAP DUAL SELECT", true)
                        scope.launch {
                            delay(800)
                            updateGestureActive("DOUBLE-TAP DUAL SELECT", false)
                        }
                    },
                    onTap = { offset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val tx = offset.x - cx
                        val ty = offset.y - cy
                        val touchRadius = sqrt(tx * tx + ty * ty)

                        val baseRadius = min(cx, cy) * 0.7f
                        val layerOffset = 18.dp.toPx()

                        // Single tap selects ONLY the waveform in that place on Deck A or Deck B
                        var selectedId: String? = null
                        if (touchRadius > baseRadius) {
                            val layerIdx = ((touchRadius - baseRadius) / layerOffset).toInt()
                            if (layerIdx in loadedTracksA.indices) {
                                selectedId = loadedTracksA[layerIdx].id
                            }
                        } else {
                            val layerIdx = ((baseRadius - touchRadius) / layerOffset).toInt()
                            if (layerIdx in loadedTracksB.indices) {
                                selectedId = loadedTracksB[layerIdx].id
                            }
                        }
                        viewModel.selectTrack(selectedId)

                        updateGestureActive("TAP SELECT", true)
                        scope.launch {
                            delay(800)
                            updateGestureActive("TAP SELECT", false)
                        }
                    },
                    onLongPress = { offset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val tx = offset.x - cx
                        val ty = offset.y - cy
                        val touchRadius = sqrt(tx * tx + ty * ty)

                        val baseRadius = min(cx, cy) * 0.7f
                        val layerOffset = 18.dp.toPx()

                        if (selectedTrackIds.isNotEmpty()) {
                            val layerIdx = if (touchRadius > baseRadius) {
                                ((touchRadius - baseRadius) / layerOffset).toInt()
                            } else {
                                ((baseRadius - touchRadius) / layerOffset).toInt()
                            }

                            var touchedTrackId: String? = null
                            if (touchRadius > baseRadius) {
                                if (layerIdx in loadedTracksA.indices) {
                                    touchedTrackId = loadedTracksA[layerIdx].id
                                }
                            } else {
                                if (layerIdx in loadedTracksB.indices) {
                                    touchedTrackId = loadedTracksB[layerIdx].id
                                }
                            }

                            if (touchedTrackId != null && selectedTrackIds.contains(touchedTrackId)) {
                                selectedTrackIds.forEach { id ->
                                    viewModel.removeTrackFromDecks(id)
                                }
                                viewModel.setSelectedTracks(emptySet())

                                updateGestureActive("UNLOAD TRACK", true)
                                scope.launch {
                                    delay(1000)
                                    updateGestureActive("UNLOAD TRACK", false)
                                }
                            }
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseRadius = min(cx, cy) * 0.7f
            val layerOffset = 18.dp.toPx()

            // Draw primary deck dividing circle outline
            drawCircle(
                color = Color(0xFF3F3F46),
                radius = baseRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )

            // 1. Draw outer zone Deck A waveforms (protruding outward)
            loadedTracksA.forEachIndexed { idx, track ->
                val r = baseRadius + idx * layerOffset
                val isTargeted = selectedTrackIds.contains(track.id)
                val color = if (isTargeted) Color.Cyan else Color(0xFF0891B2).copy(alpha = 0.7f)

                // Optional guide circle for stacked layers
                if (idx > 0) {
                    drawCircle(
                        color = if (isTargeted) Color.Cyan.copy(alpha = 0.15f) else Color.DarkGray.copy(alpha = 0.1f),
                        radius = r,
                        center = Offset(cx, cy),
                        style = Stroke(width = 0.8f.dp.toPx())
                    )
                }

                val count = 72
                for (i in 0 until count) {
                    val angle = (i.toFloat() / count) * 2 * Math.PI + totalRotation
                    val peakH = 10f + (track.id.hashCode() % (i + 4) % 12f)
                    val sx = cx + cos(angle).toFloat() * r
                    val sy = cy + sin(angle).toFloat() * r
                    val ex = cx + cos(angle).toFloat() * (r + peakH)
                    val ey = cy + sin(angle).toFloat() * (r + peakH)

                    drawLine(
                        color = color,
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = if (isTargeted) 2.2f.dp.toPx() else 1.2f.dp.toPx()
                    )
                }
            }

            // 2. Draw inner zone Deck B waveforms (protruding inward)
            loadedTracksB.forEachIndexed { idx, track ->
                val r = baseRadius - idx * layerOffset
                val isTargeted = selectedTrackIds.contains(track.id)
                val color = if (isTargeted) Color.Yellow else Color(0xFFD97706).copy(alpha = 0.7f)

                if (idx > 0) {
                    drawCircle(
                        color = if (isTargeted) Color.Yellow.copy(alpha = 0.15f) else Color.DarkGray.copy(alpha = 0.1f),
                        radius = r,
                        center = Offset(cx, cy),
                        style = Stroke(width = 0.8f.dp.toPx())
                    )
                }

                val count = 54
                for (i in 0 until count) {
                    val angle = (i.toFloat() / count) * 2 * Math.PI - totalRotation
                    val peakH = 8f + (track.id.hashCode() % (i + 3) % 10f)
                    val sx = cx + cos(angle).toFloat() * r
                    val sy = cy + sin(angle).toFloat() * r
                    val ex = cx + cos(angle).toFloat() * (r - peakH)
                    val ey = cy + sin(angle).toFloat() * (r - peakH)

                    drawLine(
                        color = color,
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = if (isTargeted) 2.0f.dp.toPx() else 1.0f.dp.toPx()
                    )
                }
            }

            // Center Platter Spindle
            val innerSpindleRadius = baseRadius - (loadedTracksB.size * layerOffset).coerceAtLeast(0f) - 15f
            if (innerSpindleRadius > 20f) {
                drawCircle(
                    color = Color(0xFF1F1F23),
                    radius = innerSpindleRadius.coerceAtLeast(15f),
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = Color.DarkGray,
                    radius = innerSpindleRadius.coerceAtLeast(15f),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            drawCircle(Color(0xFF312E81), radius = 8.dp.toPx(), center = Offset(cx, cy))
            drawCircle(Color.White, radius = 3.dp.toPx(), center = Offset(cx, cy))
        }

        // HUD overlay showing targeted track names
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (selectedTrackIds.isNotEmpty()) {
                val selectedTitles = (loadedTracksA + loadedTracksB)
                    .filter { selectedTrackIds.contains(it.id) }
                    .map { it.title }
                Text("TARGETED: ${selectedTitles.joinToString(" + ")}", color = Color.Cyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("Single tap targets one | Double tap targets both decks in spot", color = Color.LightGray, fontSize = 8.sp)
            } else {
                Text("GLOBAL MODE (Apply to Deck Zones)", color = Color.LightGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("Single tap to target one | Double tap targets both decks", color = Color.Gray, fontSize = 8.sp)
            }
        }

        // Floating gesture text overlays aligned to radial clock positions
        val density = androidx.compose.ui.platform.LocalDensity.current
        val cx = platterSize.width / 2f
        val cy = platterSize.height / 2f
        val baseRadius = min(cx, cy) * 0.7f
        val layerOffset = with(density) { 18.dp.toPx() }

        if (platterSize.width > 0) {
            spots.forEach { spot ->
                if (spot.alpha > 0f) {
                    val radiusOffset = baseRadius + (loadedTracksA.size * layerOffset).coerceAtLeast(0f) + 30f
                    val posX = cx + cos(spot.angleRad) * radiusOffset
                    val posY = cy + sin(spot.angleRad) * radiusOffset

                    val posXDp = with(density) { posX.toDp() } - 60.dp
                    val posYDp = with(density) { posY.toDp() } - 15.dp

                    Box(
                        modifier = Modifier
                            .offset(x = posXDp, y = posYDp)
                            .alpha(spot.alpha)
                            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                            .border(1.dp, Color.Cyan.copy(alpha = 0.4f * spot.alpha), RoundedCornerShape(6.dp))
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
