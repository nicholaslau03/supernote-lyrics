package com.supernote.lyrics

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "SupernoteLyrics"

/**
 * Fallback lyric source: KuGou Music (酷狗音乐) — large Chinese + decent
 * Japanese catalogue, lighter anti-bot than NetEase.
 *
 * Three-step flow:
 *   1. /api/v3/search/song  → song hash + duration
 *   2. /search (krcs)       → lyric candidates with id + accesskey
 *   3. /download (lyrics)   → base64-encoded LRC content
 */
object KuGouClient {

    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun fetch(title: String, artist: String, durationMs: Long): LyricsBundle? =
        withContext(Dispatchers.IO) {
            try {
                val keyword = "$title $artist".trim()
                Log.d(TAG, "KuGou.fetch keyword='$keyword' durationMs=$durationMs")
                val hashPair = searchSongHash(keyword)
                if (hashPair == null) {
                    Log.d(TAG, "KuGou search returned no hash")
                    return@withContext null
                }
                val (hash, songDurMs) = hashPair
                Log.d(TAG, "KuGou search hit hash=$hash kgDurMs=$songDurMs")
                val durationForCandidate = if (durationMs > 0) durationMs else songDurMs
                val cand = findLyricCandidate(hash, durationForCandidate)
                if (cand == null) {
                    Log.d(TAG, "KuGou no lyric candidate")
                    return@withContext null
                }
                val (id, accesskey) = cand
                Log.d(TAG, "KuGou candidate id=$id")
                val lrc = downloadLrc(id, accesskey)
                if (lrc.isNullOrBlank()) {
                    Log.d(TAG, "KuGou download empty")
                    return@withContext null
                }
                Log.d(TAG, "KuGou OK ${lrc.length} chars")
                LyricsBundle(synced = lrc, plain = null, source = "KuGou")
            } catch (e: Exception) {
                Log.w(TAG, "KuGou exception", e)
                null
            }
        }

    private fun searchSongHash(keyword: String): Pair<String, Long>? {
        val url = "https://mobiles.kugou.com/api/v3/search/song?" +
            "keyword=${enc(keyword)}&page=1&pagesize=5"
        val (code, body) = httpGet(url) ?: return null
        if (code != 200 || body.isNullOrBlank()) return null
        val info = JSONObject(body).optJSONObject("data")?.optJSONArray("info") ?: return null
        if (info.length() == 0) return null
        val first = info.optJSONObject(0) ?: return null
        val hash = first.optString("hash").takeIf { it.isNotBlank() } ?: return null
        val durMs = first.optLong("duration", 0L) * 1000L
        return hash to durMs
    }

    private fun findLyricCandidate(hash: String, durationMs: Long): Pair<String, String>? {
        val url = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi" +
            "&hash=$hash&duration=$durationMs"
        val (code, body) = httpGet(url) ?: return null
        if (code != 200 || body.isNullOrBlank()) return null
        val candidates = JSONObject(body).optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val first = candidates.optJSONObject(0) ?: return null
        val id = first.optString("id").takeIf { it.isNotBlank() } ?: return null
        val key = first.optString("accesskey").takeIf { it.isNotBlank() } ?: return null
        return id to key
    }

    private fun downloadLrc(id: String, accesskey: String): String? {
        val url = "https://lyrics.kugou.com/download?ver=1&client=pc" +
            "&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8"
        val (code, body) = httpGet(url) ?: return null
        if (code != 200 || body.isNullOrBlank()) return null
        val content = JSONObject(body).optString("content").takeIf { it.isNotBlank() } ?: return null
        return try {
            String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): Pair<Int, String?>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            Log.d(TAG, "httpGet $code ${url.take(80)} bodyLen=${body?.length ?: 0}")
            code to body
        } catch (e: Exception) {
            Log.w(TAG, "httpGet failed url=$url err=${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
