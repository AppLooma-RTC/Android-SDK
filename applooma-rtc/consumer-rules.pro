# AppLooma RTC SDK consumer rules
-keep class com.applooma.rtc.** { *; }

# Live encoder settings (bitrate, resolution cap, frame rate) reach the camera
# track's RTP sender and capture source by reflection; keep those members so an
# app's shrinker cannot inline or remove them.
-keepclassmembers class io.livekit.android.room.track.LocalVideoTrack {
    livekit.org.webrtc.VideoSource source;
    livekit.org.webrtc.RtpTransceiver transceiver;
    livekit.org.webrtc.RtpSender getSender*();
}
-keepclassmembers class io.livekit.android.room.Room {
    io.livekit.android.room.RTCEngine engine;
}
-keepclassmembers class io.livekit.android.room.RTCEngine {
    public livekit.org.webrtc.PeerConnection getPublisherPeerConnection();
}
