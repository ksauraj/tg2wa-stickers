# TG2WA Stickers

Unofficial Android app that copies your Telegram sticker sets into WhatsApp. Log in with Telegram,
tap "Sync all to WhatsApp", then confirm each pack in WhatsApp's own dialog.

This app is built on the Telegram API and is part of the Telegram ecosystem. It is not made by,
affiliated with, or endorsed by Telegram or WhatsApp.

## Read this before you use it

Logging in to Telegram with any non-official client carries real account risk. This is not a
disclaimer invented here, it is what Telegram's own developer documentation says:

> Due to excessive abuse of the Telegram API, all accounts that log in using unofficial Telegram
> API clients are automatically put under observation to avoid violations of the Terms of Service.

> If you use the Telegram API for flooding, spamming, faking subscriber and view counters of
> channels, you will be banned forever.

> If you didn't violate the Terms of Service but your account does get banned after using the API,
> write to recover@telegram.org explaining what you intend to do with the API, asking to unban your
> account. Please note that emails are checked by a human, so automatically generated emails will
> be detected and banned.

Source: [Creating your Telegram Application](https://core.telegram.org/api/obtaining_api_id).

What that means in practice for this app:

- Your account goes under automated observation the moment you log in here. That is normal for
  every third-party client, but it is a state you choose to enter.
- Downloading a lot of sticker files in a short time looks like API traffic. The app downloads
  sequentially with a bounded worker pool and does not retry aggressively, but a very large sticker
  library still means a lot of requests. If you have hundreds of sets, sync in sessions rather than
  all at once on a brand new account.
- A brand new or recently created account that immediately logs in through an unofficial client and
  pulls its whole sticker library is the riskiest combination. Use an established account.
- Nobody here can get your account back. If you are not willing to risk the account, do not use
  this app. There is no safe-for-certain method, and anyone telling you otherwise is wrong.
- Never paste a Telegram session string into any app, including this one. This app never asks for
  one. A session string is full control of your account.

The stickers themselves are content owned by whoever made them. WhatsApp's requirement is that
"stickers on WhatsApp must be legal, authorized, and acceptable" (see
[WhatsApp Terms of Service](https://www.whatsapp.com/legal/#terms-of-service)). Copying somebody
else's sticker art into your own WhatsApp tray for personal use is one thing; redistributing packs
is another. That judgement is yours.

## What Telegram's documentation requires, and how this app complies

From the [Telegram API Terms of Service](https://core.telegram.org/api/terms) and
[Creating your Telegram Application](https://core.telegram.org/api/obtaining_api_id):

| Requirement | How this app meets it |
| --- | --- |
| "You must obtain your own api_id for your application" (2.1) | No api_id ships in the APK. Each user creates their own at [my.telegram.org/apps](https://my.telegram.org/apps) and enters it on first launch. The sample id in Telegram's open source code is deliberately not used, because it causes `API_ID_PUBLISHED_FLOOD` for end users. |
| "your users must be aware of the fact that your app uses the Telegram API" (2.2) | Stated at the top of this README, in the release notes, and on the login screen. |
| "the title of your app must not include the word Telegram" unless preceded by "Unofficial" (2.3) | The app is named "TG2WA Stickers". |
| "You must not use the official Telegram logo" (2.4) | The launcher icon is original artwork. No Telegram or WhatsApp marks are bundled. |
| "It is forbidden to interfere with the basic functionality of Telegram ... making actions on behalf of the user without the user's knowledge and consent" (1.4) | The app only reads sticker sets and downloads sticker files. It never sends messages, never reads chats, never marks anything read, and never changes account settings. |
| No using platform data to train or deploy AI or ML models (1.5) | The app has no backend, no analytics and no telemetry. Nothing leaves the device except traffic to Telegram's own servers and the local handoff to WhatsApp. |
| "If you use the Telegram API for flooding, spamming ... banned forever" | Read-only sticker access, sequential downloads, bounded concurrency, no polling loops, no mass actions. |

Per-number limit worth knowing: "each number can only have one api_id connected to it", so the
credentials you create are reused for anything else you build.

WhatsApp's side comes from the official
[WhatsApp/stickers](https://github.com/WhatsApp/stickers/blob/main/Android/README.md) sample and
[sticker FAQ](https://faq.whatsapp.com/1056840314992666): 512x512 WebP, 100 KB static, 500 KB
animated, 10 s maximum animation, 8 ms minimum frame duration, 3 to 30 stickers per pack, packs are
all-static or all-animated, a 96x96 tray icon of at most 50 KB, and a user confirmation per pack.
The converter enforces every one of those.

Two places where this app knowingly goes past WhatsApp's guidance for sticker apps, stated plainly
because it affects you:

- That sample says a sticker app should hold 1 to 10 packs. A full Telegram sticker library becomes
  many more than 10. The app does not cap the count.
- It also says do not build an "add all packs" operation. One-click sync over many packs is the
  entire point of this app, so the app queues the per-pack dialogs back to back. WhatsApp still
  shows its own confirmation for every pack, and that cannot be bypassed by any third-party app.

If WhatsApp changes its import behaviour, syncing can break. There is no contract here that
guarantees it keeps working.

## What it does

1. Logs in to your Telegram account with TDLib, Telegram's official client library (phone number,
   login code, and 2-step password if you have one).
2. Lists your installed sticker sets and downloads every sticker.
3. Converts each sticker to WhatsApp's format:
   - still `.webp`: padded to 512x512, at most 100 KB
   - animated `.tgs` (Lottie): rendered frame by frame into an animated WebP, at most 500 KB
   - video `.webm` (VP9): decoded with MediaCodec, transparency included (the alpha stream is
     demuxed by hand), re-encoded as an animated WebP, at most 500 KB
4. Splits sets into WhatsApp packs (3 to 30 stickers each, animated and still kept separate) and
   hands them to WhatsApp one after another.

Sets that have not changed are skipped on the next sync.

## Security model

- No session strings. The app never asks for, shows or exports a Telegram session.
- No middle server. The phone talks to Telegram's servers directly through TDLib. The app has no
  backend, analytics or crash reporting.
- Encrypted at rest. TDLib's database is encrypted with a random 256-bit key. That key, your api_id
  and your api_hash are encrypted with an AES-GCM key held in the Android Keystore.
- Stays on the device. `allowBackup=false` and data-extraction rules exclude everything from cloud
  backup and device transfer. Cleartext traffic is disabled.
- Screens are protected. `FLAG_SECURE` is set while login codes and passwords are on screen.
- Only WhatsApp can read the packs. The sticker ContentProvider requires
  `com.whatsapp.sticker.READ`, serves only files listed in the pack index, and is read-only.
- Log out in the app ends the Telegram session on Telegram's side and deletes the local database.
  You can also end it from Telegram > Settings > Devices.

## Install

Download the APK for your phone from the
[latest release](https://github.com/ksauraj/tg2wa-stickers/releases/latest). `arm64-v8a` fits
almost every phone sold in the last several years; use `universal` if you are unsure. Verify the
download against `SHA256SUMS.txt` from the same release.

Releases are built by GitHub Actions from a tagged commit, so the APK you install corresponds to
source you can read.

First launch asks for your own `api_id` and `api_hash`. Create them at
[my.telegram.org/apps](https://my.telegram.org/apps).

## Limits

- WhatsApp shows its own confirmation for each pack. No third-party app can skip it.
- Packs hold at most 30 stickers, so a 120-sticker set becomes 4 packs ("Cats 1/4" and so on).
- Very complex animations may lose frame rate or quality to stay under 500 KB.
- Custom emoji and mask stickers are not synced. Only regular sticker sets are.
- Live Telegram login and WhatsApp import are verified on CI emulators and by unit tests, not yet
  on a wide range of physical devices.

## Build

Requires JDK 17 or newer and the Android SDK (compileSdk 37).

```
./gradlew testDebugUnitTest assembleDebug
```

## CI and the release cycle

Two workflows:

- `.github/workflows/android.yml` (every push and pull request): unit tests, verification of the
  animated-WebP muxer output with libwebp's `webpinfo` and `webpmux`, instrumented converter and
  provider tests on an API 34 emulator, then `assembleDebug assembleRelease`. The APKs are uploaded
  as build artifacts.
- `.github/workflows/release.yml` (on a `v*` tag, or manually): re-runs the unit tests, checks the
  tag against `versionName` in `app/build.gradle.kts` so a tag cannot ship a mismatched build,
  builds the split and universal release APKs, writes `SHA256SUMS.txt`, and publishes a GitHub
  Release with the APKs attached. A tag containing a hyphen (`v0.1.0-alpha.1`) is published as a
  prerelease.

Versioning follows an immutable SemVer train: `alpha` to `beta` to `rc` to stable. Tags and
releases are never rewritten or deleted; a mistake gets a new version.

To cut a release:

```
# bump versionCode and versionName in app/build.gradle.kts, commit, then
git tag v0.1.0-alpha.1
git push origin v0.1.0-alpha.1
```

Release signing reads `TG2WA_KEYSTORE`, `TG2WA_KEYSTORE_PASSWORD`, `TG2WA_KEY_ALIAS` and
`TG2WA_KEY_PASSWORD` from the environment (CI secrets: `TG2WA_KEYSTORE_B64` plus the three
passwords). Without them the release APK is debug-signed, which is fine for sideloading but cannot
be used to update an installation signed with the real key.

## Layout

```
app/src/main/java/dev/ksauraj/tg2wa/
  telegram/   TDLib login + sticker download
  convert/    StickerConverter, WebpMuxer (animated WebP writer), WebmDemuxer, Vp9AlphaDecoder
  store/      PackPlanner (WhatsApp pack rules), PackStore (on-disk packs + index)
  whatsapp/   StickerContentProvider (WhatsApp contract), add-pack intent + whitelist check
  sync/       SyncWorker (foreground WorkManager job)
  security/   SecureStore (Keystore-backed secrets)
  ui/         Compose UI
```

## License

MIT, see [LICENSE](LICENSE). Third-party components keep their own licenses: TDLib and
`tdl-coroutines` (Boost Software License 1.0), Lottie for Android (Apache 2.0), AndroidX and
Jetpack Compose (Apache 2.0). "Telegram" and "WhatsApp" are trademarks of their respective owners
and are used here only to describe what the app interoperates with.
