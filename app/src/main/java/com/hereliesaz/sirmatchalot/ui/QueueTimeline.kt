package com.hereliesaz.sirmatchalot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.sirmatchalot.domain.MixTimeline

/**
 * The plan drawn out: one lane per deck, the clips where [TimelineLayout][com.hereliesaz.sirmatchalot.domain.TimelineLayout]
 * put them, the blends between them, and a ruler of bar lines.
 *
 * Read-only. It shows what the planner decided — which is what makes "the bars
 * line up" something to look at rather than something to take on trust — and
 * redraws when an order is edited or a point pinned.
 */
@Composable
fun QueueTimeline(
    timeline: MixTimeline,
    titleOf: (String) -> String,
    modifier: Modifier = Modifier,
    secondWidth: Dp = 3.dp,
) {
    val end = timeline.endSeconds
    if (timeline.clips.size < 2 || end <= 0.0) return
    val total = secondWidth * end.toFloat()
    val laneHeight = 24.dp
    val rulerHeight = 12.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Mix timeline: ${timeline.clips.size} clips over ${timeline.transitions.size} blends" }
            .horizontalScroll(rememberScrollState()),
    ) {
        Box(modifier = Modifier.width(total).height(rulerHeight + laneHeight * 2 + 2.dp)) {
            // Bar ruler: a tick at every audible downbeat, from every clip.
            val bars = timeline.clips.flatMap { clip ->
                clip.snapPoints(clip.masterIn, clip.masterOut).filter { it.isDownbeat }.map { it.timeSeconds }
            }
            Canvas(modifier = Modifier.width(total).height(rulerHeight)) {
                val perSecond = secondWidth.toPx()
                for (t in bars) {
                    val x = (t * perSecond).toFloat()
                    drawLine(Color(0xFF52525B), Offset(x, size.height * 0.4f), Offset(x, size.height), strokeWidth = 1f)
                }
            }

            for ((index, clip) in timeline.clips.withIndex()) {
                val lane = index % 2
                Box(
                    modifier = Modifier
                        .offset(x = secondWidth * clip.masterIn.toFloat(), y = rulerHeight + (laneHeight + 2.dp) * lane)
                        .width(secondWidth * (clip.masterOut - clip.masterIn).toFloat())
                        .height(laneHeight)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (lane == 0) Color(0xFF27272A) else Color(0xFF3F3F46)),
                ) {
                    Text(
                        titleOf(clip.trackId),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }

            // The blends sit over both lanes, drawn last so they read as the overlap.
            for (blend in timeline.transitions) {
                Box(
                    modifier = Modifier
                        .offset(x = secondWidth * blend.startSeconds.toFloat(), y = rulerHeight)
                        .width(secondWidth * blend.lengthSeconds.toFloat())
                        .height(laneHeight * 2 + 2.dp)
                        .background(Color(0x3322D3EE)),
                )
            }
        }
    }
}
