package com.supernote.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Best-effort plain-text fallback: Mojim (魔鏡歌詞網).
 *
 * Taiwanese lyrics site, huge HK/TW/Mando catalogue. No API, just HTML
 * pages. We do a search, follow the first lyric link, extract the lyric
 * block, and clean it. If anything in this chain fails (site blocks us,
 * structure changed, etc.) we return null and the caller falls through.
 *
 * Returns PLAIN text only — Mojim does not host LRC files.
 */
object MojimClient {

    private const val BASE = "https://mojim.com"
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Matches per-song result links like /cnh100782x18x33.htm, /usy.../, /jpy.../
    private val LYRIC_LINK_RE =
        Regex("""href="(/(?:cnh|cny|twy|usy|jpy|krh|kry)[A-Za-z0-9]*\d+x\d+x\d+\.htm)"""")

    // Lyrics body usually inside <dl id="fsZx3"> ... </dl>
    private val LYRIC_BLOCK_RE = Regex("""<dl[^>]*id="fsZx3"[^>]*>([\s\S]*?)</dl>""")

    private val TAG_RE = Regex("""<[^>]+>""")
    private val BR_RE = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    suspend fun fetch(title: String, artist: String): LyricsBundle? =
        withContext(Dispatchers.IO) {
            try {
                val keyword = "$title $artist".trim()
                Log.d("SupernoteLyrics", "Mojim.fetch keyword='$keyword'")
                val searchHtml = getHtml("$BASE/wzhall.htm?u3=${enc(keyword)}") ?: run {
                    Log.d("SupernoteLyrics", "Mojim search returned no body")
                    return@withContext null
                }
                val firstLink = LYRIC_LINK_RE.find(searchHtml)?.groupValues?.getOrNull(1)
                if (firstLink.isNullOrBlank()) {
                    Log.d("SupernoteLyrics", "Mojim no lyric link in search")
                    return@withContext null
                }
                Log.d("SupernoteLyrics", "Mojim lyric link=$firstLink")
                val lyricHtml = getHtml("$BASE$firstLink") ?: return@withContext null
                val block = LYRIC_BLOCK_RE.find(lyricHtml)?.groupValues?.getOrNull(1)
                if (block.isNullOrBlank()) {
                    Log.d("SupernoteLyrics", "Mojim no lyric block")
                    return@withContext null
                }
                val cleaned = block
                    .replace(BR_RE, "\n")
                    .let { TAG_RE.replace(it, "") }
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("更多") }
                    .toList()
                    .joinToString("\n")
                if (cleaned.length < 30) {
                    Log.d("SupernoteLyrics", "Mojim cleaned too short (${cleaned.length})")
                    return@withContext null
                }
                Log.d("SupernoteLyrics", "Mojim OK ${cleaned.length} chars")
                LyricsBundle(synced = null, plain = cleaned, source = "Mojim")
            } catch (e: Exception) {
                Log.w("SupernoteLyrics", "Mojim exception", e)
                null
            }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getHtml(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8")
                instanceFollowRedirects = true
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d("SupernoteLyrics", "Mojim http $code ${url.take(80)}")
                return null
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            body.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w("SupernoteLyrics", "Mojim httpGet failed url=$url err=${e.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
