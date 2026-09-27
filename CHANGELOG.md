# Changelog

## Unreleased

- `setMicGain()` and `setAgcTargetLevel()` no longer silence the microphone. The engine hands the microphone stage floats in 16-bit range (±32768); the processor assumed ±1, so any gain other than unity squashed the voice to ±1 in the limiter. It now detects the scale and works in true dBFS, which also makes the noise gate levels correct. Covered by unit tests.

## rtc-android 0.4.12

- Voice-room seats: a speaker put back on a seat can be heard again. A seat change arrives as two events (role and publish permission) in either order; the SDK reacted to the role alone, tried to publish before the permission existed, and never retried when the permission arrived. It now acts on the pair and publishes the microphone as soon as both allow it.

## rtc-android 0.4.11 · uikit-android 0.2.5

- Echo: the software echo canceller is now the default (`AppAudioOptions.preferHardwareEffects = false`). Many budget phones ship a weak hardware canceller, and in loudspeaker voice rooms a speaker heard their own voice come back quietly. Apps that play in-room music through the SDK player on the loudspeaker can set `preferHardwareEffects = true`, because only the platform canceller references that playout.

- Stable HD on software-encoder phones (no usable hardware encoder, e.g. MediaTek today) runs 720p at a steady 20 fps instead of 30: measured on a realme RMX3941, 30 fps swung between 2 and 28 fps under CPU limit. Camera capture follows the same rate. An app that sets `fps` or `height` keeps its value.

- `AppVideoMode` on `AppVideoConfig.mode`: `STABLE_HD` (new default), `ULTRA_HD_4K` (premium, opt-in), `ADAPTIVE` (the previous behaviour). STABLE_HD publishes one layer (no simulcast), keeps resolution under pressure (`KEEP_RESOLUTION`) and turns adaptive stream off so receivers keep the full picture. Its size is chosen once per phone: 1080p30 at 3.5 Mbps where the chosen codec has a hardware encoder, 720p30 at 1.8 Mbps where it would be encoded on the CPU. Fixes a 1:1 call on a MediaTek phone swinging between 160p and 1280p.
- `ULTRA_HD_4K`: 2160p30 (3840x2160), one layer, 25 Mbps, resolution kept. Only where the hardware encoder for the chosen codec accepts 3840x2160; otherwise STABLE_HD with a warning. `AppEngine.isUltraHdSupported()` returns the check.
- Fields set explicitly still win over the mode. `AppVideoConfig.height` now defaults to 0 (= by mode) and `simulcast` to `null` (= by mode; on in ADAPTIVE, off otherwise); `degradation = AUTO` means KEEP_RESOLUTION outside ADAPTIVE. `height = 2160` is a new preset.
- UIKit: the call screen's quality badge no longer shows a pixel count — "HD" normally, "Weak network" after five seconds of a bandwidth-limited camera or heavy audio loss. Call and live screens no longer force 720p and follow the default mode.
- Hardware video encoding on MediaTek, Unisoc, HiSilicon, Intel and Codec2-only Qualcomm chipsets. The SDK now ships its own video encoder factory: it enumerates the phone's MediaCodec encoders and drives any hardware H.264, VP8, VP9, AV1 or H.265 encoder from a known vendor (on Android 10+ any encoder the platform flags as hardware-accelerated), instead of only the two vendor prefixes the previous factory accepted. Phones such as the realme RMX3941 (Helio G85) and Infinix X6532C, which used to fall back to software VP8, now publish hardware H.264 at 720p/1080p. A hardware encoder that fails to start falls back to the software encoder for that codec (VP8/VP9/AV1) per simulcast layer.
- `AppVideoCodec.AUTO` now prefers hardware H.264, then hardware VP8, then software VP8. Every codec but VP8 still carries the VP8 backup layer.
- One EGL context is shared by the encoder, the decoders and every `AppVideoView` for the life of the process (rooms no longer create and release their own).
- `enableDebugLogging` prints the encoder chosen for the published codec at join (`video codec=h264 encoder=… mime=… hardware=…`).

## rtc-android 0.4.10

- `AppVideoConfig.degradation` — `AppVideoDegradation.KEEP_RESOLUTION` / `KEEP_FRAMERATE` / `BALANCED` / `AUTO` (what the encoder gives up first under pressure), applied through the engine's publish defaults; `AppVideoConfig.minBitrate` (reserved — the engine exposes no per-sender floor on Android yet).
- Default bitrate caps raised when `maxBitrate` is 0: 4 Mbps at 1080p, 2.2 Mbps at 720p (was 3 / 1.7). New `height = 1440` preset (2560×1440, 5 Mbps, 30 fps).
- `AppVideoCodec.AV1` where the chipset has a hardware AV1 encoder (otherwise AUTO with a warning); `AppVideoCodec.H265` resolves to AUTO — the engine has no H.265 encoder path on Android. Every codec but VP8 keeps the VP8 backup layer. `setVideoConfig` republishes when the codec or degradation changes.
- `AppEventHandler.onVideoQualityChanged(quality)` — `AppVideoQualityInfo(width, height, fps, bitrateKbps, layer, reason)` for your own camera, checked every two seconds while it is on and reported on change.
- Bluetooth headsets: the engine's device switcher now prefers a connected Bluetooth headset, then a wired headset, then the loudspeaker (MEDIA) or the earpiece (CALL), and re-evaluates on every device change. New `AppAudioRoute`, `AppEngine.getAudioRoutes()`, `AppEngine.currentAudioRoute`, `AppEngine.setAudioRoute(route): Boolean` and `AppEventHandler.onAudioRouteChanged(route, available)` for an in-call route picker; `setSpeakerphone` is built on them. On Android 12+ the app must hold `BLUETOOTH_CONNECT` at runtime or no headset is offered — the engine logs a warning at join when it is missing. Request it next to `RECORD_AUDIO` and treat a refusal as non-fatal.

## uikit-android 0.2.4

- The permission dialog also asks for `BLUETOOTH_CONNECT` on Android 12+, so Bluetooth headsets work in the kit screens. Only microphone/camera remain required; refusing Bluetooth does not block the join.
