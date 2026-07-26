package com.hereliesaz.sirmatchalot.ui.platter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.sirmatchalot.data.Track
import com.hereliesaz.sirmatchalot.gesture.GestureEngine
import com.hereliesaz.sirmatchalot.gesture.GestureKind
import com.hereliesaz.sirmatchalot.gesture.GestureLabels
import com.hereliesaz.sirmatchalot.gesture.Pointer
import androidx.compose.runtime.withFrameMillis
import kotlin.math.min

/** What the platter screen can ask the app to do. */
interface PlatterActions {
    fun onCrossfade(delta: Float)
    fun onScratchBegin()
    fun onScratch(totalDeltaY: Float)
    fun onScratchEnd()
    fun onVolume(delta: Float)
    fun onBassBoost(delta: Float)
    fun onSelectAt(deck: PlatterGeometry.Deck, fraction: Float, additive: Boolean)
    fun onSelectBothAt(fraction: Float)
    fun onRemoveSelected()
    fun onLoadTrack(track: Track)

    /**
     * A track was dragged from the strip and released on the platter.
     *
     * @param deck which ring it landed on — outside is A, inside is B.
     * @param fraction where around the circle, 0 at twelve o'clock. Angle is
     *   time, so this is the point in the deck's timeline the clip starts at.
     */
    fun onDropTrack(track: Track, deck: PlatterGeometry.Deck, fraction: Float)

    /** A clip already on the platter was dragged to a new point on its deck. */
    fun onMoveClip(clipId: String, deck: PlatterGeometry.Deck, fraction: Float)

    /** A clip was dragged off the circle entirely. */
    fun onRemoveClip(clipId: String)
}

/**
 * The platter, as the feature of the app.
 *
 * Not inside a card, not on a panel — it fills the screen, with only the
 * controls row above and the horizontally scrolling track list below. Layout is
 * identical in portrait and landscape.
 *
 * Gestures are handled here rather than on the canvas, on a modifier covering the
 * whole area, because they are global: it must not matter where on screen the
 * gesture happens. Only tap-to-select consults position.
 */
