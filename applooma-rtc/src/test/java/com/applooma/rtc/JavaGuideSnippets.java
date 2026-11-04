package com.applooma.rtc;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-only check: the Java code in the live-room developer guide
 * (developer-notes/5-java-live-room-guide.html). If this stops compiling,
 * the guide is out of date. Nothing here runs.
 */
@SuppressWarnings("unused")
final class JavaGuideSnippets {

    private AppEngine engine;
    private final Set<String> seenIds = new HashSet<>();
    private final List<AppMessage> pending = new ArrayList<>();
    private long lastSentAt = 0;

    void create(Context context, String appId) {
        AppEngineOptions options = new AppEngineOptions(
                AppAudioScenario.MEDIA,              // live and voice rooms
                new AppVideoConfig(1080, 30));       // 1080p30; the SDK steps down on a weak phone or network
        engine = AppEngine.create(context, appId, handler, options);
    }

    void joinAsHost(String token, String wsUrl) {
        engine.joinChannelAsync(token, wsUrl, new AppJoinOptions(AppRole.HOST, true, true));
    }

    void joinAsViewer(String token, String wsUrl) {
        engine.joinChannelAsync(token, wsUrl, new AppJoinOptions(AppRole.AUDIENCE, false, false));
    }

    void leaveRoom() {
        engine.leaveChannel();
        engine.destroy();
        engine = null;
    }

    final AppEventHandler handler = new AppEventHandler() {
        @Override public void onJoined(String localUid) { }
        @Override public void onReconnecting() { /* show "Reconnecting..." */ }
        @Override public void onReconnected() { /* hide it */ }
        @Override public void onDisconnected(String reason) {
            if ("DUPLICATE_IDENTITY".equals(reason)) {
                // The same user joined again somewhere else. Do not rejoin automatically.
            }
        }
        @Override public void onError(Throwable error) {
            if (error instanceof JoinAbandonedException) return;   // the user left during the join
        }
        @Override public void onTokenWillExpire(String token) {
            String fresh = "";                                       // fetch from your server
            engine.renewToken(fresh);
        }
        @Override public void onVideoQualityChanged(AppVideoQualityInfo q) {
            if (q.getReason() == AppVideoQualityReason.BANDWIDTH) { /* "Your network is weak" */ }
            if (q.getReason() == AppVideoQualityReason.CPU) { /* phone is too busy */ }
        }
        @Override public void onMessage(AppMessage m) {
            if (!seenIds.add(m.getId())) return;                     // already shown
            AppRemoteUser from = m.getFrom();
            String uid = from != null ? from.getUid() : "server";
            Map<String, Object> data = m.getData();
            String name = data != null && data.get("name") != null ? String.valueOf(data.get("name")) : uid;
            pending.add(m);                                          // drawn every 150 ms
        }
    };

    AppMessage sendComment(String text, String myName, int myLevel, AppMessage replyTo) {
        long now = System.currentTimeMillis();
        if (now - lastSentAt < 1000) return null;                    // at most one per second
        text = text.trim();
        if (text.isEmpty()) return null;
        if (text.length() > 200) text = text.substring(0, 200);
        lastSentAt = now;

        Map<String, Object> data = new HashMap<>();
        data.put("type", "comment");
        data.put("name", myName);
        data.put("level", myLevel);
        if (replyTo != null) {
            data.put("replyToId", replyTo.getId());
            data.put("replyToText", replyTo.getText());
        }
        AppMessage mine = engine.sendMessage(text, data);
        seenIds.add(mine.getId());
        return mine;                                                 // add to your own list now
    }

    void sendReaction() {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "heart");
        engine.sendMessage(null, data, false);                       // lossy is fine for reactions
    }

    void loadOlder() {
        engine.loadHistoryAsync(50, null, (page, error) -> {
            if (error != null) {
                if (error instanceof AppException && "CHAT_NOT_IN_PLAN".equals(((AppException) error).getCode())) { }
                return;
            }
            for (AppStoredMessage m : page.getMessages()) {
                String who = m.getSenderName() != null ? m.getSenderName() : m.getSender();
            }
            if (page.getNextBefore() != null) {
                engine.loadHistoryAsync(50, page.getNextBefore(), (older, e2) -> { });
            }
        });
    }

    void checkNetworkBeforeLive(String wsUrl, String token) {
        engine.startNetworkTest(wsUrl, token, result -> {
            if (result.getError() == null && result.getUplinkQuality() == AppNetworkQuality.POOR) {
                // "Your upload is weak; the video may be blurry"
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    void viewerFallback() {
        engine.setRemoteSubscribeFallback(AppFallbackOption.VIDEO_LOW_QUALITY);
    }
}
