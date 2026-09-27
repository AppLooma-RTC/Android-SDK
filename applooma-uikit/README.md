# AppLooma RTC UIKit for Android

Drop-in Jetpack Compose screens: **live streaming**, **voice room**, **1-to-1 call**.

```kotlin
// settings.gradle.kts → repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
implementation("com.applooma:uikit-android:0.2.8")
```

```kotlin
val kit = AppLoomaKit(appId = "YOUR_APP_ID", user = KitUser(me.id, me.name)) { room, role, user ->
    val r = myApi.rtcToken(room, role, user.id, user.name)      // YOUR server
    KitToken(r.token, r.wsUrl)
}

AppLoomaLiveStream(kit, room = "show-42", role = "host", onLeave = { finish() })
AppLoomaVoiceRoom(kit, room = "party-7", seats = 8, onLeave = { finish() })
AppLoomaCall(kit, room = "call-abc", video = true, onLeave = { finish() })
```

The screens ask for camera and microphone permission when they join.

On Android 12 and above the dialog also asks for `BLUETOOTH_CONNECT` so Bluetooth
headsets work; refusing it does not block the join. Requires `com.applooma:rtc-android`
0.4.10 or newer, which the kit pulls in.

## From Java (or any non-Compose app)

```java
AppLoomaUiKit.setup(new AppLoomaKit("YOUR_APP_ID", new KitUser(me.id, me.name, null), (room, role, user) -> {
    MyApi.Token t = myApi.rtcToken(room, role, user.getId(), user.getName());   // YOUR server, runs on a background thread
    return new KitToken(t.token, t.wsUrl);
}));

AppLoomaUiKit.openLiveStream(this, "show-42", "host");
AppLoomaUiKit.openVoiceRoom(this, "party-7", 8);
AppLoomaUiKit.openCall(this, "call-abc", true);
```

Each call opens a full-screen Activity; it finishes itself when the user leaves.
