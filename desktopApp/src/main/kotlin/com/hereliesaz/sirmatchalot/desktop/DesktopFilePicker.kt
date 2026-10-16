package com.hereliesaz.sirmatchalot.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Native OS file-choose dialogs (`java.awt.FileDialog`), rather than a
 * Compose-drawn one — this is the same dialog Finder/Explorer/the desktop's
 * own file manager already know how to drive, so it works the way a user's
 * muscle memory expects instead of reinventing navigation, favourites, and
 * search inside the app.
 */
object DesktopFilePicker {

    private val AUDIO_EXTENSIONS = listOf(".wav", ".wave", ".aif", ".aiff", ".au", ".snd")

    /** True when [name] has one of the extensions [DesktopAudioDecoder] can read. */
    internal fun isAudioFileName(name: String): Boolean =
        AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    /** One file, or null if the dialog was cancelled or nothing usable was picked. */
    fun pickAudioFile(owner: Frame?): File? = pickAudioFiles(owner, multiple = false).firstOrNull()

    /**
     * Any number of files, or an empty list if the dialog was cancelled.
     * Files without a supported extension are dropped.
     */
    fun pickAudioFiles(owner: Frame?, multiple: Boolean = true): List<File> {
        val dialog = FileDialog(owner, "Choose an audio file", FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        dialog.setFilenameFilter { _, name -> isAudioFileName(name) }
        // The Windows FileDialog peer ignores FilenameFilter entirely; its
        // only filter is a wildcard pattern in the file-name box.
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            dialog.file = AUDIO_EXTENSIONS.joinToString(";") { "*$it" }
        }
        dialog.isVisible = true
        // Validated again here: the filter is advisory on some platforms
        // (Windows, and anything a user types straight into the name box).
        return dialog.files?.filter { isAudioFileName(it.name) } ?: emptyList()
    }
}
