package com.supernote.lyrics

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

object SpotifyAuth {

    const val REDIRECT_URI = "supernotelyrics://callback"
    private const val AUTH_BASE = "https://accounts.spotify.com/authorize"
    private const val TOKEN_BASE = "https://accounts.spotify.com/api/token"
    private val SCOPES = listOf("user-read-currently-playing", "user-read-playback-state")

    private val ALLOWED_CHARS =
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~").toCharArray()

    fun newCodeVerifier(length: Int = 64): String {
        val rng = SecureRandom()
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALLOWED_CHARS[rng.nextInt(ALLOWED_CHARS.size)]) }
        return sb.toString()
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun newState(): String = newCodeVerifier(24)

    fun buildAuthUrl(clientId: String, challenge: String, state: String): String {
        val params = listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "scope" to SCOPES.joinToString(" "),
            "state" to state,
        )
        val query = params.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        return "$AUTH_BASE?$query"
    }

    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSec: Long,
    )

    suspend fun exchangeCode(clientId: String, code: String, verifier: String): TokenResponse? =
        postForm(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to clientId,
                "code_verifier" to verifier,
            )
        )

    suspend fun refresh(clientId: String, refreshToken: String): TokenResponse? =
        postForm(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            )
        )

    private suspend fun postForm(form: Map<String, String>): TokenResponse? =
        withContext(Dispatchers.IO) {
            val body = form.entries.joinToString("&") { (k, v) ->
                "$k=" + URLEncoder.encode(v, "UTF-8")
            }
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(TOKEN_BASE).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) return@withContext null
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(resp)
                TokenResponse(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                    expiresInSec = json.optLong("expires_in", 3600L),
                )
            } catch (_: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }
}
