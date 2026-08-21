package com.hereliesaz.sirmatchalot.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object AzphaltStoreRepository {
    // The store lives at azphalt.org; the previous constant pointed at
    // azphalt.store, a different host.
    private const val BASE_URL = "https://azphalt.org"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * Ceiling on the package-listing response [fetchAudioPackages] will
     * inflate into memory.
     *
     * `readText()` on the raw response stream has no bound at all: a
     * compromised or simply malfunctioning store returning a
     * gigabyte-and-growing response would be read to EOF in full before
     * `JSONObject` ever got a chance to reject it, exhausting the heap on
     * what is nominally just "the list of packages". Mirrors the bounded
     * reads [com.hereliesaz.sirmatchalot.session.SessionArchive] uses for
     * the same reason.
     */
    private const val MAX_LISTING_BYTES = 50L * 1024 * 1024

    /** Reads [from] to EOF, refusing to allocate past [limit] bytes. */
    internal fun readBounded(from: InputStream, limit: Long): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = from.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) {
                throw IOException("Package listing exceeded $limit bytes")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toString("UTF-8")
    }

    data class StorePackage(
        val id: String,
        val name: String,
        val version: String
    )

    suspend fun fetchAudioPackages(): List<StorePackage> = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/packages?types=audio")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            if (connection.responseCode != 200) {
                return@withContext emptyList()
            }

            val jsonString = readBounded(connection.inputStream, MAX_LISTING_BYTES)
            val root = JSONObject(jsonString)
            val packagesArray = root.optJSONArray("packages") ?: return@withContext emptyList()

            val result = mutableListOf<StorePackage>()
            for (i in 0 until packagesArray.length()) {
                val pkg = packagesArray.getJSONObject(i)
                result.add(
                    StorePackage(
                        id = pkg.getString("id"),
                        name = pkg.getString("name"),
                        version = pkg.optString("latest", "1.0.0")
                    )
                )
            }
            return@withContext result
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A package id is chosen by the store, not by us, and is used to build a
     * filesystem path. Anything other than a plain path segment — `..`, a
     * separator, an empty string — is rejected outright rather than merely
     * sanitised, so a hostile store cannot aim the extraction directory
     * anywhere but inside our own `azphalt/` folder.
     */
    private fun safePackageDirName(id: String): String {
        require(id.isNotBlank()) { "Package id is blank" }
        require(id != "." && id != "..") { "Package id is not a valid path segment: $id" }
        require(!id.contains('/') && !id.contains('\\') && !id.contains("..")) {
            "Package id is not a valid path segment: $id"
        }
        return id
    }

    suspend fun downloadAndExtractPackage(context: Context, pkg: StorePackage): List<Track> = withContext(Dispatchers.IO) {
        val dirName = safePackageDirName(pkg.id)
        val url = URL("$BASE_URL/packages/${pkg.id}/versions/${pkg.version}/download")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS

        val packagesRoot = File(context.filesDir, "azphalt").canonicalFile
        val extractDir = File(packagesRoot, dirName)
        try {
            if (connection.responseCode != 200) {
                throw Exception("Failed to download package: ${connection.responseCode}")
            }

            // Belt and braces: the name was already validated, but the
            // canonical path is checked again against the fixed packages
            // root before anything is written under it.
            if (!extractDir.canonicalPath.startsWith(packagesRoot.canonicalPath + File.separator)) {
                throw Exception("Package id resolves outside the packages directory: ${pkg.id}")
            }
            if (!extractDir.exists()) {
                extractDir.mkdirs()
            }

            unzip(connection.inputStream, extractDir)
        } finally {
            connection.disconnect()
        }

        // Downloaded audio is registered unanalysed. It is measured by the
        // analysis pipeline like any other import — the previous version filled
        // these rows with `bpm = (90..150).random()` and a hardcoded key of
        // "8A", so a pack's tempo changed every time it was imported.
        val audioExtensions = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus")
        val tracks = mutableListOf<Track>()
        extractDir.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.lowercase() in audioExtensions) {
                val parsedNames = LinkParser.parseFileName(file.name)
                tracks.add(
                    Track(
                        title = parsedNames.first,
                        artist = pkg.name,
                        sourceUri = android.net.Uri.fromFile(file).toString(),
                        isUserAdded = false,
                        analysisVersion = 0,
                    ),
                )
            }
        }
        return@withContext tracks
    }

    private fun unzip(inputStream: InputStream, destDir: File) {
        ZipInputStream(inputStream).use { zis ->
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                val newFile = File(destDir, zipEntry.name)
                
                // Prevent Zip Slip vulnerability
                if (!newFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    throw Exception("Zip entry is outside of the target dir: ${zipEntry.name}")
                }
                
                if (zipEntry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zipEntry = zis.nextEntry
            }
            zis.closeEntry()
        }
    }
}
