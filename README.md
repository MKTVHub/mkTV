# mkTV

**A web browser built for the TV remote.** By [MKTVHub](https://github.com/MKTVHub).

mkTV turns the D-pad into a fast way to use any website: the highlight jumps from link to link like a TV app, videos take over the remote when they play, and ads, pop-ups and hostile redirects are blocked by default. It runs on Android TV and Google TV today, with Fire TV, LG webOS and Samsung Tizen on the [roadmap](docs/ROADMAP.md).

## Features

- **Jump navigation.** ▲▼◀▶ move a highlight between links and buttons; OK clicks, hold OK to long-press. Hold ▼ or ▲ to jump to a site's fixed bottom or top bar. Prefer a mouse? Switch to **pointer mode**.
- **Out of the way.** The top bar hides while you browse and comes back when you need it.
- **Home screen.** Popular sites, your Favourites and Recently visited, one press away.
- **YouTube on the TV interface.** The YouTube tile opens YouTube's own TV interface, with the remote passed straight through.
- **Watch mode.** When a big video plays, the remote controls it: seek, pause, next/previous in vertical feeds, ▼ for real full screen. Videos start with sound.
- **Native player.** If a page plays an HLS or DASH stream, **Watch full screen** opens it in a native player that is smoother on TV hardware.
- **Blocking.** Ads and trackers (built-in list plus the StevenBlack hosts list), invisible click-trap layers, pop-ups and redirects you didn't click. Each block shows a small notice with **Open anyway**. Pause blocking for one site from the shield.
- **YouTube ad removal** using the same technique as uBlock Origin and Brave.
- **Private browsing.** A separate session that is deleted when you close it. Tracking codes are stripped from links and Global Privacy Control is on.
- **Phone remote** *(in progress).* Scan a QR code and use your phone's browser as a remote and keyboard.

Full list with details and limits: [docs/FEATURES.md](docs/FEATURES.md).

## Remote controls

| Where | Key | Does |
|---|---|---|
| Page (jump mode) | ▲▼◀▶ | Move the highlight |
| | OK / hold OK | Click / long-press |
| | Hold ▼ / hold ▲ | Jump to the site's bottom / top bar |
| | ▲ at the top | Go to the toolbar |
| Page (pointer mode) | ▲▼◀▶ | Move the pointer; push an edge to scroll |
| Any page | Menu | Open the ⋮ menu |
| | CH+ / CH− | Scroll up / down |
| | Play/Pause, FF, REW, Next, Prev | Control the page's video |
| Watch mode | ◀ ▶ | Seek 10 s |
| | OK | Pause / play |
| | ▲ ▼ | Previous / next (vertical feeds) |
| | ▼ / ▲ | Full screen / leave full screen (normal video) |
| | Back | Browse the page again |
| YouTube TV | All keys | Go to YouTube; **hold Back** for the mkTV home screen |
| Anywhere | Back | Close what's open, go back, press twice to exit |

## Install

**From a release:** download `mkTV-<version>.apk` from [github.com/MKTVHub/mkTV/releases/latest](https://github.com/MKTVHub/mkTV/releases/latest) and install it with `adb install mkTV-<version>.apk` or a side-loading app (for example Downloader or Send Files to TV). Release APKs are signed by MKTVHub; the certificate SHA-256 is `16:45:35:90:AA:A7:BD:7E:86:EC:B1:BD:E8:CC:8E:B9:AE:66:F3:30:CB:7C:BE:DC:72:3D:5F:3A:E5:BE:B7:FD`.

**From source** (JDK 17, Android SDK 35):

```sh
git clone https://github.com/MKTVHub/mkTV.git && cd mkTV
./gradlew test assembleDebug
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app appears on the TV home screen as **mkTV**. Package: `com.mktvhub.mktv`. Requires Android 7.0 (API 24) or newer.

Build-time defaults in `app/build.gradle.kts`: `DEFAULT_HOME_URL` (home page address, `https://www.google.com`) and `DEFAULT_BLOCKLIST_URL` (StevenBlack unified hosts). Both can be changed in Settings.

## Screenshots

*Coming soon.* Planned: home screen, jump navigation ring, watch mode dock, shield panel, settings, private browsing.

<!--
![Home screen](docs/images/home.png)
![Jump navigation](docs/images/jump.png)
![Watch mode](docs/images/watch.png)
![Shield panel](docs/images/shield.png)
-->

## Documentation

- [docs/FEATURES.md](docs/FEATURES.md): every feature, how it works, limits
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): native shell, injected JS, key routing
- [docs/PHONE_REMOTE.md](docs/PHONE_REMOTE.md): phone remote protocol and security
- [docs/PRIVACY.md](docs/PRIVACY.md): what is stored and sent, private mode limits
- [docs/ROADMAP.md](docs/ROADMAP.md): release checklist, next features, other TV platforms
- [AGENTS.md](AGENTS.md): guide for contributors and AI coding agents
- [CONTRIBUTING.md](CONTRIBUTING.md)

## Privacy in one paragraph

No accounts, no analytics, no telemetry. mkTV itself only downloads the blocklist and site icons for your home screen. Private browsing keeps nothing after you close it, but it does not hide your traffic from websites or your internet provider. Details: [docs/PRIVACY.md](docs/PRIVACY.md).

## License

Apache License 2.0. Copyright 2026 MKTVHub. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

mkTV is a general-purpose web browser. You are responsible for the sites you visit and for following their terms and the law where you live.
