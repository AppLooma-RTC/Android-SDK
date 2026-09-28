# H.264 on Codec2 encoders (c2.mtk / c2.qti): investigation and proposal

Status: investigation + proposal only. No SDK source was changed. Proposed code is in
`sdks/android/mediatek-h264.patch` (not applied, needs review against whatever the other
agents land first).

Symptom: publishing H.264 through `AppLoomaVideoEncoderFactory` on realme RMX3941
(`c2.mtk.avc.encoder`) and realme RMX5555 (`c2.qti.avc.encoder`): the server receives
packets, but every subscriber hits "track not bound after timeout" and viewers show black. Since
0.4.14 `isUsableHardwareEncoder` only allows H.264 on `OMX.qcom.` / `OMX.Exynos.`.

## What the engine actually does (webrtc-sdk `HardwareVideoEncoder.java`)

Read from `webrtc-sdk/webrtc` `sdk/android/src/java/org/webrtc/HardwareVideoEncoder.java`
(our bridge constructs this class unchanged):

- `initEncode`: `KEY_BITRATE_MODE = CBR` always; `KEY_COLOR_FORMAT` = surface format (surface
  mode when an EGL14 context is shared, which is our case) or YUV format; `KEY_I_FRAME_INTERVAL
  = keyFrameIntervalSec` (we pass 3600).
- H.264 profile: **only** when fmtp `profile-level-id == 640c1f` does it set `profile=High,
  level=3`. For `42e01f` (constrained baseline, the only profile we offer on non-Exynos) it
  sets **nothing** — no `profile`, no `level`. The encoder picks its own default.
- No low-latency, no `prepend-sps-pps-to-idr-frames`, no vendor keys (except AV1 on c2.google).
- Output path:
  ```java
  if ((info.flags & BUFFER_FLAG_CODEC_CONFIG) != 0) {   // save SPS/PPS
      configBuffer = ...; configBuffer.put(outputBuffer);
      return;                                            // <-- whole buffer dropped
  }
  final boolean isKeyFrame = (info.flags & BUFFER_FLAG_SYNC_FRAME) != 0;
  if (isKeyFrame && configBuffer != null) prepend configBuffer
  ```
  So: (a) a buffer flagged CODEC_CONFIG is never delivered even if it also carries the IDR;
  (b) key-frame detection is purely the SYNC_FRAME flag; (c) SPS/PPS are the first ones seen,
  never refreshed.

## Ranked hypotheses

### 1. Encoder default profile is not constrained baseline (most likely)
Evidence: for 42e01f the engine sets no profile/level (above). Codec2 components pick the
default from their `C2StreamProfileLevelInfo`; MediaTek and many c2.qti AVC encoders default to
**Main or High** (MTK often High@4.1 on recent Dimensity), whereas OMX.qcom defaults to Baseline —
which is exactly why OMX.qcom/OMX.Exynos "work" and all c2 encoders do not. The SDP says
`profile-level-id=42e01f`, so the subscribers' decoders (and the vivo/Samsung hardware decoders
set up for CB) get CABAC / B-frame-capable High streams. Many Android HW decoders configured
for baseline refuse or output nothing; libwebrtc's receiver also treats SPS whose profile does
not match as fine, but hardware decode fails → no first frame. Our own logs: "server receives
packets, no phone can decode" (comment in `isUsableHardwareEncoder`).
Also: Codec2 High default may use **B-frames** (`max-bframes`) on some MTK firmware; WebRTC
cannot carry reordered frames (timestamps out of order → jitter buffer drops everything).

Why "track not bound": LiveKit SFU (v1.13.x) binds a DownTrack only after it has a codec match
*and* the forwarder can start from a key frame; for H.264 the SFU's key-frame detection looks for
an IDR (NAL 5) — with SPS/PPS — in the STAP-A/FU-A payload. With hypotheses 1/2 no packet the SFU
accepts as a starting key frame arrives before the timeout. (Worth confirming in the server logs:
look for `waiting for keyframe` / PLI counts on that track.)

