# Features

Each feature: what it does, how it works, where it lives in the code, and its known limits. File references point to functions and region markers in `app/src/main/java/com/mktvhub/mktv/` and `app/src/main/assets/`.

---

## Jump navigation (default)

**What.** The D-pad moves a highlight ring from one link or button to the next, like a TV app. OK clicks; holding OK long-presses. ▲ at the top of a page goes to the toolbar. Holding ▼ or ▲ jumps straight to the site's fixed bottom or top bar (for example a bottom tab bar). A one-time hint per site tells you when such a bar exists.

**How.** `page_script.js` collects candidates: standard interactive elements and ARIA roles, plus a bounded scan (1,200 elements near the viewport) for elements with `cursor: pointer`, keeping only the outermost one. Candidates are cached and rebuilt when the DOM changes (`MutationObserver`) or you scroll into a new screen. Each move scores candidates in the pressed direction, preferring the same row or column. Elements inside `position: fixed/sticky` layers are "pinned" and only used for vertical moves once page content runs out, so headers and bottom bars do not trap the highlight. The chosen element is scrolled into the middle band of the screen. The page returns its rectangle; the app draws the ring natively and clicks with a synthesized touch at its centre.

**Where.** `page_script.js` (jump-mode IIFE, `window.__sp.move/rect/bars`), `BrowserActivity.jump`, `showRingFor`, `pollRing`, `maybeHintBars`, `tapAt`, `CursorView.showRing`.

**Limits.** Elements inside cross-origin iframes cannot be reached individually (the iframe itself is a target). Canvas-only UIs have nothing to jump to; use pointer mode. Very dynamic pages may need a second press while the cache rebuilds.

## Pointer mode

**What.** The D-pad moves a mouse pointer that speeds up while held. Push against a screen edge to scroll. OK clicks at the pointer. The pointer fades after 6 seconds of inactivity. Moving it to the top edge shows the toolbar.

**How.** `CursorView` runs a `Choreographer` frame loop while a direction is held, ramping speed from 260 to 1,300 dp/s over 0.9 s. At an edge it calls `onEdgeScroll`, which sends a mouse wheel event at the pointer.

**Where.** `CursorView.kt`, `BrowserActivity.scrollAtCursor`, `touchAt`. Switch modes in ⋮ → Navigation or Settings → Navigation (`Prefs.navMode`).

**Limits.** Slower than jump mode for long pages. Hover-only menus get a touch, not a hover.

## Auto-hiding top bar

**What.** The toolbar (back, forward, reload, home, address, voice search, Watch full screen, shield, ⋮) slides away while you use a page. It comes back on page load, when you press ▲ at the top of the page, when the pointer reaches the top edge, or when any overlay is open.

**Where.** `// ---- Auto-hiding top bar ----` (`showBar`, `scheduleHideBar`, `hideBar`, `canHideBar`). Setting: Settings → Browsing → Auto-hide the top bar.

**Limits.** It stays visible while a page is loading.

## ⋮ menu

**What.** Opened with the ⋮ button or the remote's Menu (or Bookmark) key. Items: Add to / Remove from Favourites, Private browsing, Navigation (Jump / Pointer), Desktop site (On / Off), History, Phone remote, Clear browsing data…, Settings. Phone remote (see below).

**Where.** `buildMenu`, `showClearMenu`, `clearData`.

**Clear browsing data (menu).** Checkboxes for History (back/forward, the History page and Recently visited, on by default), Cookies & site data (off by default) and Cached images & files (on by default).

**Limits.** Saved site icons in `filesDir/icons` are not removed by either clear action.

## Home screen

**What.** The start screen (and the ⌂ button) shows three rows:
- **Popular:** YouTube (opens `youtube.com/tv`, the TV interface), YouTube Music, TikTok, Twitch, Dailymotion.
- **Favourites:** add with ⋮ → Add to Favourites, "☆ Add this page", or by typing an address. Edit removes them.
- **Recently visited:** up to 10 shown (12 stored), one per site, newest first, excluding favourites. Not shown in private mode.

