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

    /** One file, or null if the dialog was cancelled. */
    fun pickAudioFile(owner: Frame?): File? = pickAudioFiles(owner, multiple = false).firstOrNull()

    /** Any number of files, or an empty list if the dialog was cancelled. */
    fun pickAudioFiles(owner: Frame?, multiple: Boolean = true): List<File> {
        val dialog = FileDialog(owner, "Choose an audio file", FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        dialog.setFilenameFilter { _, name -> AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } }
        dialog.isVisible = true
        return dialog.files?.toList() ?: emptyList()
    }
}
