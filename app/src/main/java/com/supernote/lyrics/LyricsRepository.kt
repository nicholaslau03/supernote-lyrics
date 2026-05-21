package com.supernote.lyrics

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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

enum class SourceStatus {
    /** Not yet started (used briefly before launch). */
    Idle,

    /** Network call in flight. */
    Searching,

    /** Source returned synced or plain lyrics for this track. */
    Found,

    /** Source returned nothing (404 / not in their catalogue / cookie missing). */
    NotFound,
}

object LyricsRepository {

    /** Display order for the chain in the menu, and priority for plain-text fallback. */
    val SOURCE_NAMES = listOf("Spotify", "Musixmatch", "LRCLIB", "KuGou", "QQMusic", "Mojim")

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

    private val _sourceStates = MutableStateFlow<Map<String, SourceStatus>>(emptyMap())
    val sourceStates: StateFlow<Map<String, SourceStatus>> = _sourceStates.asStateFlow()

    private var lastFetchedKey: String? = null
    private var fetchJob: Job? = null

    /** Results from each source for the current track. Used to switch on-demand. */
    private val cachedResults = ConcurrentHashMap<String, LyricsBundle>()

    /** Optional internal-Spotify client. Set by MainActivity on startup. */
    @Volatile
    var spotifyInternal: SpotifyInternalClient? = null

    fun onTrack(info: TrackInfo) {
        val current = _track.value
        if (current?.key == info.key && lastFetchedKey == info.key) return
        _track.value = info
        if (lastFetchedKey == info.key) return
        lastFetchedKey = info.key
        _lines.value = emptyList()
        _state.value = LyricsState.Loading
        _source.value = null
        _sourceStates.value = SOURCE_NAMES.associateWith { SourceStatus.Searching }
        cachedResults.clear()
        fetchJob?.cancel()

        val trackKey = info.key
        fetchJob = scope.launch {
            // First-to-return wins the displayed slot. Other sources keep running
            // so we can show "Found" badges in the menu even after the bold one
            // has been chosen. Synced beats plain — plain is only used if every
            // source has reported.
            val applied = AtomicBoolean(false)
            val plainCandidates = ConcurrentHashMap<String, LyricsBundle>()

            suspend fun tryOne(name: String, fetcher: suspend () -> LyricsBundle?) {
                val result = try { fetcher() } catch (_: Exception) { null }
                if (lastFetchedKey != trackKey) return
                when {
                    result?.synced != null -> {
                        cachedResults[name] = result
                        markStatus(name, SourceStatus.Found)
                        if (applied.compareAndSet(false, true)) applyBundle(result)
                    }
                    result?.plain != null -> {
                        cachedResults[name] = result
                        markStatus(name, SourceStatus.Found)
                        plainCandidates[name] = result
                    }
                    else -> markStatus(name, SourceStatus.NotFound)
                }
            }

            val jobs = listOf(
                launch { tryOne("Spotify") { spotifyInternal?.fetch(info.spotifyTrackId) } },
                launch { tryOne("Musixmatch") { MusixmatchClient.fetch(info.title, info.artist, info.durationMs) } },
                launch { tryOne("LRCLIB") { LrcLibClient.fetch(info.title, info.artist, info.album, info.durationMs) } },
                launch { tryOne("KuGou") { KuGouClient.fetch(info.title, info.artist, info.durationMs) } },
                launch { tryOne("QQMusic") { QQMusicClient.fetch(info.title, info.artist) } },
                launch { tryOne("Mojim") { MojimClient.fetch(info.title, info.artist) } },
            )
            jobs.joinAll()

            if (lastFetchedKey != trackKey) return@launch
            if (!applied.get()) {
                // Use first plain in priority order.
                val plain = SOURCE_NAMES.firstNotNullOfOrNull { plainCandidates[it] }
                if (plain != null) {
                    applyPlain(plain)
                } else {
                    _lines.value = emptyList()
                    _state.value = LyricsState.NoLyrics
                }
            }
        }
    }

    private fun markStatus(name: String, status: SourceStatus) {
        _sourceStates.value = _sourceStates.value.toMutableMap().apply { put(name, status) }
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

    /**
     * Manually switch to a different source's cached result. Used when the
     * user taps an underlined source in the chain. No-op if that source
     * never returned anything for the current track.
     */
    fun useSource(name: String) {
        val bundle = cachedResults[name] ?: return
        if (bundle.synced != null) applyBundle(bundle) else applyPlain(bundle)
    }

    /**
     * Force a fresh fetch for the currently-playing track (re-runs all
     * sources, ignores cache). Used by the toolbar refresh button when the
     * user wants the latest result immediately.
     */
    fun refresh() {
        val info = _track.value ?: return
        lastFetchedKey = null  // bypass the dedup check
        onTrack(info)
    }

    fun clear() {
        _track.value = null
        _lines.value = emptyList()
        _state.value = LyricsState.Idle
        _playback.value = null
        _source.value = null
        _sourceStates.value = emptyMap()
        cachedResults.clear()
        lastFetchedKey = null
        fetchJob?.cancel()
    }
}
