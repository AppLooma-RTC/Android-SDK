# AppLooma RTC SDK consumer rules
-keep class com.applooma.rtc.** { *; }

# Live encoder settings (bitrate, resolution cap, frame rate) reach the camera
# track's RTP sender and capture source by reflection; keep those members so an
# app's shrinker cannot inline or remove them.
-keepclassmembers class com.applooma.rtc.transport.room.track.LocalVideoTrack {
    org.webrtc.VideoSource source;
    org.webrtc.RtpTransceiver transceiver;
    org.webrtc.RtpSender getSender*();
}
-keepclassmembers class com.applooma.rtc.transport.room.Room {
    com.applooma.rtc.transport.room.RTCEngine engine;
}
-keepclassmembers class com.applooma.rtc.transport.room.RTCEngine {
    public org.webrtc.PeerConnection getPublisherPeerConnection();
}
