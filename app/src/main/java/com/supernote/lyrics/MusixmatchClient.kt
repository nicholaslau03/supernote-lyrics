package com.supernote.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Top-priority lyric source: Musixmatch (the same database Spotify uses
 * internally for in-app lyrics). Reached via the unofficial
 * `web-desktop-app-v1.0` anonymous app, which doesn't require login but
 * does require a `user_token` that we fetch once and cache.
 *
 * Coverage is the best of any source by a wide margin — Western, Asian,
 * indie, anything Spotify has lyrics for is probably here.
 *
 * Single-shot fetch via `macro.subtitles.get` which combines search +
 * lyric lookup in one round trip.
 */
object MusixmatchClient {

    private const val TAG = "SupernoteLyrics"
    private const val BASE = "https://apic-desktop.musixmatch.com/ws/1.1"
    private const val APP_ID = "web-desktop-app-v1.0"
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Token is good for ~10 minutes per request; cache liberally to avoid 429s.
    private val tokenMutex = Mutex()
    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenFetchedAtMs: Long = 0L
    private const val TOKEN_TTL_MS = 8L * 60 * 1000  // 8 min (server limit is ~10)

    suspend fun fetch(title: String, artist: String, durationMs: Long): LyricsBundle? =
        withContext(Dispatchers.IO) {
            try {
                val token = obtainToken() ?: run {
                    Log.d(TAG, "Musixmatch token unavailable")
                    return@withContext null
                }
                val params = buildString {
                    append("format=json")
                    append("&app_id=").append(APP_ID)
                    append("&usertoken=").append(token)
                    append("&subtitle_format=lrc")
                    append("&q_track=").append(enc(title))
                    append("&q_artist=").append(enc(artist))
                    if (durationMs > 0) append("&q_duration=").append(durationMs / 1000)
                }
                val (code, body) = httpGet("$BASE/macro.subtitles.get?$params")
                if (code != 200 || body.isNullOrBlank()) {
                    Log.d(TAG, "Musixmatch macro http=$code")
                    return@withContext null
                }
                val lrc = extractSyncedLrc(body)
                if (lrc.isNullOrBlank()) {
                    Log.d(TAG, "Musixmatch no synced subtitle (404 from server)")
                    return@withContext null
                }
                Log.d(TAG, "Musixmatch OK ${lrc.length} chars")
                LyricsBundle(synced = lrc, plain = null, source = "Musixmatch")
            } catch (e: Exception) {
                Log.w(TAG, "Musixmatch exception", e)
                null
            }
        }

    private suspend fun obtainToken(): String? = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (cached != null && now - tokenFetchedAtMs < TOKEN_TTL_MS) return@withLock cached
        val (code, body) = httpGet("$BASE/token.get?app_id=$APP_ID&format=json")
        if (code != 200 || body.isNullOrBlank()) return@withLock cachedToken // keep stale if refresh fails
        try {
            val root = JSONObject(body).optJSONObject("message") ?: return@withLock cachedToken
            val statusCode = root.optJSONObject("header")?.optInt("status_code", 0) ?: 0
            if (statusCode != 200) return@withLock cachedToken
            val token = root.optJSONObject("body")?.optString("user_token")?.takeIf {
                it.isNotBlank() && it != "UpgradeOnlyUpgradeOnlyUpgradeOnlyUpgradeOnly"
            } ?: return@withLock cachedToken
            cachedToken = token
            tokenFetchedAtMs = now
            token
        } catch (_: Exception) {
            cachedToken
        }
    }

    /**
     * The macro response nests like:
     *   message.body.macro_calls."track.subtitles.get".message.body.subtitle_list[0].subtitle.subtitle_body
     * Returns the LRC string, or null if any step is missing.
     */
    private fun extractSyncedLrc(body: String): String? {
        return try {
            val macro = JSONObject(body)
                .optJSONObject("message")
                ?.optJSONObject("body")
                ?.optJSONObject("macro_calls")
                ?: return null
            val subMsg = macro.optJSONObject("track.subtitles.get")?.optJSONObject("message")
                ?: return null
            val status = subMsg.optJSONObject("header")?.optInt("status_code", 0) ?: 0
            if (status != 200) return null
            val list = subMsg.optJSONObject("body")?.optJSONArray("subtitle_list") ?: return null
            if (list.length() == 0) return null
            val first = list.optJSONObject(0)?.optJSONObject("subtitle") ?: return null
            first.optString("subtitle_body").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                // Required by Musixmatch's AWS load balancer for the anon-token flow.
                setRequestProperty("Cookie", "AWSELB=0; AWSELBCORS=0")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            Log.d(TAG, "Musixmatch httpGet $code bodyLen=${text?.length ?: 0}")
            code to text
        } catch (e: Exception) {
            Log.w(TAG, "Musixmatch httpGet failed err=${e.javaClass.simpleName}: ${e.message}")
            0 to null
        } finally {
            conn?.disconnect()
        }
    }
}
