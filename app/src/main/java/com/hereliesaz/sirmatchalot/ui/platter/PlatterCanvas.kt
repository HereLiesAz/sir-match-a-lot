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
 * - No base circle, no centre circle, no bounding ring around the platter
 *   itself — but the waveform is not the only thing this draws: the beat
 *   grid, cue/structure markers and a pending-clip's ghost are drawn here
 *   too (see [drawBeatGrid], [drawMarkers], [drawPending]), and gesture
 *   labels are a separate overlay drawn by the caller, not by this function.
 * - Ray length and glow both scale with the **live metered level**, so the ring
 *   careens with the audio.
 *
 * All geometry comes from [PlatterGeometry] and all colour from [ClipPalette], so
 * the arithmetic here is covered by unit tests even though the drawing is not.
 */
@Composable
fun PlatterCanvas(
    state: PlatterState,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    rotation: Float = 0f,
    /**
     * Free-running phase, for the pending-clip pulse.
     *
     * A pending clip has no measurable progress — a decode reports nothing until
     * it is done — so what marks it as *alive* has to come from a clock. A still
     * ghost on the ring looks like a rendering artefact; one that breathes reads
     * as something being worked on.
     */
    pulse: Float = 0f,
    /**
     * Where a track being dragged from the strip would land if released now, or
     * null when nothing is being dragged.
     *
     * Drawn with [drawPending]'s own arc — the same "something is coming here"
     * mark a decode-in-progress clip gets — but fainter and never written to
     * [PlatterState]: nothing is committed until the finger actually lifts over
     * the ring, so a drag that ends up somewhere else, or nowhere, leaves no
     * trace.
     */
    dragPreview: PendingClip? = null,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f + offsetX
        val cy = size.height / 2f + offsetY
        val baseRadius = PlatterGeometry.baseRadius(size.width, size.height, scale)
        // Headroom for peaks to overshoot into.
        val maxHeight = baseRadius * 0.85f
        // exaggeratedHeight's overshoot (up to 2.9x at full deck affinity) and
        // level scale (up to 1.25x at full output) can multiply maxHeight to
        // over 3x baseRadius — well past the screen at anything but a small
        // zoom. Rays and the playhead are both clamped to this bound before
        // they are drawn: the inscribed-circle radius from the platter's
        // centre, so a tip can never leave the canvas regardless of angle.
        //
        // Measured at scale 1 and then scaled up with it, not measured
        // directly against the live (already-scaled) baseRadius: the screen
        // edges do not move when the platter zooms, so a headroom computed
        // against the current baseRadius shrinks as scale grows and hits zero
        // well before 2x zoom — every ray clamps to nothing, and "zooming in"
        // reads as the waveform collapsing into a filled disc. Scaling the
        // *proportion* instead keeps the waveform's own shape identical at
        // every zoom level, same as the rest of the platter; it is only the
        // hard screen-edge clamp for extreme overshoot that changes size, and
        // it changes together with everything else, as a zoom should.
        val unscaledBaseRadius = PlatterGeometry.baseRadius(size.width, size.height, scale = 1f)
        val maxRayLength = (minOf(cx, cy, size.width - cx, size.height - cy) - unscaledBaseRadius)
            .coerceAtLeast(0f) * scale

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
            maxRayLength = maxRayLength,
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
            maxRayLength = maxRayLength,
            lengthScale = lengthScale,
            glow = glow,
            rotation = rotation,
            outward = false,
        )

        // Under the playhead, so the playhead is never hidden behind a cue that
        // happens to sit at the same angle.
        // Under everything: the grid is what the music is measured against, not
        // a thing in its own right.
        drawBeatGrid(state, cx, cy, baseRadius, rotation)
        drawMarkers(state, cx, cy, baseRadius, rotation)
        drawPlayhead(state, cx, cy, baseRadius, maxHeight, maxRayLength, lengthScale, glow, rotation)
        drawPending(state.pending, cx, cy, baseRadius, rotation, pulse, alphaScale = 1f)
        // The drag ghost reuses the exact same arc, at a fraction of the
        // opacity, so "this is where it would land" and "this is loading" read
        // as the same kind of promise at different stages of being kept.
        if (dragPreview != null) {
            drawPending(listOf(dragPreview), cx, cy, baseRadius, rotation, pulse, alphaScale = 0.35f)
        }
    }
}

/**
 * Draws beat and bar lines around each deck's ring.
 *
 * The grid every clip placement snaps to, and until now the only evidence it
 * existed was a clip jumping slightly on release — towards a line nobody could
 * see. Bars are longer and brighter than beats, because "how fast" and "where
 * the phrase starts" are different questions and a grid of identical ticks only
 * answers the first.
 *
 * Faint, and inside the band the waveform occupies, so it reads as ruling on the
 * page rather than as another signal.
 */
