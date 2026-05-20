package com.supernote.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object LrcLibClient {
    private const val BASE_URL = "https://lrclib.net/api/get"
    private const val USER_AGENT = "SupernoteLyrics/0.1 (https://github.com/supernote-lyrics)"

    data class Result(val synced: String?, val plain: String?)

    suspend fun fetch(track: String, artist: String, album: String?, durationMs: Long): Result? =
        withContext(Dispatchers.IO) {
            val params = mutableListOf(
                "track_name=" + URLEncoder.encode(track, "UTF-8"),
                "artist_name=" + URLEncoder.encode(artist, "UTF-8")
            )
            if (!album.isNullOrBlank()) {
                params += "album_name=" + URLEncoder.encode(album, "UTF-8")
            }
            if (durationMs > 0) {
                params += "duration=" + (durationMs / 1000)
            }
            val url = URL("$BASE_URL?${params.joinToString("&")}")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                if (conn.responseCode != 200) return@withContext null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(body)
                Result(
                    synced = obj.optString("syncedLyrics").takeIf { it.isNotBlank() },
                    plain = obj.optString("plainLyrics").takeIf { it.isNotBlank() }
                )
            } catch (_: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }
}
