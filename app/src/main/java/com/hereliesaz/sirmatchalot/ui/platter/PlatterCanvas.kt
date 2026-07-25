package com.hereliesaz.sirmatchalot.ui.platter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.hereliesaz.sirmatchalot.gesture.GestureLabel
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the platter.
 *
 * Follows `docs/PLATTER_VISUAL.md`. The essentials:
 *
 * - **Discrete radial rays**, one per angular bucket, outward for Deck A and
 *   inward for Deck B. Not a connected path through peak and valley points —
 *   that is what previously read as zigzags rather than a waveform.
 * - **Nothing is drawn but the waveform, the playhead and the labels.** No base
 *   circle, no centre circle, no bounding rings.
 * - Ray length and glow both scale with the **live metered level**, so the ring
 *   careens with the audio.
 *
 * All geometry comes from [PlatterGeometry] and all colour from [ClipPalette], so
 * the arithmetic here is covered by unit tests even though the drawing is not.
 */
@Composable
fun PlatterCanvas(
    state: PlatterState,
    labels: List<GestureLabel>,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    rotation: Float = 0f,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f + offsetX
        val cy = size.height / 2f + offsetY
        val baseRadius = min(size.width, size.height) * 0.30f * scale
        // Headroom for peaks to overshoot into.
        val maxHeight = baseRadius * 0.85f

        val level = state.outputLevel.coerceIn(0f, 1f)
        // A floor so the ring is still legible when paused or between transients.
        val lengthScale = 0.35f + level * 0.9f
        val glow = 0.25f + level * 0.75f

        val rays = PlatterGeometry.rayCount(baseRadius)
        if (rays == 0) return@Canvas

        drawDeck(
            state = state,
            deck = PlatterGeometry.Deck.A,
            rays = rays,
            cx = cx,
            cy = cy,
            baseRadius = baseRadius,
            maxHeight = maxHeight,
            lengthScale = lengthScale,
            glow = glow,
            rotation = rotation,
            outward = true,
        )
        drawDeck(
            state = state,
            deck = PlatterGeometry.Deck.B,
            rays = rays,
            cx = cx,
            cy = cy,
            baseRadius = baseRadius,
            maxHeight = maxHeight,
            lengthScale = lengthScale,
            glow = glow,
            rotation = rotation,
            outward = false,
        )

        drawPlayhead(state, cx, cy, baseRadius, maxHeight, lengthScale, glow, rotation)
    }
}

/** Draws one deck's rays. */
private fun DrawScope.drawDeck(
    state: PlatterState,
    deck: PlatterGeometry.Deck,
    rays: Int,
    cx: Float,
    cy: Float,
    baseRadius: Float,
    maxHeight: Float,
    lengthScale: Float,
    glow: Float,
    rotation: Float,
    outward: Boolean,
) {
    val clips = state.clipsFor(deck)
    if (clips.isEmpty()) return

    val halo = 7.dp.toPx()
    val mid = 3.dp.toPx()
    val core = 1.dp.toPx()

    for (clip in clips) {
        if (clip.spanFraction <= 0f) continue
        val hue = ClipPalette.hueFor(deck, clip.paletteIndex)
        val saturation = ClipPalette.saturationFor(clip.selected)

        // Only iterate the rays this clip actually covers.
        val firstRay = (clip.startFraction * rays).toInt()
        val rayCount = (clip.spanFraction * rays).toInt().coerceAtLeast(1)

        for (i in 0..rayCount) {
            val rayIndex = (firstRay + i) % rays
            val fraction = rayIndex.toFloat() / rays

            val height = PlatterGeometry.waveformHeight(
                envelope = clip.peaks,
                fraction = fraction,
                clipStartFraction = clip.startFraction,
                clipSpanFraction = clip.spanFraction,
                maxHeight = maxHeight,
                levelScale = lengthScale,
            )
            if (height <= 0.5f) continue

            val energyHere = clip.energy?.let { curve ->
                var offset = (fraction - clip.startFraction) % 1f
                if (offset < 0f) offset += 1f
                val within = (offset / clip.spanFraction).coerceIn(0f, 1f)
                curve.at(within.toDouble() * curve.size * curve.windowSeconds)
            } ?: 0.5f

            val lightness = ClipPalette.lightnessFor(energyHere, clip.selected)
            val colour = Color.hsl(hue, saturation, lightness)

            val angle = PlatterGeometry.screenAngleForFraction(fraction) + rotation
            val cosA = cos(angle)
            val sinA = sin(angle)
            val tip = if (outward) baseRadius + height else baseRadius - height

            val start = Offset(cx + cosA * baseRadius, cy + sinA * baseRadius)
            val end = Offset(cx + cosA * tip, cy + sinA * tip)

            // Three stacked strokes: wide and dim, medium, then a bright core.
            drawLine(colour.copy(alpha = 0.10f * glow), start, end, halo, StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(colour.copy(alpha = 0.35f * glow), start, end, mid, StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(colour.copy(alpha = (0.65f + 0.35f * glow).coerceAtMost(1f)), start, end, core, StrokeCap.Round)
        }
    }
}

/**
 * Draws the playhead: a glowing red slash centred on the ring whose total length
 * is twice the combined waveform height at its angle, so it bounces over the
 * hills and valleys. Collapses to a dot when there is no waveform.
 */
private fun DrawScope.drawPlayhead(
    state: PlatterState,
    cx: Float,
    cy: Float,
    baseRadius: Float,
    maxHeight: Float,
    lengthScale: Float,
    glow: Float,
    rotation: Float,
) {
    val fraction = state.playheadFraction
    val red = Color(0xFFFF1744)

    fun heightAt(deck: PlatterGeometry.Deck): Float {
        val clip = state.clipAt(deck, fraction) ?: return 0f
        return PlatterGeometry.waveformHeight(
            envelope = clip.peaks,
            fraction = fraction,
            clipStartFraction = clip.startFraction,
            clipSpanFraction = clip.spanFraction,
            maxHeight = maxHeight,
            levelScale = lengthScale,
        )
    }

    val outward = heightAt(PlatterGeometry.Deck.A)
    val inward = heightAt(PlatterGeometry.Deck.B)
    val angle = PlatterGeometry.screenAngleForFraction(fraction) + rotation
    val cosA = cos(angle)
    val sinA = sin(angle)

    if (PlatterGeometry.playheadIsDot(outward, inward)) {
        val centre = Offset(cx + cosA * baseRadius, cy + sinA * baseRadius)
        drawCircle(red.copy(alpha = 0.25f * glow), 9.dp.toPx(), centre, blendMode = BlendMode.Plus)
        drawCircle(red.copy(alpha = 0.7f * glow), 4.dp.toPx(), centre, blendMode = BlendMode.Plus)
        drawCircle(Color.White, 1.5.dp.toPx(), centre)
        return
    }

    val half = PlatterGeometry.playheadHalfLength(outward, inward)
    val start = Offset(cx + cosA * (baseRadius - half), cy + sinA * (baseRadius - half))
    val end = Offset(cx + cosA * (baseRadius + half), cy + sinA * (baseRadius + half))

    drawLine(red.copy(alpha = 0.22f * glow), start, end, 16.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
    drawLine(red.copy(alpha = 0.6f * glow), start, end, 6.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
    drawLine(Color.White.copy(alpha = 0.9f), start, end, 1.5.dp.toPx(), StrokeCap.Round)
}
