package com.supernote.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fallback lyric source: QQ Music (QQ音乐) — Tencent's streaming service.
 * Open endpoints (no encryption), requires `Referer: y.qq.com` header.
 *
 * Two-step flow:
 *   1. /soso/fcgi-bin/client_search_cp → songmid
 *   2. /lyric/fcgi-bin/fcg_query_lyric_new.fcg?nobase64=1 → plain-text LRC
 */
object QQMusicClient {

    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun fetch(title: String, artist: String): LyricsBundle? =
        withContext(Dispatchers.IO) {
            try {
                val keyword = "$title $artist".trim()
                Log.d("SupernoteLyrics", "QQ.fetch keyword='$keyword'")
                val songmid = searchSongmid(keyword) ?: run {
                    Log.d("SupernoteLyrics", "QQ search no songmid")
                    return@withContext null
                }
                Log.d("SupernoteLyrics", "QQ songmid=$songmid")
                val lrc = downloadLyric(songmid)
                if (lrc.isNullOrBlank()) {
                    Log.d("SupernoteLyrics", "QQ lyric empty")
                    return@withContext null
                }
                Log.d("SupernoteLyrics", "QQ OK ${lrc.length} chars")
                LyricsBundle(synced = lrc, plain = null, source = "QQMusic")
            } catch (e: Exception) {
                Log.w("SupernoteLyrics", "QQ exception", e)
                null
            }
        }

    private fun searchSongmid(keyword: String): String? {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?" +
            "p=1&n=5&w=${enc(keyword)}&format=json"
        val (code, body) = httpGet(url) ?: return null
        if (code != 200 || body.isNullOrBlank()) return null
        val json = JSONObject(stripJsonp(body))
        val list = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: return null
        if (list.length() == 0) return null
        return list.optJSONObject(0)?.optString("songmid")?.takeIf { it.isNotBlank() }
    }

    private fun downloadLyric(songmid: String): String? {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?" +
            "songmid=$songmid&format=json&nobase64=1"
        val (code, body) = httpGet(url) ?: return null
        if (code != 200 || body.isNullOrBlank()) return null
        val json = JSONObject(stripJsonp(body))
        if (json.optInt("retcode", -1) != 0) return null
        return json.optString("lyric").takeIf { it.isNotBlank() }
    }

    /** QQ sometimes wraps responses in `callback(...)` or `MusicJsonCallback(...)`. */
    private fun stripJsonp(s: String): String {
        val openParen = s.indexOf('(')
        val closeParen = s.lastIndexOf(')')
        return if (openParen in 0 until closeParen && !s.trimStart().startsWith("{")) {
            s.substring(openParen + 1, closeParen)
        } else s
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String): Pair<Int, String?>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://y.qq.com/")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            Log.d("SupernoteLyrics", "httpGet $code ${url.take(80)} bodyLen=${body?.length ?: 0}")
            code to body
        } catch (e: Exception) {
            Log.w("SupernoteLyrics", "QQ httpGet failed url=$url err=${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
