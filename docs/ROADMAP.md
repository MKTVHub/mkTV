# Roadmap

Goal: one remote-first, privacy-first browsing experience on every TV. The Android TV app is the reference implementation. This plan is in phases; dates are not committed.

## Phase 0: today (Android TV / Google TV)

Shipped: jump and pointer navigation, auto-hiding bar, ⋮ menu, home screen with Popular / Favourites / Recently visited, YouTube TV interface, watch mode, auto-unmute, HLS/DASH detection with native player, ad/tracker/pop-up/redirect blocking with per-site pause, YouTube ad pruning, private browsing, tracking-parameter stripping, GPC, settings, search engine choice with address-bar suggestions (local always, engine suggestions opt-in), voice search, a searchable History page, in-app updates from GitHub releases, tabs (pop-ups and links from other apps open in a new tab), a downloads manager, Favourites folders, per-site page zoom, full screen that fills the TV, more blocklists with custom lists and an allowlist, Settings in a sidebar of sections, an editable Home screen, Share Wi-Fi, and the phone remote ([PHONE_REMOTE.md](PHONE_REMOTE.md): touchpad, live typing, Home tiles, now-playing controls, Connect | Devices tabs, New code, on/off switch). See [FEATURES.md](FEATURES.md).

## Phase 1: harden and release on Android TV, Google TV and Fire TV

### Store release checklist

