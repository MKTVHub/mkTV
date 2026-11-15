# Architecture

mkTV is a single-module Android app (`:app`, package `com.mktvhub.mktv`). It is a **native shell** around the system **WebView**, plus a **JavaScript layer** injected into every page. This document explains how the pieces fit. For what each feature does, see [FEATURES.md](FEATURES.md).

## Components

```
+-------------------------------------------------------------+
| BrowserActivity (native shell)                              |
|                                                             |
|  topBar/toolbar   shieldPanel   menuPanel   shortcutsPanel  |
|  settingsPanel    historyPanel  suggestPanel  snackbar      |
|  watchDock        watchFlash                                |
|                                                             |
|  webContainer ── active tab's WebView (normal or private)   |
|                   │  page_script.js  (every page)           |
|                   │  yt_clean.js     (YouTube origins)      |
|  fullscreenContainer (page's own fullscreen view)           |
|  CursorView (pointer arrow / jump-mode ring, on top)        |
+-------------------------------------------------------------+
        │ uses                         │ starts
   Prefs, AdBlocker, HostRules     PlayerActivity (Media3 ExoPlayer)
   WebSearch, History, Updates     system speech recogniser, package installer
```

| File | Responsibility |
|---|---|
| `BrowserActivity.kt` | Everything user-facing: WebView setup, request policy, input routing, overlays, home screen, settings, watch mode, private browsing |
| `CursorView.kt` | A non-focusable `View` on top of everything. Pointer mode: draws an arrow, moves it on a `Choreographer` loop with acceleration, reports edge pushes for scrolling, auto-hides after 6 s. Jump mode: draws a rounded focus ring around a rectangle |
| `AdBlocker.kt` | Builds the blocked host set from `assets/adhosts.txt` plus every filter list that is on, each cached at `filesDir/lists/<id>.txt` and refreshed daily on one single-thread worker. The set and the allowlist are swapped atomically (`@Volatile`) so WebView's network threads can read them |
| `FilterLists.kt` | Android-free, unit-tested: filter list presets, the hosts / domain / `||domain^` line parser, allowlist matching, list URL and domain clean-up |
| `HostRules.kt` | Android-free helpers, unit-tested: `isBlocked` (host or any parent domain), `siteOf` (approximate registrable domain), `sameSite`, `parseHosts`, `isStreamUrl`, `isVariantOf`, `stripTracking` |
| `WebSearch.kt` | Android-free, unit-tested: search engines, address-bar input → URL (`normalize`), OpenSearch suggestion parsing, Favourites/History matches |
| `History.kt` | Android-free, unit-tested: history list operations (add with de-duplication and cap, remove, retitle), search, day labels, JSON |
| `Bookmarks.kt` | Android-free, unit-tested: Favourites as links plus one level of folders, JSON compatible with the old flat list, add/remove/rename/move/move into and out of folders |
| `Tabs.kt` | `Tab` state and the Android-free, unit-tested `TabPolicy` (live WebView limit, close order, low-memory rule) |
| `Updates.kt` | Android-free, unit-tested: GitHub release parsing, numeric version comparison, once-a-day check |
| `PageZoom.kt` | Android-free, unit-tested: zoom levels and steps, per-host keys and storage, when to fall back to full-screen fill |
| `PlaybackSpeed.kt` | Android-free, unit-tested: watch-mode speed levels and labels, per-host speed storage, sleep timer choices and countdown text |
| `PointerMotion.kt` | Android-free, unit-tested: pointer-mode speed (Slow / Normal / Fast, acceleration ramp or steady) and the phone touchpad gain |
| `Downloads.kt` | Android-free, unit-tested: download file names (`Content-Disposition`, URL, MIME), sanitising, sizes, `data:` URL parsing, the Downloads list and its JSON |
| `DownloadCenter.kt` | WebView downloads: confirm prompt, system `DownloadManager` (page cookies, user agent, Referer), `data:`/`blob:` saving (blob read in the page and copied out in chunks with `evaluateJavascript`), status polling, Open/Remove/Delete, the Downloads panel rows. Private-mode entries stay in memory |
| `Prefs.kt` | All persisted settings and lists in the `SharedPreferences` file `browser`; history in its own file `history` (cached in memory) |
| `RemoteServer.kt` | Phone remote HTTP server (in progress). Raw `ServerSocket`, one thread per connection, token check, calls back into `BrowserActivity` through `RemoteServer.Listener` |
| `PlayerActivity.kt` | Plays one HLS or DASH URL with ExoPlayer, forwarding the Referer, Origin, Cookie and User-Agent the page used; saves and resumes the Continue watching position under the page's address |
| `ContinueWatching.kt` | Pure Continue watching rules (save, finished, resume offer, cap), labels and JSON; `HomeSections.merge` for the Home section order |
| `TvHome.kt` | Publishes Continue watching to the system Watch Next ("Play Next") row and a "mkTV" preview channel (with Favourites) on one background thread; skipped without a TV provider |
| `page_script.js` | Five IIFEs: cosmetic filters and click-trap sweep (plus GPC), jump-mode engine (`window.__sp`), watch-mode video helpers (same object), page zoom and full-screen fill (adds to `__sp`), auto-unmute |
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
| `__sp.toggle()` / `seek(s)` / `feed(step)` / `fullscreen()` | state strings (`fullscreen()`: `on`, `off`, `fail` or `null`) | watch mode and media keys |
| `__sp.rate(r)` / `pause()` / `sleepEnd(on)` | the rate / `ok` / `{fired}` or `{fired,left}` | playback controls: per-site speed (`applySpeed`, `setSpeed`), sleep timer (`sleepNow`, `checkSleepEnd`) |
| `__sp.zoom(pct)` | the level applied | `applyZoom` on page start, first paint and finish, and on every zoom change |
| `__sp.fill()` / `unfill()` | `fill` or `null` / `off` | `enterFill` / `exitFill` (full-screen fallback) |

