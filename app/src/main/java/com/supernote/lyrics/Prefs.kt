package com.supernote.lyrics

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext
        .getSharedPreferences("supernote_lyrics", Context.MODE_PRIVATE)

    var clientId: String?
        get() = sp.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
        set(value) = sp.edit().putString(KEY_CLIENT_ID, value).apply()

    var accessToken: String?
        get() = sp.getString(KEY_ACCESS_TOKEN, null)
        set(value) = sp.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = sp.getString(KEY_REFRESH_TOKEN, null)
        set(value) = sp.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    /** Elapsed-realtime millis when the current access token stops being valid. */
    var tokenExpiresAtElapsed: Long
        get() = sp.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        set(value) = sp.edit().putLong(KEY_TOKEN_EXPIRES_AT, value).apply()

    var pendingVerifier: String?
        get() = sp.getString(KEY_PENDING_VERIFIER, null)
        set(value) = sp.edit().putString(KEY_PENDING_VERIFIER, value).apply()

    var pendingState: String?
        get() = sp.getString(KEY_PENDING_STATE, null)
        set(value) = sp.edit().putString(KEY_PENDING_STATE, value).apply()

    var simplifyChinese: Boolean
        get() = sp.getBoolean(KEY_SIMPLIFY_CHINESE, true)
        set(value) = sp.edit().putBoolean(KEY_SIMPLIFY_CHINESE, value).apply()

    var textSizeSp: Int
        get() = sp.getInt(KEY_TEXT_SIZE, 22).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
        set(value) = sp.edit().putInt(KEY_TEXT_SIZE, value.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)).apply()

    val isLoggedIn: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    fun isTokenStale(skewMs: Long = 30_000L): Boolean {
        return SystemClock.elapsedRealtime() + skewMs >= tokenExpiresAtElapsed
    }

    fun saveTokens(access: String, refresh: String?, expiresInSec: Long) {
        sp.edit()
            .putString(KEY_ACCESS_TOKEN, access)
            .apply {
                if (!refresh.isNullOrBlank()) putString(KEY_REFRESH_TOKEN, refresh)
            }
            .putLong(KEY_TOKEN_EXPIRES_AT, SystemClock.elapsedRealtime() + expiresInSec * 1000L)
            .apply()
    }

    fun clearTokens() {
        sp.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRES_AT)
            .remove(KEY_PENDING_VERIFIER)
            .remove(KEY_PENDING_STATE)
            .apply()
    }

    companion object {
        const val MIN_TEXT_SP = 14
        const val MAX_TEXT_SP = 40
        const val TEXT_STEP_SP = 2

        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        private const val KEY_PENDING_VERIFIER = "pending_verifier"
        private const val KEY_PENDING_STATE = "pending_state"
        private const val KEY_SIMPLIFY_CHINESE = "simplify_chinese"
        private const val KEY_TEXT_SIZE = "text_size_sp"
    }
}
