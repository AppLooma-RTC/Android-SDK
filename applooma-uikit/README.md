# AppLooma RTC UIKit for Android

Drop-in Jetpack Compose screens: **live streaming**, **voice room**, **1-to-1 call**.

```kotlin
// settings.gradle.kts → repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
implementation("com.applooma:uikit-android:0.1.0")
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
