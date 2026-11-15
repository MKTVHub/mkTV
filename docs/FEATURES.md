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

**How.** `CursorView` runs a `Choreographer` frame loop while a direction is held. With **Pointer acceleration** on (default) speed ramps from 260 to 1,300 dp/s over 0.9 s; off, it moves at a steady 600 dp/s. **Pointer speed** (Slow / Normal / Fast) scales that by 0.6× / 1× / 1.6×, and also scales phone touchpad moves (1.8 dp per phone px at Normal). Both are in Settings → Navigation, apply at once and are saved in `Prefs` (`pointerSpeed`, `pointerAccel`). The math is in `PointerMotion` (unit-tested). At an edge it calls `onEdgeScroll`, which sends a mouse wheel event at the pointer.

**Where.** `CursorView.kt`, `PointerMotion.kt`, `BrowserActivity.applyPointerTuning`, `scrollAtCursor`, `touchAt`. Switch modes in ⋮ → Navigation or Settings → Navigation (`Prefs.navMode`).

**Limits.** Slower than jump mode for long pages. Hover-only menus get a touch, not a hover.

## Auto-hiding top bar

**What.** The toolbar (back, forward, reload, home, address, voice search, Watch full screen, shield, ⋮) slides away while you use a page. It comes back on page load, when you press ▲ at the top of the page, when the pointer reaches the top edge, or when any overlay is open.

**Where.** `// ---- Auto-hiding top bar ----` (`showBar`, `scheduleHideBar`, `hideBar`, `canHideBar`). Setting: Settings → Browsing → Auto-hide the top bar.

**Limits.** It stays visible while a page is loading.

## ⋮ menu

**What.** Opened with the ⋮ button or the remote's Menu (or Bookmark) key. Items: Tabs (n), Add to / Remove from Favourites, Private browsing, Navigation (Jump / Pointer), Zoom (current level), Desktop site (On / Off), History, Downloads, Phone remote, Share Wi-Fi, Clear browsing data…, Settings, About mkTV. The menu scrolls when it is taller than the screen.

**Where.** `buildMenu`, `showTabsMenu`, `showClearMenu`, `clearData`, `showZoomMenu`.

**Clear browsing data (menu).** Checkboxes for History (back/forward, the History page and Recently visited, on by default), Cookies & site data (off by default) and Cached images & files (on by default).

**Limits.** Saved site icons in `filesDir/icons` are not removed by either clear action.

## Home screen

**What.** The start screen (and the ⌂ button) shows the mkTV logo over a soft violet glow, and four sections:
- **Continue watching** (first, only when it has something): one row of the newest videos you didn't finish. See [Continue watching](#continue-watching).
- **Popular:** compact tiles, by default YouTube (opens `youtube.com/tv`, the TV interface), Netflix, Prime Video, Disney+, Spotify, YouTube Music, Twitch and TikTok. **Edit** lets you remove tiles and add others from one-press "＋" suggestions (`Prefs.POPULAR_SUGGESTIONS`, plus any default you removed).
- Each section is one row so the ones below stay in view. Favourites with more than five sites show four and a **Show all (n sites)** tile; it becomes **Show less**. Edit mode and folders always show every site.
- **Favourites:** add with ⋮ → Add to Favourites, "☆ Add this page", or by typing an address. One level of **folders**: a folder tile shows how many sites it holds; OK opens it in the same grid, and Back (or "◀ Favourites") returns. Adding while a folder is open puts the site in that folder. In **Edit**, OK on a tile chooses it and shows its actions under the grid: ◀ Move / Move ▶ (reorder), a name field (rename), Move into a folder / Move out of folder, Open folder, Remove. "＋ New folder" sits in the section tools. Everything saves as you change it. Removing a folder keeps its sites: they go back to the top level.
- **Recently visited:** one row (5) shown (12 stored; the rest are in History), one per site, newest first, excluding favourites. Not shown in private mode.

**Sections.** In Edit, each section (Popular, Favourites) has ↑ Move up / ↓ Move down and Hide. Hidden sections come back from Settings → Start screen, which has a Show switch per section and Home screen layout → Reset (default order, all sections shown, default Popular sites).

