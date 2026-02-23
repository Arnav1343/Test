package com.beatit.app

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Spotify Web API client using Client Credentials flow.
 * No user login required — reads public playlist and album data only.
 */
object SpotifyClient {
    private const val TAG = "SpotifyClient"
    private const val CLIENT_ID = "781875772a3a48aa9bf2f18af745e4c0"
    private const val CLIENT_SECRET = "f22d881d91f7448fa1c4e5c36a336a14"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val API_BASE = "https://api.spotify.com/v1"

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = null
    private var tokenExpiresAt: Long = 0

    // ── Data classes for API responses ──────────────────────────────

    data class TokenResponse(
        @SerializedName("access_token") val accessToken: String,
        @SerializedName("expires_in") val expiresIn: Int
    )

    data class TracksPage(
        val items: List<PlaylistItem>?,
        val next: String?,
        val total: Int?
    )

    data class PlaylistItem(
        val track: SpotifyTrack?
    )

    // Album tracks endpoint returns items directly as SimpleTrack (no wrapper)
    data class AlbumTracksPage(
        val items: List<SpotifySimpleTrack>?,
        val next: String?,
        val total: Int?
    )

    data class SpotifySimpleTrack(
        val name: String?,
        val artists: List<SpotifyArtist>?,
        @SerializedName("duration_ms") val durationMs: Int?,
        @SerializedName("track_number") val trackNumber: Int?
    )

    data class SpotifyTrack(
        val name: String?,
        val artists: List<SpotifyArtist>?,
        @SerializedName("duration_ms") val durationMs: Int?,
        val album: SpotifyAlbum?
    )

    data class SpotifyArtist(val name: String?)
    data class SpotifyAlbum(
        val name: String?,
        val images: List<SpotifyImage>?
    )
    data class SpotifyImage(val url: String?, val height: Int?)

    // Album metadata (for getting album art)
    data class AlbumMetadata(
        val name: String?,
        val images: List<SpotifyImage>?,
        val artists: List<SpotifyArtist>?
    )

    // ── Auth ────────────────────────────────────────────────────────

