package com.hereliesaz.sirmatchalot.crash

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Offers to report the last crash the first time the app is opened after it.
 *
 * Reads [store] once per composition rather than on every crash: there is at
 * most one pending report. It is only cleared once it has actually been
 * filed — dismissing the dialog, or tapping outside it, just closes it for
 * this launch and leaves the report on disk, so it is offered again next
 * time the app opens. Nothing is sent off-device on its own — "Report on
 * GitHub" only opens a browser tab GitHub's own submit button still has to
 * be pressed on.
 */
@Composable
fun CrashReportPrompt(store: CrashReportStore) {
    var report by remember { mutableStateOf<CrashReport?>(null) }
    // Whether this report has already been handled (reported or dismissed)
    // this launch. `rememberSaveable`, not `remember`: a config change like
    // rotation recreates the whole composition, and a plain `remember` would
    // forget the dismissal along with it — `report` gets reloaded from the
    // still-pending (dismiss does not clear the store) `store.load()` below,
    // and the prompt the user just closed pops right back up on rotation.
    var handledThisLaunch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(store) { report = store.load() }

    if (handledThisLaunch) return
    val current = report ?: return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = {
            handledThisLaunch = true
        },
        containerColor = Color(0xFF18181B),
        title = { Text("Sir Match-a-Lot crashed", color = Color.White) },
        text = {
            Text(
                "It closed unexpectedly last time it was open " +
                    "(${current.exceptionType.substringAfterLast('.')}). " +
                    "Reporting it opens a prefilled GitHub issue — nothing is sent without you pressing submit there.",
                color = Color.White,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(CrashReportIssue.url(current))),
                )
                store.clear()
                handledThisLaunch = true
            }) { Text("Report on GitHub") }
        },
        dismissButton = {
            TextButton(onClick = {
                handledThisLaunch = true
            }) { Text("Dismiss") }
        },
    )
}
