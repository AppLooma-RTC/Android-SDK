# Changelog

## rtc-android 0.4.19 · uikit-android 0.2.13 — 2026-09-29

- uikit-android 0.2.13: built on rtc-android 0.4.19. No UIKit API changes.
- Cloud proxy auto-retry no longer reports a disconnect to the app while it switches to the relay.
- Codec2 H.264: Baseline SPS rewritten to Constrained Baseline flags.

## rtc-android 0.4.18 · uikit-android 0.2.12 — 2026-09-28

- uikit-android 0.2.12: an Enhance button (beauty + low light) in the live stream host controls, as in the call screen. rtc-android stays 0.4.18 (only doc comments changed).
- Docs: stale "Unreleased" notes in the rtc-android API docs now name the version that shipped each API (0.4.11 video modes and 4K, 0.4.16 encoder self-test, network test, fallbacks, token renewal, region and remote diagnostics, 0.4.17 cloud proxy, 0.4.18 Clear Voice, audience latency and codec detection); the Android reference page likewise (2160p since 0.4.11, view re-bind fixes since 0.4.13).
- Sample apps (Java, Kotlin): the header shows the AppLooma brand mark instead of a letter.

## rtc-android 0.4.18 · uikit-android 0.2.11 — 2026-09-28

- uikit-android 0.2.11: built on rtc-android 0.4.18; adds an Enhance button (beauty + low light) to the call controls.
- MediaTek/Codec2 hardware H.264 path available behind remote device config (off by default).
- Video enhancement: `AppEngine.setVideoEnhance(AppVideoEnhance(beauty, lowLight, sharpen, warmth))` (null turns it off; `AppVideoEnhance.Portrait` preset). Edge-preserving skin smoothing, adaptive low-light boost, sharpening and warmth on the camera frames before encoding, on the CPU at half resolution with reused buffers. Runs before any `setVideoProcessor` processor. `AppVideoFilters` is public for use in your own processor. UIKit: an Enhance button (beauty + low light) in the call controls. Covered by unit tests.
- Clear Voice: `AppAudioOptions.noiseSuppressionMode` (`AppNoiseSuppression.STANDARD` default, `CLEAR`, `OFF`) and `engine.setNoiseSuppressionMode(mode)` / `noiseSuppressionMode`. CLEAR adds a spectral noise suppressor (DSP, not a neural model; 10 ms latency) in the microphone post-processing. Unit tests: white noise down at least 6 dB, a 440 Hz tone within 3 dB.
- Audience latency: `AppEngineOptions.audienceLatency` (`AppAudienceLatency.ULTRA_LOW` default, `LOW`, `STANDARD`), applied only while audience. No receive-buffer control on Android: LOW behaves like ULTRA_LOW; STANDARD turns on the VIDEO_LOW_QUALITY subscribe fallback unless the app set one.
- Codec detection: `AppEngine.getSupportedVideoCodecs(context)` returns `AppVideoCodecCapability(mime, hardware, encoder, decoder)` for VP8, VP9, H.264, H.265 and AV1.

## rtc-android 0.4.17 · uikit-android 0.2.10 — 2026-09-28

- uikit-android 0.2.10: built on rtc-android 0.4.17 (cloud proxy over TLS 443 with automatic retry). No UIKit API changes.

- Cloud proxy for restrictive networks: `AppEngineOptions.cloudProxy` (`AppCloudProxy.AUTO` default, `FORCE_TLS_443`, `OFF`). FORCE_TLS_443 joins with relay-only transport through AppLooma's relay on TLS 443; AUTO retries a join once that way when the direct join fails with a connection or network timeout, and logs it. New `AppEventHandler.onProxyStateChanged(state, autoRetry)`, `AppProxyState` (`DIRECT`, `CONNECTING`, `CONNECTED`) and `AppEngine.proxyState`. Retry rule covered by unit tests.

## rtc-android 0.4.16 · uikit-android 0.2.9 — 2026-09-27

