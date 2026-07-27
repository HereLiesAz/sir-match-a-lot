package com.hereliesaz.sirmatchalot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.hereliesaz.sirmatchalot.theme.SirMatchALotTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions results
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check and request permissions
        checkAndRequestPermissions()

        setContent {
            SirMatchALotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation()
                }
            }
        }
    }

    /**
     * Asks for the one permission the app actually needs: reading audio files.
     *
     * `RECORD_AUDIO` used to be first in this list, and nothing in the app ever
     * used it — the sampler records this app's own mixer output, which is a
     * buffer in memory and needs no permission. So the first thing anyone saw on
     * opening a DJ app was a microphone prompt it had no use for.
     *
     * `POST_NOTIFICATIONS` is asked for and was not. It is declared in the
     * manifest and has been a runtime permission since Android 13, so without
     * this the analysis service's progress notification — the only report a
     * minutes-long library scan makes while the app is backgrounded, and the
     * only place its Pause and Stop controls live — was silently suppressed on
     * every recent device.
     */
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }
}
