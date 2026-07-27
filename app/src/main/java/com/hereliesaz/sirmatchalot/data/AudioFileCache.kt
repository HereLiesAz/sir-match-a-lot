package com.hereliesaz.sirmatchalot.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local copies of imported audio, so the app owns the files it plays.
 *
 * A `content://` source is a *reference*, not a file. Three things follow, and
 * all three have already bitten:
 *
 * - **A cloud provider fetches over the network on every read.** A Google Drive
 *   folder imported through the document picker works, and then every decode —
 *   loading a deck, analysing, harvesting a loop — pulls the whole file down
 *   again. Off the network, it does not work at all.
 * - **A grant can lapse.** A document permission that was not persisted dies
 *   with the process, and the library row outlives it.
 * - **The file can move.** Renamed, deleted, or on storage that is unmounted,
 *   and the reference resolves to nothing.
 *
 * A copy in the app's own storage answers all three at once. It is the
 * *encoded* file — an MP3 minute is about a megabyte where a decoded minute is
 * ten, which is the difference between a cacheable library and one that is not.
 *
 * Bounded, because a library can be larger than the device. When the budget is
 * exceeded the least recently used copies go first, and a copy that is missing
 * simply means falling back to the original URI — losing the benefit, never the
 * track.
 */
class AudioFileCache(
    /** Where copies live. App-private, so uninstalling removes them. */
    private val directory: File,
) {

    /**
     * The copy for [trackId], or null when there is not one.
     *
     * A `.partial` file is never one. The write is to a partial name and then a
     * rename, so that an interrupted copy cannot be mistaken for a whole one —
     * and this lookup matched on the name *without* its extension, which made
     * `track-1.partial` answer to `track-1` and handed back the truncated file
     * the rename exists to hide. The atomic write was defeated by the read.
     */
    fun localFile(trackId: String): File? =
        completeFiles()
            .firstOrNull { it.nameWithoutExtension == trackId }
            ?.takeIf { it.length() > 0 }

    /** Total bytes held, counting only whole copies. */
    fun heldBytes(): Long = completeFiles().sumOf { it.length() }

    fun count(): Int = completeFiles().size

    /** Whole copies: files, and not the partial one a copy in flight writes to. */
    private fun completeFiles(): List<File> =
        directory.listFiles()
            ?.filter { it.isFile && it.extension != PARTIAL_EXTENSION }
            ?: emptyList()

    /**
     * Copies [uri] into the cache under [trackId], unless a copy already exists.
     *
     * @param extension the source's file extension, kept so the decoder has the
     *   same hint it would have had from the original name. A decoder sniffs the
     *   content anyway, but a wrong or missing extension makes debugging a bad
     *   file needlessly hard.
     * @return the copy, or null when the source could not be read — in which
     *   case the caller carries on with the original URI, which is no worse than
     *   before.
     */
    suspend fun store(
        context: Context,
        uri: Uri,
        trackId: String,
        extension: String? = null,
    ): File? = withContext(Dispatchers.IO) {
        localFile(trackId)?.let { return@withContext it }

        directory.mkdirs()
        val suffix = extension?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".audio"
        // Written to a partial name and renamed on completion, so a copy
        // interrupted by a crash or a kill is never mistaken for a whole one.
        // A truncated file that looks complete is worse than no file: it decodes
        // to a track that ends early, which reads as a corrupt source.
        val partial = File(directory, "$trackId.$PARTIAL_EXTENSION")
        val target = File(directory, "$trackId$suffix")

        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)

        if (!copied || partial.length() == 0L) {
            partial.delete()
            return@withContext null
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            return@withContext null
        }
        target
    }

    /** Drops the copy for [trackId] — and any partial write left behind for it. */
    fun remove(trackId: String) {
        directory.listFiles()
            ?.filter { it.nameWithoutExtension == trackId }
            ?.forEach { it.delete() }
    }

    /**
     * Evicts least-recently-used copies until the total is within [budgetBytes].
     *
     * "Recently used" is last-modified, which the callers touch on every load,
     * so a track played often stays and one imported and never touched goes
     * first.
     *
     * @param keep ids never to evict — what is on a deck right now.
     * @return how many copies were removed.
     */
    fun trim(budgetBytes: Long, keep: Set<String> = emptySet()): Int {
        if (budgetBytes < 0) return 0
        // Partials are swept regardless of budget: a copy interrupted by a kill
        // leaves one behind, and nothing else would ever remove it.
        directory.listFiles()
            ?.filter { it.isFile && it.extension == PARTIAL_EXTENSION }
            ?.forEach { it.delete() }

        val files = completeFiles()
        var total = files.sumOf { it.length() }
        if (total <= budgetBytes) return 0

        var removed = 0
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= budgetBytes) break
            if (file.nameWithoutExtension in keep) continue
            val size = file.length()
            if (file.delete()) {
                total -= size
                removed++
            }
        }
        return removed
    }

    /** Marks [trackId]'s copy as just used, so eviction takes it last. */
    fun touch(trackId: String) {
        localFile(trackId)?.setLastModified(System.currentTimeMillis())
    }

    fun clear(): Int {
        val files = directory.listFiles()?.filter { it.isFile } ?: return 0
        var removed = 0
        for (file in files) if (file.delete()) removed++
        return removed
    }

    companion object {
        /**
         * Extension of a copy still being written.
         *
         * A whole copy is never named this, and a lookup never matches it: a
         * truncated file that looked complete would decode to a track that ends
         * early, which reads as a corrupt source rather than as a failed copy.
         */
        const val PARTIAL_EXTENSION = "partial"

        /** The cache directory inside the app's own private storage. */
        fun directoryFor(context: Context): File = File(context.filesDir, "audio")

        fun forContext(context: Context): AudioFileCache =
            AudioFileCache(directoryFor(context))

        /**
         * The extension of whatever [uri] points at, or null.
         *
         * Taken from the display name rather than the path, because a document
         * URI's path is a provider-specific id and carries no extension at all.
         */
        fun extensionOf(context: Context, uri: Uri): String? {
            val fromPath = uri.lastPathSegment?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() && it.length <= 5 && !it.contains('/') }
            if (fromPath != null) return fromPath
            return runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        cursor.getString(index)?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
            }.getOrNull()
        }
    }
}
