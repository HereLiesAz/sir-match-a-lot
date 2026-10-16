package com.hereliesaz.sirmatchalot.desktop

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Replace-the-whole-file writes that never leave a half-written file behind.
 *
 * A plain `File.writeText` truncates first and then writes, so a crash, a
 * full disk, or the process being killed mid-write leaves an empty or
 * truncated file where the previous good one used to be. Writing to a
 * sibling temp file and renaming it over the target means a reader only ever
 * sees the old contents or the new ones.
 */
internal object AtomicFiles {

    /**
     * Writes [bytes] to [target] atomically. [prepareTemp] runs on the empty
     * temp file before any content is written — the place to restrict its
     * permissions, so secret content is never readable by others even
     * briefly.
     *
     * @throws IOException if the write or the rename fails; [target] is then
     *   left exactly as it was.
     */
    fun write(target: File, bytes: ByteArray, prepareTemp: (File) -> Unit = {}) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("no parent directory for $target")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("could not create $dir")
        }
        val temp = Files.createTempFile(dir.toPath(), ".${target.name}.", ".tmp").toFile()
        try {
            prepareTemp(temp)
            temp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}
