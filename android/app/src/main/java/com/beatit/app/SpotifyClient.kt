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
 * No user login required — reads public playlist data only.
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

    data class PlaylistResponse(
        val name: String?,
        val tracks: TracksPage
    )

    data class TracksPage(
        val items: List<PlaylistItem>?,
        val next: String?,
        val total: Int?
    )

    data class PlaylistItem(
        val track: SpotifyTrack?
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

    // ── Auth ────────────────────────────────────────────────────────

    @Synchronized
    private fun ensureToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt) return

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
        if (!response.isSuccessful) throw IOException("Token request failed: ${response.code}")

        val body = response.body?.string() ?: throw IOException("Empty token response")
        val token = gson.fromJson(body, TokenResponse::class.java)

        accessToken = token.accessToken
        // Refresh 60 seconds early to avoid edge cases
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
        if (!response.isSuccessful) throw IOException("Spotify API error: ${response.code}")
        return response.body?.string() ?: throw IOException("Empty API response")
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Extract playlist ID from a Spotify URL.
     * Handles: open.spotify.com/playlist/ID, spotify:playlist:ID
     */
    fun extractPlaylistId(url: String): String? {
        // https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=...
        val webMatch = Regex("""playlist/([a-zA-Z0-9]+)""").find(url)
        if (webMatch != null) return webMatch.groupValues[1]

        // spotify:playlist:37i9dQZF1DXcBWIGoYBM5M
        val uriMatch = Regex("""playlist:([a-zA-Z0-9]+)""").find(url)
        if (uriMatch != null) return uriMatch.groupValues[1]

        return null
    }

    /**
     * Fetch all tracks from a Spotify playlist.
     * Handles pagination automatically (up to 500 tracks).
     */
    fun getPlaylistTracks(playlistId: String): List<TrackCandidate> {
        val tracks = mutableListOf<TrackCandidate>()

        var url: String? = "$API_BASE/playlists/$playlistId/tracks?limit=100&fields=items(track(name,artists(name),duration_ms,album(name,images))),next,total"

        while (url != null && tracks.size < 500) {
            val body = apiGet(url)
            val page = gson.fromJson(body, TracksPage::class.java)

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
}
