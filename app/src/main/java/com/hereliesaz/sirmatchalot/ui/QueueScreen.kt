package com.hereliesaz.sirmatchalot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.sirmatchalot.domain.MixStep

/**
 * The mix as a queue: what is playing, what comes next, and why each handover
 * was chosen.
 *
 * This is the plan, made visible. Until now "AUTOMIX" planned a running order
 * and told you only "Planned 12 tracks, 78% average transition" for six seconds;
 * the order, the transitions and the tracks left out were never shown, and could
 * not be changed. The list below is the same plan, one row per track with the
 * score and advice of the transition into it, and it is editable while nothing is
 * playing: move a track, drop one, shuffle what is left, or start a radio from
 * any track in the library.
 */
@Composable
fun QueueScreen(
    viewModel: SirMatchALotViewModel,
    modifier: Modifier = Modifier,
) {
    val plan by viewModel.mixPlan.collectAsState()
    val isAutoMixing by viewModel.isAutoMixing.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()

    val steps = plan?.steps.orEmpty()
    val currentIndex = if (isAutoMixing) nowPlaying?.index ?: 0 else -1

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("QUEUE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.sp)
                    Text(
                        queueSummary(steps.size, plan?.averageScore ?: 0),
                        color = Color(0xFF9CA3AF),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                QueueButton(
                    label = if (isAutoMixing) "STOP" else "PLAY",
                    color = if (isAutoMixing) Color(0xFFDC2626) else Color(0xFF059669),
                    enabled = isAutoMixing || steps.isNotEmpty(),
                ) {
                    if (isAutoMixing) viewModel.stopAutomatchicMix() else viewModel.startAutomatchicMix()
                }
                QueueButton("SHUFFLE", Color(0xFF7C3AED), enabled = !isAutoMixing && steps.size > 1) {
                    viewModel.queueShuffle()
                }
                QueueButton("BUILD", Color(0xFFDB2777), enabled = !isAutoMixing) {
                    viewModel.buildAutomatchicMix()
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (steps.isEmpty()) {
            item {
                Text(
                    "Nothing queued. Open a track in the Library and choose QUEUE or RADIO, " +
                        "or press BUILD to plan the whole library.",
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }

        itemsIndexed(steps, key = { _, step -> step.track.id }) { index, step ->
            QueueRow(
                index = index,
                step = step,
                isCurrent = index == currentIndex,
                isPast = currentIndex >= 0 && index < currentIndex,
                locked = isAutoMixing,
                isFirst = index == 0,
                isLast = index == steps.lastIndex,
                onUp = { viewModel.queueMove(index, index - 1) },
                onDown = { viewModel.queueMove(index, index + 1) },
                onRemove = { viewModel.queueRemove(index) },
            )
        }

        val skipped = plan?.skipped.orEmpty()
        if (skipped.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "NOT IN THE PLAN (${skipped.size}) — not analysed yet",
                    color = Color(0xFFFBBF24),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    skipped.joinToString(", ") { it.title },
                    color = Color(0xFF9CA3AF),
                    fontSize = 10.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun queueSummary(count: Int, averageScore: Int): String = when (count) {
    0 -> "empty"
    1 -> "1 track"
    else -> "$count tracks · $averageScore% average transition"
}

@Composable
private fun QueueButton(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = Color(0xFF27272A)),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, color = if (enabled) Color.White else Color.Gray, fontWeight = FontWeight.Black, fontSize = 10.sp)
    }
}

@Composable
private fun QueueRow(
    index: Int,
    step: MixStep,
    isCurrent: Boolean,
    isPast: Boolean,
    locked: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val track = step.track
    val accent = if (isCurrent) Color.Cyan else Color(0xFF27272A)
    val textColor = if (isPast) Color(0xFF6B7280) else Color.White

    Column {
        // The handover into this track: why it follows the one above.
        step.transition?.let { match ->
            Text(
                "↳ ${match.overallScore}% · ${match.keyAdvice}",
                color = Color(0xFF9CA3AF),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 28.dp, top = 2.dp, bottom = 2.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF18181B))
                .border(1.dp, accent, RoundedCornerShape(10.dp))
                .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isCurrent) "▶" else "${index + 1}",
                color = if (isCurrent) Color.Cyan else Color(0xFF6B7280),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${track.artist} · ${track.bpmLabel()} BPM · ${track.keyLabel()}",
                    color = Color(0xFF9CA3AF),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!locked) {
                QueueIcon(Icons.Default.KeyboardArrowUp, "Move ${track.title} up", enabled = !isFirst, onClick = onUp)
                QueueIcon(Icons.Default.KeyboardArrowDown, "Move ${track.title} down", enabled = !isLast, onClick = onDown)
                QueueIcon(Icons.Default.Close, "Remove ${track.title} from the queue", enabled = true, onClick = onRemove)
            }
        }
    }
}

@Composable
private fun QueueIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // The default IconButton is already a 48dp touch target; only the glyph is small.
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .sizeIn(minWidth = 40.dp, minHeight = 40.dp)
            .semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) Color(0xFF9CA3AF) else Color(0xFF3F3F46), modifier = Modifier.size(18.dp))
    }
}