- Remote diagnostics: the SDK keeps its last 1500 log lines in memory (always, even with debug logging off; no audio, video or messages) and uploads them, scrubbed of names, metadata, user ids, tokens and URL parameters, when a call had a problem (codec fallback, a viewer reporting no picture, encoder self-test failure, join failure, 3 or more reconnects, an encoder error), when support turned collection on for the device or project, or on the new `AppEngine.uploadDiagnostics(reason)`. Sent at leave or disconnect (and once 60 s into the call), gzip, on a background thread; offline, one pending upload is kept on disk for the next join. Opt out with `AppEngineOptions.remoteDiagnostics = false`. Kept 14 days. Ring buffer, scrubber and trigger rules covered by unit tests.
- Encoder self-test: new `AppEngine.runEncoderSelfTest(context, callback)`. It encodes about 30 synthetic 720p frames through the hardware H.264 encoder the SDK would use (never the camera) and checks for parameter sets or a key frame and real output within 2 s; background thread, 3 s total timeout, every codec released. Runs by itself once per install and SDK version after the first `create()`; turn off with `AppEngineOptions.encoderSelfTest = false`. A failing encoder is remembered for 30 days (software path from the first publish) and an anonymous `encoder_error` device report is kept on the phone and sent with the next join (reports need a join token). New `AppEncoderSelfTestResult`. Decision rules covered by unit tests.
- Region hint: `AppEngineOptions.region` (`AppRegion.AUTO`, `BD`, `IN`, `SA`, `SG`, `US`; default `AUTO`). Preparation for multi-region: the token endpoint still returns one server URL, so today the value is only sent with the device-tuning request and device reports, and logged at join.
- Pre-call network test: `startNetworkTest(serverUrl, token, callback)` / `stopNetworkTest()`. A separate short connection (nothing published) sampled for about 5 s; `AppNetworkTestResult` with uplink/downlink quality, RTT, jitter and loss, or `error` (10 s timeout). No echo test on Android yet.
- Network fallback: `setRemoteSubscribeFallback(option)` and `setLocalPublishFallback(option)` with `AppFallbackOption.NONE`, `VIDEO_LOW_QUALITY`, `AUDIO_ONLY`. Poor network for about 4 s starts it, good for about 10 s restores it. New `AppEventHandler.onFallbackStateChanged(isLocal, state)`.
- Token renewal: `AppEventHandler.onTokenWillExpire(token)` about 30 s before the token's expiry (decoded locally), and `renewToken(token)`, which replaces the token the SDK uses for its own requests and re-arms the warning. The live connection's credential is refreshed by the media server while connected.
- uikit-android 0.2.9: built on rtc-android 0.4.16 (encoder self-test, region hint, network test, fallback options, token renewal, remote diagnostics). No UIKit API changes.

## rtc-android 0.4.15 · uikit-android 0.2.8 — 2026-09-27

- uikit-android 0.2.8: built on rtc-android 0.4.15, so the UIKit screens get automatic codec recovery and per-device video settings. No UIKit API changes.
- Automatic codec recovery. While the camera is on, the SDK checks each stats tick that the encoder keeps producing frames and a key frame within 4 s of publishing; unhealthy for more than 5 s, or an encoder that fails to start or encode, and the camera is republished on software VP8 (software tier: 720p20 in STABLE_HD). A hardware encoder found bad is remembered per device, encoder and SDK version for 30 days, so the next session starts on the working path.
- Viewer side: a subscribed, enabled remote camera that decodes no picture for more than 6 s while its sender has it on is reported to that publisher only, on a reserved internal data topic. Internal topics (prefix `_al.`) are never delivered to the app's message or data callbacks.
- Publisher side: reports from 2 different viewers within 20 s (or from the only other person in a 1:1 call) republish the camera on a safer path: software VP8 when a hardware encoder was in use, otherwise once more as a single layer. At most once per join.
- New `AppEventHandler.onVideoCodecFallback(from, to, reason)`: `from`/`to` like `"h264:hw"` → `"vp8:sw"`; `reason` is `no_frames`, `encoder_error` or `viewers_cannot_decode`. Each step is in the debug log. Decision rules covered by unit tests.
- Per-device tuning: at join the SDK fetches video settings for this phone model in the background (never delaying the join, 3 s timeout), caches them with their ETag and lifetime, and applies them from the next camera publish: hardware encoders to avoid, codec choice, resolution, frame rate and bitrate caps, single layer. Anonymous device reports (make, model, OS and SDK version, encoder, codec and outcome; no user, identity or IP address) are sent in small batches: one `ok` after 20 s of healthy publishing, and one per fallback or viewer report. All of it fails silently.

