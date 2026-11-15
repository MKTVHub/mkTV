# AGENTS.md

Guide for AI coding agents and human contributors working on **mkTV**, a remote-first web browser for TVs, maintained by **MKTVHub**. Read this before changing code.

## Mission and vision

Make every TV smart, not just Google TV and Android TV. The same browsing experience should reach LG webOS, Samsung Tizen, Amazon Fire TV and beyond.

- **Remote-first UX.** Everything must work with a D-pad, OK and Back. Fewer clicks, less noise: no pop-ups, no cluttered chrome, no hunting for a tiny button.
- **Privacy by default.** Ads, trackers, pop-ups and hostile redirects are blocked out of the box. Nothing leaves the TV unless the user asked for it.

## Product principles

1. **The remote is the only input you can count on.** Every feature must be reachable and usable with ▲▼◀▶, OK, Back and (optionally) Menu. A phone or keyboard is a bonus, never a requirement.
2. **One press beats three.** If a common action takes several presses, find a shortcut (hold ▼ to reach a site's bottom bar, watch mode for video, ▼ for full screen).
3. **Quiet UI.** The top bar hides itself, hints show once and fade, blocked items are reported in a small snackbar, not a dialog.
4. **Generic, not site-specific.** Features work on any site by detecting page structure (fixed bars, big `<video>`, feeds, manifests). Site-specific code is allowed only to make a mainstream, licensed site usable or ad-free (today: the YouTube TV interface and YouTube ad pruning) and must live in its own clearly named place.
5. **Safe defaults, easy escape hatch.** Blocking is on by default and can be paused per site or turned off in Settings when a site breaks. Blocked pop-ups and redirects offer "Open anyway".
6. **Honest privacy.** Never claim protection the app does not give. See [docs/PRIVACY.md](docs/PRIVACY.md).

## Repository map

```
AGENTS.md, README.md, CONTRIBUTING.md, LICENSE, NOTICE
docs/                     ARCHITECTURE, FEATURES, PHONE_REMOTE, PRIVACY, ROADMAP
design/                   Icon and banner source images (not used by the build)
build.gradle.kts          Plugin versions (AGP 8.7.3, Kotlin 2.0.21)
settings.gradle.kts       Single module :app
app/build.gradle.kts      SDK levels, BuildConfig fields, dependencies
app/src/main/
  AndroidManifest.xml     Two activities, INTERNET + ACCESS_NETWORK_STATE + REQUEST_INSTALL_PACKAGES (updates) + WRITE_EPG_DATA (TV home screen), FileProvider, speech and TV provider <queries>, leanback launcher
  java/com/mktvhub/mktv/
    BrowserActivity.kt    The whole browser shell (see regions below)
    CursorView.kt         Native overlay: pointer arrow (pointer mode) and focus ring (jump mode)
    AdBlocker.kt          Loads bundled + downloaded filter lists; holds the live Set<String> and the allowlist
    FilterLists.kt        Pure: filter list presets, list parser (hosts, domains, ||domain^), allowlist matching
    HostRules.kt          Pure Kotlin helpers: host matching, site-of, hosts parsing, stream URL detection, tracking-param stripping
    WebSearch.kt          Pure: search engines, address-bar input → URL, suggestion parsing, Favourites/History matching
    History.kt            Pure: browsing history list, search, day labels
    Updates.kt            Pure: GitHub release parsing, version comparison
    PageZoom.kt           Pure: zoom levels, per-site zoom storage, full-screen fill fallback decision
    PointerMotion.kt      Pure: pointer-mode speed levels, acceleration ramp, phone touchpad gain
    PlaybackSpeed.kt      Pure: watch-mode speed levels and labels, per-site speed storage, sleep timer choices
    Bookmarks.kt          Pure: Favourites tree (links + one level of folders), JSON compatible with the old flat list
    Tabs.kt               Tab state + pure TabPolicy (live WebView limit, close order, low-memory rule)
    Downloads.kt          Pure: download file names, Content-Disposition, sizes, data: URLs, the saved Downloads list
    DownloadCenter.kt     Downloads: confirm prompt, DownloadManager, data:/blob: saving, status polling, Open/Delete, Downloads panel rows
    ContinueWatching.kt   Pure: Continue watching rules (what to save, finished, resume offer), labels, JSON; HomeSections.merge (Home section order)
    TvHome.kt             Android TV home screen: Continue watching in Play Next (Watch Next) and a mkTV channel, via androidx.tvprovider, off the main thread
    Parental.kt           Pure: parental controls PIN hashing (PBKDF2-HMAC-SHA256, salted), wrong-PIN lockout, unlock window, blocked-site matching, SafeSearch URL rewriting
    PinPad.kt             Parental controls number pad overlay (3×4 keys, dots, auto-submit on the 4th digit, remote number keys)
    Prefs.kt              SharedPreferences wrapper: every user setting, favourites, recents, history, counters, update state
    PlayerActivity.kt     Full-screen Media3 ExoPlayer for HLS/DASH manifests found on a page
    RemoteServer.kt       Phone remote: tiny LAN HTTP server with a pairing token (see docs/PHONE_REMOTE.md)
    RemoteInput.kt        Pure: phone remote request validation (keys, lengths, ranges, URL scheme, icon host)
  assets/
    page_script.js        Injected into every page: cosmetic ad CSS, click-trap removal, GPC, jump-mode engine (window.__sp), watch-mode video helpers, auto-unmute
    yt_clean.js           Injected on YouTube origins only: JSON ad pruning, ad-slot CSS, ad skip
    adhosts.txt           Small bundled ad/pop-up network host list
    remote.html           Self-contained phone remote page served by RemoteServer
  res/drawable/ic_lock.xml, ic_backspace.xml   Parental controls icons (Settings sidebar, PIN pad ⌫)
  res/layout/activity_browser.xml   All browser views: toolbar, panels, dock, snackbar, fullscreen container, cursor
  res/xml/network_security_config.xml, file_paths.xml (FileProvider for downloaded updates and Android 7–9 downloads)
app/src/test/java/com/mktvhub/mktv/   JVM unit tests: HostRulesTest, FilterListsTest, WebSearchTest, HistoryTest, UpdatesTest, WifiQrTest, RemoteInputTest, PageZoomTest, PointerMotionTest, PlaybackSpeedTest, BookmarksTest, TabsTest, DownloadsTest, ContinueWatchingTest, ParentalTest
```

`BrowserActivity.kt` is large. Navigate it by its markers, not by line numbers (it changes often):

| Marker | Owns |
|---|---|
| `// region WebView` | WebView creation and settings, script injection, `shouldInterceptRequest` (ad blocking, stream detection), `shouldOverrideUrlLoading` (scheme filter, tracking strip, redirect blocking), pop-up filtering in `onCreateWindow`, user-agent selection (`uaFor`, `isTvSite`), `load()` |
| `// ---- Private browsing ----` | `enterPrivate` / `exitPrivate`, WebView profile handling |
| `// ---- Tabs` | `showTab`, `discardTab`, `closeTab`, `openInNewTab`, `onTrimMemory` (the switcher is `showTabsMenu` in the ⋮ menu) |
| `// region Toolbar` | Toolbar buttons, `submitAddress`, address-bar suggestions, voice search, shield badge and panel, per-site pause, blocked snackbar, stream button and picker, `playNative` |
| `// region Shortcuts` | Home screen: Continue watching (and its sampling, resume notice), Popular, Favourites, Recently visited, tiles, favicons; the History page; in-app updates (check, home card, download, install) |
| `// region Settings` | Settings screen built in code (`section`, `toggleRow`, `actionRow`, `fieldRow`, `segmented`); `// ---- Parental controls` (`pinPad`, `needsPin` / `askPin` / `guarded`, `parentalBlocks`, `parentalUrl`, `renderParental`) |
| `// region Remote control → cursor` | Phone remote panel and `RemoteServer.Listener` callbacks (`onKey`, `onText`, `onOpen`, `state`), ⋮ menu, clear-data submenu, focus/overlay rules, `dispatchKeyEvent`, YouTube TV passthrough, jump mode, pointer touch/scroll synthesis, auto-hiding bar, watch mode, dock, playback controls and sleep timer, `onBackPressed` |
| `// region Helpers` | `normalizeInput`, keyboard, system bars, toasts, lifecycle |
| `companion object` | `POPULAR` list, user-agent strings, request codes |

## Architecture in one page

Full detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

mkTV is a **native Android shell around a system WebView, plus a JavaScript layer injected into every page.**

- The **native shell** owns input, chrome (toolbar, panels, dock), network policy (blocking, redirects, pop-ups), user agents, private profiles and the native player.
- The **injected JS layer** (`page_script.js`, `yt_clean.js`) owns everything that needs the DOM: finding clickable elements, spatial navigation, measuring rectangles, controlling `<video>`, cosmetic filtering.
- They talk one way: Kotlin calls `webView.evaluateJavascript("window.__sp.move('down')")` and parses the JSON string that comes back. There is no `addJavascriptInterface` bridge. Keep it that way: pages cannot call into the app.
- The jump-mode highlight is **drawn natively** by `CursorView` from the rectangle the page reports, scaled from CSS px to screen px in `showRingFor`. Clicks are **synthesized touch events** at the ring centre (`tapAt` / `touchAt`), so holding OK is a long-press.

**Key routing order** in `BrowserActivity.dispatchKeyEvent` (first match wins). Before all of these, while the parental PIN pad is open, it takes the number keys and Delete, and swallows Menu and Search (`PinPad.handleKey`); arrows and OK move between its keys.

1. Soft keyboard visible: normal Android handling.
2. Menu / Bookmark opens the ⋮ menu; Search starts voice search (from anywhere).
3. Page not active (`!pageActive()`: focus is in the toolbar, an overlay, the snackbar or a text field): normal TV focus navigation. ▼ from the address field enters the suggestion list if it is showing; otherwise ▼ from the toolbar returns to the page (`leaveToPage`).
4. Page fullscreen video (`customView != null`): media keys, then watch-mode keys.
5. Fullscreen video in jump mode: OK taps the screen centre; other keys go to the player.
6. YouTube TV (`siteRemote`): `handleSiteRemote` passes arrows, OK and media keys straight to the page.
7. Media keys on any page (`handleMediaKey`).
8. Watch mode keys (`handleWatchKey`).
9. Channel ± and Page Up/Down scroll.
10. Jump mode: arrows call `jump()`; a held ▲/▼ (first repeat) jumps to the site's fixed bar; OK taps the ring.
11. Pointer mode: arrows drive `CursorView`; OK taps at the pointer.

Back is handled separately in `onBackPressed` (the parental PIN pad first, then the playback controls panel, fullscreen, fill mode, suggestion list, phone remote panel, menu, watch mode, settings, History page, Downloads page, shield panel, home edit, Favourites folder, home, toolbar, bottom notice, back in page history, close the tab if other tabs are open, then "press again to exit"). On YouTube TV, `handleSiteRemote` sends a short Back to the page as Escape and a long Back opens the mkTV home screen.

**Overlays vs page focus.** The home screen, settings, History page, Downloads page, shield panel, ⋮ menu, phone remote panel, parental PIN pad, address-bar suggestion list and watch mode's playback controls panel are native views using normal Android focus. When any is open (`overlayOpen()`), `webContainer` blocks descendant focus so the page cannot steal it, the top bar stays visible (except over the playback controls, which sit on the video), and the cursor/ring is hidden. `onOverlayChanged()` is the single place that flips this; call it whenever you open or close an overlay.

## Build, test, install

Requirements: JDK 17, Android SDK 35. Package / application ID: `com.mktvhub.mktv`. minSdk 24, targetSdk 35.

```sh
./gradlew test assembleDebug
adb connect <tv-ip>:5555          # once, with network debugging enabled on the TV
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mktvhub.mktv/.BrowserActivity
adb shell am start -n com.mktvhub.mktv/.BrowserActivity -d https://example.com           # open a URL
adb exec-out screencap -p > shot.png                                                       # check the result
adb shell input keyevent DPAD_DOWN                                                         # drive the remote
adb logcat -s AdBlocker chromium
```

Debug builds do not set `FLAG_SECURE`, so screenshots work in private mode too.

## Coding conventions (as observed in the code)

- Kotlin, plain `Activity` (no AppCompat, no Fragments, no Compose, no DI). Views come from one XML layout; panels such as Settings and the ⋮ menu are built in code.
- Small dependency set: `androidx.core`, `androidx.webkit`, `androidx.tvprovider` (TV home screen), Media3, ZXing core (QR code); tests add JUnit and `org.json` (the Android jar only has stubs of it). Adding a dependency needs a clear reason.
- Keep pure logic in `HostRules`, `WebSearch`, `History` or `Updates` (no Android imports beyond `org.json`) so it can be unit-tested on the JVM.
- Every user setting is a property on `Prefs` with a default in the getter. Settings save as they change; closing Settings reloads the page if `settingsDirty`.
- All app-initiated navigation goes through `load()` so the right user agent is set first.
- WebView callbacks such as `shouldInterceptRequest` run off the UI thread: shared state is `@Volatile`, atomic, or `CopyOnWriteArrayList`. UI work goes through `runOnUiThread`.
- Comments explain *why* (TV quirks, WebView quirks), in one line where possible. Keep that style.
- Injected JS is plain ES5-style functions in IIFEs, guarded against double injection (`window.__sp`, `__spYt`, ...), wrapped in `try/catch` so a page can't break it. Results returned to Kotlin are `JSON.stringify` strings.
- Be conservative in blocking: never add shared CDNs to `adhosts.txt` (see the header in that file). Prefer false negatives to breaking sites.
- User-facing text: short, plain, British spelling ("Favourites"), symbols for remote keys (▲▼◀▶, OK).

## Testing expectations

- **JVM tests** (`app/src/test`) for all pure logic. Any change to `HostRules` needs a test in `HostRulesTest`. If you add logic that can be pure, put it in `HostRules` (or a similar Android-free object) and test it.
- **On a real TV** for anything touching input, focus, WebView or video. Emulators do not reproduce TV WebView versions, remotes or decoders. Verify with `adb shell input keyevent ...` and `adb exec-out screencap -p`, and attach screenshots to the PR for UI changes.
- Check both navigation modes (jump and pointer), private mode, and a WebView without `MULTI_PROFILE` / `DOCUMENT_START_SCRIPT` if your change touches those paths.

## Hard rules

1. **Naming.** Use only these names: the app is **mkTV**, the organisation and copyright holder is **MKTVHub**, the package and application ID is `com.mktvhub.mktv`. Never add any other company, client or product name to package names, IDs, code, configs, assets, docs or commit metadata.
2. **No piracy tooling.** mkTV is a general-purpose browser. Do not add scrapers, extractors, presets, Popular tiles, docs or tests that target sites distributing unlicensed content, and do not name such sites anywhere in the repo. Stream detection must stay generic (any HLS/DASH manifest the page itself requests).
3. **Privacy.**
   - No analytics, telemetry, crash reporting or ad SDKs.
   - The only network requests the app makes on its own are the ones documented in [docs/PRIVACY.md](docs/PRIVACY.md). Adding a new one requires updating that file.
   - Private browsing must keep nothing after it closes. Do not write private-mode URLs, titles or icons to `Prefs` or files.
   - No `addJavascriptInterface` bridges that pages can call.
   - Local servers (phone remote) must require the pairing token on every API call and stop while mkTV is in the background.
4. **Keep features generic.** Detect structure, not domains. Site-specific code needs a comment explaining why a generic approach is not possible.
5. **Don't break the remote.** Every new view must be focusable and reachable with the D-pad, and Back must always lead somewhere sensible.
6. **License.** All contributions are Apache-2.0. Copyright holder: MKTVHub. Record new third-party code or data in `NOTICE`.
