# Phone remote

Status: **shipped (first version).** Implementation: `app/src/main/java/com/mktvhub/mktv/RemoteServer.kt` (server), `app/src/main/assets/remote.html` (phone page), and the `// ---- Phone remote (LAN web page, paired by QR code) ----` section of `BrowserActivity.kt` (panel, QR code via ZXing, key/text/open handling). Sections marked *Agreed contract* are fixed; *Recommended hardening* lists additions the implementation should adopt. See [Implementation status](#implementation-status) for where the code differs today.

## What it does

Typing on a TV with a D-pad is slow. The phone remote lets any phone on the same Wi-Fi control mkTV from its web browser, with no app to install.

1. On the TV: ⋮ → **Phone remote**.
2. mkTV starts a small HTTP server on port **8765** and shows a QR code for
   `http://<tv-ip>:8765/?t=<token>`, plus the address in text for typing by hand.
3. The phone scans the QR code and opens the remote page.
4. The remote page offers: D-pad (▲▼◀▶), OK, Back, Home, Menu, play/pause, seek −10 s / +10 s, previous / next, a keyboard box that types text into the focused field on the TV, and "open URL".
5. The server keeps running after the QR panel closes, so the phone stays connected. It stops whenever mkTV leaves the screen and restarts with the same address and token when mkTV comes back. Restarting the app makes a new token.

## Protocol (*Agreed contract*)

Plain HTTP/1.1 on port 8765. Request and response bodies are JSON (`Content-Type: application/json`) except `GET /`.

### Authentication

Every API call must carry the session token, either as:

- query parameter `?t=<token>`, or
- header `X-Token: <token>`.

`GET /` also requires the token (it arrives via the QR code URL); the page then sends it on every API call. A missing or wrong token gets `401` (or `403`) and no other information.

The token is random and generated once per app launch, so a QR code from an earlier launch stops working.

### Endpoints

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/` | | The remote page (HTML, self-contained, no external resources) |
| `POST` | `/api/key` | `{"key": "...", "action": "..."}` | `{"ok": true}` |
| `POST` | `/api/text` | `{"text": "...", "enter": false}` | `{"ok": true}` |
| `POST` | `/api/open` | `{"url": "..."}` | `{"ok": true}` |
| `GET` | `/api/state` | | `{"title": "...", "url": "..."}` |

**`/api/key`**

- `key`: one of `up`, `down`, `left`, `right`, `ok`, `back`, `home`, `menu`, `playpause`, `ff`, `rew`, `next`, `prev`.
- `action`: `down`, `up` or `press` (`press` = down then up; also the default if `action` is missing). Sending `down` and `up` separately lets the phone hold a key, so hold-OK long-press and hold-▼ bar jumps work as on the real remote.

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
| `home` | No key event. Opens the **mkTV home screen** (Android does not let apps receive or inject the system Home key) |

**`/api/text`**: types `text` into whatever has focus on the TV (an app text field such as the address bar, or the web page, e.g. a search box). Optional `enter: true` presses Enter afterwards (an extension added by the implementation; `enter` defaults to `false`).

**`/api/open`**: loads `url` in the current WebView, after the same normalisation as the address bar (so plain words become a search).

**`/api/state`**: the current page title and URL, so the phone can show what the TV is on.

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
- **Session-scoped.** The server only runs while Phone remote is active. A new session gets a new token.

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

- **Matches:** endpoints and JSON shapes; token via `?t=` or `X-Token` on every request including `GET /` (wrong token: `403` with a short "scan the QR code" page); `home` opens the mkTV home screen (or the home page address); keys are injected through `BrowserActivity.dispatchKeyEvent`, and a held phone button auto-repeats after 450 ms like a real remote; `/api/open` goes through `normalizeInput()` and `load()`; the page is self-contained; responses send `Cache-Control: no-store`; request bodies are capped at 64 KB.
- **Port:** tries 8765, then 8766 to 8769 if the port is taken. The QR code always shows the port actually used.
- **Token:** 10 characters from a 31-character alphabet (about 49 bits) from `SecureRandom`, compared in constant time (`MessageDigest.isEqual`). Recommended: raise to at least 128 bits.
- **Session lifetime:** one token per `BrowserActivity` instance. The server stops in `onStop` and restarts in `onStart` on the same port (`reuseAddress`), so the QR code stays valid while the app is open. It stops for good in `onDestroy`. There is no "disconnect phone" button yet.
- **Network scope:** the server listens on all interfaces, with no source-address or `Host` header check, and does not require `Content-Type: application/json`.
- No rate limiting, no `text` length cap, no URL scheme check in `/api/open` beyond the address-bar normalisation.
- Responses carry no CORS headers (good: other origins cannot read responses).

## Future work

- HTTPS with a self-signed certificate pinned through the QR code.
- A one-time pairing code shown on the TV instead of a long-lived URL token.
- The same protocol served by the webOS and Tizen versions, and a companion-only mode for platforms without a web engine (see [ROADMAP.md](ROADMAP.md)).