`iw` is the page's `innerWidth` in CSS px (divided by `__sp.zoomFactor()`, which is 1 except on old Chromium versions that report unzoomed boxes under CSS zoom). `showRingFor` scales the rect by `webView.width / iw`, offsets it by the WebView's position and clips it to the web area, then hands it to `CursorView.showRing`.

### Polling

While the page is active, `pollRing` runs every 350 ms: it re-reads `__sp.rect()` so the ring follows lazy-loading layouts, and every third tick runs `checkWatch()` to enter or leave watch mode.

## Input

### Key routing order

`BrowserActivity.dispatchKeyEvent`, first match wins:

1. Soft keyboard visible: default handling.
2. Menu / Bookmark toggles the ⋮ menu and Search starts voice search, from anywhere.
3. `!pageActive()` (focus in the toolbar, an overlay, the snackbar or an `EditText`): default TV focus navigation. ▼ from the address field moves into the suggestion list when it is showing; otherwise ▼ from the address field or a toolbar button calls `leaveToPage()`.
4. The page is showing its own fullscreen view (`customView != null`): `handleMediaKey`, then `handleWatchKey` if watching.
5. Fullscreen and jump mode: OK becomes a tap at the screen centre; other keys go to the player.
6. YouTube TV interface (`siteRemote`, i.e. `youtube.com/tv*`): `handleSiteRemote` forwards arrows, OK and media keys to the WebView unchanged. Back: short press sends Escape to the page, long press opens the mkTV home screen.
7. `handleMediaKey`: play/pause, FF/REW (±10 s), next/previous (feed step) on any page with a video.
8. `handleWatchKey` when watching.
9. Channel ± and Page Up/Down: scroll.
10. Jump mode: arrows call `jump(dir)`. The first auto-repeat of ▲/▼ (`repeatCount == 1`) jumps to the site's fixed top or bottom bar once per hold. OK taps the ring centre; with no ring it first picks a start element.
11. Pointer mode: arrows press/release directions on `CursorView`; OK taps at the pointer.

