# TiviMate Channel Bridge

A tiny Android **accessibility service** that reads the **currently playing channel** from
TiviMate's on-screen info bar and reports it to **Home Assistant** — on a **non-rooted**
device, entirely set up over ADB.

## Why

TiviMate (DexProtector-hardened) exposes no intent, content provider, media session, or
broadcast for the current channel, and its database is unreadable without root. The channel
name is only ever available as **on-screen text** in the info bar TiviMate shows on every
channel change. Android's accessibility API is the sanctioned, root-free way for one app to
read another app's visible text — so this service listens for TiviMate's info-bar updates,
extracts the channel name, and POSTs it to a Home Assistant webhook.

## How it works

1. The service filters accessibility events from `ar.tvplayer.tv`.
2. When the info bar appears (channel change / OK / info), it walks the node tree.
3. It anchors on the stream badge row (`… FPS`, `STEREO`, resolution) and takes the
   left-most non-badge text on that row as the channel name.
4. It POSTs `{"channel":"<name>"}` to `HA_WEBHOOK_URL` and logs `CHANNEL=<name>`
   (tag `TiviMateBridge`) for debugging.

Set your HA webhook via `config.properties` (see **Configuration**), or edit the default in `ChannelAccessibilityService.java` before building.

## Build

```bash
ANDROID_HOME=/path/to/android-sdk ./build.sh
# -> build/tivimate-channel-bridge.apk
```

Needs SDK `build-tools;34.0.0`, `platforms;android-34`, and a JDK.

## Install (no root)

```bash
adb install -r build/tivimate-channel-bridge.apk
adb shell settings put secure enabled_accessibility_services \
  com.jkfran.tivimatebridge/com.jkfran.tivimatebridge.ChannelAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

Verify: `adb shell dumpsys accessibility | grep tivimate`, then change a channel in
TiviMate and watch `adb logcat -s TiviMateBridge`.

## Configuration

The only per-user setting is your Home Assistant webhook URL. Two options:

- **Runtime (no rebuild):** push a `config.properties` to the app's external files dir:
  ```bash
  cp config.properties.example config.properties   # edit ha_webhook_url
  adb push config.properties /sdcard/Android/data/com.jkfran.tivimatebridge/files/config.properties
  # then toggle the accessibility service off/on
  ```
- **Build time:** edit `DEFAULT_HA_WEBHOOK_URL` in `ChannelAccessibilityService.java`.

The service targets `ar.tvplayer.tv` (TiviMate). Channel-name extraction is heuristic and
tuned for typical IPTV naming; if your provider's channel names are unusual you may need to
adjust the patterns in the service.

## Home Assistant

Create an automation with a webhook trigger (`webhook_id: tivimate_channel`) that stores
`{{ trigger.json.channel }}` into an `input_text`, then classify movie vs live via your
Xtream EPG or a channel map.


## Status: validated on real hardware

Confirmed non-root, end to end, on a Google TV Streamer (Android 14) with TiviMate 5.3.3:
zapping channels updates Home Assistant in real time.

```
DPAD zap -> TiviMate info bar -> accessibility read -> POST 200
HA input_text.tivimate_current_channel: 'ES| ANTENA 3 HEVC' -> 'ES| CUATRO HEVC'
```

**Note:** Android disables an accessibility service when its app is *updated*. After
`adb install -r`, re-run the two `settings put secure` commands to re-enable it. A first
enable also survives reboots on its own.

## Uninstall / disable

```bash
adb shell settings put secure enabled_accessibility_services ""
adb shell settings put secure accessibility_enabled 0
adb uninstall com.jkfran.tivimatebridge
```

## Disclaimer

Unofficial, third-party tool. Not affiliated with or endorsed by TiviMate or Armobsoft.
It only reads the channel name that is already visible on your own device's screen and sends
it to your own Home Assistant. Use it with content and services you are authorized to access.

## License

MIT — see [LICENSE](LICENSE).
