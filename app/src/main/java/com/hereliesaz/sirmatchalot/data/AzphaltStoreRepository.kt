package com.hereliesaz.sirmatchalot.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object AzphaltStoreRepository {
    // The store lives at azphalt.org; the previous constant pointed at
    // azphalt.store, a different host.
    private const val BASE_URL = "https://azphalt.org"

    data class StorePackage(
        val id: String,
        val name: String,
        val version: String
    )

    suspend fun fetchAudioPackages(): List<StorePackage> = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/packages?types=audio")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        
        if (connection.responseCode != 200) {
            return@withContext emptyList()
        }
        
        val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
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
    }

    suspend fun downloadAndExtractPackage(context: Context, pkg: StorePackage): List<Track> = withContext(Dispatchers.IO) {
        val url = URL("$BASE_URL/packages/${pkg.id}/versions/${pkg.version}/download")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        
        if (connection.responseCode != 200) {
            throw Exception("Failed to download package: ${connection.responseCode}")
        }

        val extractDir = File(context.filesDir, "azphalt/${pkg.id}")
        if (!extractDir.exists()) {
            extractDir.mkdirs()
        }

        unzip(connection.inputStream, extractDir)

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
