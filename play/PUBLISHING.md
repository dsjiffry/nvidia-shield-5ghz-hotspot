# Publishing checklist (Google Play, Android TV)

## 0. Build

```powershell
.\build.ps1 -Release      # -> ShieldHotspot-release.aab (upload this)
```

The first release build creates `upload-keystore.jks` + `upload-keystore.properties`.
**Back both up somewhere safe** (password manager / cloud). You need them for every future update.
Don't share them, and don't commit them to a public repo.

## 1. Play Console account

- One-time USD 25 registration plus identity verification.
- **New personal developer accounts must run a closed test with at least 12 testers for 14 days**
  before they can publish to production. Plan for that (Testing › Closed testing).

## 2. Create the app

- App name: see `LISTING.md` (30-character limit).
- Default language, App, Free.
- Enable **Play App Signing** (default). Upload `ShieldHotspot-release.aab`.

## 3. Android TV

- Setup › Advanced settings › Form factors: **add Android TV** and opt in.
  TV apps get an extra quality review (D-pad navigation, banner, no touch requirement). This app
  already declares `LEANBACK_LAUNCHER`, a 320×180 banner, and `touchscreen required=false`.
- Upload the TV banner (`tv-banner-1280x720.png`) and at least one TV screenshot.

## 4. Device catalog (important)

The app depends on SHIELD-specific behaviour (legacy "Network debugging" on port 5555 and Android 11
Wi-Fi Direct internals). In **Release › Device catalog**, exclude everything except the NVIDIA SHIELD
models (search "NVIDIA": `foster`, `darcy`, `mdarcy`, `sif`). The manifest already excludes phones
(leanback required) and anything without Wi-Fi Direct.

## 5. App content forms

| Form | Answer |
|---|---|
| Privacy policy | `https://dsjiffry.github.io/nvidia-shield-5ghz-hotspot/privacy.html` (source: `docs/privacy.html`, served by GitHub Pages) |
| Ads | **No**. The corner card only links to the project's own GitHub. If it ever promotes other apps, answer **Yes** |
| App access | "All functionality available without special access", plus reviewer instructions (below) |
| Content rating | Questionnaire: Utility/Tools, no violence, no user content, no sharing of location → Everyone |
| Target audience | 18+ (avoids the Families policy) |
| Data safety | No data collected, no data shared. Data is not encrypted in transit (none sent). No account deletion needed (no accounts) |
| Government / financial / health apps | No |

### Notes for the reviewer (App access › Instructions)

> This app needs Android's "Network debugging" (Developer options) to manage the built-in hotspot
> without root. To test on an NVIDIA SHIELD TV connected by Ethernet: Settings › Device Preferences ›
> About › select Build 7×; Developer options › Network debugging ON. Open the app, press Start
> hotspot, choose "Always allow" on the prompt. A Wi-Fi network named DIRECT-SH-Shield appears on
> 5 GHz; join it from a phone and browse. Demo video: [LINK]

A short screen recording of that flow (phone joining, web page loading) makes approval much more
likely. Upload it unlisted to YouTube and paste the link.

## 6. Policy notes

- **Name:** "Nvidia Shield" in the title can trigger an intellectual-property rejection. If that
  happens, rename to the "for Nvidia Shield" form and keep the trademark disclaimer in the description.
- **Permissions:** only INTERNET (local connection to the device's own debug service) and
  RECEIVE_BOOT_COMPLETED. No location, no foreground service, no ad SDK.
- **Developer options requirement:** allowed. Several Play apps (e.g. Shizuku, LADB) use the same
  approach. The description must state it clearly (it does).

## 7. Updating later

Bump `android:versionCode` (and `versionName`) in `AndroidManifest.xml`, run `.\build.ps1 -Release`,
and upload the new `.aab` to the same track.
