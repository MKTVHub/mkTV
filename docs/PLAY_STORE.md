# Google Play (Android TV)

Status as of 1.3.5 (versionCode 10, 2026-11-15): ready to upload; nothing has been uploaded. The listing text, Data safety answers and graphics are prepared outside the repo, in `MKTVHub/play/` (`LISTING.md`, the bundle and the images).

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew bundleRelease          # app/build/outputs/bundle/release/app-release.aab
```

The bundle is signed with the same release key as the GitHub APKs (`~/.mktvhub/keystore.properties`; certificate SHA-256 `16:45:35:90:AA:A7:BD:7E:86:EC:B1:BD:E8:CC:8E:B9:AE:66:F3:30:CB:7C:BE:DC:72:3D:5F:3A:E5:BE:B7:FD`). In Play App Signing choose **Use my own key** and export that key with Play's PEPK tool. Play then signs the APKs it delivers with the same key, so a copy installed from GitHub and a copy from Play have the same signature (Android still won't move a sideloaded copy to Play updates by itself; the user installs from Play over it). Check a bundle with:

```sh
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab | grep SHA256
```

## Android TV requirements checked

| Requirement | State |
|---|---|
| Leanback launcher intent | `BrowserActivity` has `MAIN` + `LEANBACK_LAUNCHER` (and `LAUNCHER`). `aapt2 dump badging` shows `leanback-launchable-activity`. |
| TV banner | `android:banner="@drawable/banner"`, `drawable-xhdpi/banner.png` 640 × 360 px (320 × 180 dp). The 1280 × 720 store banner is a separate upload. |
| Touchscreen not required | `android.hardware.touchscreen` `required="false"`. |
| Leanback feature | `android.software.leanback` `required="false"` (the same APK runs on phones and Fire TV). |
| No implied hardware | **Fixed in 1.3.5.** `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (only for reading the Wi-Fi name in Share Wi-Fi) implied `android.hardware.location`, `.location.gps` and `.location.network`; `ACCESS_WIFI_STATE` implied `android.hardware.wifi`. Each implied feature would hide mkTV on TVs without that hardware (most TVs have no GPS; some boxes are Ethernet-only). All are now declared `required="false"`, plus `android.hardware.microphone` (voice search uses the system recogniser). The only remaining implied feature is `android.hardware.screen.landscape`, which every TV has. |
| Target API level | `targetSdk = 35`. Play's rule since 2026-08-31: new apps and updates must target API 36, **except Android TV apps, which need API 34 or higher**. mkTV meets the TV rule **when released on Play's dedicated Android TV track** (Advanced settings → Form factors → Android TV, "Android TV only" releases). Uploaded to the main (phone) tracks, the bundle is checked against the phone rule and targetSdk 35 is expected to be refused. If mkTV is also offered to phones and tablets, Play will ask for API 36 (Android 16: predictive Back would stop calling `onBackPressed`, which both activities use, so that move needs `android:enableOnBackInvokedCallback="false"` or a move to `OnBackPressedCallback`, plus testing on a TV). |
| Native code / 16 KB pages | No `.so` files in the APK or bundle, so the 16 KB page-size rule doesn't apply. |
| D-pad only | Every screen works with the remote (see [FEATURES.md](FEATURES.md)). |
| Self-updating | **Fixed in 1.3.5.** Play forbids updating outside Play. Installed from Play (`com.android.vending`) or Amazon (`com.amazon.venezia`), mkTV never checks GitHub and shows no update card or rows (`Updates.selfUpdates`). Sideloaded copies keep the GitHub updater. |
| Privacy policy | https://mktvhub.github.io/privacy.html (from [PRIVACY.md](PRIVACY.md)). |

## Permissions

| Permission | Why | Play notes |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | A web browser; the phone remote's LAN server | Normal |
| `ACCESS_WIFI_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Share Wi-Fi reads the connected network's name, which Android only reveals with location permission. Asked for only when the user presses "Fill in automatically". No location is read or sent. | Location is a runtime permission asked in context, foreground only (no background location), so no location declaration form. Mention in Data safety: not collected. |
| `REQUEST_INSTALL_PACKAGES` | Opening an `.apk` the user downloaded in the browser (Downloads), and the GitHub updater on sideloaded copies | Sensitive: Play Console asks for a declaration. Core use: **web browser that lets users download files** (an allowed use). The updater is inactive on Play installs. If Play refuses it, remove the permission from a Play-only build; Downloads would then hand APKs to the system file manager instead of the installer. |
| `WRITE_EPG_DATA` (+ `READ_EPG_DATA` from androidx.tvprovider) | Play Next and the mkTV channel on the Android TV home screen | Normal for TV apps |

No SMS, call log, contacts, accounts, camera, microphone, storage, background location, accessibility or `QUERY_ALL_PACKAGES` permissions. Package visibility uses a narrow `<queries>` (speech recogniser, TV provider).

## Policy notes

- **Content.** mkTV is an ad and tracker blocking web browser with D-pad browsing and a phone remote. The listing, screenshots and graphics show only mkTV's own screens, Favourites and generic pages; no third-party video content and no claims about free access to paid services.
- **Ad blocking.** Play allows ad blockers that act inside their own app (mkTV blocks only in its own WebView). It doesn't block ads in other apps or change other apps' behaviour.
- **Content rating.** Answer the IARC questionnaire as a web browser: "unrestricted internet access" yes; users can't talk to each other through mkTV; no in-app purchases, no gambling, no location sharing. Parental controls (PIN, Safe search, blocked sites) exist but don't change the rating answers.
- **Data safety.** No data collected or shared (answers and reasoning in `MKTVHub/play/LISTING.md`). Requests mkTV makes on its own (filter lists, site icons, the update check on sideloaded copies, opt-in search suggestions) carry no personal data beyond the IP address any connection reveals, and pages the user opens are user-directed browsing.
- **Families.** Not designed for children: don't opt into the Families programme. Target audience 18+ (or 13+), as with other browsers.