@Composable
fun PlatterScreen(
    state: PlatterState,
    tracks: List<Track>,
    actions: PlatterActions,
    modifier: Modifier = Modifier,
) {
    val gestures = remember { GestureEngine() }
    val labels = remember { GestureLabels() }

    // Three-finger platter transform.
    var scale by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableFloatStateOf(0f) }

    // Hold the playhead still and turn the record under it, the way a deck
    // actually reads: the needle does not move, the disc does. Off by default,
    // because a fixed waveform is easier to aim a gesture at.
    var playheadLocked by remember { mutableStateOf(false) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Drag-and-drop from the strip onto the circle. The state lives here rather
    // than in TrackStrip because the drop target is the platter, which is a
    // sibling: the strip cannot hit-test something it does not contain.
    var draggingTrack by remember { mutableStateOf<Track?>(null) }

    // A clip already on the platter, held under the finger. `offCircle` means it
    // has been dragged out of the ring band and will be removed on release — but
    // it stays under the finger until then, so the gesture can be reconsidered
    // by moving back onto the circle.
    var heldClipId by remember { mutableStateOf<String?>(null) }
    var heldClipTitle by remember { mutableStateOf("") }
    var heldClipDeck by remember { mutableStateOf(PlatterGeometry.Deck.A) }
    var heldClipOffCircle by remember { mutableStateOf(false) }
    var heldClipPosition by remember { mutableStateOf(Offset.Zero) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var platterOrigin by remember { mutableStateOf(Offset.Zero) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }

    /** Where a drop at [rootPosition] would land, or null if outside the platter. */
    fun dropTargetAt(rootPosition: Offset): Pair<PlatterGeometry.Deck, Float>? {
        if (canvasSize.width == 0 || canvasSize.height == 0) return null
        val local = rootPosition - platterOrigin
        if (local.x < 0f || local.y < 0f ||
            local.x > canvasSize.width || local.y > canvasSize.height
        ) {
            return null
        }
        val cx = canvasSize.width / 2f
        val cy = canvasSize.height / 2f
        val baseRadius = minOf(canvasSize.width, canvasSize.height) * 0.30f * scale
        val radius = PlatterGeometry.radiusOf(local.x, local.y, cx, cy)
        return PlatterGeometry.deckAt(radius, baseRadius) to
            PlatterGeometry.fractionOf(local.x, local.y, cx, cy)
    }
    var visibleLabels by remember { mutableStateOf(labels.visible()) }
    var scratching by remember { mutableStateOf(false) }

    // Driven by Compose's frame clock rather than a fixed 16 ms delay loop, so
    // it stays in step with the display and pauses when the composition is not
    // being drawn. The previous implementation ran an unconditional
    // `while(true) { delay(16) }` for the screen's entire lifetime.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameTimeMillis ->
                labels.update(
                    activeTexts = gestures.active.map { it.kind.label }.toSet(),
                    nowMillis = frameTimeMillis,
                )
                visibleLabels = labels.visible()
            }
        }
    }

    // A Box so the dragged card can float above the screen; the offset it is
    // positioned by needs a container whose origin matches the coordinates the
    // drag reports in.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05050A))
            // Drag positions are reported in window coordinates so the platter
            // can be hit-tested; the ghost is positioned relative to this Box,
            // which sits below a top bar, so its origin has to be subtracted.
            .onGloballyPositioned { screenOrigin = it.positionInRoot() },
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it }
                .onGloballyPositioned { platterOrigin = it.positionInRoot() }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointers = event.changes
                                .filter { it.pressed }
                                .map { Pointer(it.id.value, it.position.x, it.position.y) }

                            // One finger starting on a clip drags that clip's
                            // placement rather than feeding the gesture engine.
                            // Angle is time here, so moving it around the circle
                            // *is* moving it in the timeline.
                            val single = pointers.singleOrNull()
                            if (single != null && canvasSize.width > 0) {
                                val cx = canvasSize.width / 2f
                                val cy = canvasSize.height / 2f
                                val baseRadius =
                                    minOf(canvasSize.width, canvasSize.height) * 0.30f * scale
                                val radius = PlatterGeometry.radiusOf(single.x, single.y, cx, cy)
                                val fraction =
                                    PlatterGeometry.fractionOf(single.x, single.y, cx, cy)

                                if (heldClipId == null) {
                                    if (PlatterGeometry.isOnRing(radius, baseRadius)) {
                                        val deck = PlatterGeometry.deckAt(radius, baseRadius)
                                        val clip = PlatterGeometry.clipAt(state.clipsFor(deck), fraction)
                                        if (clip != null) {
                                            heldClipId = clip.id
                                            heldClipTitle = clip.title
                                            heldClipDeck = deck
                                            heldClipOffCircle = false
                                        }
                                    }
                                } else {
                                    heldClipPosition = Offset(single.x, single.y)
                                    val onRing = PlatterGeometry.isOnRing(radius, baseRadius)
                                    heldClipOffCircle = !onRing
                                    if (onRing) {
                                        heldClipDeck = PlatterGeometry.deckAt(radius, baseRadius)
                                        actions.onMoveClip(heldClipId!!, heldClipDeck, fraction)
                                    }
                                }
                            }

                            if (heldClipId != null && pointers.isEmpty()) {
                                if (heldClipOffCircle) actions.onRemoveClip(heldClipId!!)
                                heldClipId = null
                                heldClipOffCircle = false
                            }

                            // While a clip is held, the gesture engine must not
                            // also read the finger as a scratch or a crossfade.
                            val recognised =
                                if (heldClipId != null) emptyList() else gestures.update(pointers)

                            for (gesture in recognised) {
                                when (gesture.kind) {
                                    GestureKind.CROSSFADE -> actions.onCrossfade(gesture.delta)
                                    GestureKind.SCRATCH -> {
                                        if (!scratching) {
                                            scratching = true
                                            actions.onScratchBegin()
                                        }
                                        actions.onScratch(gesture.total)
                                    }
                                    GestureKind.VOLUME -> actions.onVolume(gesture.delta)
                                    GestureKind.BASS_BOOST -> actions.onBassBoost(gesture.delta)
                                    GestureKind.PLATTER_SCALE ->
                                        scale = (scale + gesture.delta * 0.004f).coerceIn(0.4f, 4f)
                                    GestureKind.PLATTER_ROTATE -> rotation += gesture.delta
                                    GestureKind.CLIP_DRAG -> Unit
                                }
                            }

                            if (scratching && recognised.none { it.kind == GestureKind.SCRATCH }) {
                                scratching = false
                                actions.onScratchEnd()
                            }
                        }
                    }
                }
                .pointerInput(state) {
                    detectTapGestures(
                        onTap = { position ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val radius = PlatterGeometry.radiusOf(position.x, position.y, cx, cy)
                            val baseRadius = min(size.width, size.height) * 0.30f * scale
                            val fraction = PlatterGeometry.fractionOf(position.x, position.y, cx, cy)
                            actions.onSelectAt(
                                PlatterGeometry.deckAt(radius, baseRadius),
                                fraction,
                                additive = false,
                            )
                        },
                        // Double tap selects both decks' waveforms at that spot.
                        onDoubleTap = { position ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            actions.onSelectBothAt(
                                PlatterGeometry.fractionOf(position.x, position.y, cx, cy),
                            )
                        },
                        // Long press removes the selected track(s).
                        onLongPress = { actions.onRemoveSelected() },
                    )
                },
        ) {
            PlatterCanvas(
                state = state,
                labels = visibleLabels,
                scale = scale,
                offsetX = 0f,
                offsetY = 0f,
                // Locking the playhead is the same thing as turning the platter
                // by the playhead's own position: the mark then always lands at
                // the top and the waveform sweeps past it.
                rotation = rotation + if (playheadLocked) {
                    -state.playheadFraction * TWO_PI
                } else {
                    0f
                },
            )

            GestureLabelOverlay(visibleLabels, canvasSize, scale, 0f, 0f)

            // While a track is held over the platter, show exactly where it will
            // land: which ring, and which point on it. A drop that guesses is
            // worse than no drop at all.
            // A clip dragged off the circle is gone from the deck but still in
            // hand: it follows the finger, marked for removal, until release.
            if (heldClipId != null && heldClipOffCircle) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color(0xFFDC2626),
                        radius = 22f,
                        center = heldClipPosition,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                    )
                }
            }

            draggingTrack?.let {
                val target = dropTargetAt(dragPosition)
                if (target != null) {
                    val (deck, fraction) = target
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val baseRadius = minOf(size.width, size.height) * 0.30f * scale
                        val ringRadius = if (deck == PlatterGeometry.Deck.A) {
                            baseRadius * 1.35f
                        } else {
                            baseRadius * 0.65f
                        }
                        val colour =
                            if (deck == PlatterGeometry.Deck.A) Color(0xFF06B6D4) else Color(0xFFF59E0B)
                        drawCircle(
                            color = colour.copy(alpha = 0.25f),
                            radius = ringRadius,
                            center = Offset(cx, cy),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                        )
                        val (px, py) = PlatterGeometry.pointAt(fraction, ringRadius, cx, cy)
                        drawCircle(color = colour, radius = 16f, center = Offset(px, py))
                    }
                }
            }
        }

        // Playhead lock. Off by default: a fixed waveform is easier to aim a
        // gesture at, and a constantly turning one is harder to read.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (playheadLocked) "PLAYHEAD LOCKED" else "PLAYHEAD FREE",
                color = if (playheadLocked) Color(0xFF22D3EE) else Color(0xFF52525B),
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { playheadLocked = !playheadLocked }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        TrackStrip(
            tracks = tracks,
            onLoad = actions::onLoadTrack,
            onDragStart = { track, position ->
                draggingTrack = track
                dragPosition = position
            },
            onDrag = { position -> dragPosition = position },
            onDragEnd = {
                val track = draggingTrack
                val target = dropTargetAt(dragPosition)
                draggingTrack = null
                if (track != null && target != null) {
                    actions.onDropTrack(track, target.first, target.second)
                }
            },
        )
    }

    // The dragged card itself, following the finger above everything else.
    draggingTrack?.let { track ->
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (dragPosition.x - screenOrigin.x - with(density) { 70.dp.toPx() }).toInt(),
                        (dragPosition.y - screenOrigin.y - with(density) { 20.dp.toPx() }).toInt(),
                    )
                }
                .width(140.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xCC1F2937))
                .padding(8.dp),
        ) {
            Text(track.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
    }
}

