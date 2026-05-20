package com.supernote.lyrics

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var spotifyClient: SpotifyClient

    private lateinit var lyricsScreen: View
    private lateinit var setupScreen: View
    private lateinit var loginScreen: View
    private lateinit var menuButton: Button

    private lateinit var clientIdInput: EditText
    private lateinit var saveClientIdButton: Button
    private lateinit var openDashboardButton: Button
    private lateinit var loginButton: Button
    private lateinit var resetSetupButton: Button
    private lateinit var loginErrorText: TextView

    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var statusText: TextView
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsContainer: LinearLayout

    private var lineViews: List<TextView> = emptyList()
    private var currentIndex: Int = -1
    private var renderedTrackKey: String? = null
    private var pollJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateActiveLine()
            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        spotifyClient = SpotifyClient(prefs)

        lyricsScreen = findViewById(R.id.lyricsScreen)
        setupScreen = findViewById(R.id.setupScreen)
        loginScreen = findViewById(R.id.loginScreen)
        menuButton = findViewById(R.id.menuButton)

        clientIdInput = findViewById(R.id.clientIdInput)
        saveClientIdButton = findViewById(R.id.saveClientIdButton)
        openDashboardButton = findViewById(R.id.openDashboardButton)
        loginButton = findViewById(R.id.loginButton)
        resetSetupButton = findViewById(R.id.resetSetupButton)
        loginErrorText = findViewById(R.id.loginErrorText)

        trackTitle = findViewById(R.id.trackTitle)
        trackArtist = findViewById(R.id.trackArtist)
        statusText = findViewById(R.id.statusText)
        lyricsScroll = findViewById(R.id.lyricsScroll)
        lyricsContainer = findViewById(R.id.lyricsContainer)

        clientIdInput.setText(prefs.clientId.orEmpty())

        saveClientIdButton.setOnClickListener {
            val id = clientIdInput.text.toString().trim()
            if (id.isBlank()) {
                Toast.makeText(this, R.string.no_client_id, Toast.LENGTH_SHORT).show()
            } else {
                prefs.clientId = id
                applyScreen()
            }
        }
        openDashboardButton.setOnClickListener {
            openUrl("https://developer.spotify.com/dashboard")
        }
        loginButton.setOnClickListener { startLoginFlow() }
        resetSetupButton.setOnClickListener {
            prefs.clearTokens()
            prefs.clientId = null
            LyricsRepository.clear()
            applyScreen()
        }
        menuButton.setOnClickListener { showMenu() }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    LyricsRepository.track.collect { renderTrack(it) }
                }
                launch {
                    LyricsRepository.lines.collect { renderLines(it) }
                }
                launch {
                    LyricsRepository.state.collect { renderState(it) }
                }
            }
        }

        handleAuthRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        applyScreen()
        startPollingIfReady()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        stopPolling()
    }

    private fun applyScreen() {
        when {
            prefs.clientId.isNullOrBlank() -> {
                setupScreen.visibility = View.VISIBLE
                loginScreen.visibility = View.GONE
                lyricsScreen.visibility = View.GONE
                menuButton.visibility = View.GONE
            }
            !prefs.isLoggedIn -> {
                setupScreen.visibility = View.GONE
                loginScreen.visibility = View.VISIBLE
                lyricsScreen.visibility = View.GONE
                menuButton.visibility = View.GONE
            }
            else -> {
                setupScreen.visibility = View.GONE
                loginScreen.visibility = View.GONE
                lyricsScreen.visibility = View.VISIBLE
                menuButton.visibility = View.VISIBLE
                if (LyricsRepository.track.value == null) {
                    statusText.text = getString(R.string.waiting_for_spotify)
                    statusText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startLoginFlow() {
        val clientId = prefs.clientId
        if (clientId.isNullOrBlank()) {
            applyScreen()
            return
        }
        val verifier = SpotifyAuth.newCodeVerifier()
        val challenge = SpotifyAuth.codeChallenge(verifier)
        val state = SpotifyAuth.newState()
        prefs.pendingVerifier = verifier
        prefs.pendingState = state
        loginErrorText.visibility = View.GONE
        openUrl(SpotifyAuth.buildAuthUrl(clientId, challenge, state))
    }

    private fun handleAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "supernotelyrics") return
        val error = data.getQueryParameter("error")
        if (error != null) {
            showLoginError(error)
            return
        }
        val code = data.getQueryParameter("code") ?: return
        val state = data.getQueryParameter("state")
        val savedState = prefs.pendingState
        val verifier = prefs.pendingVerifier
        if (savedState == null || verifier == null || state != savedState) {
            showLoginError("state mismatch")
            return
        }
        val clientId = prefs.clientId ?: return
        // Consume one-shot intent extras so we don't re-process on rotate
        intent.data = null
        lifecycleScope.launch {
            val tr = SpotifyAuth.exchangeCode(clientId, code, verifier)
            if (tr != null) {
                prefs.saveTokens(tr.accessToken, tr.refreshToken, tr.expiresInSec)
                prefs.pendingVerifier = null
                prefs.pendingState = null
                applyScreen()
                startPollingIfReady()
            } else {
                showLoginError(getString(R.string.login_failed))
            }
        }
    }

    private fun showLoginError(message: String) {
        loginErrorText.text = message
        loginErrorText.visibility = View.VISIBLE
        applyScreen()
        if (prefs.clientId != null && !prefs.isLoggedIn) {
            loginScreen.visibility = View.VISIBLE
            lyricsScreen.visibility = View.GONE
        }
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.logout), getString(R.string.reset_setup))) { _, which ->
                when (which) {
                    0 -> {
                        stopPolling()
                        prefs.clearTokens()
                        LyricsRepository.clear()
                        applyScreen()
                    }
                    1 -> {
                        stopPolling()
                        prefs.clearTokens()
                        prefs.clientId = null
                        LyricsRepository.clear()
                        applyScreen()
                    }
                }
            }
            .show()
    }

    private fun startPollingIfReady() {
        if (!prefs.isLoggedIn) return
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            spotifyClient.pollLoop()
            // pollLoop returned -> tokens cleared
            applyScreen()
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Lyrics rendering ----------------

    private fun renderTrack(info: TrackInfo?) {
        trackTitle.text = info?.title.orEmpty()
        trackArtist.text = info?.artist.orEmpty()
    }

    private fun renderState(state: LyricsState) {
        when (state) {
            LyricsState.Idle -> {
                if (prefs.isLoggedIn && LyricsRepository.track.value == null) {
                    statusText.text = getString(R.string.waiting_for_spotify)
                    statusText.visibility = View.VISIBLE
                }
            }
            LyricsState.Loading -> {
                statusText.text = getString(R.string.loading_lyrics)
                statusText.visibility = View.VISIBLE
            }
            LyricsState.Loaded, LyricsState.LoadedUnsynced -> {
                statusText.visibility = View.GONE
            }
            LyricsState.NoLyrics -> {
                statusText.text = getString(R.string.no_lyrics)
                statusText.visibility = View.VISIBLE
            }
        }
    }

    private fun renderLines(lines: List<LrcLine>) {
        val trackKey = LyricsRepository.track.value?.key
        if (trackKey == renderedTrackKey && lineViews.size == lines.size) return
        renderedTrackKey = trackKey
        lyricsContainer.removeAllViews()
        currentIndex = -1
        if (lines.isEmpty()) {
            lineViews = emptyList()
            return
        }
        val inflater = LayoutInflater.from(this)
        lineViews = lines.map { line ->
            val tv = inflater.inflate(R.layout.item_lyric_line, lyricsContainer, false) as TextView
            tv.text = if (line.text.isBlank()) " " else line.text
            lyricsContainer.addView(tv)
            tv
        }
        lyricsScroll.post { lyricsScroll.scrollTo(0, 0) }
    }

    private fun updateActiveLine() {
        if (lyricsScreen.visibility != View.VISIBLE) return
        val lines = LyricsRepository.lines.value
        val pb = LyricsRepository.playback.value
        if (lines.isEmpty() || pb == null || lineViews.isEmpty()) return
        val nowMs = pb.currentMs()
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= nowMs) idx = i else break
        }
        if (idx < 0) idx = 0
        if (idx == currentIndex) return
        applyActiveStyle(previous = currentIndex, next = idx)
        currentIndex = idx
        scrollToLine(idx)
    }

    private fun applyActiveStyle(previous: Int, next: Int) {
        val gray = ContextCompat.getColor(this, R.color.ink_gray)
        val black = ContextCompat.getColor(this, R.color.ink_black)
        if (previous in lineViews.indices) {
            val prev = lineViews[previous]
            prev.setTypeface(null, Typeface.NORMAL)
            prev.setTextColor(gray)
        }
        if (next in lineViews.indices) {
            val active = lineViews[next]
            active.setTypeface(null, Typeface.BOLD)
            active.setTextColor(black)
        }
    }

    private fun scrollToLine(idx: Int) {
        val tv = lineViews.getOrNull(idx) ?: return
        tv.post {
            val targetY = tv.top - (lyricsScroll.height / 2) + (tv.height / 2)
            lyricsScroll.scrollTo(0, targetY.coerceAtLeast(0))
        }
    }
}
