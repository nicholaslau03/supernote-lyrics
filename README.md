# Supernote Lyrics

Minimal Android app for the Supernote Manta (Android 11+, e-ink). Shows real-time lyrics for whatever your Spotify account is playing **on any device** — phone, Mac, browser — pulled via Spotify's Web API. Black-on-white, vertical, current line bold + centered, snap-on-line-change (e-ink friendly).

## Setup (one-time, ~3 min)

You need a Spotify "Client ID" for the app to talk to Spotify on your behalf. It's free.

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) and log in with your Spotify account.
2. Click **Create app**.
3. Fill in:
   - **App name:** anything (e.g. *Supernote Lyrics*)
   - **App description:** anything
   - **Redirect URI:** `supernotelyrics://callback`
   - **Which API/SDKs are you planning to use?** Check **Web API**.
4. Save. Open the app's settings page and copy the **Client ID** (long hex string).

You'll paste this Client ID into the app on first launch.

## Build the APK (via GitHub Actions)

You don't need Android Studio locally — the included workflow builds in the cloud.

```sh
cd "Supernote lyrics"
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin git@github.com:YOUR_USERNAME/supernote-lyrics.git
git push -u origin main
```

On GitHub: **Actions** tab → wait for the green check → open the run → download **SupernoteLyrics-debug-apk** artifact.

## Sideload onto the Manta

1. On the Manta: **Settings → Security and Privacy → Sideloading** → on.
2. Transfer `SupernoteLyrics-debug.apk` to the Manta.
3. Tap it in the file browser to install.
4. Open the app. Paste your Client ID → **Save and continue** → **Log in with Spotify**.
5. The Manta's browser opens Spotify's login page. Sign in, click **Agree**. You'll be redirected back to the app.
6. Done. Start a song on your phone/Mac/etc. — lyrics appear within ~2 seconds.

## How it works

- **Currently playing:** polls `GET /v1/me/player/currently-playing` every 2s (when playing) / 8s (when idle). Position is interpolated locally between polls so the bold-line snap stays accurate.
- **Auth:** OAuth 2.0 Authorization Code with PKCE. No client secret. Tokens stored in app-private prefs; refresh token is used to renew the access token automatically.
- **Lyrics:** [LRCLIB](https://lrclib.net) free open API (no key, ~3M songs). Returns `[mm:ss.xx]` synced lyrics; parsed locally.
- **Rendering:** standard Views (no Compose), only the previous + new active line are restyled on each line change → ~2 view updates per beat, no full-screen redraw, no smooth animation.

## Project layout

```
app/src/main/java/com/supernote/lyrics/
  MainActivity.kt       — onboarding, OAuth callback, lyrics rendering, 200ms tick
  SpotifyAuth.kt        — PKCE helpers + token exchange
  SpotifyClient.kt      — poll /currently-playing, refresh tokens on 401
  LyricsRepository.kt   — shared StateFlow + LRCLIB orchestration
  LrcLibClient.kt       — HTTP GET to lrclib.net/api/get
  LrcParser.kt          — [mm:ss.xx] → (timeMs, text)
  Prefs.kt              — client_id + tokens
app/src/main/res/layout/
  activity_main.xml     — three states: setup, login, lyrics
  item_lyric_line.xml   — single lyric TextView
.github/workflows/build.yml
```

## Troubleshooting

- **"No browser available" when tapping Log in:** the Manta needs a browser app that handles `https://` and the `supernotelyrics://` redirect. The built-in Supernote Browser should work. If not, install a lightweight browser via Aurora Store / F-Droid first.
- **Login completes but app doesn't catch the redirect:** check that the Redirect URI in your Spotify app dashboard is exactly `supernotelyrics://callback` (no trailing slash, no extra path).
- **"No synced lyrics found":** LRCLIB doesn't have synced lyrics for that song. Many obscure/regional tracks aren't in the database. Lyrics will only appear for songs that have *synced* entries, not plain text.
- **Want a different Client ID later:** menu (⋮ top-right of the lyrics screen) → **Use a different Client ID**.
- **Logging out:** menu → **Disconnect Spotify**.

## Rate-limit & ToS notes

Spotify's Web API allows roughly 180 req/min per app. This app does 30/min when actively playing (2s poll), 7.5/min when idle. Well under. Your Client ID is yours; don't share the APK with someone else's ID baked in.
