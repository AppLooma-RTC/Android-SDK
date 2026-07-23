package com.liolive.rtc

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Lio Live Android SDK — main entry point.
 * © AppLooma LLC
 *
 * Kotlin:
 * ```kotlin
 * val engine = LioEngine.create(context, "YOUR_APP_ID", eventHandler)
 * engine.joinChannel(token, wsUrl, LioJoinOptions(role = LioRole.HOST, camera = true))
 * ```
 *
 * Java:
 * ```java
 * LioEngine engine = LioEngine.create(context, "YOUR_APP_ID", handler);
 * engine.joinChannelAsync(token, wsUrl, options);
 * ```
 */
class LioEngine private constructor(
    context: Context,
    val appId: String,
    private val eventHandler: LioEventHandler?,
) {
    private val room: Room = LiveKit.create(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventsJob: Job? = null
    private val users = LinkedHashMap<String, LioRemoteUser>()

    @Volatile
    var isJoined: Boolean = false
        private set

    companion object {
        /** Create an engine instance with your Lio Live App ID (console.liolive.com). */
        @JvmStatic
        @JvmOverloads
        fun create(context: Context, appId: String, eventHandler: LioEventHandler? = null): LioEngine {
            require(appId.isNotBlank()) { "LioEngine.create: appId is required" }
            return LioEngine(context, appId, eventHandler)
        }
    }

    /**
     * Join a channel with a token from your server
     * (POST https://api.liolive.com/v1/token → { token, wsUrl }).
     */
    suspend fun joinChannel(token: String, wsUrl: String, options: LioJoinOptions = LioJoinOptions()) {
        check(!isJoined) { "Already in a channel — call leaveChannel() first" }
        subscribeEvents()
        room.connect(wsUrl, token)
        isJoined = true
        if (options.role != LioRole.AUDIENCE) {
            if (options.microphone) room.localParticipant.setMicrophoneEnabled(true)
            if (options.camera) room.localParticipant.setCameraEnabled(true)
        }
    }

    /** Java-friendly fire-and-forget variant. */
    fun joinChannelAsync(token: String, wsUrl: String, options: LioJoinOptions = LioJoinOptions()) {
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

    // ---- Local media controls ----
    fun enableCamera(on: Boolean) { scope.launch { room.localParticipant.setCameraEnabled(on) } }
    fun enableMicrophone(on: Boolean) { scope.launch { room.localParticipant.setMicrophoneEnabled(on) } }

    /** Broadcast data to the channel (chat, signals, gifts). */
    fun sendData(data: ByteArray, reliable: Boolean = true) {
        scope.launch {
            room.localParticipant.publishData(
                data,
                if (reliable) io.livekit.android.room.track.DataPublishReliability.RELIABLE
                else io.livekit.android.room.track.DataPublishReliability.LOSSY
            )
        }
    }

    // ---- State ----
    val localUid: String get() = room.localParticipant.identity?.value ?: ""
    val channelName: String get() = room.name ?: ""
    val remoteUsers: List<LioRemoteUser> get() = users.values.toList()

    /** internal — escape hatch for UIKits */
    val raw: Room get() = room

    private fun userFor(p: RemoteParticipant): LioRemoteUser =
        users.getOrPut(p.identity?.value ?: "") { LioRemoteUser(p) }

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
                        eventHandler?.onDisconnected(event.reason?.name)
                    }
                    is RoomEvent.DataReceived -> {
                        val from = (event.participant as? RemoteParticipant)?.let { userFor(it) }
                        eventHandler?.onDataReceived(event.data, from)
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
