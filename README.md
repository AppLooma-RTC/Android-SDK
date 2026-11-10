# AppLooma RTC Android SDK (`com.applooma:rtc-android`)

Real-time voice, video, live streaming and audio rooms by AppLooma LLC. Kotlin-first with a Java-friendly API.

## Install

```gradle
dependencies {
    implementation("com.applooma:rtc-android:0.9.0")
}
```

Permissions (`CAMERA`, `RECORD_AUDIO`) are declared by the SDK manifest; request them at runtime.

On Android 12 and above also request `BLUETOOTH_CONNECT` next to `RECORD_AUDIO`,
or a Bluetooth headset is never offered as an output; treat a refusal as non-fatal.

## Quickstart (Kotlin)

```kotlin
val engine = AppEngine.create(context, "YOUR_APP_ID", object : AppEventHandler() {
    override fun onUserJoined(user: AppRemoteUser) { /* add tile */ }
    override fun onTrackSubscribed(user: AppRemoteUser) {
        videoTile.attach(engine, user)   // AppVideoView
    }
})

// Get { token, wsUrl } from YOUR server, which calls
// POST https://api.applooma.dev/v1/token with your API key/secret.
lifecycleScope.launch {
    engine.joinChannel(token, wsUrl, AppJoinOptions(role = AppRole.HOST, camera = true))
}
```

## Quickstart (Java)

```java
AppEngine engine = AppEngine.create(context, "YOUR_APP_ID", new AppEventHandler() {
    @Override public void onUserJoined(AppRemoteUser user) { }
});
engine.joinChannelAsync(token, wsUrl, new AppJoinOptions(AppRole.HOST, true, true));
```

## Controls

```kotlin
engine.enableCamera(false)
engine.enableMicrophone(false)
engine.switchCamera()          // front <-> back
engine.setSpeakerphone(true)   // loudspeaker for a video call; earpiece otherwise
engine.sendData("hello".toByteArray())
engine.leaveChannel()
engine.destroy()
```

## Audio routing

A connected Bluetooth headset is preferred, then a wired headset, then the
loudspeaker or the earpiece. For an in-call route picker use
`engine.getAudioRoutes()`, `engine.currentAudioRoute`, `engine.setAudioRoute(route)`
and `AppEventHandler.onAudioRouteChanged(route, available)`.

## Video quality

1080p at 4 Mbps by default (720p at 2.2 Mbps, 1440p preset at 5 Mbps). Decide what
the encoder gives up first under pressure with `degradation`, cap it with `maxBitrate`,
and watch what actually goes out in `onVideoQualityChanged`:

```kotlin
val engine = AppEngine.create(context, "YOUR_APP_ID", handler, AppEngineOptions(
    video = AppVideoConfig(height = 1080, fps = 30, maxBitrate = 4_000_000,
                           degradation = AppVideoDegradation.KEEP_RESOLUTION),
))
```

`AppVideoCodec.AUTO` picks the camera codec per phone, so a published camera always
carries frames. `AppEngine.enableDebugLogging(sink)` mirrors engine logs for support
tickets.

## Roles

`HOST` (admin + publish) · `COHOST` (publish) · `AUDIENCE` (view only — live streaming viewers)

Docs: https://applooma.dev/dashboard/docs
