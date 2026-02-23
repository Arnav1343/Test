package com.beatit.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow

object BatchManager {
    private const val TAG = "BatchManager"
    private const val MAX_CONCURRENT_DOWNLOADS = 5
    private const val MAX_RETRIES = 3
    private const val HEALTH_CHECK_INTERVAL_MS = 60_000L
    private const val DEBOUNCE_INTERVAL_MS = 300L
    private const val WATCHDOG_TIMEOUT_MS = 45_000L

    private val executor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS)
    private val activeWorkerCount = AtomicInteger(0)
    private val mutex = Mutex()

    private var isRecovering = false
    private var globalRateLimitUntil = 0L
    private var globalConsecutive429s = 0

    private val trackWatchdogs = ConcurrentHashMap<String, Long>()
    private val lastProgressBytes = ConcurrentHashMap<String, Long>()
    private val emaSpeeds = ConcurrentHashMap<String, Double>()

    private lateinit var dao: BatchDao
    private lateinit var youtubeHelper: YoutubeHelper
    private lateinit var musicDir: File
    
    // Optimized HTTP client: HTTP/2, connection pooling, large timeouts
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    fun init(context: Context, musicDir: File) {
        val database = AppDatabase.getDatabase(context)
        this.dao = database.batchDao()
        this.youtubeHelper = YoutubeHelper()
        this.musicDir = musicDir

        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                runHealthMonitor()
            }
        }

        runRecovery()
        startDispatchLoop()
    }

    private fun startDispatchLoop() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                if (System.currentTimeMillis() < globalRateLimitUntil) {
                    delay(5000)
                    continue
                }

                if (activeWorkerCount.get() >= MAX_CONCURRENT_DOWNLOADS || isRecovering) {
                    delay(500)
                    continue
                }

                dispatchNextIfPossible()
                delay(300)  // Faster dispatch polling
            }
        }
    }

    private suspend fun dispatchNextIfPossible() {
        mutex.withLock {
            CoroutineScope(Dispatchers.IO).launch {
                val nextTrack = dao.getQueuedTracks().firstOrNull() ?: return@launch
                
                if (nextTrack.status != TrackStatus.QUEUED) return@launch
                
                transitionInternal(nextTrack, TrackStatus.DISPATCHING)
                dao.updateTrack(nextTrack)
                
                activeWorkerCount.incrementAndGet()
                executor.submit {
                    runWorker(nextTrack.id)
                }
            }
        }
    }

    private fun runWorker(trackId: String) {
        val track = runBlocking { dao.getTrack(trackId) } ?: run {
            activeWorkerCount.decrementAndGet()
            return
        }

        try {
            runBlocking { transition(track.id, TrackStatus.DOWNLOADING) }
            
            val videoUrl = track.youtubeVideoId ?: throw IOException("No Video ID")
            Log.d(TAG, "Extracting stream for: ${track.title} (${videoUrl})")
            val (streamUrl, streamError) = youtubeHelper.getAudioStreamUrl(videoUrl)
            
            if (streamUrl == null) throw IOException(streamError ?: "Stream extraction failed")

            val extension = "mp3"
            val finalFile = File(musicDir, "${sanitize(track.title)}.$extension")
            val tempFile = File(musicDir, "${sanitize(track.title)}.$extension.tmp")
            track.outputFilePath = finalFile.absolutePath
            
            Log.d(TAG, "Starting download: ${track.title}")
            downloadWithProgress(streamUrl, tempFile, track)
            
            if (tempFile.renameTo(finalFile)) {
                runBlocking { transition(track.id, TrackStatus.COMPLETED) }
                globalConsecutive429s = 0
                Log.d(TAG, "✓ Completed: ${track.title}")
            } else {
                throw IOException("Failed to rename temp file")
            }
        } catch (e: Exception) {
            handleWorkerFailure(track, e)
        } finally {
            activeWorkerCount.decrementAndGet()
            trackWatchdogs.remove(track.id)
            lastProgressBytes.remove(track.id)
            emaSpeeds.remove(track.id)
        }
    }

    private fun handleWorkerFailure(track: Track, e: Exception) {
        val msg = e.message ?: "Unknown error"
        Log.e(TAG, "Worker failed for ${track.title}: $msg")
        
        if (msg.contains("429") || msg.contains("403")) {
            globalConsecutive429s++
            val cooldown = if (globalConsecutive429s == 1) 30_000L else 60_000L
            globalRateLimitUntil = System.currentTimeMillis() + cooldown
            Log.w(TAG, "Rate limit hit. Cooling down for ${cooldown/1000}s")
        }

        track.retryCount++
        track.errorCode = msg
        runBlocking {
            if (track.retryCount < MAX_RETRIES) {
                transition(track.id, TrackStatus.QUEUED)
            } else {
                transition(track.id, TrackStatus.FAILED)
            }
        }
    }

    /**
     * Optimized download with 256KB buffer, BufferedOutputStream, and throttled progress updates.
     */
    private fun downloadWithProgress(url: String, dest: File, track: Track) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            if (response.code == 429 || response.code == 403) throw IOException("HTTP ${response.code}")
            throw IOException("Download failed: ${response.code}")
        }
        
        val body = response.body ?: throw IOException("Empty body")
        track.totalBytes = body.contentLength()
        
        var lastWriteTime = System.currentTimeMillis()

        BufferedOutputStream(FileOutputStream(dest), 262144).use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(262144)  // 256KB buffer
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read
                    
                    track.bytesDownloaded = downloaded
                    trackWatchdogs[track.id] = System.currentTimeMillis()
                    
                    // Throttled progress update (every 300ms)
                    val now = System.currentTimeMillis()
                    if (now - lastWriteTime > DEBOUNCE_INTERVAL_MS) {
                        calculateEmaSpeed(track, downloaded, now - lastWriteTime)
                        runBlocking { dao.updateTrack(track) }
                        lastWriteTime = now
                    }
                }
            }
        }
        // Final flush
        runBlocking { dao.updateTrack(track) }
    }

    private fun calculateEmaSpeed(track: Track, currentBytes: Long, deltaMs: Long) {
        val lastBytes = lastProgressBytes[track.id] ?: 0L
        val instantSpeed = (currentBytes - lastBytes).toDouble() / (deltaMs / 1000.0)
        val prevEma = emaSpeeds[track.id] ?: instantSpeed
        val newEma = (instantSpeed * 0.3) + (prevEma * 0.7)
        emaSpeeds[track.id] = newEma
        lastProgressBytes[track.id] = currentBytes
    }

    // ── Internal Helpers ──────────────────────────────────────────

    private fun runRecovery() {
        isRecovering = true
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                try {
                    val stalled = dao.getStalledTracks()
                    stalled.forEach { track ->
                        track.outputFilePath?.let { File(it).delete() }
                        transitionInternal(track, TrackStatus.QUEUED)
                        dao.updateTrack(track)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recovery failed", e)
                } finally {
                    isRecovering = false
                    activeWorkerCount.set(0)
                }
            }
        }
    }

    private fun runHealthMonitor() {
        val now = System.currentTimeMillis()
        trackWatchdogs.forEach { (id, lastTime) ->
            if (now - lastTime > WATCHDOG_TIMEOUT_MS) {
                Log.w(TAG, "Watchdog timeout for track $id. Requeuing.")
                runBlocking { transition(id, TrackStatus.QUEUED) }
            }
        }
        
        if (activeWorkerCount.get() > 0 && trackWatchdogs.isEmpty()) {
            Log.e(TAG, "CRITICAL: activeWorkerCount > 0 but no active watchdogs. Resetting.")
            activeWorkerCount.set(0)
        }
    }

    suspend fun transition(trackId: String, nextStatus: TrackStatus) {
        mutex.withLock {
            val track = dao.getTrack(trackId) ?: return
            if (isValidTransition(track.status, nextStatus)) {
                transitionInternal(track, nextStatus)
                dao.updateTrack(track)
                updateBatchState(track.batchId)
            }
        }
    }

    private fun transitionInternal(track: Track, nextStatus: TrackStatus) {
        track.status = nextStatus
        track.updatedAt = System.currentTimeMillis()
    }

    private fun isValidTransition(from: TrackStatus, to: TrackStatus): Boolean {
        return when (from) {
            TrackStatus.EXTRACTED -> to in listOf(TrackStatus.MATCHING, TrackStatus.MATCHED, TrackStatus.QUEUED)
            TrackStatus.MATCHING -> to in listOf(TrackStatus.MATCHED, TrackStatus.MATCHED_LOW_CONFIDENCE, TrackStatus.FAILED)
            TrackStatus.MATCHED -> to == TrackStatus.QUEUED
            TrackStatus.MATCHED_LOW_CONFIDENCE -> to in listOf(TrackStatus.MATCHED, TrackStatus.MATCHING, TrackStatus.MATCHING_MANUAL)
            TrackStatus.MATCHING_MANUAL -> to in listOf(TrackStatus.MATCHED, TrackStatus.MATCHED_LOW_CONFIDENCE, TrackStatus.FAILED)
            TrackStatus.QUEUED -> to == TrackStatus.DISPATCHING
            TrackStatus.DISPATCHING -> to in listOf(TrackStatus.DOWNLOADING, TrackStatus.QUEUED)
            TrackStatus.DOWNLOADING -> to in listOf(TrackStatus.COMPLETED, TrackStatus.FAILED, TrackStatus.QUEUED)
            TrackStatus.FAILED -> to == TrackStatus.QUEUED
            TrackStatus.COMPLETED -> false
        }
    }

    private suspend fun updateBatchState(batchId: String) {
        val batchWithTracks = dao.getBatchWithTracks(batchId) ?: return
        val batch = batchWithTracks.batch
        val tracks = batchWithTracks.tracks

        val completed = tracks.count { it.status == TrackStatus.COMPLETED }
        val failed = tracks.count { it.status == TrackStatus.FAILED }
        val lowConf = tracks.count { it.status == TrackStatus.MATCHED_LOW_CONFIDENCE }
        val active = tracks.count { it.status in listOf(TrackStatus.MATCHING, TrackStatus.QUEUED, TrackStatus.DISPATCHING, TrackStatus.DOWNLOADING) }

        batch.completedCount = completed
        batch.failedCount = failed
        batch.updatedAt = System.currentTimeMillis()

        when {
            completed + failed == batch.totalTracks && lowConf == 0 -> batch.state = BatchState.COMPLETED
            failed == batch.totalTracks -> batch.state = BatchState.FAILED
            lowConf > 0 && active == 0 -> batch.state = BatchState.AWAITING_USER
            active > 0 -> batch.state = BatchState.DOWNLOADING
            else -> batch.state = BatchState.QUEUED
        }
        dao.updateBatch(batch)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().take(80)

    data class ImportResult(val success: Boolean, val trackCount: Int = 0, val error: String? = null)

    suspend fun submitBatch(url: String, platform: SourcePlatform): ImportResult {
        if (isRecovering) return ImportResult(false, error = "System is recovering, please try again in a moment")

        val batch = BatchTask(state = BatchState.EXTRACTING)
        dao.insertBatch(batch)
        
        return try {
            Log.d(TAG, "Starting extraction for URL: $url, platform: $platform")
            val candidates = PlaylistExtractor.extract(url, platform)
            Log.d(TAG, "Extraction returned ${candidates.size} candidates")
            
            if (candidates.isEmpty()) {
                batch.state = BatchState.FAILED
                batch.errorCode = "Could not extract tracks from this URL. The playlist may be private or the service is unavailable."
                dao.updateBatch(batch)
                return ImportResult(false, error = batch.errorCode)
            }
            
            if (candidates.size > 500) {
                batch.state = BatchState.FAILED
                batch.errorCode = "Playlist too large (${candidates.size} tracks, max 500)"
                dao.updateBatch(batch)
                return ImportResult(false, error = batch.errorCode)
            }
            
            val tracks = candidates.map { c ->
                Track(
                    batchId = batch.id,
                    fingerprint = PlaylistExtractor.computeFingerprint(c.title, c.artist, c.durationSeconds),
                    title = c.title,
                    artist = c.artist,
                    durationSeconds = c.durationSeconds,
                    thumbnailUrl = c.thumbnailUrl,
                    sourcePlatform = c.sourcePlatform,
                    // If we already have the YouTube URL from extraction, set it directly
                    youtubeVideoId = c.sourceUrl
                )
            }
            dao.insertTracks(tracks)
            batch.totalTracks = tracks.size
            batch.state = BatchState.MATCHING
            dao.updateBatch(batch)
            
            Log.d(TAG, "Batch created with ${tracks.size} tracks, starting matching...")
            
            // Start matching and downloading in background
            CoroutineScope(Dispatchers.IO).launch {
                processMatching(batch.id)
            }
            
            ImportResult(true, trackCount = tracks.size)
        } catch (e: Exception) {
            Log.e(TAG, "submitBatch failed: ${e.message}", e)
            batch.state = BatchState.FAILED
            batch.errorCode = e.message
            dao.updateBatch(batch)
            ImportResult(false, error = "Import failed: ${e.message}")
        }
    }

    /**
     * Process matching for tracks in a batch.
     * For YouTube tracks that already have video URLs, skip matching entirely.
     * For other tracks, search YouTube in parallel (up to 3 concurrent searches).
     */
    private suspend fun processMatching(batchId: String) {
        val tracks = dao.getTracksForBatch(batchId)
        val searchSemaphore = Semaphore(3) // Limit concurrent YouTube searches
        
        coroutineScope {
            tracks.map { track ->
                async(Dispatchers.IO) {
                    if (track.status != TrackStatus.EXTRACTED) return@async
                    
                    // Fast path: YouTube tracks already have the video URL
                    if (track.youtubeVideoId != null) {
                        Log.d(TAG, "⚡ Fast-match (already have URL): ${track.title}")
                        track.matchConfidence = 1.0
                        track.status = TrackStatus.QUEUED
                        track.updatedAt = System.currentTimeMillis()
                        dao.updateTrack(track)
                        Log.d(TAG, "✓ Queued: ${track.title} -> ${track.status}")
                        return@async
                    }
                    
                    // Slow path: Need to search YouTube (for Spotify/Apple Music tracks)
                    searchSemaphore.acquire()
                    try {
                        Log.d(TAG, "🔍 Searching YouTube for: ${track.title} ${track.artist}")
                        track.status = TrackStatus.MATCHING
                        track.updatedAt = System.currentTimeMillis()
                        dao.updateTrack(track)
                        
                        val (videoId, confidence) = TrackMapper.mapTrack(track, dao, youtubeHelper)
                        track.youtubeVideoId = videoId
                        track.matchConfidence = confidence
                        
                        if (videoId == null) {
                            track.status = TrackStatus.FAILED
                            track.errorCode = "No match found"
                        } else if (confidence < 0.75) {
                            track.status = TrackStatus.MATCHED_LOW_CONFIDENCE
                        } else {
                            track.status = TrackStatus.QUEUED
                        }
                        track.updatedAt = System.currentTimeMillis()
                        dao.updateTrack(track)
                        Log.d(TAG, "✓ Matched: ${track.title} -> ${track.status}")
                    } finally {
                        searchSemaphore.release()
                    }
                }
            }.awaitAll()
        }
        
        updateBatchState(batchId)
    }
}
