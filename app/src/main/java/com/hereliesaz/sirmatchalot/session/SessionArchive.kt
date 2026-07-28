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

        val ok = runCatching {
            ZipInputStream(from).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == SessionDocument.MANIFEST ->
                            manifest = zip.readBytes().toString(Charsets.UTF_8)

                        // Only files directly inside the takes directory, and only
                        // by their bare name. A zip entry is free to say
                        // "../../etc/passwd", and this one never writes to disk —
                        // but a name that escapes its directory is malformed
                        // whatever it is used for, so it is refused here rather
                        // than trusted to stay harmless downstream.
                        name.startsWith("${SessionDocument.TAKES}/") && !entry.isDirectory -> {
                            val leaf = name.substringAfterLast('/')
                            if (leaf.isNotBlank() && !name.contains("..")) {
                                takes[leaf] = zip.readBytes()
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }.isSuccess

        if (!ok) return null
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
