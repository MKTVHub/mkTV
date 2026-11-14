# Roadmap

Goal: one remote-first, privacy-first browsing experience on every TV. The Android TV app is the reference implementation. This plan is in phases; dates are not committed.

## Phase 0: today (Android TV / Google TV)

Shipped: jump and pointer navigation, auto-hiding bar, ⋮ menu, home screen with Popular / Favourites / Recently visited, YouTube TV interface, watch mode, auto-unmute, HLS/DASH detection with native player, ad/tracker/pop-up/redirect blocking with per-site pause, YouTube ad pruning, private browsing, tracking-parameter stripping, GPC, settings. See [FEATURES.md](FEATURES.md).

In progress: phone remote ([PHONE_REMOTE.md](PHONE_REMOTE.md)), first implementation in `RemoteServer.kt`.

## Phase 1: harden and release on Android TV, Google TV and Fire TV

### Store release checklist

- [ ] **Signing:** release signing config with the key kept outside the repo (CI secret); Play App Signing enrolled.
- [ ] **Minify:** turn on R8 (`isMinifyEnabled = true`) and resource shrinking; add keep rules if needed; test the release build on a TV.
- [ ] **Version:** `versionCode` / `versionName` bumped from 1 / 1.0 and scripted.
- [ ] **Cleartext:** tighten `network_security_config.xml` (it allows cleartext everywhere, with a comment saying to tighten it). Decide on a clear policy for `http://` sites (allow with a "Not secure" indicator, or upgrade to HTTPS first).
- [ ] **Backups:** set `allowBackup="false"` or add backup rules that exclude favourites, recents and icons.
- [ ] **Privacy fixes:** clear `files/icons` in Clear browsing data; only fetch icons for sites the user has visited or saved; send `Sec-GPC: 1`; isolate or clear web storage in private mode on WebViews without `MULTI_PROFILE` (or warn in the private-mode text).
- [ ] **Per-site pause:** make it also stop cosmetic filtering, the click-trap sweep and YouTube pruning, so pausing is a real escape hatch.
- [ ] **Phone remote hardening:** stop the server and rotate the token when the session ends or the app goes to the background; 128-bit token with constant-time compare; local-address and `Host` checks; size and rate limits ([PHONE_REMOTE.md](PHONE_REMOTE.md#implementation-status)).
- [ ] **Search engine:** add a choice (currently hard-coded to Google).
- [ ] **Leanback requirements:** banner (present), no touchscreen required (present), every screen usable by D-pad, a privacy policy URL (publish [PRIVACY.md](PRIVACY.md)).
- [ ] **Store content policy:** listing and screenshots show only licensed content; Popular tiles are mainstream licensed sites; no claims about watching paid content for free.
- [ ] **Accessibility:** content descriptions on all toolbar buttons (some are missing), readable sizes at 10 ft, colour contrast.
- [ ] **Fire TV:** test on Fire OS (same APK). Its WebView may lack `MULTI_PROFILE` or `DOCUMENT_START_SCRIPT`; the existing fallbacks must work there. Submit to the Amazon Appstore.
- [ ] **Localisation:** move user-facing strings out of Kotlin into `strings.xml`.
- [ ] **Tests:** keep `HostRulesTest` green; add tests for any logic moved out of `BrowserActivity`.

## Phase 2: next features

| Feature | Notes |
|---|---|
| Voice search | Use the remote's mic button (`KEYCODE_SEARCH` / `RecognizerIntent` on Android TV) to fill the address bar or the focused field. Fall back to the phone remote's keyboard where no recogniser exists |
| Continue watching | Remember the last position of long videos per URL (normal mode only, stored locally) and offer a row on the home screen. Opt-out in Settings; never in private mode |
| Page zoom | ◀ ▶ zoom levels in the ⋮ menu, remembered per site. WebView `textZoom` / `setInitialScale`, with jump-mode rect scaling already handled by `iw` |
| Per-site settings | One panel per site: blocking on/off, desktop site, zoom, auto-unmute, navigation mode. Generalise `pausedSites` into a per-site settings map in `Prefs` |
| Theater mode | A "lean back" layout for any page video: hide the toolbar, dim everything but the main `<video>`, scale it to fill the screen without using the Fullscreen API (useful where the site blocks fullscreen) |
| Tabs (light) | A small number of background pages, mainly so allowed pop-ups don't replace the current page |
| Downloads policy | Decide and document what happens when a page triggers a download |

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