private fun DrawScope.drawBeatGrid(
    state: PlatterState,
    cx: Float,
    cy: Float,
    baseRadius: Float,
    rotation: Float,
) {
    for (deck in PlatterGeometry.Deck.entries) {
        val grid = state.beatGridFor(deck)
        if (grid.isEmpty) continue

        val ring = PlatterGeometry.ringRadius(deck, baseRadius)
        val outward = deck == PlatterGeometry.Deck.A
        val direction = if (outward) 1f else -1f
        // `grid.downbeats.toHashSet()` here built a HashSet of up to 512 boxed
        // Floats per deck per frame, and then boxed every lookup against it —
        // on the draw path this file is otherwise carefully tuned for. The two
        // lists are both sorted and the downbeats are a subset of the beats, so
        // one cursor answers the same question with no allocation at all.
        val downbeats = grid.downbeats
        var downbeatCursor = 0

        for (fraction in grid.beats) {
            while (downbeatCursor < downbeats.size && downbeats[downbeatCursor] < fraction) {
                downbeatCursor++
            }
            val isBar = downbeatCursor < downbeats.size && downbeats[downbeatCursor] == fraction
            val length = if (isBar) baseRadius * 0.16f else baseRadius * 0.07f
            val alpha = if (isBar) 0.30f else 0.14f
            val angle = PlatterGeometry.screenAngleForFraction(fraction) + rotation
            val cosine = cos(angle)
            val sine = sin(angle)

            drawLine(
                color = Color.White.copy(alpha = alpha),
                start = Offset(cx + cosine * ring, cy + sine * ring),
                end = Offset(
                    cx + cosine * (ring + direction * length),
                    cy + sine * (ring + direction * length),
                ),
                strokeWidth = if (isBar) 2f else 1f,
            )
        }
    }
}

/**
 * Draws clips that are still being prepared, where they will land.
 *
 * An arc rather than a point, because a clip occupies a span of the revolution
 * and the honest thing to show is "something is coming here", not a precise
 * boundary nobody knows yet — the length is not known until the audio is
 * decoded and conformed.
 *
 * Faint, and on the ring radius of its own deck, so it reads as a placeholder
 * rather than as audio. It pulses because there is nothing else to say it is
 * alive: a decode reports no progress until it finishes.
 */
