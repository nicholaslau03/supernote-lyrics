package com.supernote.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Top-priority lyric source: Spotify's INTERNAL color-lyrics endpoint.
 *
 * This is the same endpoint the Spotify desktop/mobile/web apps use to
 * show lyrics in-app. It's powered by Musixmatch via Spotify's licensed
 * relationship, which means its catalogue is much larger than what the
 * public Musixmatch web-desktop-app-v1.0 anonymous token can access.
 *
 * Auth flow:
 *   1. User provides their `sp_dc` cookie (one-time, from spotify.com)
 *   2. We exchange it for a short-lived web-player Bearer token at
 *      /get_access_token (token good for ~1 hour, cached in-process)
 *   3. We call /color-lyrics/v2/track/<id> with the Bearer token
 *   4. Convert Spotify's JSON line-list to LRC string
 *
 * If sp_dc isn't configured or any step fails, returns null and the
 * caller falls through to other sources.
 */
class SpotifyInternalClient(private val prefs: Prefs) {

    private val tokenMutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0L

    suspend fun fetch(trackId: String?): LyricsBundle? = withContext(Dispatchers.IO) {
        val id = trackId?.takeIf { it.isNotBlank() } ?: return@withContext null
        val sp = prefs.spDcCookie ?: return@withContext null  // not configured
        val token = ensureToken(sp) ?: run {
            Log.d(TAG, "Spotify-internal token unavailable (sp_dc may be expired/invalid)")
            return@withContext null
        }
        val url = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$id" +
            "?format=json&vocalRemoval=false&market=from_token"
        var conn: HttpURLConnection? = null
        return@withContext try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("App-platform", "WebPlayer")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            Log.d(TAG, "Spotify-internal lyrics http=$code track=$id")
            if (code == 401) {
                // Token expired — invalidate cache so next call refreshes
                cachedToken = null
                tokenExpiresAtMs = 0
                return@withContext null
            }
            if (code != 200) return@withContext null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val lrc = convertToLrc(body) ?: return@withContext null
            Log.d(TAG, "Spotify-internal OK ${lrc.length} chars")
            LyricsBundle(synced = lrc, plain = null, source = "Spotify")
        } catch (e: Exception) {
            Log.w(TAG, "Spotify-internal exception: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun ensureToken(spDc: String): String? = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (cached != null && now < tokenExpiresAtMs - 60_000) return@withLock cached

        var conn: HttpURLConnection? = null
        return@withLock try {
            val url = URL("https://open.spotify.com/get_access_token" +
                "?reason=transport&productType=web_player")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("App-platform", "WebPlayer")
                setRequestProperty("Cookie", "sp_dc=$spDc")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.d(TAG, "Spotify get_access_token http=$code (sp_dc invalid?)")
                return@withLock null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("isAnonymous", true)) {
                Log.d(TAG, "Spotify token came back anonymous — sp_dc not valid")
                return@withLock null
            }
            val token = json.optString("accessToken").takeIf { it.isNotBlank() } ?: return@withLock null
            val expMs = json.optLong("accessTokenExpirationTimestampMs", now + 3600_000)
            cachedToken = token
            tokenExpiresAtMs = expMs
            token
        } catch (e: Exception) {
            Log.w(TAG, "Spotify get_access_token error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Spotify's lyrics response shape (relevant bits):
     *   { "lyrics": { "syncType": "LINE_SYNCED" | "UNSYNCED",
     *                 "lines": [ { "startTimeMs": "27500", "words": "..." }, ... ] } }
     */
    private fun convertToLrc(body: String): String? {
        return try {
            val lyrics = JSONObject(body).optJSONObject("lyrics") ?: return null
            val syncType = lyrics.optString("syncType")
            val lines = lyrics.optJSONArray("lines") ?: return null
            if (lines.length() == 0) return null
            val sb = StringBuilder()
            val isSynced = syncType == "LINE_SYNCED"
            for (i in 0 until lines.length()) {
                val line = lines.optJSONObject(i) ?: continue
                val words = line.optString("words")
                if (isSynced) {
                    val tMs = line.optString("startTimeMs", "0").toLongOrNull() ?: 0L
                    sb.append('[')
                    val mm = tMs / 60_000
                    val ss = (tMs % 60_000) / 1000
                    val cs = (tMs % 1000) / 10  // hundredths
                    sb.append(String.format("%02d:%02d.%02d", mm, ss, cs))
                    sb.append(']')
                }
                sb.append(words).append('\n')
            }
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "SupernoteLyrics"
        private const val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