- [ ] **Signing:** release signing config with the key kept outside the repo (CI secret); Play App Signing enrolled.
- [ ] **Minify:** turn on R8 (`isMinifyEnabled = true`) and resource shrinking; add keep rules if needed; test the release build on a TV.
- [x] **Version:** `versionCode` / `versionName` bumped with every release (1.3.5 is versionCode 10).
- [ ] **Cleartext:** tighten `network_security_config.xml` (it allows cleartext everywhere, with a comment saying to tighten it). Decide on a clear policy for `http://` sites (allow with a "Not secure" indicator, or upgrade to HTTPS first).
- [ ] **Backups:** set `allowBackup="false"` or add backup rules that exclude favourites, recents and icons.
- [ ] **Privacy fixes:** clear `files/icons` in Clear browsing data; only fetch icons for sites the user has visited or saved; send `Sec-GPC: 1`; isolate or clear web storage in private mode on WebViews without `MULTI_PROFILE` (or warn in the private-mode text).
- [ ] **Per-site pause:** make it also stop cosmetic filtering, the click-trap sweep and YouTube pruning, so pausing is a real escape hatch.
- [ ] **Phone remote hardening:** done: 128-bit token with constant-time compare, server stops when the app goes to the background, New code unpairs all phones, per-device Disconnect, 64 KB body cap. Left: local-address and `Host` checks, rate limits ([PHONE_REMOTE.md](PHONE_REMOTE.md#implementation-status)).
- [x] **Search engine:** choice of Google, DuckDuckGo, Bing, Brave, Startpage (Settings → Search).
- [x] **Updates vs stores:** installed from Google Play or the Amazon Appstore, mkTV turns off its own update check, card and rows (`Updates.selfUpdates`, by installer package), so one build serves GitHub and the stores.
- [x] **Leanback requirements:** banner, leanback launcher, no touchscreen or other implied hardware required, every screen usable by D-pad, privacy policy at https://mktvhub.github.io/privacy.html. See [PLAY_STORE.md](PLAY_STORE.md).
- [ ] **Store content policy:** listing and screenshots show only licensed content; Popular tiles are mainstream licensed sites; no claims about watching paid content for free.
- [ ] **Accessibility:** content descriptions on all toolbar buttons (some are missing), readable sizes at 10 ft, colour contrast.
- [ ] **Fire TV:** test on Fire OS (same APK). Its WebView may lack `MULTI_PROFILE` or `DOCUMENT_START_SCRIPT`; the existing fallbacks must work there. Submit to the Amazon Appstore.
- [ ] **Localisation:** move user-facing strings out of Kotlin into `strings.xml`.
- [ ] **Tests:** keep `HostRulesTest` green; add tests for any logic moved out of `BrowserActivity`.

## Phase 2: next features

| Feature | Notes |
|---|---|
| Continue watching | Remember the last position of long videos per URL (normal mode only, stored locally) and offer a row on the home screen. Opt-out in Settings; never in private mode |
| ~~Page zoom~~ | **Shipped:** ⋮ → Zoom, remembered per site, default in Settings. Original note: ◀ ▶ zoom levels in the ⋮ menu, remembered per site. WebView `textZoom` / `setInitialScale`, with jump-mode rect scaling already handled by `iw` |
| ~~Bookmarks~~ | **Shipped:** Favourites folders with rename and reorder. Original note: Favourites are the bookmarks today. Add renaming, reordering and folders (TV Bro #27, #36, #48). Sync stays out of scope: no accounts, no servers |
| Per-site settings | One panel per site: blocking on/off, desktop site, zoom, auto-unmute, navigation mode. Generalise `pausedSites` into a per-site settings map in `Prefs` |
| Theater mode | A "lean back" layout for any page video: hide the toolbar, dim everything but the main `<video>`, scale it to fill the screen without using the Fullscreen API (useful where the site blocks fullscreen) |
| ~~Tabs (light)~~ | **Shipped:** ⋮ → Tabs; clicked pop-ups open in a new tab. Original note: A small number of background pages, mainly so allowed pop-ups don't replace the current page. Keep blocking by default; offer "Open in new tab" on the blocked pop-up snackbar. Risky: `webView` is swapped for private mode and used throughout `BrowserActivity`, so tabs need a small page-holder abstraction first |
| ~~Downloads~~ | **Shipped:** ⋮ → Downloads with open, remove, delete and APK install. Original note: The most-requested missing feature in comparable TV browsers (TV Bro #10, its most-commented issue). Today a download does nothing. Add a `DownloadListener` → Android `DownloadManager`, a remote-friendly downloads list, and "open with" for APKs and media |
| ~~Fullscreen reliability~~ | **Shipped:** where a site's player can't go full screen, mkTV fills the screen with the video itself. A test page set of common players is still worth building. Original note: The top complaint against comparable browsers (TV Bro #135 "Full screen stopped working"). Watch mode and ▼ full screen exist; build a test page set of common players (embedded iframes, custom controls) and fix gaps |
| ~~Custom filter lists~~ | **Shipped:** OISD, HaGeZi, Peter Lowe's and StevenBlack variants, any hosts or domain list by address, and an allowlist (cosmetic rules not yet). Original note: Users ask for uBlock-style lists (TV Bro #37, #143). mkTV takes one hosts URL; allow several, and consider cosmetic rules |
| ~~Page zoom~~ | Shipped (see above). Also asked for as a "page resolution" setting (TV Bro #128) |
| Voice into web fields | Voice search fills the address bar and mkTV's own fields; extend it to the focused field inside a page |

### Research notes (November 2026)

This is desk research, not confirmed demand from mkTV users.

**Sources.** Read in full: the AFTVnews article on improving Amazon Silk and its 22 reader comments (https://www.aftvnews.com/?p=30368). From search results only, threads not opened: TV Bro and SmartTube GitHub issue titles with their reaction and comment counts. From search snippets only: a Puffin TV review summary (https://android-stats.com/d/com.cloudmosa.puffinTV) and the end of Firefox for Fire TV (https://www.aftvnews.com/?p=36195). Reddit blocked automated access and Play Store reviews weren't reached.

**Most frequent complaints about comparable TV browsers:**

1. Broken fullscreen video (TV Bro #135; 16 issues mention it).
2. Downloads missing or broken (TV Bro #10, its most-commented issue; 30 issues; also Silk users).
3. Pop-ups and redirects (TV Bro #18, its most-reacted issue). mkTV already blocks these.
4. Custom filter lists, uBlock style (TV Bro #37, #143; Silk users).
5. Sending a link from the phone (TV Bro #79). mkTV's phone remote covers this.
6. Zoom and page scaling (TV Bro #128, #234).
7. Bookmarks, with sync (Silk's top request; TV Bro #27, #36, #48 on editing, ordering and removing).
8. Slow scrolling and a laggy cursor (TV Bro #160, #106).

SmartTube's top problem is YouTube breaking; its top requests are SponsorBlock (105 reactions) and DeArrow (66).

**Built from this round:** a searchable History page, search engine choice with suggestions, voice search, and in-app updates (for sideloaded installs; see the store checklist item above).

**Built since:** a downloads manager, per-site zoom, custom filter lists with an allowlist, Favourites folders, tabs with pop-ups opening in a new tab, and full screen that fills the TV. Still open from this list: continue watching, per-site settings, theater mode and voice into web fields (Phase 2 table).

## Phase 3: platform-agnostic core

Most of mkTV's value is in two places that do not depend on Android: the **injected JS layer** and the **remote protocol**. Extract them so other platforms reuse them instead of re-implementing them.

**Core package (`core/`, plain JS + JSON specs):**

- `page_script.js` and `yt_clean.js`, split into modules (cosmetic filters, click traps, spatial navigation, media control, auto-unmute, site rules) with a small build step that produces the injected bundles.
- The `__sp` call contract (inputs and JSON outputs) written down as an interface, with a test page and headless-browser tests.
- Host-list logic from `HostRules` (matching, `siteOf`, hosts parsing, tracking-param stripping) ported to JS with the same test vectors as `HostRulesTest`.
- The phone remote protocol and the remote web page, served by each platform.

**What stays per platform (the "shell"):** loading arbitrary sites, injecting scripts into them, intercepting requests for blocking, drawing the focus ring, input routing, private storage and a native player. Extracting the JS does not remove this work, and the shell is where the platforms differ most.

## Phase 4: other TV platforms

| Platform | Approach | Key questions |
|---|---|---|
| **Fire TV** | Same APK as Android TV (Phase 1) | WebView feature support varies by Fire OS version; test fallbacks |
| **LG webOS** | Packaged web app (plain JS or Enact) | A webOS web app is itself a web page. Opening third-party sites inside it means iframes (blocked by many sites' `X-Frame-Options` / CSP `frame-ancestors`, and cross-origin so the injected layer cannot reach in) or the system browser (no injection). Investigate whether webOS offers a WebView-like component or privileged app type that can load any origin and run scripts in it. If not, the webOS product is a different shape: a remote-first launcher plus phone remote, not a full browser |
| **Samsung Tizen** | Packaged web app (Tizen web) | Same constraints as webOS. Tizen web apps can open the system browser via app control but cannot inject into it. Check privileged APIs and partner-level options. Same fallback product shape if injection is not possible |
| **Roku** | Companion-remote only | Roku has no web engine for apps (BrightScript/SceneGraph). A browser is not possible. Possible product: a Roku channel that plays HLS/DASH URLs sent from the phone remote, and the phone remote controlling Roku via its External Control Protocol |
| **Others (Vidaa, Android-based sticks, set-top boxes)** | Evaluate case by case | Android-based devices get the APK; others follow the webOS/Tizen analysis |

Order: Fire TV first (no new code), then a webOS spike to answer the injection question, then Tizen. Roku only after the phone remote is stable.

## Principles for all phases

- Remote-first: every feature works with D-pad, OK and Back.
- Privacy by default: no telemetry; any new network request is documented in [PRIVACY.md](PRIVACY.md).
- Generic features: detect page structure, not specific sites. No features aimed at sites distributing unlicensed content.
