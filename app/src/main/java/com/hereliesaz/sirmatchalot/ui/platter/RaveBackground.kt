package com.hereliesaz.sirmatchalot.ui.platter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The room behind the platter: out-of-focus lights, driven by the audio.
 *
 * C15 asks for *"an out-of-focus rave light show, genuinely driven by the audio,
 * not a strobe"*, and the three words that matter are **out-of-focus**,
 * **genuinely**, and **not a strobe**. Each one rules out an easy answer.
 *
 * **Out of focus.** Every light is a radial gradient with no hard edge, drawn
 * far larger than a light would be — a bloom rather than a beam. There is no
 * blur pass, because there is nothing sharp to blur: a gradient from colour to
 * transparent already *is* the out-of-focus image of a point source, and
 * rendering it directly costs one draw call where a blur costs a render target.
 *
 * **Genuinely driven by the audio.** Brightness comes from [SpectrumBands],
 * measured off the master bus. Not from a clock, not from a beat grid, not from
 * an animation curve triggered by playback. If the audio stops, the lights go
 * out; if a filter sweep takes the treble away, the treble lights dim, because
 * they are looking at the same signal the speakers are.
 *
 * **Not a strobe.** Two decisions. The bands are already attack/release
 * smoothed, so a light blooms and fades rather than flicking. And *motion* is
 * continuous and slow, driven by a free-running phase rather than by the audio
 * — lights in a real room sweep on their own and change colour with the music,
 * they do not teleport on every kick. Position from the clock, brightness from
 * the audio, is what separates a light show from a flashing rectangle.
 *
 * Drawn behind everything, additively, so it can only ever lighten the black
 * the platter sits on.
 */
@Composable
fun RaveBackground(
    bands: SpectrumBands,
    phase: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawLights(bands, phase)
    }
}

/**
 * The three band levels the lights read, 0..1.
 *
 * A value class over three floats rather than the meter itself, so the drawing
 * has no dependency on the audio engine and can be given a synthetic room.
 */
data class SpectrumBands(
    val low: Float = 0f,
    val mid: Float = 0f,
    val high: Float = 0f,
) {
    val isDark: Boolean get() = low + mid + high < 1e-3f

    companion object {
        val DARK = SpectrumBands()
    }
}

/**
 * One light in the room.
 *
 * @param hue its colour. Fixed per light: real fixtures are gelled, and a light
 *   that changed hue with the music would read as a screensaver.
 * @param orbit how far from centre it swings, as a fraction of the short side.
 * @param speed revolutions per unit phase. Different per light and deliberately
 *   not in any ratio, so the pattern never repeats visibly.
 * @param size its bloom radius, as a fraction of the short side.
 * @param band which band drives its brightness.
 */
private data class Light(
    val hue: Float,
    val orbit: Float,
    val speed: Float,
    val phaseOffset: Float,
    val size: Float,
    val band: Band,
)

private enum class Band { LOW, MID, HIGH }

/**
 * The rig.
 *
 * Weighted toward the low band, because that is what a room is: a few big slow
 * washes moving with the kick, and smaller sharper lights picking out the top
 * end. An even split across bands makes a background that is busy everywhere
 * and therefore reads as noise.
 */
private val RIG = listOf(
    Light(hue = 300f, orbit = 0.42f, speed = 0.23f, phaseOffset = 0.0f, size = 0.75f, band = Band.LOW),
    Light(hue = 190f, orbit = 0.38f, speed = -0.17f, phaseOffset = 2.1f, size = 0.68f, band = Band.LOW),
    Light(hue = 265f, orbit = 0.55f, speed = 0.31f, phaseOffset = 4.0f, size = 0.52f, band = Band.MID),
    Light(hue = 155f, orbit = 0.48f, speed = -0.37f, phaseOffset = 1.2f, size = 0.46f, band = Band.MID),
    Light(hue = 30f, orbit = 0.62f, speed = 0.53f, phaseOffset = 3.3f, size = 0.30f, band = Band.HIGH),
    Light(hue = 210f, orbit = 0.58f, speed = -0.61f, phaseOffset = 5.4f, size = 0.26f, band = Band.HIGH),
)

private fun DrawScope.drawLights(bands: SpectrumBands, phase: Float) {
    // Silence is darkness. A background that glowed with nothing playing would
    // be decoration, and decoration is the thing C15 rules out.
    if (bands.isDark) return

    val shortSide = min(size.width, size.height)
    val cx = size.width / 2f
    val cy = size.height / 2f

    for (light in RIG) {
        val level = when (light.band) {
            Band.LOW -> bands.low
            Band.MID -> bands.mid
            Band.HIGH -> bands.high
        }
        if (level <= 0.01f) continue

        val angle = phase * light.speed + light.phaseOffset
        // The orbit breathes a little with the level, so a loud passage pushes
        // the lights outward. Small — the movement should read as the rig
        // reacting, not as the rig being thrown about.
        val distance = shortSide * light.orbit * (0.85f + 0.15f * level)
        val centre = Offset(cx + cos(angle) * distance, cy + sin(angle) * distance)

        val radius = shortSide * light.size * (0.8f + 0.2f * level)
        // Saturation falls as a light gets brighter, the way a real fixture
        // washes toward white at full output.
        val colour = Color.hsl(
            hue = light.hue,
            saturation = (0.95f - 0.25f * level).coerceIn(0f, 1f),
            lightness = 0.5f,
        )

        drawCircle(
            brush = Brush.radialGradient(
                // Three stops rather than two: a linear falloff has a visible
                // edge where it reaches zero, and an out-of-focus light does
                // not have an edge.
                colorStops = arrayOf(
                    0.0f to colour.copy(alpha = 0.30f * level),
                    0.45f to colour.copy(alpha = 0.11f * level),
                    1.0f to Color.Transparent,
                ),
                center = centre,
                radius = radius,
            ),
            radius = radius,
            center = centre,
            // Additive, so lights overlapping get brighter where they cross —
            // which is what light does, and what alpha compositing does not.
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * How far the rig has swung, from elapsed time.
 *
 * Kept as a function rather than an animation so the caller owns the clock, and
 * so the motion is reproducible: the same elapsed time always gives the same
 * arrangement.
 */
fun rigPhase(elapsedMillis: Long): Float =
    (elapsedMillis % ROTATION_PERIOD_MS) / ROTATION_PERIOD_MS.toFloat() * TWO_PI

private const val TWO_PI = (2.0 * Math.PI).toFloat()

/** One full sweep of the slowest light. Slow: this is ambience, not an effect. */
private const val ROTATION_PERIOD_MS = 24_000L

/** Exposed for tests, which need the period to reason about wrapping. */
internal const val RIG_ROTATION_PERIOD_MS = ROTATION_PERIOD_MS

/** Exposed for tests. */
internal val RIG_SIZE = RIG.size