### 2. SPS/PPS + IDR delivered in one buffer flagged CODEC_CONFIG|SYNC_FRAME, or IDRs without SPS/PPS
Evidence: output path above `return`s on any CODEC_CONFIG flag. Several Codec2 encoders
(reported for c2.mtk and some c2.exynos) emit the first access unit as `csd` merged with the IDR,
or set CODEC_CONFIG on the IDR. The engine then drops the **first key frame**. The next key frame
only comes on a PLI (`requestKeyFrame` → `PARAMETER_KEY_REQUEST_SYNC_FRAME`, which c2.mtk is
known to honour late or ignore in surface mode) or on our 20 s forced interval
(`forcedKeyFrameIntervalMs` for MediaTek) — for c2.qti it is 0 → effectively never (3600 s).
Conversely, when c2 emits separate csd but later IDRs are delivered *with* inline SPS/PPS, the
engine prepends a second copy (harmless). When the encoder changes resolution (simulcast/adapt),
Codec2 emits new csd; engine handles that (overwrites configBuffer).

### 3. Key-frame request not honoured in surface mode
`KEY_I_FRAME_INTERVAL=3600` plus reliance on `setParameters(request-sync)`. c2.mtk in surface
mode is reported to ignore sync requests until the next GOP. Combined with #2 this means viewers
never get an IDR. The self-test uses I-frame interval 1 and ByteBuffer input, so it passes while
the real path fails — which matches what we saw (self-test OK, calls black).

### 4. Colour format / surface mode
`surfaceColorFormat` = `COLOR_FormatSurface` (0x7F000789) — fine on c2. ByteBuffer path uses
`COLOR_FormatYUV420Flexible` on c2 and the engine writes NV12/I420 by stride from
`getInputFormat()`; c2.mtk sometimes reports a slice-height/stride that the engine ignores →
green/garbled image, **not** "no frames". Would not cause "not bound". Low probability.

### 5. Level too low / too high
With no level set, MTK High@4.1 or c2 default; for 42e01f the SDP says level 3.1 (720p30 max).
A 1080p layer at level 3.1 is out of spec but decoders generally ignore it. Low.

### 6. CBR unsupported
c2.mtk supports CBR; c2.qti too. If unsupported, `configure` throws and we would see
FALLBACK_SOFTWARE, not a silent stream. Ruled out by the symptom (packets arrive).

### 7. Packetization-mode / AUD / SEI
libwebrtc's H264 packetizer handles AUD (type 9) and SEI (type 6) NALs; packetization-mode=1 is
what both sides negotiate. MTK does emit AUD + SEI on some firmware — harmless for libwebrtc but
worth stripping (the SFU key-frame check only looks at the first NAL of a STAP-A in some
versions). Low, but the patch strips them cheaply.

### 8. Server v1.13.6 dropping 42001f
Only removes baseline (non-constrained) from defaults. We offer 42e01f and 640c1f, so
negotiation is unaffected. It would matter only if a subscriber (e.g. an old iOS/Safari) offered
42001f only. Not the cause here (same failure Android→Android).

## Patch proposal (`mediatek-h264.patch`)

Three parts, all gated per device (see "Shipping"):

1. **`H264StreamFixer` callback wrapper** (new file, pure Kotlin, unit-testable). Wraps the
   `VideoEncoder.Callback` given to `LayerEncoder.initEncode`. For every H.264 `EncodedImage`:
   - parse Annex-B NALs; cache latest SPS(7)/PPS(8);
   - drop AUD(9) and SEI(6);
   - if the image contains an IDR (5) — regardless of the MediaCodec flag — mark it
     `VideoFrameKey` and make sure SPS+PPS precede it (prepend cached ones if missing);
   - parse the SPS `profile_idc`/constraint flags and expose them (logged once, and reported
     through `reportFailure` if the profile is not 66/constrained, so codec recovery falls
     back to VP8 in-call instead of streaming black).
   Note: it cannot fix #2 when the engine already `return`ed on a merged CODEC_CONFIG buffer; for
   that, part 2.

