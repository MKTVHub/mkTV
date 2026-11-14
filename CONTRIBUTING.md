# Contributing to mkTV

Thanks for helping. mkTV is maintained by MKTVHub and licensed under Apache-2.0.

## Before you start

- Read [AGENTS.md](AGENTS.md): product principles, repo map, conventions and hard rules. It applies to humans and AI agents alike.
- For anything bigger than a bug fix, open an issue first so we can agree on the approach.

## Making a change

1. Fork and branch from `main`.
2. Keep the change focused. Follow the existing style (plain Kotlin `Activity`, logic in `HostRules` where it can be pure, short "why" comments).
3. Run `./gradlew test assembleDebug`. Add or update tests in `HostRulesTest` for any pure logic.
4. Test on a real Android TV or Google TV device with the remote (or `adb shell input keyevent`). Check jump mode, pointer mode and private mode if your change could affect them.
5. For UI changes, attach before/after screenshots (`adb exec-out screencap -p > shot.png`).
6. Update the docs in `docs/` if behaviour, storage or network requests change. New network requests must be listed in [docs/PRIVACY.md](docs/PRIVACY.md).

## Pull requests will not be accepted if they

- add analytics, telemetry, ad SDKs or any undocumented network request,
- add features, presets or scrapers aimed at sites distributing unlicensed content, or name such sites,
- break D-pad navigation or Back,
- add site-specific code where a generic approach would work.

## Reporting bugs

Include the TV model, Android version, Android System WebView version (Settings → Apps), mkTV version (bottom of Settings), the site, and steps using remote keys.

## Releasing (maintainers)

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build a signed APK: `./gradlew test assembleRelease`. Signing reads `~/.mktvhub/keystore.properties` (or the file named by `MKTV_KEYSTORE_PROPS`) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. The keystore never goes in the repo. Without it, the release APK is unsigned.
3. Check it: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`. The SHA-256 must match the one in the README, or existing users cannot update.
4. Tag `vX.Y.Z` and attach the APK as `mkTV-X.Y.Z.apk` to a GitHub release, with its SHA-256 in the notes.

## Security issues

Do not open a public issue. Contact the maintainers through the MKTVHub GitHub organisation's security advisory page.

## License

By contributing you agree that your contribution is licensed under the Apache License 2.0 ([LICENSE](LICENSE)).
