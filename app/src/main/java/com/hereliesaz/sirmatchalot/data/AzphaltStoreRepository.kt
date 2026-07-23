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
    private const val BASE_URL = "https://azphalt.store"

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

        val tracks = mutableListOf<Track>()
        extractDir.walkTopDown().forEach { file ->
            if (file.isFile && (file.extension.equals("mp3", true) || file.extension.equals("wav", true))) {
                val extractedData = com.hereliesaz.sirmatchalot.audio.AudioWaveformExtractor.extract(context, android.net.Uri.fromFile(file))
                
                var finalDuration = 0L
                var finalTrimStart = 0L
                var finalTrimEnd = 0L
                var peaksPath: String? = null
                
                if (extractedData != null) {
                    finalDuration = extractedData.durationMs
                    finalTrimStart = extractedData.trimStartMs
                    finalTrimEnd = extractedData.trimEndMs
                    
                    // Save peaks to a binary file
                    val peaksFile = File(file.absolutePath + ".peaks")
                    try {
                        val byteBuffer = java.nio.ByteBuffer.allocate(extractedData.peaks.size * 4)
                        byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (peak in extractedData.peaks) {
                            byteBuffer.putFloat(peak)
                        }
                        FileOutputStream(peaksFile).use { fos ->
                            fos.write(byteBuffer.array())
                        }
                        peaksPath = peaksFile.absolutePath
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val parsedNames = LinkParser.parseFileName(file.name)
                tracks.add(
                    Track(
                        id = file.absolutePath.hashCode().toString(),
                        title = parsedNames.first,
                        artist = pkg.name,
                        energyLevel = (5..10).random(),
                        bpm = (90..150).random(),
                        keyName = "A minor",
                        camelotKey = "8A",
                        progression = "",
                        atmosphere = "",
                        mixTips = "",
                        youtubeId = null,
                        localPath = file.absolutePath,
                        durationMs = finalDuration,
                        trimStartMs = finalTrimStart,
                        trimEndMs = finalTrimEnd,
                        peaksPath = peaksPath
                    )
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