2. **Our own copy of the MediaFormat config for c2 H.264** — `AppLoomaEncoderBridge
   .createHardwareEncoder` gets an `h264Tweaks: Boolean`. When set, it constructs
   `AppLoomaHardwareVideoEncoder`, a copy of the engine's `HardwareVideoEncoder` (same package,
   ~800 lines, Apache/BSD licensed) with these changes only:
   - always set `profile = AVCProfileConstrainedBaseline` (API 27+, else `AVCProfileBaseline`)
     and `level = AVCLevel31` (or 4 for 1080p) for 42e01f;
   - `max-bframes = 0`, `KEY_LATENCY = 1` (API 30+), `KEY_PRIORITY = 0`,
     `KEY_PREPEND_HEADER_TO_SYNC_FRAMES = 1` (API 29+, Codec2 honours it);
   - `KEY_I_FRAME_INTERVAL = 2` s for these encoders (bounded recovery if sync requests are ignored);
   - output path: if a CODEC_CONFIG buffer also contains an IDR NAL (or has SYNC_FRAME), save the
     SPS/PPS **and** deliver it as a key frame instead of returning.
   Copying the class is the only way: its format and output code are private. The copy stays
   inside `livekit.org.webrtc` (same as the bridge), so the rebrand rules are unaffected.

3. **Input mode switch**: `h264ByteBuffer: Boolean` passes `sharedContext = null` so frames go
   through the YUV path (the self-test path, which is proven on c2.mtk). Costs a GPU→CPU copy per
   frame; only enabled if the surface path still fails after 1+2.

4. **Gate** in `isUsableHardwareEncoder`: allow a non-proven H.264 encoder only when
   `DeviceVideoConfig.h264Codec2 == true` (new server field) *and* the self-test passed with the
   new bitstream checks.

## Test plan

Self-test (EncoderSelfTest.kt) extensions — these make it test the same config the call uses:
- configure with the same keys as part 2 (CB profile, level, i-frame 2, prepend header, CBR,
  latency) and, when enabled, surface input via an `EGL14` pbuffer + `createInputSurface`;
- collect output, run `H264StreamFixer.inspect()` on it and fail unless: SPS present, PPS
  present, `profile_idc == 66` with `constraint_set1 == 1` (i.e. 42e0xx / 42c0xx), no B-slices
  (POC type 2 or monotonic), first delivered frame is an IDR, and a second IDR appears after
  `setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)` within 1 s;
- record the verdict and the SPS hex in the diagnostics upload
  (`encoder self-test encoder=… profile=42e01f idr_on_request=… bframes=…`).

Device test (realme RMX3941 and RMX5555, viewers: vivo V2419, a Samsung, Web Chrome, iOS):
1. Server DeviceRule for the model: `{"video":{"forceCodec":"h264","h264Codec2":true},"diag":{"enabled":true}}`.
2. Build current SDK with the patch; run sample app, publish 720p simulcast 30 s, then rotate/
   toggle camera (forces reconfig) and join a late viewer at t=20 s.
3. Pass criteria: no "track not bound" in SFU logs; first frame on every viewer < 1.5 s;
   late joiner first frame < 2.5 s; diagnostics show `profile=42e0..`, keyframes delivered,
   PLI count small; `adb logcat -s HardwareVideoEncoder` shows "Prepending config buffer" or
   inline SPS.
4. Bisect if it fails: enable only part 1 → 1+2 → 1+2+3 (each is its own flag) to find which
   cause it was; report the result to the next agent/memory.
5. Regression: OMX.qcom and Exynos phones with the flag off must produce byte-identical config
   (the copy is only instantiated when the flag is on).

## Shipping safely

- New optional `DeviceVideoConfig` fields (parse ignores unknown → old servers fine):
  `h264Codec2: Boolean` (allow c2 H.264 + tweaks), `h264ByteBuffer: Boolean` (part 3).
  Default false everywhere: behaviour identical to 0.4.14+.
- Server `DeviceRule` allowlist keyed by `Build.MODEL` + encoder name (e.g. `RMX3941` +
  `c2.mtk.avc.encoder`). Add a model only after the test plan passes on that model; use
  `preferCodec: "h264"` (not `forceCodec`) so apps that ask for VP8 keep VP8.
- Keep the existing safety nets active: self-test failure → `CodecMemory.rememberBad` +
  `forceSoftware`; automatic codec recovery (viewer no-frame reports → VP8) still applies.
  The fixer's profile check adds an in-call trigger.
- Roll out: 1 model → watch viewer no-frame telemetry 48 h → widen by chipset family
  (Dimensity 6xxx/7xxx, then c2.qti). Kill switch = remove the rule (TTL 3600 s).
