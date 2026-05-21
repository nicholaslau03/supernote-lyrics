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
        LyricsRepository.spotifyInternal = SpotifyInternalClient(prefs)
        ChineseConverter.load(this)

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
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_menu, null)
        view.findViewById<TextView>(R.id.sourceLabel).text = buildSourceChain()

        val simplifyTv = view.findViewById<TextView>(R.id.menuSimplify)
        simplifyTv.text = if (prefs.simplifyChinese)
            getString(R.string.simplify_on) else getString(R.string.simplify_off)

        val dialog = AlertDialog.Builder(this).setView(view).create()

        simplifyTv.setOnClickListener {
            prefs.simplifyChinese = !prefs.simplifyChinese
            renderTrack(LyricsRepository.track.value)
            applyTextStyle()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menuSmaller).setOnClickListener {
            prefs.textSizeSp = prefs.textSizeSp - Prefs.TEXT_STEP_SP
            applyTextStyle()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menuLarger).setOnClickListener {
            prefs.textSizeSp = prefs.textSizeSp + Prefs.TEXT_STEP_SP
            applyTextStyle()
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.menuSetupSpotify).setOnClickListener {
            dialog.dismiss()
            showSpDcDialog()
        }
        view.findViewById<TextView>(R.id.menuLogout).setOnClickListener {
            dialog.dismiss()
            stopPolling()
            prefs.clearTokens()
            LyricsRepository.clear()
            applyScreen()
        }
        view.findViewById<TextView>(R.id.menuReset).setOnClickListener {
            dialog.dismiss()
            stopPolling()
            prefs.clearTokens()
            prefs.clientId = null
            LyricsRepository.clear()
            applyScreen()
        }
        view.findViewById<TextView>(R.id.menuQuit).setOnClickListener {
            dialog.dismiss()
            quitApp()
        }
        dialog.show()
    }

    /**
     * Build the source-chain label shown at the top of the menu, e.g.
     *   "Spotify → Musixmatch → LRCLIB → KuGou → QQMusic → Mojim"
     * Bold = source whose lyrics are currently being displayed.
     * Underline = source that returned anything (synced or plain) for this song.
     */
    private fun buildSourceChain(): CharSequence {
        val activeSource = LyricsRepository.source.value
        val states = LyricsRepository.sourceStates.value
        if (states.isEmpty() && activeSource == null) {
            return getString(R.string.source_unknown)
        }
        val ssb = android.text.SpannableStringBuilder()
        LyricsRepository.SOURCE_NAMES.forEachIndexed { i, name ->
            val start = ssb.length
            ssb.append(name)
            val end = ssb.length
            if (states[name] == SourceStatus.Found) {
                ssb.setSpan(
                    android.text.style.UnderlineSpan(),
                    start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (name == activeSource) {
                ssb.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (i < LyricsRepository.SOURCE_NAMES.size - 1) ssb.append(" → ")
        }
        return ssb
    }

    private fun quitApp() {
        stopPolling()
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun showSpDcDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_sp_dc, null)
        val input = view.findViewById<EditText>(R.id.spDcInput)
        val status = view.findViewById<TextView>(R.id.spDcStatus)
        prefs.spDcCookie?.let {
            input.setText(it)
            status.text = "Currently set (${it.length} chars)"
        }
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    prefs.spDcCookie = null
                    Toast.makeText(this, R.string.sp_dc_cleared, Toast.LENGTH_SHORT).show()
                } else {
                    prefs.spDcCookie = value
                    Toast.makeText(this, R.string.sp_dc_saved, Toast.LENGTH_SHORT).show()
                }
                // Force re-fetch of current track so the new source is tried immediately.
                LyricsRepository.track.value?.let { current ->
                    LyricsRepository.clear()
                    LyricsRepository.onTrack(current)
                }
            }
            .setNeutralButton("Clear") { _, _ ->
                prefs.spDcCookie = null
                Toast.makeText(this, R.string.sp_dc_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Convert a piece of text for display, honoring the T→S preference. */
    private fun displayText(s: String?): String {
        val v = s.orEmpty()
        return if (prefs.simplifyChinese) ChineseConverter.convert(v) else v
    }

    /**
     * Re-apply per-view text properties (size + conversion) to existing lyric
     * line TextViews without rebuilding the list — used when the user changes
     * the size or T→S toggle. The current line stays bold/black.
     */
    private fun applyTextStyle() {
        val sizeSp = prefs.textSizeSp.toFloat()
        val lines = LyricsRepository.lines.value
        lineViews.forEachIndexed { i, tv ->
            tv.textSize = sizeSp
            val raw = lines.getOrNull(i)?.text.orEmpty()
            tv.text = if (raw.isBlank()) " " else displayText(raw)
        }
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
        trackTitle.text = displayText(info?.title)
        trackArtist.text = displayText(info?.artist)
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
        val unsynced = LyricsRepository.state.value == LyricsState.LoadedUnsynced
        val defaultColor = ContextCompat.getColor(
            this,
            if (unsynced) R.color.ink_black else R.color.ink_gray
        )
        val inflater = LayoutInflater.from(this)
        val sizeSp = prefs.textSizeSp.toFloat()
        lineViews = lines.map { line ->
            val tv = inflater.inflate(R.layout.item_lyric_line, lyricsContainer, false) as TextView
            tv.textSize = sizeSp
            tv.setTextColor(defaultColor)
            tv.text = if (line.text.isBlank()) " " else displayText(line.text)
            lyricsContainer.addView(tv)
            tv
        }
        lyricsScroll.post { lyricsScroll.scrollTo(0, 0) }
    }

    private fun updateActiveLine() {
        if (lyricsScreen.visibility != View.VISIBLE) return
        // Plain-text fallback: no sync, no highlight, no auto-scroll.
        if (LyricsRepository.state.value == LyricsState.LoadedUnsynced) return
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
