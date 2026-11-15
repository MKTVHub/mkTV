# Privacy

mkTV has no accounts, no analytics, no telemetry, no crash reporting and no ad SDKs. MKTVHub runs no servers that the app talks to. This page lists exactly what the app stores, what it sends, and what private browsing does and does not do.

## What is stored on the TV

In normal mode, in the app's private storage (`/data/data/com.mktvhub.mktv`):

| Data | Where | Cleared by |
|---|---|---|
| Settings (home page, toggles, navigation mode, blocklist URL, search engine) | `SharedPreferences` file `browser` | Uninstall / Android "Clear storage" |
| Favourites (title + URL) | same | Removing them on the home screen (Edit) |
| Recently visited (up to 12 entries: title + URL, one per site) | same | Clear browsing data (History) |
| History (up to 500 entries: title, URL, time) | `SharedPreferences` file `history` | Clear browsing data (History, or Settings → Browsing), History → Clear history, Remove on a row |
| Update check result (latest version number, its download URL and size, time of the last check, a version you chose "Later" for) | `SharedPreferences` file `browser` | Uninstall / "Clear storage" |
| A downloaded update (`mkTV-<version>.apk`) | `cache/updates/` | Replaced by the next download; Android may clear app caches |
| Sites where blocking is paused | same | Settings → Protection → Resume |
| All-time blocked count | same | Uninstall |
| Site icons (one PNG per site you visited or saved, and for the Popular tiles) | `files/icons/` | Uninstall / "Clear storage" only |
| Downloaded blocklist | `files/remote_hosts.txt` | Replaced on refresh |
| Cookies, cache, local storage, IndexedDB, back/forward history | WebView's own storage | Clear browsing data |

`android:allowBackup` is currently `true`, so Android's device backup may include the settings and history files with favourites, recents and history.

## What is sent over the network

mkTV itself only makes these requests. Everything else is the web pages you open, loaded by the system WebView.

1. **Blocklist download.** At app start, if the cached copy is over a day old (or when you press Update now), mkTV downloads the hosts file from the Blocklist URL. Default: `https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts` (GitHub sees your IP address). Clear the URL in Settings → Blocklist to turn this off.
2. **Site icons.** For home-screen tiles without an icon, mkTV requests `https://<site>/apple-touch-icon.png` and then `https://<site>/favicon.ico`, once per site per app session, with the WebView user agent. This includes the Popular tiles, even if you have never opened those sites. Not done in private mode.
3. **The native player.** "Watch full screen" requests the stream directly, sending the Referer, Origin and cookies the page used for that stream, and the WebView user agent.
4. **Update check.** Once a day at launch (and when you press Check now), mkTV requests `https://api.github.com/repos/MKTVHub/mkTV/releases/latest` with the user agent `mkTV/<version>`. GitHub sees your IP address and the mkTV version. Nothing about your browsing is sent. Turn it off in Settings → Updates → Check for updates. In private mode it still runs, since it carries nothing from the session.
5. **Update download.** Only when you press Update: mkTV downloads `mkTV.apk` from the GitHub release (GitHub redirects to its download servers).
6. **Search suggestions (off by default).** When you turn on Settings → Search → Search suggestions, what you type in the address bar is sent to the chosen engine's suggestion service as you type, without cookies and with the WebView user agent: Google (`suggestqueries.google.com`), DuckDuckGo (`duckduckgo.com/ac`), Bing (`api.bing.com`), Brave (`search.brave.com/api/suggest`) or Startpage (`www.startpage.com/osuggestions`). Text that looks like an address is never sent, and nothing is sent in private browsing. Suggestions from your own Favourites and History are matched on the TV and send nothing.

Address-bar text that is not a URL is searched with the engine you chose in Settings → Search (default Google: `https://www.google.com/search?q=...`).

**Voice search** uses the TV's own speech recogniser through Android's standard voice input screen. mkTV doesn't record audio and has no microphone permission. On Google TV the recogniser is Google's speech service, so what you say is processed under Google's terms, as with the TV's own voice search.

What pages receive from mkTV beyond a normal browser:

- A **Smart-TV user agent** on `youtube.com/tv`, and a desktop Chrome user agent when "Desktop site" is on.
- **Global Privacy Control:** `navigator.globalPrivacyControl` is `true` in all modes. The `Sec-GPC` HTTP header is not sent.
- Links have tracking parameters (`utm_*`, `fbclid`, `gclid`, ...) removed when "Remove tracking codes from links" is on (default).

## What blocking protects

- Requests to known ad and tracker hosts are answered locally with an empty response and never leave the TV.
- Pop-ups and cross-site redirects that you did not click are stopped.
- Third-party cookies are allowed by default in normal mode (some sign-ins and embedded players need them). Turn on Settings → Privacy → Block third-party cookies to refuse them. They are always refused in private mode.

## Private browsing

### Guarantees

When the TV's WebView supports multiple profiles (`MULTI_PROFILE`, most up-to-date Android TV devices):

- Private pages use a separate WebView profile with its own cookies, local storage, IndexedDB, cache and history.
- When you close private browsing, or the app closes, the private WebView is destroyed and the profile is deleted. A leftover profile from a crash or force-stop is deleted at the next launch.
- Nothing from the session is written to mkTV's own storage: no recents, no site icons, no favourites unless you add one on purpose (adding a favourite in private mode saves it permanently).
- Third-party cookies are refused, ad and tracker blocking stays on, and tracking parameters are stripped.
- Release builds set `FLAG_SECURE`, which keeps private pages out of the Recents screen thumbnails and screenshots. Debug builds do not, for testing.

### Limits

- **Your traffic is not hidden.** Websites see your IP address. Your internet provider, your network administrator and anyone on your network can see which sites you connect to (DNS and TLS server names). Private browsing does not change that. Use a VPN on the TV if you need to hide it.
- **Sites can still recognise you** by your IP address, your TV model and user agent, and browser fingerprinting, and you are signed in to any site where you sign in during the session.
- **Older WebViews (no `MULTI_PROFILE`).** mkTV refuses all cookies while private and disables the cache, but local storage and IndexedDB are shared with normal mode. Data a site stores there during a private session stays on the TV and is visible to that site in normal mode afterwards. The in-app private-mode text does not yet mention this.
- **The phone remote** (when used) sends page titles and URLs to the phone over plain HTTP on your local network. See [PHONE_REMOTE.md](PHONE_REMOTE.md).
- The TV's operating system, other apps with network access, and the TV maker's own data collection are outside mkTV's control.

## Phone remote

The phone remote server is meant for your local network and requires a random token on every request. It sends nothing to the internet. In the current build, once started it keeps running (with the same token) until the app is closed, including while the app is in the background, and it listens on all network interfaces without checking that requests come from the local network. Because it uses plain HTTP, someone sniffing your local network could read the token during a session. Details: [PHONE_REMOTE.md](PHONE_REMOTE.md).

## Known gaps

These are tracked for fixing before a store release (see [ROADMAP.md](ROADMAP.md)):

- Clear browsing data does not delete saved site icons, which reveal sites you visited.
- The home-screen icon fetch contacts Popular sites you never opened.
- `allowBackup="true"` lets favourites and recents go into device backups.
- Cleartext HTTP is allowed for all sites (`network_security_config.xml`).
- GPC is only exposed to JavaScript, not sent as a header.
- On older WebViews, private-mode local storage is not isolated.
- The phone remote server is not yet session-scoped and uses a short token (see [PHONE_REMOTE.md](PHONE_REMOTE.md#implementation-status)).