/**
 * Draws gesture names at their clock positions.
 *
 * Text only — no box, no background — floating upward and dissolving.
 */
@Composable
private fun GestureLabelOverlay(
    labels: List<com.hereliesaz.sirmatchalot.gesture.GestureLabel>,
    canvasSize: IntSize,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
) {
    if (canvasSize.width == 0 || labels.isEmpty()) return
    val density = LocalDensity.current

    val cx = canvasSize.width / 2f
    val cy = canvasSize.height / 2f
    val radius = min(canvasSize.width, canvasSize.height) * 0.30f * scale * 1.45f

    for (label in labels) {
        val fraction = GestureLabels.CLOCK_SLOTS[label.slot]
        val (x, y) = PlatterGeometry.pointAt(fraction, radius, cx, cy)
        // Float upward as it lives, so a fast sequence never backs up.
        val rise = label.riseFraction * canvasSize.height * 0.25f

        Box(
            modifier = Modifier
                .offset(
                    x = with(density) { x.toDp() } - 48.dp,
                    y = with(density) { (y - rise).toDp() } - 8.dp,
                )
                .alpha(label.alpha),
        ) {
            Text(
                text = label.text,
                color = Color(0xFF7DF9FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 10f)),
            )
        }
    }
}

/**
 * The track list: along the bottom, scrolling horizontally.
 *
 * No A/B buttons and no drag handle — tapping a row loads it.
 */
private const val TWO_PI = (2.0 * Math.PI).toFloat()

@Composable
private fun TrackStrip(
    tracks: List<Track>,
    onLoad: (Track) -> Unit,
    onDragStart: (Track, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            // Where this card sits in the window, so a drag can be reported in
            // the same coordinates the platter is hit-tested in.
            var cardOrigin by remember { mutableStateOf(Offset.Zero) }
            Column(
                modifier = Modifier
                    .width(180.dp)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF121218))
                    .onGloballyPositioned { cardOrigin = it.positionInRoot() }
                    .clickable { onLoad(track) }
                    // After a long press, not immediately: an immediate drag
                    // would take every horizontal swipe and the strip would stop
                    // scrolling.
                    .pointerInput(track.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset -> onDragStart(track, cardOrigin + offset) },
                            onDrag = { change, _ -> onDrag(cardOrigin + change.position) },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd,
                        )
                    }
                    .padding(10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(track.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(track.artist, color = Color(0xFF9CA3AF), fontSize = 11.sp, maxLines = 1)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${track.bpmLabel()} BPM",
                        color = if (track.bpm != null) Color(0xFF7DF9FF) else Color(0xFF52525B),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        track.keyLabel(),
                        color = if (track.camelotKey != null) Color(0xFFF0ABFC) else Color(0xFF52525B),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
