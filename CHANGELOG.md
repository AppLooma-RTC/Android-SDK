# Changelog

## rtc-android 0.4.10

- `AppVideoConfig.degradation` — `AppVideoDegradation.KEEP_RESOLUTION` / `KEEP_FRAMERATE` / `BALANCED` / `AUTO` (what the encoder gives up first under pressure), applied through the engine's publish defaults; `AppVideoConfig.minBitrate` (reserved — the engine exposes no per-sender floor on Android yet).
- Default bitrate caps raised when `maxBitrate` is 0: 4 Mbps at 1080p, 2.2 Mbps at 720p (was 3 / 1.7). New `height = 1440` preset (2560×1440, 5 Mbps, 30 fps).
- `AppVideoCodec.AV1` where the chipset has a hardware AV1 encoder (otherwise AUTO with a warning); `AppVideoCodec.H265` resolves to AUTO — the engine has no H.265 encoder path on Android. Every codec but VP8 keeps the VP8 backup layer. `setVideoConfig` republishes when the codec or degradation changes.
- `AppEventHandler.onVideoQualityChanged(quality)` — `AppVideoQualityInfo(width, height, fps, bitrateKbps, layer, reason)` for your own camera, checked every two seconds while it is on and reported on change.
- Bluetooth headsets: the engine's device switcher now prefers a connected Bluetooth headset, then a wired headset, then the loudspeaker (MEDIA) or the earpiece (CALL), and re-evaluates on every device change. New `AppAudioRoute`, `AppEngine.getAudioRoutes()`, `AppEngine.currentAudioRoute`, `AppEngine.setAudioRoute(route): Boolean` and `AppEventHandler.onAudioRouteChanged(route, available)` for an in-call route picker; `setSpeakerphone` is built on them. On Android 12+ the app must hold `BLUETOOTH_CONNECT` at runtime or no headset is offered — the engine logs a warning at join when it is missing. Request it next to `RECORD_AUDIO` and treat a refusal as non-fatal.

## uikit-android 0.2.4

- The permission dialog also asks for `BLUETOOTH_CONNECT` on Android 12+, so Bluetooth headsets work in the kit screens. Only microphone/camera remain required; refusing Bluetooth does not block the join.
