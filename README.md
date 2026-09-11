# AppLooma RTC Android SDK (`com.applooma:rtc-android`)

Real-time voice, video, live streaming and audio rooms by AppLooma LLC. Kotlin-first with a Java-friendly API.

## Install

```gradle
dependencies {
    implementation("com.applooma:rtc-android:0.3.2")
}
```

Permissions (`CAMERA`, `RECORD_AUDIO`) are declared by the SDK manifest; request them at runtime.

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

## Roles

`HOST` (admin + publish) · `COHOST` (publish) · `AUDIENCE` (view only — live streaming viewers)

Docs: https://applooma.dev/dashboard/docs