The header has **Phone remote**, **History** (see below) and **Private browsing** buttons. When a newer mkTV is available, an update card appears at the top (see [In-app updates](#in-app-updates)).

Settings → Start screen can replace it with a fixed home page address.

**How.** The glow is a drawable (`home_bg.xml`), so it costs no memory or start-up time. Tiles show a saved site icon or a coloured letter. Icons come from the page's favicon while you browse, or are fetched in the background (`/apple-touch-icon.png`, then `/favicon.ico`) for tiles that have none.

**Where.** `// region Shortcuts` (`arrangeHome`, `renderSectionTools`, `renderPopularAdd`, `Prefs.homeOrder`, `Prefs.hiddenSections`, `Prefs.popular`; Favourites folders and edit actions: `renderFavourites`, `renderFavActions`), `Bookmarks.kt` (pure tree operations, tested in `BookmarksTest`), `Prefs.bookmarks` (still stored under the old `shortcuts` key: links are `{title,url}`, a folder is `{title,items}`; old lists load unchanged), `Prefs.shortcuts` (flattened), `Prefs.recent`. The phone remote's `/api/home` lists favourites flattened, folders opened in place.

**Limits.** Once you make a folder, installing an older mkTV over this one is not supported: older builds can't read folders. The icon fetch contacts every site on the home screen once, including Popular sites you have never opened (not in private mode). See [PRIVACY.md](PRIVACY.md).

## Continue watching

**What.** mkTV remembers where you stopped in long videos (3 minutes or more) and shows up to 10 first on Home: site icon, title, a thin progress bar and "12 min left". OK opens the page. When you open a page with a saved position, a small notice offers **Resume from 12:34**: press ▼ to reach its Resume button (in watch mode too) and OK to jump there; it fades after 10 s, and Back on the Resume button closes it. Sites that resume by themselves get no notice. The native player (Watch full screen) resumes the page's position by itself and says "Resumed from 12:34". On Home, **Edit** shows ✕ on each tile (OK removes it) and the section tools (Move up / Move down / Hide).

On Android TV the same videos appear in the system **Play Next** row (Watch Next) with their progress, and in a **mkTV** channel on the TV home screen with your Favourites. Choosing a card opens mkTV on that page: in the tab already showing it, in the current tab if it is blank, otherwise in a new tab.

**How.** Every 10 s while mkTV is on screen (and when you switch tabs or leave mkTV), mkTV reads the page's main `<video>` with `__sp.status()`, the same call the phone remote's Now playing uses, and saves page address, title, site, position, duration and time. Only while the video plays; nothing under 30 s; within the last 60 s or past 95 % counts as finished and removes the entry. At most 20, newest first. The notice seeks with `__sp.seekTo`, like the phone remote's seek bar. `PlayerActivity` saves under the page's address on the same rules. The launcher rows are updated on a background thread when an entry is added, finished or removed, and when mkTV goes to the background.

**Where.** `ContinueWatching.kt` (pure rules and JSON, tested in `ContinueWatchingTest`; `HomeSections.merge` puts the new section on top of older saved orders), `TvHome.kt` (TV provider), the "Continue watching" block in `// region Shortcuts` (`continueTile`, `sampleWatching`, `commitWatch`, `maybeOfferResume`, `openFromTvHome`), `PlayerActivity.savePosition`, `Prefs.continueWatching`. Settings → Privacy → Remember where I stopped watching (on by default; off also forgets the list) and Clear Continue watching. Clear browsing data (History) clears it too.

**Limits.** Never in private browsing. Only the top page's largest visible video (players in cross-origin iframes aren't reachable, as for watch mode). On the YouTube TV interface the notice can't be reached with ▼ (the site has the remote), but YouTube resumes by itself. Launcher cards show the mkTV banner, not the site's icon (site icons are in mkTV's private storage). Google TV may not show third-party channels at all; Play Next is the dependable row. A card you remove from Play Next stays in Continue watching and comes back to Play Next only when you watch that video again. Positions on the launcher can be up to a few minutes behind while you keep watching.

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

**What.** Once a day at launch, mkTV asks GitHub for the latest release. If it's newer than the installed version, the home screen shows a card: "mkTV 1.3.0 is available" with **Update** and **Later**. Update downloads `mkTV.apk` (progress on the button) and opens Android's installer, which asks you to confirm. Later hides the card until the next version. Settings → Updates shows the status, has **Check now** / **Update**, and a switch to turn the daily check off.

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
- Normal video: ▼ full screen, ▲ playback controls.
- ◀ ▶ seek 10 s, OK pause/play, hold OK playback controls, Back leave full screen, then browse the page again.
- A key-hint dock slides up for 2.5 s after each press, and a large symbol flashes for feedback.

Media keys (play/pause, FF/REW, next/previous) work on any page, watch mode or not. After Back, watch mode does not re-enter on the same URL.

**How.** `checkWatch` asks `__sp.media()` about every second for the largest visible `<video>`. "Next" scrolls the video's scroll container by one screen (snap-scrolling feeds land on exactly one item), falling back to synthetic arrow-key events. Full screen calls the Fullscreen API on the player container, after sending a harmless key to the page to provide the user activation the API needs. If the API is missing or throws, the video is in a same-origin iframe, or no full-screen view appears within 0.9 s, mkTV uses **fill mode** instead: `__sp.fill()` pins the largest visible (preferably playing) `<video>`, or the same-origin iframe that holds it, over the whole viewport with injected CSS (`position:fixed`, full size, `object-fit:contain`, top z-index; ancestors lose transforms and filters that would trap it; everything else is hidden), and the top bar slides away. OK and Play/Pause toggle, ◀ ▶ seek, ▲ opens the playback controls, Back puts the page back as it was (marks and styles removed, scroll position restored). If a single-page site swaps the `<video>` element, fill follows the new one; after about 5 s with no video it gives up. Watch mode exits after about 3 s with no big video.

**Where.** `// ---- Watch mode ----` (`checkWatch`, `enterWatch`, `exitWatch`, `handleWatchKey`, `handleMediaKey`, `toggleTheater`, `showDock`, `flash`), `// region Zoom and full-screen fill` (`afterFullscreenRequest`, `enterFill`, `exitFill`, `syncFill`), `PageZoom.kt`, `page_script.js` (`mainVideo`, `feed`, `fullscreen`, `fill`, `unfill`).

**Limits.** Videos inside cross-origin iframes (most embedded players) and inside shadow DOM are invisible to the script, so watch mode, fill and media keys don't reach them. Use the player's own controls or "Watch full screen" if a stream was detected. Fill hides the site's own controls and any captions drawn as page elements (captions from `<track>` still show).

## Playback controls and sleep timer

**What.** In watch mode, ▲ (normal video) or holding OK (any video, including vertical feeds) opens a small panel over the video, also in full screen:
- **−10 s · Pause/Play · +10 s.**
- **Speed:** 0.75×, 1×, 1.25×, 1.5×, 2×. Remembered per site (host, `www.` ignored) and applied to every video on that site as it starts, including the native player. Private browsing keeps speeds in memory only.
- **Sleep timer:** Off, 15, 30, 60 min, End of video. The heading shows the time left. When it fires, every video and audio in the open tabs pauses (and the native player, if it is on screen), the notice "Sleep timer: paused" appears with a Play button, and the screen is allowed to turn off until the next key press. mkTV stays open. **End of video** pauses the current video about half a second before it ends, so the site's autoplay of the next video never starts; it is offered only for videos with a duration (not live streams).

Every button has an icon; the current speed and timer are highlighted. Changes apply at once; Back (or Menu) closes the panel. The D-pad stays inside it. The phone remote's Now playing card has the same speed and sleep controls.

**How.** The panel is built in code (`renderControls`) and counts as an overlay (`overlayOpen`), but does not bring the toolbar back. Speed: `__sp.rate(r)` sets the wanted speed for the document; a capture-phase `playing` listener applies it once per media source (so a speed picked in the site's own menu stays for that video). The site's speed is sent on page load (`applySpeed`, next to `applyZoom`) and when watch mode starts. Timed sleep is a single `Handler.postDelayed` (no polling; the panel's countdown updates once a second only while it is open). End of video: `__sp.sleepEnd(true)` arms a capture-phase `timeupdate` check in the page; the app re-arms it and learns that it fired with checks spaced by the video's remaining time (2 to 30 s apart). After pausing, the page holds off autoplay for a few seconds; Play from mkTV ends the hold.

**Where.** `// ---- Playback controls` in `BrowserActivity` (`openControls`, `renderControls`, `setSpeed`, `applySpeed`, `setSleep`, `checkSleepEnd`, `sleepNow`), `PlaybackSpeed.kt` (levels, labels, per-site storage, sleep choices; unit-tested), `Prefs.siteSpeed`, `page_script.js` (`rate`, `pause`, `sleepEnd`), `PlayerActivity` (`EXTRA_SPEED`, `pauseForSleep`, `sleepAtEnd`).

**Limits.** Players in cross-origin iframes and sites that reset `playbackRate` themselves may ignore the speed. A speed picked with the native player's speed button is not saved. End of video in the native player pauses at the stream's end (a single stream has nothing to autoplay). If a site starts its next video by other means than `ended` (for example a timer in the last seconds), End of video may pause the next one instead. The timer stops if mkTV is closed.

## Page zoom

**What.** Makes whole pages bigger or smaller: ⋮ → **Zoom** shows − / level / + / Reset inside the menu (75, 90, 100, 110, 125, 150, 175, 200 %). Changes apply at once and are remembered for the site (host, ignoring `www.`); Reset returns the site to the default. Settings → Browsing → **Default zoom** sets the level for every site without its own, and **Reset sites** forgets the per-site levels. In private browsing, zoom changes last only for that session and are not saved. TV interfaces (YouTube TV) ignore the default zoom.

**How.** CSS `zoom` on the page's `<html>` (`__sp.zoom`), applied when a page starts, first paints and finishes loading. Unlike WebView's text zoom it enlarges images and buttons too, and unlike page scale it reflows the page to the screen width, so nothing needs sideways panning.

**Where.** `// region Zoom and full-screen fill` (`zoomFor`, `applyZoom`, `setZoom`, `showZoomMenu`, `zoomSettingRow`), `PageZoom.kt`, `Prefs.defaultZoom` / `Prefs.siteZoom`, `page_script.js` (`__sp.zoom`, `__sp.zoomFactor`).

**Limits.** A page can show briefly at 100 % before its level applies. Elements sized with `vh`/`vw` grow with the zoom, so a full-height banner can become taller than the screen. Not available from the phone remote.

## Auto-unmute

**What.** Many sites start video muted. When a large video starts playing muted, mkTV clicks the site's own unmute button if it finds one, then unmutes the element. Small hover previews and videos inside an ad container stay muted.

**Where.** `page_script.js` (auto-unmute IIFE). Setting: Settings → Browsing → Auto-unmute videos (full effect after an app restart).

## Stream detection and native player

**What.** When a page requests an HLS (`.m3u8`) or DASH (`.mpd`) manifest, a **Watch full screen** button appears in the toolbar (with a count if there are several; you pick from a list). It opens the stream in a native ExoPlayer, which is usually smoother on TV hardware than the in-page player.

**How.** `shouldInterceptRequest` records manifest URLs with the Referer and Origin the page sent, skipping variant playlists under an already-seen master. The player receives those headers plus the WebView user agent and the cookies for that URL.

**Player controls.** The player has its own remote-first overlay instead of Media3's controls:

- **Top:** the page's title (or the file name from Downloads; without a title, the site), the site under it, and the time of day.
- **Bottom:** a progress bar (played part in the accent gradient, buffered part lighter), elapsed and total time (**LIVE** for live streams), and **Ends at 9:42 PM** (follows the TV's 12/24-hour setting and the playback speed; hidden for live streams).
- **Buttons:** back 10 s, play/pause, forward 10 s, **Next** (only when the player has a next item; every caller passes a single stream or file today, so it stays hidden), **Subtitles** (only when the stream has subtitle tracks: Off → each track → Off) and **Speed** (cycles 0.75× to 2×).
- **Keys:** the bar has focus when the overlay opens. ◀ ▶ seek 10 s per press; holding one moves a preview on the bar (time and "+1:30" in a bubble) by 10 s steps, then 30 s after about 1 s and 60 s after about 3 s, and seeks once on release. Rewind / Fast forward do the same from anywhere. OK and Play/Pause toggle play/pause; Next skips when there is a next item. ▼ moves to the buttons, ◀ ▶ between them, ▲ back to the bar. The overlay hides after 4 s without a key while playing and stays up while paused; any key brings it back (◀ ▶ and OK act at once). Back hides the overlay first, then leaves the player.

**Where.** `HostRules.isStreamUrl`, `isVariantOf`, `DetectedStream`, `updateStreamButton`, `openStreamPicker`, `playNative`, `PlayerActivity.kt` (overlay, keys, seek, subtitles), `PlayerBar.kt` (the drawn progress bar), `PlayerUi.kt` (pure: seek steps and clamping, time labels, "Ends at", title fallback; tested in `PlayerUiTest`), `res/layout/activity_player.xml`. The player starts at the site's saved speed (see Playback controls).

**Limits.** DRM-protected streams will not play. Streams that need tokens generated by page scripts per segment may fail. Detection is by URL extension only; manifests without `.m3u8`/`.mpd` in the URL are missed. There is no audio language or quality picker (ExoPlayer picks the audio track and adapts quality by itself). Next has no playlist to follow yet: pages hand over one stream at a time.

## Ad and tracker blocking

**What.** On by default. The shield in the toolbar shows a badge with the number blocked on this page; OK opens a panel with counts for ads and trackers, pop-ups and redirects, the all-time total, and an ON/OFF switch to pause blocking on this site.

**How.**
- **Network:** subresource requests to blocked hosts (and their subdomains) get an empty response, unless the host (or a parent domain) is on the allowlist. The blocked set is `assets/adhosts.txt` (a small, conservative list) plus every filter list that is on, merged into one deduplicated set (see Filter lists below).
- **Cosmetic:** `page_script.js` hides common ad containers with CSS.
- **Click traps:** `page_script.js` removes invisible full-screen layers that turn any click into a pop-up (fixed/absolute, z-index ≥ 100, over half the viewport, nearly transparent, no media and no text). It sweeps for 10 s after load.
- **Pop-ups:** pop-ups without a click are dropped. Pop-ups from a click open in a **new tab** (see [Tabs](#tabs)) only if they stay on the same site and the host is not blocked; blocked hosts never get a tab. "Open anyway" on a blocked pop-up also opens it in a new tab.
- **Redirects:** script-driven jumps to another site without a click are blocked; server redirects are allowed unless blocklisted. Links to apps (`intent:`, `market:` and similar) are always blocked.
- **Snackbar:** each blocked pop-up or redirect shows a small message with **Open anyway**, which lets that URL through once.

**Where.** `AdBlocker.kt`, `FilterLists.kt`, `HostRules.isBlocked/parseHosts/sameSite`, `shouldInterceptRequest`, `shouldOverrideUrlLoading`, `onCreateWindow`, `// region Toolbar` (`updateShield`, `bindShieldPanel`, `toggleSitePause`, `showBlocked`), `Prefs.pausedSites`, `Prefs.totalBlocked`.

**Limits.**
- Ads served from the site's own domain are not blocked by the host list.
- Pausing a site, or turning "Block ads & trackers" off, stops network, pop-up and redirect blocking only. The cosmetic CSS and click-trap sweep in `page_script.js` always run, and YouTube ad pruning keeps running on a paused YouTube.
- Pop-ups without a click are dropped even when protection is off.
- "Sneaky redirect" detection can block a legitimate cross-site sign-in that is started by script; use Open anyway or pause the site.

### Filter lists and allowlist

**What.** Settings → Blocklists. A summary row shows the total number of blocked domains with **Update all**. Each list has an ON/OFF area (one D-pad stop: the name, "N domains · updated …" or why the last update failed, and the pill), an **Update** button when on, and **Remove** (press twice) for lists you added. **Add a list by address** takes any hosts or domain list URL (added on the keyboard's Done, not when focus leaves). **Never blocked** is the allowlist: remove entries, add a domain, or press **Allow <site>** for the page you came from (not offered in private mode). An allowlisted domain also covers its subdomains and wins over every list. To stop all blocking on one site, use the shield's ON/OFF instead.

Built-in presets (none bundled; each is downloaded from its author only when on):

| List | URL | Format | Licence | Default |
|---|---|---|---|---|
| StevenBlack unified | `https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts` | hosts | MIT | on |
| StevenBlack + gambling | `…/StevenBlack/hosts/master/alternates/gambling/hosts` | hosts | MIT | off |
| StevenBlack + fake news | `…/StevenBlack/hosts/master/alternates/fakenews/hosts` | hosts | MIT | off |
| OISD small | `https://small.oisd.nl/domainswild` | `*.domain` | GPL-3.0 | off |
| OISD big | `https://big.oisd.nl/domainswild` | `*.domain` | GPL-3.0 | off |
| HaGeZi Light | `https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt` | domains | GPL-3.0 | off |
| HaGeZi Normal | `…/hagezi/dns-blocklists/main/wildcard/multi-onlydomains.txt` | domains | GPL-3.0 | off |
| HaGeZi Pro | `…/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt` | domains | GPL-3.0 | off |
| Peter Lowe's list | `https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext` | hosts | McRae GPL (non-commercial use) | off |

**How.**
- **Parsing** (`FilterLists.parseLine`): hosts lines (`0.0.0.0 host`, `127.0.0.1 host`, `::1 host`, several hosts per line), plain domains, `*.domain`, and bare Adblock-style `||domain^`. Ignored: comments (`#` at line start or after a space, `!`), `[Adblock …]` headers, `@@` exceptions, cosmetic rules (`##`, `#@#`, `#?#`, `#$#`), rules with paths, wildcards or `$options`, IP addresses, `localhost`-style names and anything that is not a valid host name (so an HTML error page adds nothing).
- **Updates:** at launch and whenever mkTV comes back on screen, lists whose copy is over a day old are downloaded, one at a time on one background worker. A download goes to a temp file and replaces the cached copy only if it contains at least one domain; on any failure the old copy keeps working, the row shows the error, and automatic retries wait 3 hours (Update always tries). Up to 5 redirects are followed, including http to https. Downloads over 40 MB are refused.
- **Memory:** one `HashSet`, built by streaming each cached file line by line; lookups walk the host's parent domains as before. Above 1,000,000 domains Settings warns; at 1,500,000 the rest is left out and Settings says so. All presets on together come to roughly 500,000 domains.
- **Migration:** an older install's "Extra blocklist URL" becomes StevenBlack on (default URL), StevenBlack off (empty), or a list added by you (another URL); the old `remote_hosts.txt` is reused as StevenBlack's copy.

**Where.** `FilterLists.kt` (presets, parser, allowlist matching, URL and domain clean-up), `AdBlocker.kt` (cache, downloads, rebuild), `Prefs.filterListsOn/customFilterLists/allowlist`, `// region Settings` (`renderFilterLists`, `listRow`).

**Limits.** Domain lists only: no URL-path, cosmetic or exception rules from Adblock lists. Counts are entries per list (a domain in two lists is counted in each but stored once). Several large lists can use 50–100 MB of the TV's memory.

## YouTube ad pruning

**What.** Removes ads on YouTube's web, mobile and TV interfaces.

**How.** Same approach as uBlock Origin and Brave scriptlets: `yt_clean.js` wraps `JSON.parse`, `Response.prototype.json` (for `/youtubei/v1/` responses) and the `ytInitialPlayerResponse` / `ytInitialData` globals to delete ad keys (`adPlacements`, `playerAds`, `adSlots`, ...) and ad renderers from lists before YouTube reads them. It hides ad slots with CSS, and every 300 ms mutes and skips anything that still plays as an ad.

**Where.** `assets/yt_clean.js`; injected in `createWebView` when "Block ads & trackers" is on.

**Limits.** YouTube changes often; this needs maintenance. On WebViews without document-start injection it runs later and is less effective.

## Tabs

**What.** Lightweight tabs. ⋮ → **Tabs (n)** (or hold the remote's Menu key) opens the switcher: "＋ New tab" (opens Home), then one row per tab with its icon, title and site. OK switches; ◀▶ reaches the ✕ that closes a tab. A pop-up you clicked opens in a new tab in front, with a short "Opened in a new tab" notice.

**Back.** On a tab's first page (nothing left to go back to) with other tabs open, Back closes the tab and returns to the one you used before it. With one tab, Back works as before (press again to exit). On YouTube TV a short Back goes to the page, so use the switcher there.

**Private tabs.** Tabs opened from a private tab (pop-ups, New tab) are private too, with the same private profile. The switcher shows only the tabs of the current kind. Closing private browsing closes all private tabs; closing the last private tab closes private browsing.

**How.** Up to 6 tabs keep a live WebView; opening or showing more discards the least recently used one, keeping its address and back/forward list in memory, and it reloads when you show it again. At most 20 tabs are kept (the oldest is closed). A tab going to the background has its video and audio paused and its WebView paused. When Android reports low memory (`onTrimMemory`), every background tab is discarded. Tabs are never saved: they don't survive closing the app.

**Where.** `Tabs.kt` (`Tab`, pure `TabPolicy`, tested in `TabsTest`), `// ---- Tabs ----` in `BrowserActivity` (`showTab`, `discardTab`, `closeTab`, `openInNewTab`), `showTabsMenu`, `onCreateWindow`, `onBackPressed`.

## Private browsing

**What.** ⋮ → Private browsing (or the button on the home screen, or Settings → Privacy). The toolbar turns purple. No history, recents, cookies or site data are kept; everything is deleted when you close private browsing or the app. Blocking stays on, third-party cookies are refused, and release builds hide private pages from Recents and screenshots.

Private browsing has its own tabs (see [Tabs](#tabs)).

**Where.** `// ---- Private browsing ----`, `createWebView(isPrivate = true, tab)`. Details and limits: [PRIVACY.md](PRIVACY.md).

## Tracking-parameter stripping

**What.** Removes `utm_*`, `fbclid`, `gclid`, `msclkid` and similar click IDs from addresses you type and from page navigations, plus `si` and `feature` on YouTube links.

**Where.** `HostRules.stripTracking`, `normalizeInput`, `shouldOverrideUrlLoading`. Setting: Settings → Privacy.

**Limits.** Applies to main-frame navigations only, not to requests a page makes in the background.

## Global Privacy Control

**What.** Pages see `navigator.globalPrivacyControl === true`, which asks sites not to sell or share your data where the law honours it.

**Where.** `page_script.js` (first IIFE). Applies in normal and private mode.

**Limits.** Only the JavaScript property is set. The `Sec-GPC: 1` request header is not sent, so server-side checks will not see it.

## Settings

**What.** A full-screen page with a sidebar of sections on the left; the chosen section's settings show on the right. The mkTV version is shown at the bottom. Closing Settings with no page behind it returns to Home.

Sections, in sidebar order (`SETTINGS_TABS`, `renderSettings`):

| Section | Settings |
|---|---|
| Navigation | Jump between items / Mouse pointer; Pointer speed (Slow / Normal / Fast); Pointer acceleration |
| Start screen | Home screen / Home page address, the address (default `https://www.google.com`, build-time `DEFAULT_HOME_URL`), Show Popular / Favourites / Recently visited, Home screen layout (current order; Reset) |
| Search | Search engine (Google, DuckDuckGo, Bing, Brave, Startpage), Search suggestions (off by default) |
| Protection | Block ads & trackers, Block pop-ups, Block sneaky redirects, list of paused sites with Resume |
| Blocklists | Total blocked domains and Update all; built-in filter lists (on/off, count, last update, Update); lists added by address (also Remove); Add a list by address; Never blocked (allowlist) with Allow <current site> and Allow a domain |
| Privacy | Private browsing, Remove tracking codes from links, Block third-party cookies (off by default), Remember where I stopped watching (on by default), Clear Continue watching |
| Parental controls | PIN lock, Change PIN, what the PIN protects, Safe search, Blocked sites, Forgot PIN (see [Parental controls](#parental-controls)) |
| Phone remote | On/off switch (turns on the first time you open ⋮ → Phone remote; off stops the server), Unpair all phones (New code), and a note to use it on home Wi-Fi |
| Browsing | Auto-hide the top bar, Auto-unmute videos, Default zoom (− / + and Reset sites), Desktop sites, Clear browsing data (clears cookies, site storage, cache, history, recents and Continue watching; press twice to confirm) |
| Downloads | Where files are saved, and Open (the Downloads list) |
| Updates | Installed version and status, Check now / Update, Check for updates (daily, on by default) |

Settings save as you change them. Closing Settings reloads the page if anything that needs a reload changed.

## Parental controls

**What.** For a shared living-room TV. Settings → Parental controls:

- **PIN lock.** Turning it on asks for a 4-digit PIN twice. Turning it off, or Change PIN, always asks for the current PIN.
- **Ask for the PIN before** (all on when a PIN is set): opening Settings; opening private browsing; Downloads (starting a download, and installing an APK from Downloads); the phone remote (turning it on, from ⋮ / Home / Settings, and New code, including the "Unpair all" notice). The Parental controls section itself always needs the PIN, even when "Opening Settings" is off; while locked it shows a summary and an Unlock button.
- **Safe search.** Google searches get `safe=active`, Bing `adlt=strict`, DuckDuckGo `kp=1`, replacing any weaker value. Applies to searches typed or spoken in the address bar (`WebSearch`), to every app-initiated load, and to page navigations to those search addresses.
- **Blocked sites.** A list of domains that never load, with their subdomains, in every tab (private too). Type a site in "Block a site" and press Done or move away; Remove takes it off. A blocked page shows a calm "Blocked by parental controls" notice instead of the site; requests to it from other pages (frames, scripts, images) get an empty answer. The phone remote's "open URL" is refused for a blocked site and the TV shows a notice.
- **Forgot PIN?** Clearing mkTV's data in the TV's own settings (Apps → mkTV → Clear data) resets it, and also deletes Favourites, history and every other mkTV setting.

The PIN pad is a full-screen overlay: a 3×4 grid of big keys (1–9, ✕ Cancel, 0, ⌫), four dots, no OK key (the 4th digit submits). The remote's number keys and the phone remote's typing work too; Back cancels. A wrong PIN shakes the dots and says how many tries are left; after 5 wrong tries the pad waits 30 s with a countdown (kept across app restarts). A correct PIN unlocks mkTV for 5 minutes, or until mkTV goes to the background.

**How.** `Parental.kt` (pure, unit-tested in `ParentalTest`): PBKDF2-HMAC-SHA256 with a random 16-byte salt and 12,000 iterations, written on `javax.crypto.Mac` because `SecretKeyFactory` has no PBKDF2-SHA256 before Android 8; constant-time compare; lockout and unlock-window rules; blocked-site matching (`HostRules.isBlocked`); SafeSearch URL rewriting; the blocked page's HTML. `PinPad.kt` draws the pad and runs the enter / confirm / change steps. In `BrowserActivity`: `needsPin`, `askPin` and `guarded` gate `showSettings`, `enterPrivate`, `showPhoneRemote`, `setRemoteEnabled`, `unpairPhones`, the WebView `DownloadListener` and `DownloadCenter.Host.beforeInstall`. Blocking is the first check in `shouldInterceptRequest` (which serves the notice), and also in `shouldOverrideUrlLoading`, because `shouldInterceptRequest` isn't called again for the target of a redirect (`youtu.be`, short links, a search result's `/url?q=`): a redirect into a blocked site is reloaded as a fresh request, which gets the notice. Both checks come before the ad blocker's, so turning blocking off, pausing a site, the allowlist or "Open anyway" can't get past it. A `ServiceWorkerClientCompat` gives a blocked site's service worker (installed before it was blocked) empty answers. SafeSearch runs in `load()` and first thing in `shouldOverrideUrlLoading` for main frames. `dispatchKeyEvent` hands digits to the pad and swallows Menu and Search while it is open; `onText` feeds phone typing to it; `onBackPressed` cancels it first; `onStop` locks again.

**Where.** `Parental.kt`, `PinPad.kt`, `// ---- Parental controls` in the Settings region (`renderParental`), `Prefs.pinRecord`, `pinGuards`, `pinLockout`, `safeSearch`, `blockedSites`.

**Limits.**
- A determined person with adb, or who clears mkTV's data, gets past it; it's meant to keep children from changing things, not to stop the TV's owner. A 4-digit PIN's hash can be brute-forced quickly by anyone who can read mkTV's storage (for example from a device backup).
- SafeSearch covers Google, Bing and DuckDuckGo only. Brave and Startpage aren't rewritten, and searches a results page makes in the background without navigating can slip through. YouTube Restricted Mode is not set: it needs a header or cookie on every YouTube request, which WebView can't add to a page's own requests.
- An image or script on an allowed site that redirects to a blocked site still loads (only main-frame and frame navigations are re-checked after a redirect). Service-worker blocking needs a WebView with `SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST`.
- The phone remote page isn't told that an open was refused (the TV shows the notice); Search suggestions aren't filtered by Safe search.
- Blocking is by domain: other sites showing the same content, or a site reached by IP address, aren't blocked. Home tiles and History still list blocked sites (opening them shows the notice).
- Any activity in front of mkTV (the native player, voice search, the system installer) counts as leaving mkTV, so the PIN is asked again afterwards.
- While the phone remote is already on, its pairing QR can be shown without the PIN (a paired phone still goes through the same PIN prompts).
- mkTV's own update (Settings → Updates) is not behind the PIN.

## Other behaviour

- **Address bar:** text without a scheme becomes `https://…` if it looks like a host, otherwise a search with the chosen engine (see above).
- **Desktop site:** switches the user agent to desktop Chrome on Windows.
- **One mkTV (single instance):** the activity is `singleTask`, so opening a link from another app or adb (an intent with a data URI) never starts a second mkTV. The link opens in a new tab of the running mkTV, or in the current tab if it is still blank (`onNewIntent`). Cards on the TV home screen carry a marker and reuse a tab already on that page.
- **Exit:** Back on the first page asks "Press Back again to exit".

## Phone remote

**What.** ⋮ → Phone remote starts a small web server on the TV (port 8765, local network only) and shows a QR code. Scanning it opens a remote page in the phone's browser: D-pad or touchpad (moves the pointer in pointer mode), OK, Back, Home, Menu, media keys, volume and page up/down, **Add to Favourites** for the TV's current page (a star that turns to **In Favourites**; tap again to remove), live typing into the focused field, the TV's Home tiles, a Now playing card while a video plays (seek bar, ±10 s, play/pause, full screen, captions, a speed chip that cycles 0.75× to 2×, and a sleep timer chip with Off / 15 / 30 / 60 min / End), and "open URL". No app install on the phone. The QR code has the mkTV logo in its centre.

**Panel.** Two tabs: **Connect** (QR code, steps, address, Help, New code, Share Wi-Fi) and **Devices (n)**, which lists each phone or computer using the remote with the current code (address and last activity) with a **Disconnect** button. Disconnect refuses that address until the next New code (everyone shares one code, so the address is what can be shut out).

**Pairing.** The pairing code is about 128 bits from `SecureRandom` and is kept across restarts, so a bookmarked remote page keeps working. **New code** (press twice to confirm) or Settings → Phone remote → Unpair all phones makes a new code and unpairs every phone. When a new device connects, the TV shows "New phone connected" with Unpair all. Settings → Phone remote has an on/off switch.

**Where.** `RemoteServer.kt`, `assets/remote.html`, the phone remote section of `BrowserActivity` (`showPhoneRemote`, `onKey`, `onText`, `onOpen`, `onMove`, `onMedia`, `onFavourite`, `state`, `home`), `RemoteInput.kt`. Protocol, security model and current gaps: [PHONE_REMOTE.md](PHONE_REMOTE.md).

**Lifetime.** Once turned on, the server runs while mkTV is on screen and stops when mkTV goes to the background (`onStart` / `onStop`). The port and code stay the same.

**Limits.** Plain HTTP on the local network, with no source-address or `Host` check and no rate limit; anyone on the network with the code can use it. Use it on home Wi-Fi, not guest or public networks.
