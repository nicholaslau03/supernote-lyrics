package com.supernote.lyrics

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
                val (hash, songDurMs) = searchSongHash(keyword) ?: return@withContext null
                val durationForCandidate = if (durationMs > 0) durationMs else songDurMs
                val (id, accesskey) = findLyricCandidate(hash, durationForCandidate)
                    ?: return@withContext null
                val lrc = downloadLrc(id, accesskey) ?: return@withContext null
                if (lrc.isBlank()) return@withContext null
                LyricsBundle(synced = lrc, plain = null, source = "KuGou")
            } catch (_: Exception) {
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
            code to body
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
