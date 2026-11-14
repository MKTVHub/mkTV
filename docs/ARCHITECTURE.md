# Architecture

mkTV is a single-module Android app (`:app`, package `com.mktvhub.mktv`). It is a **native shell** around the system **WebView**, plus a **JavaScript layer** injected into every page. This document explains how the pieces fit. For what each feature does, see [FEATURES.md](FEATURES.md).

## Components

```
+-------------------------------------------------------------+
| BrowserActivity (native shell)                              |
|                                                             |
|  topBar/toolbar   shieldPanel   menuPanel   shortcutsPanel  |
|  settingsPanel    snackbar      watchDock   watchFlash      |
|                                                             |
|  webContainer ── WebView (normal)  or  WebView (private)    |
|                   │  page_script.js  (every page)           |
|                   │  yt_clean.js     (YouTube origins)      |
|  fullscreenContainer (page's own fullscreen view)           |
|  CursorView (pointer arrow / jump-mode ring, on top)        |
+-------------------------------------------------------------+
        │ uses                         │ starts
   Prefs, AdBlocker, HostRules     PlayerActivity (Media3 ExoPlayer)
```

| File | Responsibility |
|---|---|
| `BrowserActivity.kt` | Everything user-facing: WebView setup, request policy, input routing, overlays, home screen, settings, watch mode, private browsing |
| `CursorView.kt` | A non-focusable `View` on top of everything. Pointer mode: draws an arrow, moves it on a `Choreographer` loop with acceleration, reports edge pushes for scrolling, auto-hides after 6 s. Jump mode: draws a rounded focus ring around a rectangle |
| `AdBlocker.kt` | Builds the blocked host set from `assets/adhosts.txt` plus a downloaded hosts file cached at `filesDir/remote_hosts.txt`. The set is swapped atomically (`@Volatile`) so WebView's network threads can read it |
| `HostRules.kt` | Android-free helpers, unit-tested: `isBlocked` (host or any parent domain), `siteOf` (approximate registrable domain), `sameSite`, `parseHosts`, `isStreamUrl`, `isVariantOf`, `stripTracking` |
| `Prefs.kt` | All persisted settings and lists in one `SharedPreferences` file named `browser` |
| `RemoteServer.kt` | Phone remote HTTP server (in progress). Raw `ServerSocket`, one thread per connection, token check, calls back into `BrowserActivity` through `RemoteServer.Listener` |
| `PlayerActivity.kt` | Plays one HLS or DASH URL with ExoPlayer, forwarding the Referer, Origin, Cookie and User-Agent the page used |
| `page_script.js` | Four IIFEs: cosmetic filters and click-trap sweep (plus GPC), jump-mode engine (`window.__sp`), watch-mode video helpers (same object), auto-unmute |
| `yt_clean.js` | YouTube-only ad removal |

## Native shell and injected JS

### Injection

At WebView creation (`createWebView`):

- If the WebView supports `DOCUMENT_START_SCRIPT`, `page_script.js` is registered for all origins with `WebViewCompat.addDocumentStartJavaScript`, so it runs before page scripts in every frame. If "Block ads" is on, `yt_clean.js` is registered for `www.youtube.com`, `m.youtube.com`, `youtube.com`, `music.youtube.com` and `www.youtube-nocookie.com`. If auto-unmute is off, a one-line `window.__spNoUnmute = true` is registered first.
- On older WebViews the scripts are run with `evaluateJavascript` in `onPageStarted` (and `page_script.js` again in `onPageFinished`). This is later in the page lifecycle, so YouTube JSON pruning in particular is less effective there.

Script blocks guard themselves against double injection (`window.__tvbClean`, `window.__sp`, `window.__spUnmute`, `window.__spYt`). The jump-mode block runs in the top frame only.

### Native to JS calls

There is no `addJavascriptInterface`. All communication is Kotlin calling `evaluateJavascript` and parsing the returned JSON string (`parseJs`). The page cannot call the app.

| Call | Returns | Used by |
|---|---|---|
| `__sp.move(dir)` with `left/right/up/down/barUp/barDown` | `{x,y,w,h,iw}` rect, `{edge:dir}` or `{nobar:where}` | `jump()` |
| `__sp.rect()` | rect of the current element or `null` | `pollRing`, re-measure after a jump |
| `__sp.bars()` | `{top,bottom}` fixed bars present | one-time hint per host |
| `__sp.clear()` | | leaving watch mode |
| `__sp.media()` | `{has,playing,vertical}` for the largest visible video | `checkWatch()` |
| `__sp.toggle()` / `seek(s)` / `feed(step)` / `fullscreen()` | state strings | watch mode and media keys |

`iw` is the page's `innerWidth` in CSS px. `showRingFor` scales the rect by `webView.width / iw`, offsets it by the WebView's position and clips it to the web area, then hands it to `CursorView.showRing`.

### Polling

While the page is active, `pollRing` runs every 350 ms: it re-reads `__sp.rect()` so the ring follows lazy-loading layouts, and every third tick runs `checkWatch()` to enter or leave watch mode.

## Input

### Key routing order

`BrowserActivity.dispatchKeyEvent`, first match wins:

