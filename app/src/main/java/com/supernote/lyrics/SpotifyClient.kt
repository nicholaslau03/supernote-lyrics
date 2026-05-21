package com.supernote.lyrics

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SpotifyClient(private val prefs: Prefs) {

    sealed class PollState {
        object NotLoggedIn : PollState()
        object NothingPlaying : PollState()
        data class Playing(val track: TrackInfo, val playback: PlaybackInfo) : PollState()
        data class Error(val message: String) : PollState()
    }

    suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            val result = fetchOnce()
            when (result) {
                is PollState.Playing -> {
                    LyricsRepository.onTrack(result.track)
                    LyricsRepository.onPlayback(result.playback)
                }
                PollState.NothingPlaying -> {
                    // Keep last shown state; user may resume.
                }
                PollState.NotLoggedIn -> {
                    prefs.clearTokens()
                    return
                }
                is PollState.Error -> {
                    // transient — try again next tick
                }
            }
            val playing = (result as? PollState.Playing)?.playback?.playing == true
            delay(if (playing) ACTIVE_POLL_MS else IDLE_POLL_MS)
        }
    }

    suspend fun fetchOnce(): PollState {
        val token = ensureValidToken() ?: return PollState.NotLoggedIn
        val (code, body) = httpGet(CURRENTLY_PLAYING_URL, token)
        if (code == 401) {
            // Force refresh on next call
            prefs.tokenExpiresAtElapsed = 0L
            val refreshed = ensureValidToken() ?: return PollState.NotLoggedIn
            val retry = httpGet(CURRENTLY_PLAYING_URL, refreshed)
            return parseResponse(retry.first, retry.second)
        }
        return parseResponse(code, body)
    }

    private fun parseResponse(code: Int, body: String?): PollState {
        if (code == 204 || body.isNullOrBlank()) return PollState.NothingPlaying
        if (code !in 200..299) return PollState.Error("HTTP $code")
        return try {
            val now = SystemClock.elapsedRealtime()
            val json = JSONObject(body)
            val item = json.optJSONObject("item") ?: return PollState.NothingPlaying
            val type = json.optString("currently_playing_type")
            if (type.isNotBlank() && type != "track") return PollState.NothingPlaying
            val title = item.optString("name").orEmpty()
            val artists = item.optJSONArray("artists")
            val artist = buildString {
                if (artists != null) {
                    for (i in 0 until artists.length()) {
                        val a = artists.optJSONObject(i) ?: continue
                        val name = a.optString("name").orEmpty()
                        if (name.isBlank()) continue
                        if (isNotEmpty()) append(", ")
                        append(name)
                    }
                }
            }
            val album = item.optJSONObject("album")?.optString("name")?.takeIf { it.isNotBlank() }
            val durationMs = item.optLong("duration_ms", 0L).coerceAtLeast(0L)
            val progressMs = json.optLong("progress_ms", 0L).coerceAtLeast(0L)
            val playing = json.optBoolean("is_playing", false)
            val trackId = item.optString("id").takeIf { it.isNotBlank() }
            if (title.isBlank() || artist.isBlank()) return PollState.NothingPlaying
            PollState.Playing(
                track = TrackInfo(title.trim(), artist.trim(), album?.trim(), durationMs, trackId),
                playback = PlaybackInfo(
                    positionMs = progressMs,
                    updatedAtElapsedMs = now,
                    playing = playing,
                    speed = 1f,
                )
            )
        } catch (e: Exception) {
            PollState.Error(e.message ?: "parse error")
        }
    }

    private suspend fun ensureValidToken(): String? {
        val clientId = prefs.clientId ?: return null
        val current = prefs.accessToken
        if (!current.isNullOrBlank() && !prefs.isTokenStale()) return current
        val refresh = prefs.refreshToken ?: return null
        val tr = SpotifyAuth.refresh(clientId, refresh) ?: return null
        prefs.saveTokens(tr.accessToken, tr.refreshToken, tr.expiresInSec)
        return tr.accessToken
    }

    private suspend fun httpGet(url: String, bearer: String): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $bearer")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }
                code to body
            } catch (_: Exception) {
                0 to null
            } finally {
                conn?.disconnect()
            }
        }

    companion object {
        private const val CURRENTLY_PLAYING_URL =
            "https://api.spotify.com/v1/me/player/currently-playing"
        private const val ACTIVE_POLL_MS = 2_000L
        private const val IDLE_POLL_MS = 8_000L
    }
}
