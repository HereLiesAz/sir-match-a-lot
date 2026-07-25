package com.hereliesaz.sirmatchalot.ui.platter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var rotation by remember { mutableFloatStateOf(0f) }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
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

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF05050A))) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointers = event.changes
                                .filter { it.pressed }
                                .map { Pointer(it.id.value, it.position.x, it.position.y) }

                            val recognised = gestures.update(pointers)

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
                                    GestureKind.PLATTER_MOVE -> {
                                        offsetX += gesture.delta * 0.5f
                                        offsetY += gesture.delta * 0.5f
                                    }
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
                            val cx = size.width / 2f + offsetX
                            val cy = size.height / 2f + offsetY
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
                            val cx = size.width / 2f + offsetX
                            val cy = size.height / 2f + offsetY
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
                offsetX = offsetX,
                offsetY = offsetY,
                rotation = rotation,
            )

            GestureLabelOverlay(visibleLabels, canvasSize, scale, offsetX, offsetY)
        }

        TrackStrip(tracks = tracks, onLoad = actions::onLoadTrack)
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

    val cx = canvasSize.width / 2f + offsetX
    val cy = canvasSize.height / 2f + offsetY
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
@Composable
private fun TrackStrip(tracks: List<Track>, onLoad: (Track) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            Column(
                modifier = Modifier
                    .width(180.dp)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF121218))
                    .clickable { onLoad(track) }
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