Back goes through `onBackPressed`: close the playback controls, exit page fullscreen, leave fill mode (unless an overlay is open), leave the suggestion list (to the address field) or close it, close the phone remote panel, leave the clear-data submenu, close the menu, leave theater, leave watch mode, close settings, close history, close downloads, close the shield panel, leave home Edit, leave a Favourites folder, close home, leave the toolbar, leave the bottom notice, go back in history, close the tab if other tabs are open, then "Press Back again to exit". While the on-screen keyboard is open, the keyboard takes Back first and closes itself.

### Clicking and scrolling

- `tapAt` turns OK down/up into touch `ACTION_DOWN`/`ACTION_UP` at a point, dispatched to the root view as `SOURCE_TOUCHSCREEN`. The up event reuses the down position, so holding OK long-presses even if the ring moves.
- `scrollAtCursor` sends a mouse `ACTION_SCROLL` at the pointer (or ring centre), so inner scroll areas scroll, not just the document. Overlays scroll their own `ScrollView` instead.

### Overlays vs page focus

The home screen (`shortcutsPanel`), `settingsPanel`, `historyPanel`, `downloadsPanel`, `shieldPanel`, `menuPanel`, the phone remote `remotePanel` and the address-bar `suggestPanel` are native views using standard Android focus. Lists built in code (history rows, download rows, suggestions) set explicit `nextFocus*` ids so ▲▼◀▶ stay inside them. `onOverlayChanged(target)` is the single switch:

- sets `webContainer.descendantFocusability` to `FOCUS_BLOCK_DESCENDANTS` while any overlay is open, so the WebView cannot take focus,
- keeps the top bar visible while an overlay is open, schedules hiding otherwise,
- moves focus to `target` (or back to the WebView),
- refreshes the cursor/ring visibility (`refreshCursor`).

The cursor/ring is visible only when `pageActive()` and not on YouTube TV or in watch mode.

## Network policy

All in the `WebViewClient` created in `createWebView`:

- **`shouldInterceptRequest`** (network thread). Subresource requests to a blocked host get an empty `200 text/plain` response when protection is on. Any URL ending in `.m3u8` or `.mpd` is recorded as a `DetectedStream` with the request's Referer and Origin, unless it is a variant under an already-seen master.
- **`shouldOverrideUrlLoading`**. Non-web schemes (`intent:`, `market:`, ...) are always blocked. A one-time bypass lets "Open anyway" through. A change of user agent (entering/leaving `youtube.com/tv`) reloads via `load()`. Tracking parameters are stripped from main-frame navigations. When protection is on: navigations to blocked hosts are stopped, and main-frame cross-site navigations without a user gesture and not server redirects are blocked as "sneaky redirects".
- **`onCreateWindow`**. Pop-ups without a user gesture are dropped. With a gesture, a throwaway WebView catches the target URL; unless it is a blocked host or (with "Block pop-ups" on) a different site, it opens in a new tab (`openInNewTab`), private if the opener is private.

`protectionOn` is `prefs.adBlock && site not in pausedSites`, recomputed on each page start.

## Private browsing

Private browsing is a set of private tabs (see Tabs below). `enterPrivate()` opens a private tab; `inPrivate` means the tab on screen is private. Normal tabs stay alive but paused:

- With `WebViewFeature.MULTI_PROFILE`: every private tab's WebView uses a named profile `private` with its own cookies, storage and cache. On exit every private tab is destroyed and the profile deleted (after a short delay, since deletion needs all its WebViews gone). A leftover profile is deleted at startup and in `onDestroy`.
- Without it: cookies are refused globally while private, and the cache is disabled. Web storage is still the shared default store (see [PRIVACY.md](PRIVACY.md)).