1. Soft keyboard visible: default handling.
2. `!pageActive()` (focus in the toolbar, an overlay, the snackbar or an `EditText`): default TV focus navigation. ▼ from the address field or a toolbar button calls `leaveToPage()`.
3. The page is showing its own fullscreen view (`customView != null`): `handleMediaKey`, then `handleWatchKey` if watching.
4. Fullscreen and jump mode: OK becomes a tap at the screen centre; other keys go to the player.
5. YouTube TV interface (`siteRemote`, i.e. `youtube.com/tv*`): `handleSiteRemote` forwards arrows, OK and media keys to the WebView unchanged. Back: short press sends Escape to the page, long press opens the mkTV home screen. Menu falls through.
6. `handleMediaKey`: play/pause, FF/REW (±10 s), next/previous (feed step) on any page with a video.
7. `handleWatchKey` when watching.
8. Menu or Bookmark key: toggle the ⋮ menu (on key up). Channel ± and Page Up/Down: scroll.
9. Jump mode: arrows call `jump(dir)`. The first auto-repeat of ▲/▼ (`repeatCount == 1`) jumps to the site's fixed top or bottom bar once per hold. OK taps the ring centre; with no ring it first picks a start element.
10. Pointer mode: arrows press/release directions on `CursorView`; OK taps at the pointer.

Back goes through `onBackPressed`: exit page fullscreen, close the phone remote panel, leave the clear-data submenu, close the menu, leave theater, leave watch mode, close settings, close the shield panel, leave home Edit, close home, leave the toolbar, go back in history, then "Press Back again to exit".

### Clicking and scrolling

- `tapAt` turns OK down/up into touch `ACTION_DOWN`/`ACTION_UP` at a point, dispatched to the root view as `SOURCE_TOUCHSCREEN`. The up event reuses the down position, so holding OK long-presses even if the ring moves.
- `scrollAtCursor` sends a mouse `ACTION_SCROLL` at the pointer (or ring centre), so inner scroll areas scroll, not just the document. Overlays scroll their own `ScrollView` instead.

### Overlays vs page focus

The home screen (`shortcutsPanel`), `settingsPanel`, `shieldPanel`, `menuPanel` and the phone remote `remotePanel` are native views using standard Android focus. `onOverlayChanged(target)` is the single switch:

- sets `webContainer.descendantFocusability` to `FOCUS_BLOCK_DESCENDANTS` while any overlay is open, so the WebView cannot take focus,
- keeps the top bar visible while an overlay is open, schedules hiding otherwise,
- moves focus to `target` (or back to the WebView),
- refreshes the cursor/ring visibility (`refreshCursor`).

The cursor/ring is visible only when `pageActive()` and not on YouTube TV or in watch mode.

## Network policy

All in the `WebViewClient` created in `createWebView`:

- **`shouldInterceptRequest`** (network thread). Subresource requests to a blocked host get an empty `200 text/plain` response when protection is on. Any URL ending in `.m3u8` or `.mpd` is recorded as a `DetectedStream` with the request's Referer and Origin, unless it is a variant under an already-seen master.
- **`shouldOverrideUrlLoading`**. Non-web schemes (`intent:`, `market:`, ...) are always blocked. A one-time bypass lets "Open anyway" through. A change of user agent (entering/leaving `youtube.com/tv`) reloads via `load()`. Tracking parameters are stripped from main-frame navigations. When protection is on: navigations to blocked hosts are stopped, and main-frame cross-site navigations without a user gesture and not server redirects are blocked as "sneaky redirects".
- **`onCreateWindow`**. Pop-ups without a user gesture are dropped. With a gesture, a throwaway WebView catches the target URL; it is opened in the current WebView via `load()` unless it is a blocked host or (with "Block pop-ups" on) a different site. There are no tabs.

`protectionOn` is `prefs.adBlock && site not in pausedSites`, recomputed on each page start.

## Private browsing

`enterPrivate()` keeps the normal WebView alive but paused, and creates a second WebView:

- With `WebViewFeature.MULTI_PROFILE`: the WebView uses a named profile `private` with its own cookies, storage and cache. On exit the WebView is destroyed and the profile deleted (after a short delay, since deletion needs the WebView gone). A leftover profile is deleted at startup and in `onDestroy`.
- Without it: cookies are refused globally while private, and the cache is disabled. Web storage is still the shared default store (see [PRIVACY.md](PRIVACY.md)).

Release builds add `FLAG_SECURE` while private.

## User agents

`uaFor(url)`: `youtube.com/tv*` gets `TV_UA` (a Tizen Smart-TV string) so YouTube serves its TV interface. Otherwise `DESKTOP_UA` when "Desktop site" is on, else the WebView default.

## Native player

`playNative()` starts `PlayerActivity` with the manifest URL, the page's Referer and Origin for that request, the WebView's user agent and the cookies for that URL. `PlayerActivity` picks DASH for URLs containing `.mpd`, otherwise HLS, and shows an error message if playback fails.

## Threads

- WebView network callbacks: read `@Volatile` fields (`pageHost`, `protectionOn`, `bypassOnce`), atomic counters, `CopyOnWriteArrayList` for streams.
- `AdBlocker.load` runs on its own thread and swaps the set when ready (bundled list first, then cached, then downloaded).
- Favicon fetches run on a background thread.
- Everything else is on the UI thread.

## Phone remote (in progress)

`RemoteServer` serves `assets/remote.html` and a small JSON API on port 8765. `BrowserActivity` implements `RemoteServer.Listener`; each callback hops to the UI thread. Keys are turned into `KeyEvent`s and passed to the activity's own `dispatchKeyEvent`, so they follow exactly the routing above (a held phone button auto-repeats like a real remote). `home` opens the mkTV home screen, since apps cannot inject the system Home key. Text is turned into key events for whatever has focus. `/api/open` goes through `normalizeInput()` and `load()`. The panel with the QR code is an overlay like the others (`remotePanel`, counted in `overlayOpen()`). Protocol, security model and current gaps: [PHONE_REMOTE.md](PHONE_REMOTE.md).
