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

    private var lastFetchedKey: String? = null
    private var fetchJob: Job? = null

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
        fetchJob?.cancel()
        fetchJob = scope.launch {
            val resp = LrcLibClient.fetch(info.title, info.artist, info.album, info.durationMs)
            if (lastFetchedKey != info.key) return@launch
            when {
                resp?.synced != null -> {
                    val parsed = LrcParser.parse(resp.synced)
                    _lines.value = parsed
                    _state.value = if (parsed.isEmpty()) LyricsState.NoLyrics else LyricsState.Loaded
                }
                resp?.plain != null -> {
                    _lines.value = resp.plain.split("\n").map { LrcLine(0L, it) }
                    _state.value = LyricsState.LoadedUnsynced
                }
                else -> {
                    _lines.value = emptyList()
                    _state.value = LyricsState.NoLyrics
                }
            }
        }
    }

    fun onPlayback(info: PlaybackInfo) {
        _playback.value = info
    }

    fun clear() {
        _track.value = null
        _lines.value = emptyList()
        _state.value = LyricsState.Idle
        _playback.value = null
        lastFetchedKey = null
        fetchJob?.cancel()
    }
}
