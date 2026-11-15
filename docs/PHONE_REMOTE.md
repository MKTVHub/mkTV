# Phone remote

Status: **shipped (first version).** Implementation: `app/src/main/java/com/mktvhub/mktv/RemoteServer.kt` (server), `app/src/main/assets/remote.html` (phone page), `RemoteInput.kt` (request validation), and the `// ---- Phone remote (LAN web page, paired by QR code) ----` section of `BrowserActivity.kt` (panel, QR code via ZXing, key/text/open handling). Sections marked *Agreed contract* are fixed; *Recommended hardening* lists additions the implementation should adopt. See [Implementation status](#implementation-status) for where the code differs today.

## What it does

Typing on a TV with a D-pad is slow. The phone remote lets any phone on the same Wi-Fi control mkTV from its web browser, with no app to install.

1. On the TV: ⋮ → **Phone remote**.
2. mkTV starts a small HTTP server on port **8765** and shows a QR code (with the mkTV logo in the centre) for
   `http://<tv-ip>:8765/?t=<token>`, plus the address in text for typing by hand.
3. The phone scans the QR code and opens the remote page.
4. The remote page offers:
   - **Buttons or Touchpad** (the choice is remembered on the phone). Buttons: D-pad (▲▼◀▶) and OK. Touchpad: swipe = an arrow per ~60 px of travel (one axis per swipe, a short flick counts as one), tap = OK, two-finger tap or long-press = Back. In pointer navigation mode a drag moves the TV's pointer smoothly instead (faster finger, further pointer); on overlays, YouTube TV, watch mode and full screen it falls back to swipes.
   - Back, Home, Menu; play/pause, −10 s / +10 s, previous / next; volume −, mute, volume +; page up / page down.
   - **Type on TV**: live typing into the focused field on the TV (sent about 150 ms after you stop typing), **↵ Enter** to submit, **✕** to clear the TV field.
   - **Now playing** (only while the TV page has a big `<video>`): title, a seek bar with current time and duration (hidden for live streams), −10 s, play/pause, +10 s, full screen, and captions when the video has `<track>` subtitles.
   - **Your Home**: the TV's Popular, Favourites and Recently visited tiles (no Recent in private browsing; sections hidden on the TV stay hidden). Tap a tile to open it on the TV. Icons are the favicons the TV already saved, served by the TV; otherwise a coloured letter. **Open a website** is below the tiles.
   - Haptic tick on presses where the phone supports `navigator.vibrate`; a two-column layout in landscape and on desktop browsers; from a computer keyboard: arrows, Enter = OK, Backspace / Escape = Back (when the text box isn't focused).
5. Once opened, the phone remote listens whenever mkTV is on screen, including after the app restarts, and stops while mkTV is in the background. The port and pairing code stay the same, so a phone that has the remote page open keeps working.

## Protocol (*Agreed contract*)

Plain HTTP/1.1 on port 8765. Request and response bodies are JSON (`Content-Type: application/json`) except `GET /`.

### Authentication

Every API call must carry the pairing token, either as:

- query parameter `?t=<token>`, or
- header `X-Token: <token>`.

`GET /` also requires the token (it arrives via the QR code URL); the page then sends it on every API call. A missing or wrong token gets `401` (or `403`) and no other information.

The token is random and kept across restarts. **New code** in the Phone remote panel (or Settings → Phone remote → Unpair all phones) replaces it, so every old link stops working.

### Endpoints

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/` | | The remote page (HTML, self-contained, no external resources) |
| `POST` | `/api/key` | `{"key": "...", "action": "..."}` | `{"ok": true}` |
| `POST` | `/api/text` | `{"text": "...", "del": 0, "clear": false, "enter": false}` | `{"ok": true}` |
| `POST` | `/api/open` | `{"url": "..."}` | `{"ok": true}` |
| `POST` | `/api/move` | `{"dx": 12.5, "dy": -3}` | `{"ok": true}` |
| `POST` | `/api/media` | `{"action": "seek", "to": 93}`, `{"action": "speed", "value": 1.5}`, `{"action": "sleep", "value": 30}` | `{"ok": true}` |
| `GET` | `/api/state` | | `{"title", "url", "nav", "pointer", "sleep", "media"?}` |
| `GET` | `/api/home` | | `{"popular": [...], "favourites": [...], "recent": [...]}` |
| `GET` | `/api/icon?h=<host>` | | The TV's saved favicon (`image/png`), or `404` |

All `POST`s need `Content-Type: application/json` (otherwise `415`). Invalid input gets `400`.

**`/api/key`**

- `key`: one of `up`, `down`, `left`, `right`, `ok`, `back`, `home`, `menu`, `playpause`, `ff`, `rew`, `next`, `prev`, `volup`, `voldown`, `mute`, `pageup`, `pagedown`. Anything else is `400`.
- `action`: `down`, `up` or `press` (`press` = down then up; also the default if `action` is missing or unknown). Sending `down` and `up` separately lets the phone hold a key, so hold-OK long-press and hold-▼ bar jumps work as on the real remote.

Suggested mapping to Android key codes:

| key | KeyEvent |
|---|---|
| `up` `down` `left` `right` | `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT` |
| `ok` | `KEYCODE_DPAD_CENTER` |
| `back` | `KEYCODE_BACK` |
| `menu` | `KEYCODE_MENU` |
| `playpause` | `KEYCODE_MEDIA_PLAY_PAUSE` |
| `ff` / `rew` | `KEYCODE_MEDIA_FAST_FORWARD` / `KEYCODE_MEDIA_REWIND` |
| `next` / `prev` | `KEYCODE_MEDIA_NEXT` / `KEYCODE_MEDIA_PREVIOUS` |
| `pageup` / `pagedown` | `KEYCODE_PAGE_UP` / `KEYCODE_PAGE_DOWN` (scrolls the page at the pointer or highlight) |
| `home` | No key event. Opens the **mkTV home screen** (Android does not let apps receive or inject the system Home key) |
| `volup` / `voldown` / `mute` | No key event (apps can't inject volume keys). `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE / ADJUST_LOWER / ADJUST_TOGGLE_MUTE, FLAG_SHOW_UI)`, so the TV shows its own volume bar |

**`/api/text`**: types into whatever has focus on the TV (an app text field such as the address bar, or the web page, e.g. a search box). In order: `clear: true` empties the field (app fields directly; web fields with Ctrl+A, Delete), then `del` Backspaces (0 to 1,000), then `text` (up to 1,000 characters; characters without a key event, such as emoji, are inserted with `execCommand('insertText')`), then Enter if `enter: true`. All fields are optional.

Live typing is incremental: the phone remembers what it has sent, and on each change sends the Backspaces and new characters needed to turn that into the box's current value (the common prefix stays). Only one typing request is in flight at a time, so changes arrive in order. Enter submits and starts afresh. Limits: Backspaces only remove what the phone typed (✕ clears the whole field), and if focus moves to another field on the TV mid-word, the next change applies there.

**`/api/open`**: loads `url` in the current WebView, after the same normalisation as the address bar (so plain words become a search). The result must be an `http` or `https` URL (otherwise the TV shows "The phone can only open web pages"); `url` is capped at 2,048 characters.

**`/api/move`**: moves the pointer by `dx`, `dy` phone pixels (each clamped to ±400; non-numbers count as 0), scaled by about 1.8 dp per pixel at the Normal pointer speed (0.6× at Slow, 1.6× at Fast; Settings → Navigation → Pointer speed). Pushing past a screen edge scrolls the page, as with a held D-pad. Ignored unless `pointer` is true in `/api/state`.

**`/api/media`**: `action` is `seek` (with `to` in seconds, 0 to 172,800, clamped to the video's duration), `fullscreen` (the same full screen toggle as ▼ in watch mode) or `captions` (toggles the first `subtitles`/`captions` `<track>`; sites that draw their own captions are not covered). Play/pause and ±10 s use `/api/key` (`playpause`, `rew`, `ff`), the same path as the TV remote. `speed` with `value` one of `0.75`, `1`, `1.25`, `1.5`, `2` (sets the video's speed and remembers it for the site, as the TV's playback controls do; in memory only while private), and `sleep` with `value` one of `0` (off), `15`, `30`, `60` (minutes) or `-1` (pause at the end of the current video). Any other value, or a missing `value`, gets `400`.

**`/api/state`**: the current page title and URL, the navigation mode (`nav`: `jump` or `pointer`), `pointer` (true when a touchpad drag would move the pointer), `sleep`: `{"mode", "left"}` (`mode` is the sleep choice above, `0` when off; `left` is whole seconds until a timed sleep fires, else `0`), and `media` while the page has a big video: `{"has": true, "playing", "t", "d", "cc", "ccOn", "fs", "speed"}` (`d` is 0 for live streams; `speed` is the video's current playback rate). The page polls it every second while a video is present, every 2.5 s otherwise, and not at all while the phone tab is hidden.

**`/api/home`**: up to the TV's Popular, Favourites and 10 Recently visited entries, each `{"title", "url", "letter", "color", "icon"}`, where `icon` is a host to fetch from `/api/icon` (or empty). Recent is always empty in private browsing.

**`/api/icon?h=<host>`**: the favicon mkTV already saved for that host. `h` must be letters, digits, dots and dashes (so it can't escape the icon folder). The phone page uses it as an `<img>` source with `?t=` because images can't send headers.

### Example

```sh
TOKEN=...   # from the QR code
curl -X POST "http://192.168.1.50:8765/api/key?t=$TOKEN" -H 'Content-Type: application/json' \
     -d '{"key":"down","action":"press"}'
curl -X POST "http://192.168.1.50:8765/api/open" -H "X-Token: $TOKEN" -H 'Content-Type: application/json' \
     -d '{"url":"example.com"}'
curl "http://192.168.1.50:8765/api/state?t=$TOKEN"
```

## Integration rules

- **Same path as the real remote.** Key events from the phone must be injected on the UI thread into `BrowserActivity.dispatchKeyEvent` (and Back into the same back handling), so jump mode, pointer mode, watch mode, overlays and the YouTube TV passthrough behave exactly as with a physical remote. Do not add a second, parallel key handler.
- **Same navigation path.** `/api/open` must go through `normalizeInput()` and `load()`, so tracking stripping, user-agent selection and blocking all apply.
- **Private mode.** The remote works in private mode, but `/api/state` must not be logged, and nothing it returns may be persisted on either device by mkTV.

## Security model

### Threats considered

- Someone else on the same network sending keys, typing or opening pages on the TV.
- A web page on the phone or any device on the network trying to drive the TV from the browser (cross-site requests to `http://<tv-ip>:8765`).
- The server being reachable from outside the home network.
- The server being left running.

### Built-in protections (*Agreed contract*)

- **Token on every call.** Random per session; required on all API endpoints and the page. Without it the server does nothing.
- **LAN only.** The server is for the local network; it is not exposed through any relay or cloud service and never contacts the internet.
- **Foreground-only.** The server runs only while mkTV is on screen (after the user first opens Phone remote). The pairing token is random, 10 characters, stored in app-private preferences and kept across restarts.

### Recommended hardening

These are not yet in the agreed contract; the implementation should include them.

- Generate the token with `java.security.SecureRandom`, at least 128 bits, URL-safe encoding.
- Compare tokens in constant time (`MessageDigest.isEqual`).
- Reject requests whose source address is not loopback, private IPv4 (`10/8`, `172.16/12`, `192.168/16`), link-local, or IPv6 ULA/link-local.
- Accept JSON bodies only for `POST` (require `Content-Type: application/json`) so a cross-site HTML form cannot submit without a CORS preflight; send no `Access-Control-Allow-Origin` header.
- Check the `Host` header is the TV's IP and port, to block DNS-rebinding attacks.
- Cap request size (for example 8 KB) and `text` length (for example 1,000 characters). Accept only `http`/`https` URLs, or plain search text, in `/api/open`.
- Rate-limit per client (key presses are small; a few hundred per minute is plenty).
- Stop the server when the activity goes to the background or after a period without requests (for example 10 minutes), and when the user closes the Phone remote screen.
- Show a small on-TV indicator while a phone is connected, with a way to disconnect.
- Serve the remote page with `Cache-Control: no-store` and `Referrer-Policy: no-referrer`.

### Known limits

- **Plain HTTP.** Traffic is not encrypted. Anyone who can sniff the local network (shared or public Wi-Fi, a compromised router) can read the token and then control the TV for that session. Use it on a trusted home network.
- **The token is in the URL.** It will sit in the phone browser's history until the session ends. Ending the session invalidates it.
- **Anyone who sees the QR code** can join that session.
- **No pairing or user accounts.** Access is by possession of the current token only.

## Implementation status

As of 2026-11-15 (tested on an Android TV). Compared with this spec, `RemoteServer.kt`:

- **Matches:** endpoints and JSON shapes (plus the additions above); token via `?t=` or `X-Token` on every request including `GET /` and the new endpoints (wrong token: `403` with a short "scan the QR code" page); `home` opens the mkTV home screen (or the home page address); keys are injected through `BrowserActivity.dispatchKeyEvent`, and a held phone button auto-repeats after 450 ms like a real remote; `/api/open` goes through `normalizeInput()` and `load()`; the page is self-contained; responses send `Cache-Control: no-store` and `Referrer-Policy: no-referrer`; request bodies are capped at 64 KB; `POST`s require `Content-Type: application/json`. Request validation lives in `RemoteInput.kt` (unit-tested): key allowlist, `text` ≤ 1,000 characters, `url` ≤ 2,048 and http/https only after normalisation, numeric ranges for pointer moves and seeks, allowlists for media `speed` and `sleep` values (`RemoteInput.mediaValue`), and a strict host pattern for icons.
- **Port:** tries 8765, then 8766 to 8769 if the port is taken. The QR code always shows the port actually used.
- **Token:** 26 characters from a 31-character alphabet (about 128 bits) from `SecureRandom`, compared in constant time (`MessageDigest.isEqual`). 10-character tokens saved by 1.1.0 stay valid until the next New code.
- **Lifetime:** `Prefs.remoteEnabled` turns on the first time Phone remote opens; from then on `onStart` starts the server (retrying for a few seconds while the old socket closes) and `onStop` stops it. The token lives in `Prefs.remoteToken`. The address skips VPN tunnel interfaces. **New code** / **Unpair all phones** makes a new token. Settings → Phone remote has an on/off switch (off stops the server until Phone remote is opened again). Each new device (by IP address) gets a "New phone connected · <ip> · Unpair all" notice on the TV.
- **Devices tab:** the panel has **Connect** and **Devices (n)** tabs. Devices lists each address that used the current code (label, address, last activity), newest first, each with **Disconnect**. Disconnect drops the device and refuses its address until the next New code (`RemoteServer.devices`, `RemoteServer.disconnect`). New code needs a second press to confirm.
- **Network scope:** the server listens on all interfaces, with no source-address or `Host` header check.
- No rate limiting.
- Responses carry no CORS headers (good: other origins cannot read responses).

## Future work

- HTTPS with a self-signed certificate pinned through the QR code.
- A one-time pairing code shown on the TV instead of a long-lived URL token.
- The same protocol served by the webOS and Tizen versions, and a companion-only mode for platforms without a web engine (see [ROADMAP.md](ROADMAP.md)).