    @Synchronized
    private fun ensureToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt) return

        Log.d(TAG, "Requesting new Spotify access token...")
        val credentials = Base64.encodeToString(
            "$CLIENT_ID:$CLIENT_SECRET".toByteArray(),
            Base64.NO_WRAP
        )

        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Authorization", "Basic $credentials")
            .post("grant_type=client_credentials".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            Log.e(TAG, "Token request failed: HTTP ${response.code}, body: $errorBody")
            throw IOException("Token request failed: ${response.code}")
        }

        val body = response.body?.string() ?: throw IOException("Empty token response")
        val token = gson.fromJson(body, TokenResponse::class.java)

        accessToken = token.accessToken
        tokenExpiresAt = System.currentTimeMillis() + (token.expiresIn - 60) * 1000L
        Log.d(TAG, "Spotify token refreshed, expires in ${token.expiresIn}s")
    }

    private fun apiGet(url: String): String {
        ensureToken()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            Log.e(TAG, "Spotify API error: HTTP ${response.code} for $url, body: ${errorBody.take(300)}")
            throw IOException("Spotify API error: ${response.code}")
        }
        return response.body?.string() ?: throw IOException("Empty API response")
    }

    // ── URL Parsing ────────────────────────────────────────────────

    enum class SpotifyType { PLAYLIST, ALBUM }
    data class SpotifyId(val type: SpotifyType, val id: String)

    /**
     * Extract playlist or album ID from a Spotify URL.
     * Handles:
     *   - https://open.spotify.com/playlist/ID
     *   - https://open.spotify.com/album/ID
     *   - spotify:playlist:ID
     *   - spotify:album:ID
     */
    fun extractSpotifyId(url: String): SpotifyId? {
        // Web URLs: open.spotify.com/playlist/ID or open.spotify.com/album/ID
        val webPlaylist = Regex("""playlist/([a-zA-Z0-9]+)""").find(url)
        if (webPlaylist != null) return SpotifyId(SpotifyType.PLAYLIST, webPlaylist.groupValues[1])

        val webAlbum = Regex("""album/([a-zA-Z0-9]+)""").find(url)
        if (webAlbum != null) return SpotifyId(SpotifyType.ALBUM, webAlbum.groupValues[1])

        // URI format: spotify:playlist:ID or spotify:album:ID
        val uriPlaylist = Regex("""playlist:([a-zA-Z0-9]+)""").find(url)
        if (uriPlaylist != null) return SpotifyId(SpotifyType.PLAYLIST, uriPlaylist.groupValues[1])

        val uriAlbum = Regex("""album:([a-zA-Z0-9]+)""").find(url)
        if (uriAlbum != null) return SpotifyId(SpotifyType.ALBUM, uriAlbum.groupValues[1])

        return null
    }

    /** Backward-compatible: extract playlist ID only */
    fun extractPlaylistId(url: String): String? {
        val id = extractSpotifyId(url) ?: return null
        return if (id.type == SpotifyType.PLAYLIST) id.id else null
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Fetch tracks from any Spotify URL (playlist or album).
     */
    fun getTracks(url: String): List<TrackCandidate> {
        val spotifyId = extractSpotifyId(url)
        if (spotifyId == null) {
            Log.e(TAG, "Could not extract Spotify ID from URL: $url")
            return emptyList()
        }

        Log.d(TAG, "Extracted ${spotifyId.type} ID: ${spotifyId.id}")

        return when (spotifyId.type) {
            SpotifyType.PLAYLIST -> getPlaylistTracks(spotifyId.id)
            SpotifyType.ALBUM -> getAlbumTracks(spotifyId.id)
        }
    }

    /**
     * Fetch all tracks from a Spotify playlist.
     * Handles pagination automatically (up to 500 tracks).
     */
    fun getPlaylistTracks(playlistId: String): List<TrackCandidate> {
        val tracks = mutableListOf<TrackCandidate>()

        var url: String? = "$API_BASE/playlists/$playlistId/tracks?limit=100"

        while (url != null && tracks.size < 500) {
            val body = apiGet(url)
            Log.d(TAG, "Playlist API response (first 300 chars): ${body.take(300)}")
            val page = gson.fromJson(body, TracksPage::class.java)
            Log.d(TAG, "Parsed page: ${page.items?.size ?: 0} items, next=${page.next != null}")

            page.items?.forEach { item ->
                val t = item.track ?: return@forEach
                val name = t.name ?: return@forEach
                val artist = t.artists?.mapNotNull { it.name }?.joinToString(", ") ?: "Unknown"
                val durationSec = t.durationMs?.let { it / 1000 }
                val thumb = t.album?.images
                    ?.sortedByDescending { it.height ?: 0 }
                    ?.firstOrNull()?.url

                tracks.add(TrackCandidate(
                    title = name,
                    artist = artist,
                    durationSeconds = durationSec,
                    thumbnailUrl = thumb,
                    sourcePlatform = SourcePlatform.SPOTIFY
                ))
            }

            url = page.next
        }

        Log.d(TAG, "Fetched ${tracks.size} tracks from playlist $playlistId")
        return tracks.take(500)
    }

    /**
     * Fetch all tracks from a Spotify album.
     * Handles pagination automatically (up to 500 tracks).
     */
    fun getAlbumTracks(albumId: String): List<TrackCandidate> {
        // First, get album metadata (for artwork)
        val albumBody = apiGet("$API_BASE/albums/$albumId")
        val album = gson.fromJson(albumBody, AlbumMetadata::class.java)
        val albumThumb = album.images
            ?.sortedByDescending { it.height ?: 0 }
            ?.firstOrNull()?.url
        val albumArtist = album.artists?.mapNotNull { it.name }?.joinToString(", ") ?: ""

        Log.d(TAG, "Album: ${album.name}, artist: $albumArtist, thumb: $albumThumb")

        val tracks = mutableListOf<TrackCandidate>()
        var url: String? = "$API_BASE/albums/$albumId/tracks?limit=50"

        while (url != null && tracks.size < 500) {
            val body = apiGet(url)
            Log.d(TAG, "Album tracks API response (first 300 chars): ${body.take(300)}")
            val page = gson.fromJson(body, AlbumTracksPage::class.java)
            Log.d(TAG, "Parsed album page: ${page.items?.size ?: 0} items, next=${page.next != null}")

            page.items?.forEach { t ->
                val name = t.name ?: return@forEach
                val artist = t.artists?.mapNotNull { it.name }?.joinToString(", ") ?: albumArtist
                val durationSec = t.durationMs?.let { it / 1000 }

                tracks.add(TrackCandidate(
                    title = name,
                    artist = artist,
                    durationSeconds = durationSec,
                    thumbnailUrl = albumThumb,
                    sourcePlatform = SourcePlatform.SPOTIFY
                ))
            }

            url = page.next
        }

        Log.d(TAG, "Fetched ${tracks.size} tracks from album $albumId")
        return tracks.take(500)
    }
}
