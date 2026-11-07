package com.hereliesaz.sirmatchalot.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Asks before an action that clears both decks.
 *
 * Shuffle, play-the-mix and radio each start by emptying the decks, so one tap
 * could throw away an arrangement built by hand — which, in an app about planning
 * a mix, is the thing worth keeping. With nothing on the decks there is nothing to
 * lose and the action just runs.
 */
class WipeGuard internal constructor(
    private val hasClips: Boolean,
    private val pending: MutableState<(() -> Unit)?>,
) {
    /** Runs [action] now if the decks are empty, otherwise after the user confirms. */
    fun run(action: () -> Unit) {
        if (hasClips) pending.value = action else action()
    }
}

@Composable
fun rememberWipeGuard(hasClips: Boolean): WipeGuard {
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    pending.value?.let { action ->
        AlertDialog(
            onDismissRequest = { pending.value = null },
            containerColor = Color(0xFF18181B),
            title = { Text("Replace what's on the decks?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Text(
                    "This clears both decks first. An arrangement you built by hand is not saved unless you saved it.",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending.value = null
                    action()
                }) { Text("Replace", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { pending.value = null }) { Text("Cancel", color = Color.Gray) }
            },
        )
    }
    return WipeGuard(hasClips, pending)
}