The header has **History** (see below) and **Private browsing** buttons. When a newer mkTV is available, an update card appears at the top (see [In-app updates](#in-app-updates)).

Settings → Start screen can replace it with a fixed home page address.

**How.** Tiles show a saved site icon or a coloured letter. Icons come from the page's favicon while you browse, or are fetched in the background (`/apple-touch-icon.png`, then `/favicon.ico`) for tiles that have none.

**Where.** `// region Shortcuts`, `BrowserActivity.POPULAR`, `Prefs.shortcuts`, `Prefs.recent`.

**Limits.** The icon fetch contacts every site on the home screen once, including Popular sites you have never opened (not in private mode). See [PRIVACY.md](PRIVACY.md).

## History

**What.** Every page you open in normal mode, newest first, grouped by day ("Today", "Yesterday", "Monday 3 November"). Open it from ⋮ → History or the **History** button on the home screen. OK opens a page; ▶ moves to its **Remove** button; ▲ from the first row reaches the search field, which filters by words in the title or address as you type (all words must match). **Clear history** (press twice) removes it all, including Recently visited. Back returns to where you came from.

**How.** A revisited address moves to the top instead of appearing twice. The page's real title replaces the first one when the site sets it after loading. Up to 500 pages are kept; the panel shows the newest 150 matches (search to reach older ones). Explicit `nextFocus*` links keep ▲▼ on the rows and ◀▶ between a row and its Remove button, so focus never jumps to the toolbar.

**Where.** `History.kt` (pure: add, remove, retitle, search, day labels, JSON), `Prefs.history` (its own `SharedPreferences` file `history`, cached in memory), `// ---- History page ----` in `BrowserActivity` (`showHistory`, `renderHistory`, `historyRow`), recorded in `rememberRecent` and `onReceivedTitle`.

**Limits.** Not recorded in private mode. Single-page apps that change the address without loading a page (for example YouTube's TV interface) are recorded once, at the page load.

## Downloads

**What.** When a site offers a file, a small prompt shows its name, size (when the site says) and site, with **Download** and **Cancel** (Back cancels). Files download in the background; when one finishes, a notice says **Downloaded <name>** with **Open** (▼ past the end of the page reaches the button in jump mode). ⋮ → **Downloads** (or Settings → Downloads → Open) lists every download with its size, live progress or status, site and date. OK on a row opens the file: APKs go to Android's installer (mkTV asks once for "Install unknown apps"), videos and audio play in mkTV's player (**Open with…** offers other apps), and other files open in the TV's app for that type. **Remove** takes a row off the list and keeps the file; **Delete** (press twice) deletes the file; **Cancel** stops a running download. **Clear list** (press twice) empties the list and keeps the files. Back closes the panel.

**How.** `WebView.setDownloadListener` hands the URL, user agent, `Content-Disposition`, MIME type and length to `DownloadCenter.offer`. The file name comes from `Content-Disposition` (`filename*` first), then the URL, then "download", sanitised, with an extension from the MIME type if it has none. http(s) downloads go to Android's `DownloadManager` with the page's cookies (from the WebView's own cookie jar, the private one in private browsing), the WebView user agent and the page as Referer, so signed-in and hotlink-protected downloads work. `data:` URLs are decoded by mkTV. `blob:` URLs are read inside the page as soon as the download starts (sites often revoke them right after the click): injected JS reads the blob as a base64 `data:` URL into a window variable, which the app copies out in 1 MB chunks with `evaluateJavascript` and then deletes. There is still no JavaScript bridge the page can call. `data:` and `blob:` files are capped at 20 MB. Status is polled from `DownloadManager` every 0.7 s while something is downloading or the panel is open, and only while mkTV is on screen; progress updates in place so focus doesn't move. Rows have explicit `nextFocus*` links: ▲▼ keep the column, ◀▶ stay in the row, and the ends stop instead of escaping to the toolbar.

Where files go, without any storage permission: on Android 10 and later, the TV's shared **Downloads** folder (`setDestinationInExternalPublicDir`; `MediaStore.Downloads` for `data:`/`blob:` files). On Android 7–9 the shared folder would need `WRITE_EXTERNAL_STORAGE`, so files go to mkTV's own folder (`Android/data/com.mktvhub.mktv/files/Download`), which other apps can open through mkTV's `FileProvider` but which Android deletes when mkTV is uninstalled.

**Where.** `Downloads.kt` (pure: file names, `Content-Disposition`, sizes, `data:` URL parsing, the saved list and its JSON), `DownloadCenter.kt` (prompt, `DownloadManager`, `data:`/`blob:` saving, status polling, Open/Remove/Delete, panel rows), `Prefs.downloads`, `// ---- Downloads page ----` in `BrowserActivity` (`showDownloads`, `closeDownloads`, the snackbar notice), the listener in `createWebView`, `res/xml/file_paths.xml`, `PlayerActivity` (plays `content://` files).

**Limits.** Downloads made by a form POST are fetched again with GET by `DownloadManager`, which some sites refuse. A `blob:` created inside a cross-origin frame, or revoked before mkTV reads it, can't be saved: mkTV says so ("This site's download can't be saved on TV"). Some TVs ship with the system Download Manager disabled; mkTV then says the download couldn't start. mkTV's player can't play every format; use **Open with…**. Private-mode downloads: see [PRIVACY.md](PRIVACY.md#private-browsing).

## Address bar: search engine and suggestions

**What.** Text that isn't an address is searched with the engine chosen in Settings → Search: Google (default), DuckDuckGo, Bing, Brave or Startpage. While you type, a list under the address bar offers:
- the typed text itself ("Search Google" or "Open address"),
- up to three matching Favourites (★) and History pages (clock), always, on the TV only,
- up to five search suggestions from the engine, only if **Search suggestions** is on (off by default, and never in private browsing). While it's off, the list ends with a "Show suggestions as you type" row that says what it sends and turns it on.

With the on-screen keyboard open, Back closes the keyboard and ▼ then moves into the list (the list says so). With the phone remote's keyboard there is no on-screen keyboard, so ▼ goes straight in. ▲ from the first row returns to the address field; Back closes the list.

**How.** Only edits made while the address field has focus trigger the list (page loads set the text without focus). Each keystroke is debounced by 200 ms; the engine request runs on one background thread with a 3 s timeout, carries no cookies, and an answer for an older keystroke is dropped. Addresses are never sent for suggestions. All engines answer in the OpenSearch JSON format.

**Where.** `WebSearch.kt` (pure: engines, `normalize`, `looksLikeUrl`, `parseSuggestions`, `localMatches`), `normalizeInput`, `// ---- Address-bar suggestions ----` in `BrowserActivity` (`updateSuggestions`, `renderSuggestions`, `hideSuggestions`, `submitAddress`), `Prefs.searchEngine`, `Prefs.searchSuggestions`.

**Limits.** Gboard on Android TV keeps the D-pad while it is open (▲ only reaches its own word strip), so picking a suggestion takes Back first. Engine suggestions depend on the engines' public suggestion endpoints, which can change or rate-limit.

## Voice search

**What.** The microphone button in the toolbar (and the remote's Search key, on remotes where the system passes it to apps) opens the TV's speech recogniser. What you say is opened as an address or searched with your engine. If a text field in mkTV has focus (History search, Add a favourite), the words are typed there instead.

**How.** `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` with the web-search language model. The button only appears if the TV has a recogniser (a `<queries>` entry in the manifest makes it visible to the app on Android 11+). mkTV never records audio itself and needs no microphone permission.

**Where.** `// ---- Voice search ----` in `BrowserActivity` (`voiceAvailable`, `startVoice`, `onActivityResult`), `KEYCODE_SEARCH` in `dispatchKeyEvent`.

**Limits.** On Google TV the remote's Assistant/mic button is normally handled by the system (Google Assistant) rather than passed to apps; use the toolbar button. Voice can't type into fields inside web pages yet. Speech is processed by the TV's recogniser (on Google TV, Google's speech service), not by mkTV.

## In-app updates

**What.** Once a day at launch, mkTV asks GitHub for the latest release. If it's newer than the installed version, the home screen shows a card: "mkTV 1.2.0 is available" with **Update** and **Later**. Update downloads `mkTV.apk` (progress on the button) and opens Android's installer, which asks you to confirm. Later hides the card until the next version. Settings → Updates shows the status, has **Check now** / **Update**, and a switch to turn the daily check off.

**How.** `GET https://api.github.com/repos/MKTVHub/mkTV/releases/latest`; drafts, pre-releases and releases without a `mkTV.apk` asset are ignored. Versions are compared numerically (`1.0.10` > `1.0.9`). The result is cached, so the card shows on later launches without another request. Failures are silent (no card) and don't use up the day's check unless GitHub answered. The download goes to the app's cache (`cache/updates/`), must match the asset's size, and must be signed with the same key as the installed app (otherwise mkTV explains why Android would refuse it). It is shared with the installer through a `FileProvider`. If mkTV isn't yet allowed to install apps, it opens that setting first (`REQUEST_INSTALL_PACKAGES`).

**Where.** `Updates.kt` (pure: `parseVersion`, `isNewer`, `due`, `parseRelease`), `// ---- Updates ... ----` in `BrowserActivity` (`checkForUpdate`, `renderUpdateCard`, `startUpdate`, `installUpdate`, `signedLikeThisApp`), `Prefs.updateCheck`, `lastUpdateCheck`, `latestRelease`, `dismissedUpdate`, `res/xml/file_paths.xml`. Debug builds accept `--es debug_version 1.0.0` on the launch intent to pretend to be older, for testing the card.

**Limits.** Debug or self-built copies are signed with a different key, so they can't update to an MKTVHub release in place. Builds installed from an app store should leave updates to the store (the check can be turned off).

## YouTube TV interface

**What.** The YouTube tile opens `youtube.com/tv`, YouTube's own remote-friendly interface. mkTV sends a Smart-TV user agent so YouTube serves it, and passes the remote straight to the page: arrows, OK and media keys act exactly as on a TV app. Back acts as YouTube's back; **hold Back** to return to the mkTV home screen. The cursor and ring are hidden there.

**Where.** `isTvSite`, `uaFor`, `TV_UA`, `handleSiteRemote`, `siteRemote`.

**Limits.** Depends on YouTube continuing to serve the TV interface to that user agent.

## Watch mode

**What.** Turns on by itself when a large video (over a quarter of the screen) is playing. The remote then controls the video:
- Vertical feeds (portrait video, for example short-video feeds): ▲ previous, ▼ next.
- Normal video: ▼ real full screen, ▲ leave full screen or leave watch mode.
- ◀ ▶ seek 10 s, OK pause/play, Back browse the page again.
- A key-hint dock slides up for 2.5 s after each press, and a large symbol flashes for feedback.

Media keys (play/pause, FF/REW, next/previous) work on any page, watch mode or not. After Back, watch mode does not re-enter on the same URL.

**How.** `checkWatch` asks `__sp.media()` about every second for the largest visible `<video>`. "Next" scrolls the video's scroll container by one screen (snap-scrolling feeds land on exactly one item), falling back to synthetic arrow-key events. Full screen calls the Fullscreen API on the player container, after sending a harmless key to the page to provide the user activation the API needs. Watch mode exits after about 3 s with no big video.

**Where.** `// ---- Watch mode ----` (`checkWatch`, `enterWatch`, `exitWatch`, `handleWatchKey`, `handleMediaKey`, `toggleTheater`, `showDock`, `flash`), `page_script.js` (`mainVideo`, `feed`, `fullscreen`).

**Limits.** Videos inside cross-origin iframes (most embedded players) are invisible to the script, so watch mode and media keys don't reach them. Use the player's own controls or "Watch full screen" if a stream was detected.

## Auto-unmute

**What.** Many sites start video muted. When a large video starts playing muted, mkTV clicks the site's own unmute button if it finds one, then unmutes the element. Small hover previews and videos inside an ad container stay muted.

**Where.** `page_script.js` (auto-unmute IIFE). Setting: Settings → Browsing → Auto-unmute videos (full effect after an app restart).

## Stream detection and native player

**What.** When a page requests an HLS (`.m3u8`) or DASH (`.mpd`) manifest, a **Watch full screen** button appears in the toolbar (with a count if there are several; you pick from a list). It opens the stream in a native ExoPlayer, which is usually smoother on TV hardware than the in-page player.

**How.** `shouldInterceptRequest` records manifest URLs with the Referer and Origin the page sent, skipping variant playlists under an already-seen master. The player receives those headers plus the WebView user agent and the cookies for that URL.

**Where.** `HostRules.isStreamUrl`, `isVariantOf`, `DetectedStream`, `updateStreamButton`, `openStreamPicker`, `playNative`, `PlayerActivity.kt`.

**Limits.** DRM-protected streams will not play. Streams that need tokens generated by page scripts per segment may fail. Detection is by URL extension only; manifests without `.m3u8`/`.mpd` in the URL are missed.

## Ad and tracker blocking

**What.** On by default. The shield in the toolbar shows a badge with the number blocked on this page; OK opens a panel with counts for ads and trackers, pop-ups and redirects, the all-time total, and an ON/OFF switch to pause blocking on this site.

**How.**
- **Network:** subresource requests to blocked hosts (and their subdomains) get an empty response. The list is `assets/adhosts.txt` (a small, conservative list) plus a downloaded hosts file, by default the StevenBlack unified hosts list, refreshed at app start if the copy is over a day old. Settings → Blocklist shows the count, has "Update now", and lets you change or clear the URL.
- **Cosmetic:** `page_script.js` hides common ad containers with CSS.
- **Click traps:** `page_script.js` removes invisible full-screen layers that turn any click into a pop-up (fixed/absolute, z-index ≥ 100, over half the viewport, nearly transparent, no media and no text). It sweeps for 10 s after load.
- **Pop-ups:** pop-ups without a click are dropped. Pop-ups from a click are opened only if they stay on the same site and the host is not blocked.
- **Redirects:** script-driven jumps to another site without a click are blocked; server redirects are allowed unless blocklisted. Links to apps (`intent:`, `market:` and similar) are always blocked.
- **Snackbar:** each blocked pop-up or redirect shows a small message with **Open anyway**, which lets that URL through once.

**Where.** `AdBlocker.kt`, `HostRules.isBlocked/parseHosts/sameSite`, `shouldInterceptRequest`, `shouldOverrideUrlLoading`, `onCreateWindow`, `// region Toolbar` (`updateShield`, `bindShieldPanel`, `toggleSitePause`, `showBlocked`), `Prefs.pausedSites`, `Prefs.totalBlocked`.

**Limits.**
- Ads served from the site's own domain are not blocked by the host list.
- Pausing a site, or turning "Block ads & trackers" off, stops network, pop-up and redirect blocking only. The cosmetic CSS and click-trap sweep in `page_script.js` always run, and YouTube ad pruning keeps running on a paused YouTube.
- Pop-ups without a click are dropped even when protection is off.
- "Sneaky redirect" detection can block a legitimate cross-site sign-in that is started by script; use Open anyway or pause the site.

## YouTube ad pruning

**What.** Removes ads on YouTube's web, mobile and TV interfaces.

**How.** Same approach as uBlock Origin and Brave scriptlets: `yt_clean.js` wraps `JSON.parse`, `Response.prototype.json` (for `/youtubei/v1/` responses) and the `ytInitialPlayerResponse` / `ytInitialData` globals to delete ad keys (`adPlacements`, `playerAds`, `adSlots`, ...) and ad renderers from lists before YouTube reads them. It hides ad slots with CSS, and every 300 ms mutes and skips anything that still plays as an ad.

**Where.** `assets/yt_clean.js`; injected in `createWebView` when "Block ads & trackers" is on.

**Limits.** YouTube changes often; this needs maintenance. On WebViews without document-start injection it runs later and is less effective.

## Private browsing

**What.** ⋮ → Private browsing (or the button on the home screen, or Settings → Privacy). The toolbar turns purple. No history, recents, cookies or site data are kept; everything is deleted when you close private browsing or the app. Blocking stays on, third-party cookies are refused, and release builds hide private pages from Recents and screenshots.

**Where.** `// ---- Private browsing ----`, `createWebView(isPrivate = true)`. Details and limits: [PRIVACY.md](PRIVACY.md).

## Tracking-parameter stripping

**What.** Removes `utm_*`, `fbclid`, `gclid`, `msclkid` and similar click IDs from addresses you type and from page navigations, plus `si` and `feature` on YouTube links.

**Where.** `HostRules.stripTracking`, `normalizeInput`, `shouldOverrideUrlLoading`. Setting: Settings → Privacy.

**Limits.** Applies to main-frame navigations only, not to requests a page makes in the background.

## Global Privacy Control

**What.** Pages see `navigator.globalPrivacyControl === true`, which asks sites not to sell or share your data where the law honours it.

**Where.** `page_script.js` (first IIFE). Applies in normal and private mode.

**Limits.** Only the JavaScript property is set. The `Sec-GPC: 1` request header is not sent, so server-side checks will not see it.

## Settings

Sections and what they control (`renderSettings`):

| Section | Settings |
|---|---|
| Navigation | Jump between items / Mouse pointer |
| Start screen | Home screen / Home page address, the address (default `https://www.google.com`, build-time `DEFAULT_HOME_URL`) |
| Search | Search engine (Google, DuckDuckGo, Bing, Brave, Startpage), Search suggestions (off by default) |
| Protection | Block ads & trackers, Block pop-ups, Block sneaky redirects, list of paused sites with Resume |
| Privacy | Private browsing, Remove tracking codes from links, Block third-party cookies (off by default) |
| Blocklist | Host count and last update, Update now, Extra blocklist URL (default StevenBlack, build-time `DEFAULT_BLOCKLIST_URL`) |
| Browsing | Auto-hide the top bar, Auto-unmute videos, Desktop sites, Clear browsing data (clears cookies, site storage, cache, history and recents; press twice to confirm) |
| Updates | Installed version and status, Check now / Update, Check for updates (daily, on by default) |

Settings save as you change them. Closing Settings reloads the page if anything that needs a reload changed.

## Other behaviour

- **Address bar:** text without a scheme becomes `https://…` if it looks like a host, otherwise a search with the chosen engine (see above).
- **Desktop site:** switches the user agent to desktop Chrome on Windows.
- **Open a URL from another app or adb:** an intent with a data URI loads it.
- **Exit:** Back on the first page asks "Press Back again to exit".

## Phone remote

**What.** ⋮ → Phone remote starts a small web server on the TV (port 8765, local network only) and shows a QR code. Scanning it opens a remote page in the phone's browser: D-pad or touchpad (moves the pointer in pointer mode), OK, Back, Home, Menu, media keys, volume and page up/down, live typing into the focused field, the TV's Home tiles, a Now playing seek bar while a video plays, and "open URL". No app install on the phone.

**Where.** `RemoteServer.kt`, `assets/remote.html`, the phone remote section of `BrowserActivity` (`showPhoneRemote`, `onKey`, `onText`, `onOpen`, `onMove`, `onMedia`, `state`, `home`), `RemoteInput.kt`. Protocol, security model and current gaps: [PHONE_REMOTE.md](PHONE_REMOTE.md).

**Limits.** Plain HTTP on the local network. In the current build the server keeps running until the app is closed, and the token stays the same for that app run.