## rtc-android 0.4.14

- Black camera from newer Qualcomm phones fixed: H.264 is published only through the encoders the engine has always driven (`OMX.qcom.`, `OMX.Exynos.`). Codec2 H.264 encoders (`c2.qti`, `c2.mtk`) produced a stream no other phone could bind (realme RMX5555: every viewer black). Those phones publish VP8, which works everywhere.

## uikit-android 0.2.7

- uikit-android: loudspeaker button on the live stream (host and viewer), voice room and call screens. Speaker icon when on, speaker-off when on the earpiece; while a Bluetooth or wired headset carries the audio it shows headphones, does nothing and reads "Headset in use". Screen-reader labels "Loudspeaker on"/"Loudspeaker off". Starts on the loudspeaker for live, voice rooms and video calls, on the earpiece for voice calls.

## rtc-android 0.4.13 · uikit-android 0.2.6

- `setSpeakerphone()` is remembered when called before the first join and applied as soon as audio starts; voice rooms in the CALL scenario came up on the earpiece. New `isSpeakerphoneOn`.

- Remote video no longer stays at 0 fps after a layout change with adaptive stream on. `AppVideoView` remembers what it shows and re-binds when it returns to a window (it used to stop for good once it left one), and follows a remote user's camera through unsubscribe/resubscribe. New `AppEngine.resubscribe(uid, includeAudio = false)`. New `AppEngineOptions.autoRecoverStalledVideo` (default true): a remote camera in a visible view, whose sender is publishing, received at 0 fps for more than 4 s is resubscribed, at most once per 15 s per user, and logged in the debug log.
- `AppEngineOptions.adaptiveStream: Boolean?` (null = by mode: on in ADAPTIVE, off in STABLE_HD / ULTRA_HD_4K), so ADAPTIVE users can turn it off.
- `setRemoteVideoEnabled` works with adaptive stream on: the engine ignored the pause request there, so the SDK now unsubscribes/subscribes the video instead. `setRemoteVideoQuality` is still ignored with adaptive stream on (the view size picks the layer); this is now logged and documented.
- Local preview: a view attached with `attachLocal` no longer freezes after a seat change or a move between layouts (leaving the window dropped it from the self-view registry), and re-binds when a restart swaps the native track inside the same track object. `onLocalVideoChanged` also fires for those restarts.
- `setVideoConfig`: the "republish" path only muted and unmuted the same camera track, so bitrate, codec and simulcast changes never took effect and every viewer saw the camera mute. Bitrate and degradation now change on the live encoder with no interruption; a codec or simulcast change unpublishes and publishes a new track; resolution/fps restart the camera in place. Subscriptions are not touched. Debug log lines around each step.
- Several engines (a PK battle) share one audio route. An engine created or joining while another is in a channel adopts that engine's audio scenario and route (logging a warning when its own differs) instead of moving everyone to the earpiece; the route is released only when the last engine leaves.
- `AppVideoView.setMirror(Boolean)` / `resetMirror()`: view-only mirroring, front-camera self-view mirrored by default.
- `AppEngine.setVideoProcessor(AppVideoProcessor?)`: capture-side frame hook on I420 `AppVideoFrame`s, before encoding and preview; survives restarts and republishes.
- `onMessage` docs: payload types, how `from` is verified, arrival-time `sentAt`, queue limit.
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
