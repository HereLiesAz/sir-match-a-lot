package com.hereliesaz.sirmatchalot.session

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A `.sir` file: the manifest and any recorded takes, in one archive.
 *
 * Plain zip, so the format is inspectable with tools everyone already has. A
 * session that will not open should be diagnosable by unzipping it and reading
 * the JSON, rather than by having the author of the app explain it.
 *
 * Streams rather than files throughout, because on Android the destination is a
 * SAF `OutputStream` for a document the app does not own and cannot name.
 */
object SessionArchive {

    /**
     * Hard ceilings on what [read] will inflate.
     *
     * A zip entry's stated size cannot be trusted — that is exactly what a zip
     * bomb lies about — so these bound the *actual* bytes read out of the
     * decompression stream, not the size in the entry header. Without a
     * limit, `readBytes()` inflates an entry to EOF with no bound at all: a
     * 42-byte manifest entry compressed from a gigabyte of zeros, or a
     * hundred oversized "take" entries, would exhaust the heap before this
     * function ever got to hand back a result — an `OutOfMemoryError` from a
     * `.sir` file someone was merely sent, or attached to a bug report.
     */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /** A session with a hundred pads still has nowhere near this many entries. */
    private const val MAX_ENTRIES = 512

    /**
     * Reads [from] to EOF, refusing to allocate past [limit] bytes.
     *
     * @return the bytes, or null if the entry (or the archive's running
     *   total, via [budget]) would exceed its limit.
     */
    private fun readBounded(from: InputStream, limit: Long, budget: LongArray): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var read: Int
        while (true) {
            read = from.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
            budget[0] += read
            if (buffer.size() > limit || budget[0] > MAX_TOTAL_BYTES) return null
        }
        return buffer.toByteArray()
    }

    /** Writes a session, with [takes] keyed by the file names the pads refer to. */
    fun write(
        document: SessionDocument,
        takes: Map<String, ByteArray>,
        into: OutputStream,
    ) {
        ZipOutputStream(into).use { zip ->
            zip.putNextEntry(ZipEntry(SessionDocument.MANIFEST))
            zip.write(document.encode().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            for ((name, bytes) in takes) {
                zip.putNextEntry(ZipEntry("${SessionDocument.TAKES}/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads one back.
     *
     * @return null when the stream is not a session — not a zip, no manifest, or
     *   a manifest this version would misread. Every one of those is better
     *   reported as "this is not a session file" than partially honoured.
     */
    fun read(from: InputStream): Archive? {
        var manifest: String? = null
        val takes = HashMap<String, ByteArray>()
        val budget = longArrayOf(0L)
        var oversized = false

        val ok = runCatching {
            ZipInputStream(from).use { zip ->
                var entry = zip.nextEntry
                var entryCount = 0
                while (entry != null) {
                    // A cap on entry count as well as bytes: a great many
                    // tiny entries costs allocator and hash-map overhead
                    // without ever tripping the byte budget above.
                    entryCount++
                    if (entryCount > MAX_ENTRIES) { oversized = true; break }

                    val name = entry.name
                    when {
                        name == SessionDocument.MANIFEST -> {
                            val bytes = readBounded(zip, MAX_ENTRY_BYTES, budget)
                            if (bytes == null) { oversized = true; break }
                            manifest = bytes.toString(Charsets.UTF_8)
                        }

                        // Only files directly inside the takes directory, and only
                        // by their bare name. A zip entry is free to say
                        // "../../etc/passwd", and this one never writes to disk —
                        // but a name that escapes its directory is malformed
                        // whatever it is used for, so it is refused here rather
                        // than trusted to stay harmless downstream.
                        name.startsWith("${SessionDocument.TAKES}/") && !entry.isDirectory -> {
                            val leaf = name.substringAfterLast('/')
                            if (leaf.isNotBlank() && !name.contains("..")) {
                                val bytes = readBounded(zip, MAX_ENTRY_BYTES, budget)
                                if (bytes == null) { oversized = true; break }
                                takes[leaf] = bytes
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }.isSuccess

        if (!ok || oversized) return null
        val document = SessionDocument.decode(manifest ?: return null) ?: return null
        return Archive(document, takes)
    }

    /** In-memory form, for tests and for anything that already has the bytes. */
    fun writeToBytes(document: SessionDocument, takes: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        write(document, takes, out)
        return out.toByteArray()
    }

    data class Archive(
        val document: SessionDocument,
        val takes: Map<String, ByteArray>,
    ) {
        /** A take as audio, or null when it is absent or unreadable. */
        fun take(name: String?): WavCodec.Wav? =
            name?.let { takes[it] }?.let { WavCodec.decode(it) }
    }

    /** The file name a take for [padIndex] is stored under. */
    fun takeFileName(padIndex: Int): String = "pad-$padIndex.wav"
}
