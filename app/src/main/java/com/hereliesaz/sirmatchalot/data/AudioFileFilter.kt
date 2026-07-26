package com.hereliesaz.sirmatchalot.data

/**
 * Decides which files in a folder are music worth importing.
 *
 * Separated from the folder walk so the judgement is testable without a
 * `ContentResolver`. The walk itself is a handful of cursor queries; the part
 * that can be wrong in interesting ways is this.
 */
object AudioFileFilter {

    /**
     * Extensions accepted when a provider reports a useless MIME type.
     *
     * Storage providers routinely hand back `application/octet-stream` for
     * perfectly ordinary audio, and some return nothing at all, so the extension
     * has to be a fallback rather than a last resort.
     */
    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus",
        "wma", "aiff", "aif", "alac", "mp4", "3gp", "amr", "mka",
    )

    /** Names that are not music even when they end in an audio extension. */
    private val IGNORED_PREFIXES = listOf(".", "._")

    /**
     * True when a document should be imported.
     *
     * @param mimeType what the provider claims, which may be null or a generic
     *   binary type.
     * @param displayName the file name, used when [mimeType] is uninformative.
     */
    fun isAudio(mimeType: String?, displayName: String?): Boolean {
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) return false
        // macOS resource forks and dotfiles land in shared folders constantly and
        // are never music, whatever their extension says.
        if (IGNORED_PREFIXES.any { name.startsWith(it) }) return false

        if (mimeType != null) {
            if (mimeType.startsWith("audio/", ignoreCase = true)) return true
            // Directories are walked, not imported.
            if (mimeType == DIRECTORY_MIME) return false
            // Some containers are reported as video even when they hold only
            // audio; the extension list decides those.
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    /** True when a document is a folder to descend into. */
    fun isDirectory(mimeType: String?): Boolean = mimeType == DIRECTORY_MIME

    const val DIRECTORY_MIME = "vnd.android.document/directory"
}
