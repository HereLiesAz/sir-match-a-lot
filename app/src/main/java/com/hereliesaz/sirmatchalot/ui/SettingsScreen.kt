package com.hereliesaz.sirmatchalot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.sirmatchalot.data.LocalCopyBudget
import com.hereliesaz.sirmatchalot.data.MemoryBudget
import com.hereliesaz.sirmatchalot.data.SampleRateOption
import com.hereliesaz.sirmatchalot.data.VisualRefresh
import kotlinx.coroutines.delay

private val ACCENT = Color(0xFF22D3EE)
private val DIM = Color(0xFF71717A)
private val PANEL = Color(0xFF121218)

/**
 * Where memory and power are spent, and who decides.
 *
 * Every control here trades one of three things — fidelity, latency, smoothness
 * — for one of the other two: heap, or battery. None of those trades has a right
 * answer for every device, which is exactly why they are settings rather than
 * constants. So each row says what it costs as well as what it does; a list of
 * sample rates with no indication that 22.05 kHz is a third of the memory and
 * half the top octave is a list that cannot be chosen from.
 */
@Composable
fun SettingsScreen(
    viewModel: SirMatchALotViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val tasteSummary by viewModel.tasteSummary.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val deviceRate = viewModel.audioEngine.output.sampleRate

    // Held audio changes as tracks load and are evicted, and the whole point of
    // showing it is to make the budget legible while it is being chosen.
    var usage by remember { mutableStateOf(viewModel.memoryUsage()) }
    // Bytes held and copies on disk, both of which change while this screen is
    // open — a copy is made the first time a track is loaded or analysed.
    var copies by remember { mutableStateOf(0L to 0) }
    LaunchedEffect(settings) {
        while (true) {
            usage = viewModel.memoryUsage()
            copies = viewModel.localCopyUsage().first to viewModel.localCopyCount()
            delay(1_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section(
            title = "ENGINE SAMPLE RATE",
            explanation = "Sets how many samples a second everything is decoded, stored and " +
                "mixed at. Lower rates use proportionally less memory and less power, and " +
                "cut off the top of the treble. Changing this clears the decks.",
        ) {
            for (option in SampleRateOption.entries) {
                Choice(
                    label = option.label,
                    detail = "${"%.0f".format(option.megabytesPerMinute(deviceRate))} MB " +
                        "per minute of stereo" +
                        if (option == SampleRateOption.DEVICE) " — no extra conversion" else "",
                    selected = settings.sampleRate == option,
                    onClick = { viewModel.updateSettings { it.copy(sampleRate = option) } },
                )
            }
        }

        Section(
            title = "DECODED AUDIO BUDGET",
            explanation = "How much of the app's heap loaded tracks may occupy before the " +
                "oldest are dropped. A track being played is never dropped.",
        ) {
            for (budget in MemoryBudget.entries) {
                Choice(
                    label = budget.label,
                    detail = "${megabytes(heapBytes() / budget.heapDivisor)} on this device",
                    selected = settings.memoryBudget == budget,
                    onClick = { viewModel.updateSettings { it.copy(memoryBudget = budget) } },
                )
            }
            Text(
                text = "Holding ${megabytes(usage.first)} of ${megabytes(usage.second)}",
                color = DIM,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Section(
            title = "LOCAL COPIES",
            explanation = "Imported audio is copied into the app so it plays from your own " +
                "device. Without a copy, a cloud file is fetched over the network every time " +
                "it is read, and any file can move or have its permission withdrawn while the " +
                "library entry stays. Copies are of the original file — about a megabyte a minute.",
        ) {
            for (budget in LocalCopyBudget.entries) {
                Choice(
                    label = budget.label,
                    detail = null,
                    selected = settings.localCopies == budget,
                    onClick = { viewModel.updateSettings { it.copy(localCopies = budget) } },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${copies.second} ${if (copies.second == 1) "copy" else "copies"}, " +
                        megabytes(copies.first),
                    color = DIM,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (copies.second > 0) {
                    Text(
                        text = "REMOVE ALL",
                        color = Color(0xFFDC2626),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { viewModel.clearLocalCopies() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Section(
            title = "VISUAL REFRESH",
            explanation = "How often the platter and the light show redraw. The playhead " +
                "advances a third of a degree per frame at 60 fps, so lower costs less than " +
                "it looks like it should.",
        ) {
            for (refresh in VisualRefresh.entries) {
                Choice(
                    label = refresh.label,
                    detail = null,
                    selected = settings.visualRefresh == refresh,
                    onClick = { viewModel.updateSettings { it.copy(visualRefresh = refresh) } },
                )
            }
        }

        Section(
            title = "LEARNED TASTE",
            explanation = "The Automatchic Mix watches which of its transitions you let " +
                "run and which you cut short, and leans that way next time. It only ever " +
                "adjusts its own rules — a move the music calls for stays available however " +
                "often you have skipped it. Nothing leaves this device.",
        ) {
            val (learned, opinions) = tasteSummary
            if (opinions.isEmpty()) {
                Text(
                    text = if (learned == 0) {
                        "Nothing learned yet — run a mix"
                    } else {
                        "$learned transitions so far, nothing conclusive yet"
                    },
                    color = DIM,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                // Said in English, because a preference you cannot read is a
                // preference you cannot disagree with.
                for (opinion in opinions) {
                    Text(text = "• $opinion", color = ACCENT, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Text(
                    text = "from $learned transitions",
                    color = DIM,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (learned > 0) {
                Text(
                    text = "FORGET IT ALL",
                    color = Color(0xFFDC2626),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { viewModel.forgetTransitionTaste() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Section(
            title = "PAIRED DEVICES",
            explanation = "Devices you have compared a pairing code with. A device on " +
                "this list rejoins without asking either of you again — after proving, " +
                "by signature, that it is the device you approved. Claiming to be one " +
                "is not enough. Nothing here leaves this device.",
        ) {
            val paired = pairedDevices
            if (paired.isEmpty()) {
                Text(
                    text = "None yet — pair a device to see it here",
                    color = DIM,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                for ((fingerprint, name) in paired) {
                    Text(
                        text = "• $name",
                        color = ACCENT,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                    // The fingerprint, because "which Pixel 8?" is a question a
                    // list of names cannot answer and a device may be renamed.
                    Text(
                        text = "  $fingerprint",
                        color = DIM,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = "FORGET THEM ALL",
                    color = Color(0xFFDC2626),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { viewModel.forgetPairedDevices() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Section(
            title = "POWER",
            explanation = null,
        ) {
            Toggle(
                label = "Background light show",
                detail = "Six full-screen blooms, composited additively. The most expensive " +
                    "thing on screen, and the first thing to turn off for a long set.",
                on = settings.lightShow,
                onClick = { viewModel.updateSettings { it.copy(lightShow = !it.lightShow) } },
            )
            Toggle(
                label = "Stand down when silent",
                detail = "Stops the audio thread about a second after the last sound, and " +
                    "restarts it the moment there is another. Turn off if you would rather " +
                    "have no wake-up delay at all.",
                on = settings.idleShutdown,
                onClick = { viewModel.updateSettings { it.copy(idleShutdown = !it.idleShutdown) } },
            )
        }

        Text(
            text = "Output ${viewModel.audioEngine.output.sampleRate} Hz, " +
                "${viewModel.audioEngine.output.framesPerBuffer} frames per block",
            color = DIM,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Bytes as whole megabytes, which is the only precision worth showing here. */
private fun megabytes(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

private fun heapBytes(): Long = Runtime.getRuntime().maxMemory()

@Composable
private fun Section(
    title: String,
    explanation: String?,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            color = ACCENT,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
        if (explanation != null) {
            Text(text = explanation, color = DIM, fontSize = 11.sp, lineHeight = 15.sp)
        }
        content()
    }
}

@Composable
private fun Choice(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF0E3A44) else PANEL)
            .border(
                width = 1.dp,
                color = if (selected) ACCENT else Color(0xFF27272A),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFFD4D4D8),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                color = if (selected) ACCENT else DIM,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    detail: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PANEL)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = Color(0xFFD4D4D8), fontSize = 12.sp)
            Text(text = detail, color = DIM, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Text(
            text = if (on) "ON" else "OFF",
            color = if (on) ACCENT else DIM,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}
