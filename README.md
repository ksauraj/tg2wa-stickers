# TG2WA Stickers

Android app that copies your Telegram sticker sets into WhatsApp. Log in with Telegram, tap
**Sync all to WhatsApp**, then confirm each pack in WhatsApp's dialog.

## What it does

1. Logs in to your Telegram account with **TDLib**, Telegram's official client library
   (phone number, login code, and 2-step password if you have one).
2. Lists your installed sticker sets and downloads every sticker.
3. Converts each sticker to WhatsApp's format:
   - still `.webp`: padded to 512x512, at most 100 KB
   - animated `.tgs` (Lottie): rendered frame by frame into an animated WebP, at most 500 KB
   - video `.webm` (VP9): decoded with MediaCodec, **transparency included** (the alpha
     stream is demuxed by hand), re-encoded as an animated WebP, at most 500 KB
4. Splits sets into WhatsApp packs (3 to 30 stickers each, animated and still kept separate)
   and hands them to WhatsApp one after another.

Sets that haven't changed are skipped on the next sync.

## Security model

- **No session strings.** The app never asks for, shows or exports a Telegram session. If any app asks you
  to paste one, don't: a session string gives full control of the account.
- **No middle server.** The phone talks to Telegram's servers directly through TDLib. The app has no backend,
  analytics or crash reporting.
- **Encrypted at rest.** TDLib's database is encrypted with a random 256-bit key. That key, your api_id and
  your api_hash are encrypted with an AES-GCM key held in the Android Keystore.
- **Stays on the device.** `allowBackup=false` and data-extraction rules exclude everything from cloud backup
  and device transfer. Cleartext traffic is disabled.
- **Screens are protected.** `FLAG_SECURE` is set while login codes and passwords are on screen.
- **Only WhatsApp can read the packs.** The sticker ContentProvider requires
  `com.whatsapp.sticker.READ`, serves only files listed in the pack index, and is read-only.
- **Log out** in the app ends the Telegram session on Telegram's side and deletes the local database.
  You can also end it from Telegram > Settings > Devices.

### Why you need your own api_id

Telegram requires every client to identify itself with an `api_id`/`api_hash`. Each user creates their own
at [my.telegram.org](https://my.telegram.org/apps) and enters it on first launch, so no key ships in the APK.

## Limits

- WhatsApp shows its own confirmation for **each** pack. No third-party app can skip it.
- Packs hold at most 30 stickers, so a 120-sticker set becomes 4 packs ("Cats 1/4"...).
- Very complex animations may lose frame rate or quality to stay under 500 KB.
- Custom emoji and mask stickers are not synced. Only regular sticker sets are.

## Build

Requires JDK 17+ and the Android SDK (compileSdk 37).

```
./gradlew testDebugUnitTest assembleDebug
```

CI (`.github/workflows/android.yml`) runs the unit tests, checks the animated-WebP muxer output with
libwebp's `webpinfo`/`webpmux`, runs the converter and provider tests on an emulator, and builds the APKs.
Pushing a `v*` tag publishes a GitHub Release.

Release signing reads `TG2WA_KEYSTORE`, `TG2WA_KEYSTORE_PASSWORD`, `TG2WA_KEY_ALIAS` and `TG2WA_KEY_PASSWORD`
from the environment (CI secrets: `TG2WA_KEYSTORE_B64` plus the three passwords). Without them, the release
APK is debug-signed.

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
