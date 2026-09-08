package com.applooma.rtc

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * AppLooma RTC Android SDK — main entry point.
 * © AppLooma LLC
 *
 * Kotlin:
 * ```kotlin
 * val engine = AppEngine.create(context, "YOUR_APP_ID", eventHandler)
 * engine.joinChannel(token, wsUrl, AppJoinOptions(role = AppRole.HOST, camera = true))
 * ```
 *
 * Java:
 * ```java
 * AppEngine engine = AppEngine.create(context, "YOUR_APP_ID", handler);
 * engine.joinChannelAsync(token, wsUrl, options);
 * ```
 */
class AppEngine private constructor(
    context: Context,
    val appId: String,
    private val eventHandler: AppEventHandler?,
) {
    private val room: Room = LiveKit.create(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventsJob: Job? = null
    private val users = LinkedHashMap<String, AppRemoteUser>()

    @Volatile
    var isJoined: Boolean = false
        private set

    companion object {
        /** Create an engine instance with your AppLooma RTC App ID (applooma.dev/dashboard). */
        @JvmStatic
        @JvmOverloads
        fun create(context: Context, appId: String, eventHandler: AppEventHandler? = null): AppEngine {
            require(appId.isNotBlank()) { "AppEngine.create: appId is required" }
            return AppEngine(context, appId, eventHandler)
        }
    }

    /**
     * Join a channel with a token from your server
     * (POST https://api.applooma.dev/v1/token → { token, wsUrl }).
     */
    suspend fun joinChannel(token: String, wsUrl: String, options: AppJoinOptions = AppJoinOptions()) {
        check(!isJoined) { "Already in a channel — call leaveChannel() first" }
        subscribeEvents()
        room.connect(wsUrl, token)
        isJoined = true

        // Participants already in the room when we join never produce a
        // ParticipantConnected event; without this an audience member joining a
        // live stream sees nobody and never receives the host's video.
        seedExistingParticipants()

        if (options.role != AppRole.AUDIENCE) {
            if (options.microphone) room.localParticipant.setMicrophoneEnabled(true)
            if (options.camera) room.localParticipant.setCameraEnabled(true)
        }
    }

    /** Java-friendly fire-and-forget variant. */
    fun joinChannelAsync(token: String, wsUrl: String, options: AppJoinOptions = AppJoinOptions()) {
        scope.launch {
            try {
                joinChannel(token, wsUrl, options)
            } catch (t: Throwable) {
                eventHandler?.onError(t)
            }
        }
    }

    fun leaveChannel() {
        room.disconnect()
        isJoined = false
        users.clear()
        eventsJob?.cancel()
    }

    /** Announces everyone already present, and their already-published tracks. */
    private fun seedExistingParticipants() {
        room.remoteParticipants.values.forEach { p ->
            val user = userFor(p)
            eventHandler?.onUserJoined(user)
            if (p.trackPublications.values.any { it.track != null }) {
                eventHandler?.onTrackSubscribed(user)
            }
        }
    }

    // ---- Local media controls ----
    // These are fire-and-forget for Java callers, so a failure (missing CAMERA
    // permission, publish rejected) must be reported rather than thrown from a
    // coroutine, where it would crash the host application.
    fun enableCamera(on: Boolean) = launchSafely { room.localParticipant.setCameraEnabled(on) }
    fun enableMicrophone(on: Boolean) = launchSafely { room.localParticipant.setMicrophoneEnabled(on) }

    /** Broadcast data to the channel (chat, signals, gifts). */
    fun sendData(data: ByteArray, reliable: Boolean = true) = launchSafely {
        room.localParticipant.publishData(
            data,
            if (reliable) io.livekit.android.room.track.DataPublishReliability.RELIABLE
            else io.livekit.android.room.track.DataPublishReliability.LOSSY
        )
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                eventHandler?.onError(t)
            }
        }
    }

    // ---- State ----
    val localUid: String get() = room.localParticipant.identity?.value ?: ""
    val channelName: String get() = room.name ?: ""
    val remoteUsers: List<AppRemoteUser> get() = users.values.toList()

    // Internal: AppVideoView needs the room to initialise a renderer, and the
    // local camera track to draw a preview. Neither belongs in the public API.
    internal val raw: Room get() = room

    internal val localVideoTrack: VideoTrack?
        get() = room.localParticipant.videoTrackPublications
            .firstNotNullOfOrNull { it.second as? VideoTrack }

    /** Prepares a renderer view to receive frames. Used by [AppVideoView]. */
    internal fun initRenderer(view: io.livekit.android.renderer.TextureViewRenderer) {
        room.initVideoRenderer(view)
    }

    private fun userFor(p: RemoteParticipant): AppRemoteUser =
        users.getOrPut(p.identity?.value ?: "") { AppRemoteUser(p) }

    private fun subscribeEvents() {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.ParticipantConnected ->
                        eventHandler?.onUserJoined(userFor(event.participant))
                    is RoomEvent.ParticipantDisconnected -> {
                        val u = users.remove(event.participant.identity?.value)
                        if (u != null) eventHandler?.onUserLeft(u)
                    }
                    is RoomEvent.TrackSubscribed ->
                        (event.participant as? RemoteParticipant)?.let {
                            eventHandler?.onTrackSubscribed(userFor(it))
                        }
                    is RoomEvent.Disconnected -> {
                        isJoined = false
                        // Report everyone as gone; a stale roster after a
                        // disconnect made remoteUsers wrong until the next join.
                        users.values.forEach { eventHandler?.onUserLeft(it) }
                        users.clear()
                        eventHandler?.onDisconnected(event.reason?.name)
                    }
                    is RoomEvent.Reconnected -> {
                        users.clear()
                        seedExistingParticipants()
                    }
                    is RoomEvent.DataReceived -> {
                        val from = (event.participant as? RemoteParticipant)?.let { userFor(it) }
                        eventHandler?.onDataReceived(event.data, from)
                        AppGiftEvent.tryParse(event.data)?.let { eventHandler?.onGiftReceived(it) }
                    }
                    is RoomEvent.ActiveSpeakersChanged ->
                        eventHandler?.onActiveSpeakersChanged(
                            event.speakers.mapNotNull { it.identity?.value }
                        )
                    else -> {}
                }
            }
        }
    }

    /** Release all resources. */
    fun destroy() {
        leaveChannel()
        room.release()
    }
}