Release builds add `FLAG_SECURE` while private.

## Tabs

`Tabs.kt` holds `Tab` (its WebView or, once discarded, its last URL, title and `saveState` bundle, in memory only) and the pure `TabPolicy` (unit-tested): at most `MAX_LIVE` (6) live WebViews, least recently used discarded first, never the active tab; at most `MAX_TABS` (20) tabs; after a close, the most recently used tab of the same kind (private or normal) comes back; `onTrimMemory` at RUNNING_LOW/CRITICAL or BACKGROUND and worse discards every background tab.

`webView` is always the active tab's WebView. Every tab's WebView comes from the single `createWebView(isPrivate, tab)`, so clients, scripts, user agent and blocking are identical. The clients capture their `Tab`: `tab.active` (`@Volatile`) keeps background tabs from counting blocks or adding streams for the page on screen, and `tab.host` is the opener site for the redirect and pop-up rules. `showTab` pauses the old tab (media paused by script, then `WebView.onPause`; `pauseTimers` is global, so it is not used), attaches the new one and resets the page state (host, protection, shield, streams, address).

Back: when the page can't go back and the current kind has more than one tab, `onBackPressed` closes the tab (`closeTab`). The switcher is a sub-menu of the ⋮ menu (`showTabsMenu`); holding Menu opens it directly.

## User agents

`uaFor(url)`: `youtube.com/tv*` gets `TV_UA` (a Tizen Smart-TV string) so YouTube serves its TV interface. Otherwise `DESKTOP_UA` when "Desktop site" is on, else the WebView default.

## Native player

`playNative()` starts `PlayerActivity` with the manifest URL, the page's Referer and Origin for that request, the WebView's user agent and the cookies for that URL. `PlayerActivity` picks DASH for URLs containing `.mpd`, otherwise HLS, and shows an error message if playback fails.

## Address bar, voice and updates

- `normalizeInput` → `WebSearch.normalize` with the engine from `Prefs.searchEngine`, then `HostRules.stripTracking`. Every address-bar path (typed, suggestion, voice, phone remote `/api/open`, intents, home page) goes through it and `load()`.
- Suggestions: a `TextWatcher` on `urlField` (only while it has focus) debounces to `updateSuggestions`, which renders local matches at once and asks the engine on a single-thread executor, dropping stale answers by a generation counter.
- Voice: `startActivityForResult(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)`; the result goes to the focused app text field or through `submitAddress`.
- Updates: `checkForUpdate` 4 s after launch (daily), `startUpdate` downloads to `cache/updates/`, `installUpdate` checks the signer, the install permission, then starts `ACTION_VIEW` with a `FileProvider` URI (authority `com.mktvhub.mktv.files`).

## Threads

- WebView network callbacks: read `@Volatile` fields (`pageHost`, `protectionOn`, `bypassOnce`), atomic counters, `CopyOnWriteArrayList` for streams.
- `AdBlocker.load` runs on a single-thread executor (so loads never race) and swaps the set when ready (bundled list first, then all cached lists, then again after any download).
- Favicon fetches, the update check and the update download run on background threads; search suggestions on a single-thread executor.
- Everything else is on the UI thread.

## Phone remote (in progress)

`RemoteServer` serves `assets/remote.html` and a small JSON API on port 8765. `BrowserActivity` implements `RemoteServer.Listener`; each callback hops to the UI thread. Keys are turned into `KeyEvent`s and passed to the activity's own `dispatchKeyEvent`, so they follow exactly the routing above (a held phone button auto-repeats like a real remote). `home` opens the mkTV home screen, since apps cannot inject the system Home key. Text is turned into key events for whatever has focus. `/api/open` goes through `normalizeInput()` and `load()`. The panel with the QR code is an overlay like the others (`remotePanel`, counted in `overlayOpen()`). Protocol, security model and current gaps: [PHONE_REMOTE.md](PHONE_REMOTE.md).
