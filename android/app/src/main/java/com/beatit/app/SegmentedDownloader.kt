package com.beatit.app

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * IDM-style segmented downloader. Splits files into multiple chunks and downloads 
 * them in parallel using HTTP Range requests, then merges into the final file.
 * Falls back to single-stream download if Range not supported.
 */
object SegmentedDownloader {
    private const val TAG = "SegmentedDownloader"
    private const val NUM_SEGMENTS = 4
    private const val MIN_SEGMENT_SIZE = 256 * 1024L  // 256KB min per segment
    private const val BUFFER_SIZE = 262144  // 256KB read buffer

    // Dedicated HTTP client for downloads — optimized for throughput
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    data class DownloadProgress(
        val totalBytes: Long,
        val downloadedBytes: Long,
        val speedBytesPerSec: Double
    )

    /**
     * Download a file using segmented parallel downloads.
     * @param url The download URL
     * @param destFile Destination file
     * @param onProgress Callback for progress updates (throttled to ~300ms)
     */
    suspend fun download(
        url: String,
        destFile: File,
        onProgress: (DownloadProgress) -> Unit
    ) {
        // 1. Probe the URL to check Range support and content length
        val probeResult = probeUrl(url)
        
        if (probeResult.supportsRange && probeResult.contentLength > MIN_SEGMENT_SIZE * NUM_SEGMENTS) {
            Log.d(TAG, "Segmented download: ${probeResult.contentLength} bytes in $NUM_SEGMENTS segments")
            downloadSegmented(url, destFile, probeResult.contentLength, onProgress)
        } else {
            Log.d(TAG, "Single-stream download: contentLength=${probeResult.contentLength}, rangeSupport=${probeResult.supportsRange}")
            downloadSingleStream(url, destFile, probeResult.contentLength, onProgress)
        }
    }

    private data class ProbeResult(val contentLength: Long, val supportsRange: Boolean)

    private fun probeUrl(url: String): ProbeResult {
        return try {
            // Try HEAD request first
            val headReq = Request.Builder().url(url).head().build()
            val headResp = httpClient.newCall(headReq).execute()
            headResp.use {
                val contentLength = it.header("Content-Length")?.toLongOrNull() ?: -1L
                val acceptRanges = it.header("Accept-Ranges")
                val supportsRange = acceptRanges != null && acceptRanges != "none"
                ProbeResult(contentLength, supportsRange)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD probe failed, assuming no range support: ${e.message}")
            ProbeResult(-1L, false)
        }
    }

    /**
     * Download using multiple parallel segments with HTTP Range requests.
     */
    private suspend fun downloadSegmented(
        url: String,
        destFile: File,
        totalBytes: Long,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val segmentSize = totalBytes / NUM_SEGMENTS
        val totalDownloaded = AtomicLong(0)
        val startTime = System.currentTimeMillis()
        val segmentFiles = mutableListOf<File>()

        try {
            // Create segment temp files
            for (i in 0 until NUM_SEGMENTS) {
                segmentFiles.add(File(destFile.parent, "${destFile.name}.seg$i"))
            }

            // Download all segments in parallel
            coroutineScope {
                val jobs = (0 until NUM_SEGMENTS).map { i ->
                    val start = i * segmentSize
                    val end = if (i == NUM_SEGMENTS - 1) totalBytes - 1 else (i + 1) * segmentSize - 1

                    async(Dispatchers.IO) {
                        downloadSegment(url, segmentFiles[i], start, end, totalBytes, totalDownloaded, startTime, onProgress)
                    }
                }
                jobs.awaitAll()
            }

            // Merge segments into final file
            Log.d(TAG, "Merging ${NUM_SEGMENTS} segments...")
            BufferedOutputStream(FileOutputStream(destFile), BUFFER_SIZE).use { out ->
                for (segFile in segmentFiles) {
                    BufferedInputStream(FileInputStream(segFile), BUFFER_SIZE).use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }

            Log.d(TAG, "Merge complete: ${destFile.length()} bytes")

        } finally {
            // Clean up segment files
            segmentFiles.forEach { it.delete() }
        }
    }

    /**
     * Download a single segment with Range header.
     */
    private fun downloadSegment(
        url: String,
        segFile: File,
        startByte: Long,
        endByte: Long,
        totalBytes: Long,
        totalDownloaded: AtomicLong,
        startTime: Long,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$startByte-$endByte")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            if (response.code == 429 || response.code == 403) throw IOException("HTTP ${response.code}")
            throw IOException("Segment download failed: ${response.code}")
        }

        val body = response.body ?: throw IOException("Empty segment body")
        var lastProgressTime = 0L

        BufferedOutputStream(FileOutputStream(segFile), BUFFER_SIZE).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    val downloaded = totalDownloaded.addAndGet(read.toLong())

                    // Throttled progress update
                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime > 300) {
                        val elapsed = (now - startTime) / 1000.0
                        val speed = if (elapsed > 0) downloaded / elapsed else 0.0
                        onProgress(DownloadProgress(totalBytes, downloaded, speed))
                        lastProgressTime = now
                    }
                }
            }
        }
    }

    /**
     * Fallback: Single-stream download when Range is not supported.
     */
    private fun downloadSingleStream(
        url: String,
        destFile: File,
        totalBytes: Long,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            if (response.code == 429 || response.code == 403) throw IOException("HTTP ${response.code}")
            throw IOException("Download failed: ${response.code}")
        }

        val body = response.body ?: throw IOException("Empty body")
        val actualTotal = if (totalBytes > 0) totalBytes else body.contentLength()
        val startTime = System.currentTimeMillis()
        var lastProgressTime = 0L

        BufferedOutputStream(FileOutputStream(destFile), BUFFER_SIZE).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read

                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime > 300) {
                        val elapsed = (now - startTime) / 1000.0
                        val speed = if (elapsed > 0) downloaded / elapsed else 0.0
                        onProgress(DownloadProgress(actualTotal, downloaded, speed))
                        lastProgressTime = now
                    }
                }
            }
        }
    }
}