private fun DrawScope.drawPending(
    clips: List<PendingClip>,
    cx: Float,
    cy: Float,
    baseRadius: Float,
    rotation: Float,
    pulse: Float,
    alphaScale: Float = 1f,
) {
    if (clips.isEmpty()) return

    // 0..1 and back, so the fade has no seam where it wraps.
    val breath = (sin(pulse) * 0.5f + 0.5f).coerceIn(0f, 1f)

    for (clip in clips) {
        val outward = clip.deck == PlatterGeometry.Deck.A
        val radius = PlatterGeometry.ringRadius(clip.deck, baseRadius)
        val colour = Color.hsl(
            hue = ClipPalette.hueFor(clip.deck, 0),
            saturation = ClipPalette.saturationFor(),
            lightness = ClipPalette.lightnessFor(energy = 0.5f),
        )
        val alpha = (0.25f + 0.45f * breath) * alphaScale

        // A short arc at the landing point, drawn as a run of dots so it cannot
        // be mistaken for a waveform — waveform rays are radial, these are not.
        val steps = 24
        val span = 0.06f
        for (step in 0 until steps) {
            val fraction = clip.fraction + span * (step / (steps - 1f) - 0.5f)
            val angle = PlatterGeometry.screenAngleForFraction(fraction) + rotation
            val x = cx + cos(angle) * radius
            val y = cy + sin(angle) * radius
            drawCircle(
                color = colour.copy(alpha = alpha),
                radius = 2.5f,
                center = Offset(x, y),
                blendMode = BlendMode.Plus,
            )
        }

        // A brighter mark exactly where it starts.
        val (px, py) = PlatterGeometry.pointAt(clip.fraction, radius, cx, cy)
        val start = Offset(
            cx + (px - cx) * cos(rotation) - (py - cy) * sin(rotation),
            cy + (px - cx) * sin(rotation) + (py - cy) * cos(rotation),
        )
        drawCircle(
            color = colour.copy(alpha = ((0.5f + 0.5f * breath) * alphaScale).coerceIn(0f, 1f)),
            radius = 5f,
            center = start,
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * Draws cue points and structural landmarks as ticks straddling the ring.
 *
 * Deliberately not scaled by the metered level. Everything else on this platter
 * breathes with the audio; a marker names a fixed instant, and one that shrank
 * during a quiet passage would be hardest to see exactly when the performer is
 * looking for where to come back in.
 *
 * Deck A's ticks sit outside the ring and Deck B's inside, matching the
 * direction that deck's rays already point, so a marker belongs to a ring
 * without needing a legend.
 */
private fun DrawScope.drawMarkers(
    state: PlatterState,
    cx: Float,
    cy: Float,
    baseRadius: Float,
    rotation: Float,
) {
    if (state.markers.isEmpty()) return

    val length = 10.dp.toPx()
    val inset = 3.dp.toPx()

    for (marker in state.markers) {
        val outward = marker.deck == PlatterGeometry.Deck.A
        val colour = markerColour(marker.kind)
        val angle = PlatterGeometry.screenAngleForFraction(marker.fraction) + rotation
        val cosA = cos(angle)
        val sinA = sin(angle)

        val near = if (outward) baseRadius - inset else baseRadius + inset
        val far = if (outward) near + length else near - length
        val start = Offset(cx + cosA * near, cy + sinA * near)
        val end = Offset(cx + cosA * far, cy + sinA * far)

        drawLine(colour.copy(alpha = 0.20f), start, end, 8.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
        drawLine(colour, start, end, 2.dp.toPx(), StrokeCap.Round)
        // A dot at the tip, so a cue is findable at a glance among the rays it
        // shares an angle with.
        drawCircle(colour, 2.5.dp.toPx(), end)
    }
}

/**
 * Not private: [PlatterScreen]'s legend draws the same swatches this uses on
 * the ring, so the key and the marks it explains cannot drift apart.
 */
internal fun markerColour(kind: PlatterMarker.Kind): Color = when (kind) {
    // White reads as "mine" against the hue-coded waveform, which is the point:
    // a cue is the one mark the performer put there.
    PlatterMarker.Kind.CUE -> Color(0xFFFFFFFF)
    PlatterMarker.Kind.DROP -> Color(0xFFF59E0B)
    PlatterMarker.Kind.BREAKDOWN -> Color(0xFF60A5FA)
    PlatterMarker.Kind.BUILD -> Color(0xFFA78BFA)
    PlatterMarker.Kind.VOCAL -> Color(0xFF34D399)
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
    maxRayLength: Float,
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
                // Where the two decks agree, the ring reaches further. The
                // matchiest minute of a mix is the tallest part of the circle.
                affinity = state.affinity.at(fraction),
            ).coerceAtMost(maxRayLength)
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
 * Draws how far a reverse scratch has travelled, as a trail behind the playhead.
 *
 * `ScratchModel` has accumulated this since it was written, and
 * `AudioEngine.scratchReverseProgress` exposed it to nothing: the accumulator's
 * only live output was the boolean that fires at the threshold, so holding a
 * record backwards told the performer nothing at all until the instant it told
 * them everything.
 *
 * It is drawn *behind* the playhead in the literal sense — the arc runs
 * clockwise back from the playhead's angle, the direction the audio has come
 * from — and it brightens as it fills, so "nearly there" looks like nearly
 * there. Nothing is drawn at zero, which is almost always.
 */
private fun DrawScope.drawReverseTrail(
    state: PlatterState,
    cx: Float,
    cy: Float,
    baseRadius: Float,
    rotation: Float,
    colour: Color,
) {
    val progress = state.reverseProgress.coerceIn(0f, 1f)
    if (progress <= 0.01f) return

    // A third of the circle at full, which is far enough to read as a distance
    // travelled and short enough not to be mistaken for a clip.
    val span = progress * 0.33f
    val steps = (span * 96).toInt().coerceAtLeast(2)
    val radius = baseRadius

    for (step in 0 until steps) {
        val along = step / (steps - 1f)
        val fraction = state.playheadFraction - span * along
        val angle = PlatterGeometry.screenAngleForFraction(fraction) + rotation
        // Brightest at the playhead, fading away behind it, and the whole trail
        // brighter the closer the threshold gets.
        val alpha = (1f - along) * (0.25f + 0.55f * progress)
        drawCircle(
            color = colour.copy(alpha = alpha.coerceIn(0f, 1f)),
            radius = 2f + 2f * progress,
            center = Offset(cx + cos(angle) * radius, cy + sin(angle) * radius),
            blendMode = BlendMode.Plus,
        )
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
    maxRayLength: Float,
    lengthScale: Float,
    glow: Float,
    rotation: Float,
) {
    val fraction = state.playheadFraction
    val red = Color(0xFFFF1744)

    drawReverseTrail(state, cx, cy, baseRadius, rotation, red)

    fun heightAt(deck: PlatterGeometry.Deck): Float {
        val clip = state.clipAt(deck, fraction) ?: return 0f
        return PlatterGeometry.waveformHeight(
            envelope = clip.peaks,
            fraction = fraction,
            clipStartFraction = clip.startFraction,
            clipSpanFraction = clip.spanFraction,
            maxHeight = maxHeight,
            levelScale = lengthScale,
            affinity = state.affinity.at(fraction),
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

    // Clamped the same way a ray is: unbounded, this reached over 3x
    // baseRadius at moderate levels with two decks loaded — a red slash
    // drawn clean across the whole screen on every beat.
    val half = PlatterGeometry.playheadHalfLength(outward, inward)
        .coerceAtMost(minOf(maxRayLength, baseRadius))
    val start = Offset(cx + cosA * (baseRadius - half), cy + sinA * (baseRadius - half))
    val end = Offset(cx + cosA * (baseRadius + half), cy + sinA * (baseRadius + half))

    drawLine(red.copy(alpha = 0.22f * glow), start, end, 16.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
    drawLine(red.copy(alpha = 0.6f * glow), start, end, 6.dp.toPx(), StrokeCap.Round, blendMode = BlendMode.Plus)
    drawLine(Color.White.copy(alpha = 0.9f), start, end, 1.5.dp.toPx(), StrokeCap.Round)
}
