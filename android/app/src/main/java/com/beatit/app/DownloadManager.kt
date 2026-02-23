package com.beatit.app

import android.content.Context
import okhttp3.*
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TaskStatus(
    val status: String,          // extracting | downloading | done | error
    val percent: Int = 0,
    val result: Map<String, Any>? = null,
    val error: String? = null
)

class DownloadManager(
    private val context: Context,
    private val musicDir: File
) {
    private val tasks = ConcurrentHashMap<String, TaskStatus>()
    private val executor = Executors.newCachedThreadPool()
    private val youtubeHelper = YoutubeHelper()

    // Shared OkHttpClient optimised for large downloads
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 60, TimeUnit.SECONDS))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build()

    fun startDownload(videoUrl: String, title: String, quality: Int, codec: String): String {
        val taskId = UUID.randomUUID().toString()
        tasks[taskId] = TaskStatus("extracting", 0)

        executor.submit {
            runDownload(taskId, videoUrl, title, quality, codec)
        }
        return taskId
    }

    fun getProgress(taskId: String): Map<String, Any?>? {
        val t = tasks[taskId] ?: return null
        return buildMap {
            put("status", t.status)
            put("percent", t.percent)
            t.result?.let { put("result", it) }
            t.error?.let { put("error", it) }
        }
    }

    private fun runDownload(taskId: String, videoUrl: String, title: String, quality: Int, codec: String) {
        val extension = if (codec == "opus") "opus" else "mp3"
        val finalFile = File(musicDir, "${sanitize(title)}.$extension")
        val tempFile = File(musicDir, "${sanitize(title)}.$extension.tmp")
        try {
            // Step 1: Get audio stream URL (may be instant if pre-fetched)
            tasks[taskId] = TaskStatus("extracting", 0)
            val (streamUrl, streamError) = youtubeHelper.getAudioStreamUrl(videoUrl)
            if (streamUrl == null) {
                throw IOException(streamError ?: "Could not get audio stream URL")
            }

            // Step 2: Download audio to temp file (won't show in library)
            tasks[taskId] = TaskStatus("downloading", 5)
            downloadWithProgress(streamUrl, tempFile, taskId)

            // Step 3: Move temp file to final location (now it appears in library)
            tempFile.renameTo(finalFile)

            val sizeHuman = humanSize(finalFile.length())
            tasks[taskId] = TaskStatus(
                status = "done",
                percent = 100,
                result = mapOf(
                    "filename" to finalFile.name,
                    "title" to title,
                    "size_human" to sizeHuman
                )
            )
        } catch (e: Exception) {
            tempFile.delete() // Clean up partial download
            tasks[taskId] = TaskStatus("error", error = e.message ?: "Unknown error")
        }
    }

    private fun downloadWithProgress(url: String, dest: File, taskId: String) {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")  // No gzip — we want raw bytes for accurate progress
            .header("Connection", "keep-alive")
            .build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IOException("Download failed: HTTP ${response.code}")
        }

        val body = response.body ?: throw IOException("Empty response body")
        val contentLength = body.contentLength()

        // Use BufferedOutputStream for fewer system calls (massive speed boost)
        BufferedOutputStream(dest.outputStream(), 262144).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(262144) // 256KB read buffer
                var downloaded = 0L
                var lastUpdate = System.currentTimeMillis()
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read

                    // Throttle progress updates to every 200ms (avoids UI/thread overhead)
                    val now = System.currentTimeMillis()
                    if (contentLength > 0 && now - lastUpdate > 200) {
                        val pct = ((downloaded * 95) / contentLength).toInt().coerceIn(5, 99)
                        tasks[taskId] = TaskStatus("downloading", pct)
                        lastUpdate = now
                    }
                }
            }
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().take(80)

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}
