# Lio Live Android SDK (`com.liolive:rtc-android`)

Real-time voice, video, live streaming and audio rooms by AppLooma LLC. Kotlin-first with a Java-friendly API.

## Install

```gradle
dependencies {
    implementation("com.liolive:rtc-android:0.1.0")
}
```

Permissions (`CAMERA`, `RECORD_AUDIO`) are declared by the SDK manifest; request them at runtime.

## Quickstart (Kotlin)

```kotlin
val engine = LioEngine.create(context, "YOUR_APP_ID", object : LioEventHandler() {
    override fun onUserJoined(user: LioRemoteUser) { /* add tile */ }
    override fun onTrackSubscribed(user: LioRemoteUser) {
        user.videoTrack?.let { renderer.setTrack(it) }
    }
})

// Get { token, wsUrl } from YOUR server, which calls
// POST https://api.applooma.dev/v1/token with your API key/secret.
lifecycleScope.launch {
    engine.joinChannel(token, wsUrl, LioJoinOptions(role = LioRole.HOST, camera = true))
}
```

## Quickstart (Java)

```java
LioEngine engine = LioEngine.create(context, "YOUR_APP_ID", new LioEventHandler() {
    @Override public void onUserJoined(LioRemoteUser user) { }
});
engine.joinChannelAsync(token, wsUrl, new LioJoinOptions(LioRole.HOST, true, true));
```

## Controls

```kotlin
engine.enableCamera(false)
engine.enableMicrophone(false)
engine.sendData("hello".toByteArray())
engine.leaveChannel()
engine.destroy()
```

## Roles

`HOST` (admin + publish) · `COHOST` (publish) · `AUDIENCE` (view only — live streaming viewers)

Docs: https://applooma.dev/dashboard/docs
