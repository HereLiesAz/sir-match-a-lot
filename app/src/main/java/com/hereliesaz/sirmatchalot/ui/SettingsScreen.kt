package com.hereliesaz.sirmatchalot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
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
// 0xFF9CA3AF, not the previous 0xFF71717A: the darker grey measured under
// 4.5:1 (WCAG AA for normal text) against this screen's backgrounds; this
// one clears 7:1 against all of them.
private val DIM = Color(0xFF9CA3AF)
private val PANEL = Color(0xFF121218)
/** Destructive-action red, bright enough to clear 4.5:1 against [PANEL] and darker backgrounds. */
private val DESTRUCTIVE = Color(0xFFF87171)

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
                    DestructiveAction(
                        label = "REMOVE ALL",
                        confirmTitle = "Remove all local copies?",
                        confirmMessage = "Deletes the ${copies.second} cached ${if (copies.second == 1) "copy" else "copies"} " +
                            "(${megabytes(copies.first)}) on this device. Tracks fall back to " +
                            "fetching from their original source, which may be slower or " +
                            "unavailable if it has moved.",
                        onConfirm = { viewModel.clearLocalCopies() },
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
                DestructiveAction(
                    label = "FORGET IT ALL",
                    confirmTitle = "Forget everything learned?",
                    confirmMessage = "Erases what the Automatchic Mix has learned from " +
                        "$learned transitions. It goes back to the same defaults it started " +
                        "with, with no way to get this back.",
                    onConfirm = { viewModel.forgetTransitionTaste() },
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
                DestructiveAction(
                    label = "FORGET THEM ALL",
                    confirmTitle = "Forget all paired devices?",
                    confirmMessage = "Every device on this list — ${paired.size} of them — will " +
                        "have to compare a pairing code and be approved again before it can " +
                        "rejoin a room with this device.",
                    onConfirm = { viewModel.forgetPairedDevices() },
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

        Section(
            title = "DIAGNOSTICS",
            explanation = null,
        ) {
            Toggle(
                label = "Log platter gestures",
                detail = "Writes each two-finger platter frame — pointer positions and " +
                    "which gesture was recognized — to logcat under the tag " +
                    "\"GestureDebug\". Leave off unless you're chasing a gesture report.",
                on = settings.gestureDebugLogging,
                onClick = {
                    viewModel.updateSettings { it.copy(gestureDebugLogging = !it.gestureDebugLogging) }
                },
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

/**
 * A destructive, irreversible action — deletes cached files, forgets a
 * learned model, forgets paired devices. Two things a bare `Text.clickable`
 * did not have: a touch target at least 48dp tall (the label alone was under
 * 24dp on a 4dp padding, reachable by a scroll gesture that merely slipped),
 * and a confirmation dialog, since none of the three actions this backs can
 * be undone.
 */
@Composable
private fun DestructiveAction(
    label: String,
    confirmTitle: String,
    confirmMessage: String,
    onConfirm: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .sizeIn(minHeight = 48.dp)
            .wrapContentSize(Alignment.CenterStart)
            .clip(RoundedCornerShape(6.dp))
            .clickable { confirming = true }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            // 0xFFF87171, not 0xFFDC2626: the darker red measured under 4.5:1
            // (WCAG AA for normal text, which 10sp bold still is) against this
            // screen's near-black background — 4.12:1, just short. This one
            // clears 7:1.
            color = DESTRUCTIVE,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            // Explicit colors, matching every other dialog on this app —
            // relying on MaterialTheme's default container left the "Confirm"
            // button's red text (below) at roughly 3:1 against the default
            // dark-theme dialog surface, well under the 4.5:1 AA needs.
            containerColor = PANEL,
            titleContentColor = Color.White,
            textContentColor = DIM,
            title = { Text(confirmTitle) },
            text = { Text(confirmMessage) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onConfirm()
                }) { Text("Confirm", color = DESTRUCTIVE) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel", color = Color.White) }
            },
        )
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
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
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
            .toggleable(value = on, onValueChange = { onClick() }, role = Role.Switch)
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
