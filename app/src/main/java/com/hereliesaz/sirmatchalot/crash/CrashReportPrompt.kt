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
    LaunchedEffect(store) { report = store.load() }

    val current = report ?: return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = {
            report = null
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
                report = null
            }) { Text("Report on GitHub") }
        },
        dismissButton = {
            TextButton(onClick = {
                report = null
            }) { Text("Dismiss") }
        },
    )
}
