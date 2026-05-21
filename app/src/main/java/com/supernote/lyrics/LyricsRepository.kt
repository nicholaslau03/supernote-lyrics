package com.supernote.lyrics

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val spotifyTrackId: String? = null,
) {
    val key: String get() = "$title$artist${durationMs / 1000}"
}

data class PlaybackInfo(
    val positionMs: Long,
    val updatedAtElapsedMs: Long,
    val playing: Boolean,
    val speed: Float,
) {
    fun currentMs(now: Long = SystemClock.elapsedRealtime()): Long {
        if (!playing) return positionMs
        val delta = ((now - updatedAtElapsedMs).coerceAtLeast(0L) * speed).toLong()
        return positionMs + delta
    }
}

sealed class LyricsState {
    object Idle : LyricsState()
    object Loading : LyricsState()
    object Loaded : LyricsState()
    object LoadedUnsynced : LyricsState()
    object NoLyrics : LyricsState()
}

object LyricsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _track = MutableStateFlow<TrackInfo?>(null)
    val track: StateFlow<TrackInfo?> = _track.asStateFlow()

    private val _lines = MutableStateFlow<List<LrcLine>>(emptyList())
    val lines: StateFlow<List<LrcLine>> = _lines.asStateFlow()

    private val _state = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private val _playback = MutableStateFlow<PlaybackInfo?>(null)
    val playback: StateFlow<PlaybackInfo?> = _playback.asStateFlow()

    private val _source = MutableStateFlow<String?>(null)
    val source: StateFlow<String?> = _source.asStateFlow()

    private var lastFetchedKey: String? = null
    private var fetchJob: Job? = null

    /** Optional internal-Spotify client. Set by MainActivity on startup. */
    @Volatile
    var spotifyInternal: SpotifyInternalClient? = null

    fun onTrack(info: TrackInfo) {
        val current = _track.value
        if (current?.key == info.key && lastFetchedKey == info.key) {
            return
        }
        _track.value = info
        if (lastFetchedKey == info.key) return
        lastFetchedKey = info.key
        _lines.value = emptyList()
        _state.value = LyricsState.Loading
        _source.value = null
        fetchJob?.cancel()
        fetchJob = scope.launch {
            // Try synced sources in priority order:
            // Spotify-internal (if sp_dc cookie configured), Musixmatch, LRCLIB, KuGou, QQ Music.
            val sp = spotifyInternal?.fetch(info.spotifyTrackId)
            if (lastFetchedKey != info.key) return@launch
            if (sp?.synced != null) { applyBundle(sp); return@launch }

            val mxm = MusixmatchClient.fetch(info.title, info.artist, info.durationMs)
            if (lastFetchedKey != info.key) return@launch
            if (mxm?.synced != null) { applyBundle(mxm); return@launch }

            val lrcLib = LrcLibClient.fetch(info.title, info.artist, info.album, info.durationMs)
            if (lastFetchedKey != info.key) return@launch
            if (lrcLib?.synced != null) { applyBundle(lrcLib); return@launch }

            val kuGou = KuGouClient.fetch(info.title, info.artist, info.durationMs)
            if (lastFetchedKey != info.key) return@launch
            if (kuGou?.synced != null) { applyBundle(kuGou); return@launch }

            val qq = QQMusicClient.fetch(info.title, info.artist)
            if (lastFetchedKey != info.key) return@launch
            if (qq?.synced != null) { applyBundle(qq); return@launch }

            // No synced anywhere. Try Mojim for plain text — best Chinese
            // plain-text coverage and a fresh shot at songs the others missed.
            val mojim = MojimClient.fetch(info.title, info.artist)
            if (lastFetchedKey != info.key) return@launch
            if (mojim?.plain != null) { applyPlain(mojim); return@launch }

            // Last resort: any plain text we collected along the way.
            val plain = sp?.plain ?: mxm?.plain ?: lrcLib?.plain ?: kuGou?.plain ?: qq?.plain
            if (plain != null) {
                _lines.value = plain.split("\n").map { LrcLine(0L, it) }
                _state.value = LyricsState.LoadedUnsynced
            } else {
                _lines.value = emptyList()
                _state.value = LyricsState.NoLyrics
            }
        }
    }

    private fun applyBundle(bundle: LyricsBundle) {
        val parsed = LrcParser.parse(bundle.synced)
        _lines.value = parsed
        _source.value = bundle.source
        _state.value = if (parsed.isEmpty()) LyricsState.NoLyrics else LyricsState.Loaded
    }

    private fun applyPlain(bundle: LyricsBundle) {
        val plain = bundle.plain ?: return
        _lines.value = plain.split("\n").map { LrcLine(0L, it) }
        _source.value = bundle.source
        _state.value = LyricsState.LoadedUnsynced
    }

    fun onPlayback(info: PlaybackInfo) {
        _playback.value = info
    }

    fun clear() {
        _track.value = null
        _lines.value = emptyList()
        _state.value = LyricsState.Idle
        _playback.value = null
        _source.value = null
        lastFetchedKey = null
        fetchJob?.cancel()
    }
}
